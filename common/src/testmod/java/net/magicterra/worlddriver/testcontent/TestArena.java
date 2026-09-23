package net.magicterra.worlddriver.testcontent;

import java.util.Comparator;
import java.util.concurrent.locks.LockSupport;

import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.api.ServerThreadHop;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The arena at the driver's test origin ({@code mc.system.testOrigin}) that the validation suite's
 * scripts are written against. Testmod only: seeding clears blocks and discards every non-player
 * entity around the origin, which the shipped jar has no reason to offer.
 */
public final class TestArena {
    private TestArena() {}

    private static final long SERVER_THREAD_TIMEOUT_MS = Long.getLong("worlddriver.serverThreadTimeoutMs", 8_000L);

    /**
     * Keeps the test arena's chunks loaded, entities and all, for as long as the server runs.
     *
     * <p>Nothing else does. {@code StageWrightHarness} force-loads a window around each SCENE's
     * arena (AGENTS.md hard rule #11), and this arena is not one — the test origin is an absolute
     * position outside every scene's grid cell, so the harness's window never covers it. Left
     * unpinned it stays loaded only while a player happens to be standing near 0,200,0, and
     * {@code seed} is called from scenes that then walk the player 130,000 blocks away.
     *
     * <p>What that costs is a whole class of failure that reads as a product bug. Block writes
     * load the chunk they touch on demand, so terrain always works; {@code Level#getEntities}
     * only sees LOADED entity sections, so entities silently do not exist. The validation suite
     * then reports "exactly the 2 tagged stands, got 0" and "the two seeded props are there, got
     * 0 non-player rows of 0" — which read as the entity query being broken. Measured on both
     * NeoForge client topologies, at both ends of the suite: {@code 05_query} failed before the
     * player's teleport onto the pad had promoted the chunk, and {@code 58_query_type} failed
     * after {@code 40_scheduler} had walked them off it. Same missing ticket, opposite ends,
     * different checks each run — which is what made it look like flakiness.
     *
     * <p>Not persisted, so it never outlives the process and cannot end up in a saved world.
     */
    private static final TicketType<ChunkPos> TEST_ARENA_TICKET =
            TicketType.create("worlddriver_test_arena", Comparator.comparingLong(ChunkPos::toLong));

    /** Chunks within this many of the origin's chunk must be ENTITY_TICKING. The seed clears a
     *  ±20 box and the suite queries a ±16 radius around the origin, so ±2 chunks covers both. */
    private static final int TEST_ARENA_CHUNK_RADIUS = 2;

    /** True once every chunk within {@link #TEST_ARENA_CHUNK_RADIUS} of {@code center} has its
     *  entity sections at the ticking visibility — the state in which {@code getEntities} sees
     *  what {@code addFreshEntity} added. */
    private static boolean arenaEntityTicking(ServerLevel level, ChunkPos center, int y) {
        for (int cx = center.x - TEST_ARENA_CHUNK_RADIUS; cx <= center.x + TEST_ARENA_CHUNK_RADIUS; cx++)
            for (int cz = center.z - TEST_ARENA_CHUNK_RADIUS; cz <= center.z + TEST_ARENA_CHUNK_RADIUS; cz++)
                if (!level.isPositionEntityTicking(new BlockPos(cx << 4, y, cz << 4))) return false;
        return true;
    }

    /**
     * Lays down a deterministic test arena: 5x5 stones at y=200, oak log at y=201,
     * one cow at (3,201,0), one sheep at (-3,201,2). Clears surrounding air first
     * so {@code /worlddriver test} is idempotent.
     *
     * <p>Pins the arena's chunks on the way in ({@link #TEST_ARENA_TICKET}) and asserts on the way
     * out that the props it just placed are actually visible. "Deterministic" is the whole point of
     * this verb, and an arena whose entities exist only while somebody stands next to it is not.
     */
    public static void seed(DriverApi api, MinecraftServer server) {
        ServerLevel level = server.overworld();
        BlockPos origin = api.system.testOrigin();
        new ServerThreadHop(server, server::isSameThread, SERVER_THREAD_TIMEOUT_MS).call(() -> {
            // Pin first, write second. The ticket's level has to reach ENTITY_TICKING (31) out to
            // TEST_ARENA_CHUNK_RADIUS, and a region ticket at distance d puts its own chunk at
            // 33-d and each ring one higher — so d = radius + 2. Re-adding an identical ticket is
            // a no-op in DistanceManager, which is what makes this safe to call on every seed.
            ChunkPos center = new ChunkPos(origin);
            level.getChunkSource().addRegionTicket(
                    TEST_ARENA_TICKET, center, TEST_ARENA_CHUNK_RADIUS + 2, center);
            // Then drive the load to completion before touching anything. addRegionTicket only
            // registers intent; the chunks reach FULL (and their entity sections become visible)
            // through the chunk source's own update pass, and a blocking getChunk on the server
            // thread is what runs it. Without this the seed still writes its blocks — those load
            // on demand — and its animals still land in a section nothing can see yet.
            for (int cx = center.x - TEST_ARENA_CHUNK_RADIUS; cx <= center.x + TEST_ARENA_CHUNK_RADIUS; cx++)
                for (int cz = center.z - TEST_ARENA_CHUNK_RADIUS; cz <= center.z + TEST_ARENA_CHUNK_RADIUS; cz++)
                    level.getChunk(cx, cz);
            // The blocking loads above only SCHEDULE the step that makes entities visible. A chunk
            // reaching FULL / BLOCK_TICKING / ENTITY_TICKING goes through
            // ChunkHolder.scheduleFullChunkPromotion, which hands ChunkMap.onFullChunkStatusChange —
            // the call that flips the chunk's entity sections from HIDDEN to accessible — to the
            // main-thread executor with thenRunAsync. A task queued that way cannot run inside the
            // task that queued it, so seeding and checking in one server-thread turn saw the props
            // it had just added in a section getEntities does not iterate: "holds 0 of its 2 props"
            // on every dedicated-server run, while topologies where something had promoted these
            // chunks in an earlier tick (a player nearby, a previous seed) passed by accident.
            //
            // Pump the CHUNK SOURCE's own queue, not the server's. ServerChunkCache.pollTask runs
            // the distance-manager update and then the chunk-thread tasks, which is exactly where
            // the promotion sits — it is what a blocking getChunk spins on. MinecraftServer's
            // managedBlock would not do: its pollTaskInternal only reaches the chunk sources while
            // haveTime() holds, and haveTime() is runningTask() (a task is executing) or the tick
            // still having budget; a scene runs from the tick loop, not from a task, and by the
            // time the loads above return the tick's 50 ms are long gone — measured: ten seconds
            // of spinning with entityTicking still false. Bounded so a promotion that never lands
            // is reported by the assertion below instead of hanging the server.
            //
            // Every arena chunk, not just the origin's: the sheep stands at x = -0.5, one chunk
            // west, and a wait on the origin chunk alone seeded "1 of its 2 props".
            long promoteDeadline = System.nanoTime() + 10_000_000_000L;
            while (!arenaEntityTicking(level, center, origin.getY()) && System.nanoTime() < promoteDeadline) {
                if (!level.getChunkSource().pollTask()) {
                    LockSupport.parkNanos("TestArena.seed: chunk promotion", 100_000L);
                }
            }
            BlockState air = Blocks.AIR.defaultBlockState();
            // Clear up to dy=12 (origin.y+12) — deliberately taller than any cell the
            // suite currently writes. The ceiling was raised from +5 to +12 to kill a
            // flake whose mechanism outlives its original culprit: the world PERSISTS
            // across runs, so a block left above the cleared band (a one-off restore
            // hiccup, or world-gen residue) is never wiped by the seed and poisons the
            // next run's "cell is air before the run" precondition FOREVER. The verb
            // that first exposed this (mc.test.yaml, cells @ +6..+10) is gone, but the
            // headroom stays: it costs one pass over ~1000 air blocks and makes every
            // run self-healing regardless of which script reaches highest.
            for (int dx = -4; dx <= 4; dx++)
                for (int dy = -1; dy <= 12; dy++)
                    for (int dz = -4; dz <= 4; dz++)
                        level.setBlockAndUpdate(origin.offset(dx, dy, dz), air);
            // Floor the whole cleared footprint, not a 5×5 island in it. The suite's moving checks
            // need somewhere to move: a kiting bot backs away from what it is shooting at, and off
            // a five-wide pad it is over the edge in two steps — which reports as "kited away from
            // the skeleton, not into melee (d=3.3)", a distance that reads like a behaviour bug and
            // is a missing floor. Same width as the air above it, so walking off the stone and
            // walking out of the cleared box are the same boundary rather than two.
            BlockState stone = Blocks.STONE.defaultBlockState();
            for (int dx = -4; dx <= 4; dx++)
                for (int dz = -4; dz <= 4; dz++)
                    level.setBlockAndUpdate(origin.offset(dx, 0, dz), stone);
            level.setBlockAndUpdate(origin.offset(0, 1, 0), Blocks.OAK_LOG.defaultBlockState());

            // Despawn every non-player entity in the test box. Cow/Sheep from a
            // prior run get persisted by setPersistenceRequired() and reload with
            // the chunk; nuking them all keeps the entity count deterministic.
            AABB clearBox = new AABB(origin).inflate(20.0);
            for (Entity e : level.getEntities((Entity) null, clearBox)) {
                if (e instanceof Player) continue;
                e.discard();
            }
            // Place both animals on top of the 5x5 stone plane (x,z ∈ [-2,2]),
            // not outside it — otherwise gravity drops them out of the query AABB
            // and length-2 assertions in 05_query become flaky after a few ticks.
            // NoAI: they are query props, not livestock — a wandering cow stepped
            // one block between 06_rpc_parity's two snapshots (in-JVM vs TCP,
            // 15 ms apart) and failed the row-equality assert (2026-07-09).
            Cow cow = EntityType.COW.create(level);
            if (cow != null) {
                cow.moveTo(origin.getX() + 1 + 0.5, origin.getY() + 1, origin.getZ() + 0.5, 0f, 0f);
                cow.setPersistenceRequired();
                cow.setNoAi(true);
                level.addFreshEntity(cow);
            }
            Sheep sheep = EntityType.SHEEP.create(level);
            if (sheep != null) {
                sheep.moveTo(origin.getX() - 1 + 0.5, origin.getY() + 1, origin.getZ() + 1 + 0.5, 0f, 0f);
                sheep.setPersistenceRequired();
                sheep.setNoAi(true);
                level.addFreshEntity(sheep);
            }
            // Read the props back through the same lookup every caller will use, and refuse to
            // report a seeded arena that is not one. Both creates are null-guarded and
            // addFreshEntity can decline, so up to here every way this fails is silent — and a
            // silent failure here is not an error anyone sees, it is a suite that reports the
            // ENTITY QUERY as broken. Throwing puts the message at the cause.
            int props = 0;
            for (Entity e : level.getEntities((Entity) null, new AABB(origin).inflate(4.0))) {
                if (e instanceof Cow || e instanceof Sheep) props++;
            }
            if (props != 2) {
                throw new IllegalStateException("TestArena.seed: the arena at " + origin + " holds "
                        + props + " of its 2 props after seeding (arenaEntityTicking="
                        + arenaEntityTicking(level, center, origin.getY()) + ") — the chunk is loaded for blocks"
                        + " but not for entities, so every entity check downstream would report an"
                        + " empty world instead of this");
            }
            api.clearEvents();
            return null;
        });
    }
}
