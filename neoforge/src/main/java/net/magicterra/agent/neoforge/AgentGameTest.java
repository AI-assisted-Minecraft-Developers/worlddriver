package net.magicterra.agent.neoforge;

import net.magicterra.agent.AgentDriverCommon;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.magicterra.agent.neoforge.sim.ServerPlayerAvatar;
import net.magicterra.agent.neoforge.sim.ServerAgentDriver;
import net.magicterra.agent.neoforge.sim.ServerAgentManager;
import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.process.BboxFillProcess;
import net.magicterra.agent.bot.process.BuildProcess;
import net.magicterra.agent.bot.process.FollowProcess;
import net.magicterra.agent.bot.process.MineProcess;
import net.magicterra.agent.bot.process.RunAwayProcess;
import net.magicterra.agent.bot.process.Schematic;
import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.debug.BotLevelHolder;
import net.magicterra.agent.bot.debug.NodePhysics;
import net.magicterra.agent.bot.debug.PathArchive;
import net.magicterra.agent.bot.debug.PathArchiveRecorder;
import net.magicterra.agent.bot.debug.PathDebugBootstrap;
import net.magicterra.agent.bot.pathfinder.MultiTrace;
import net.magicterra.agent.bot.pathfinder.PathFinder;
import net.magicterra.agent.bot.pathfinder.PathTrace;
import net.magicterra.agent.bot.pathfinder.PathTraceHolder;
import net.magicterra.agent.bot.movement.Walker;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.world.LevelWorldView;
import net.magicterra.agent.bot.pathfinder.moves.Fall;
import net.magicterra.agent.bot.pathfinder.moves.FallIntoWater;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.effect.MobEffectInstance;
import net.neoforged.neoforge.common.util.FakePlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import static net.magicterra.agent.neoforge.AgentGameTestSupport.*;

/**
 * Wraps the JS validation suite as a vanilla GameTest so it shows up in
 * {@code /test runall} and runs under {@code gradlew :neoforge:runGameTestServer}.
 *
 * Requires {@code data/agent_driver/structure/empty.nbt} (singular "structure",
 * the 1.21 datapack convention — {@code StructureTemplateManager.STRUCTURE_RESOURCE_DIRECTORY_NAME}).
 * Vanilla MC needs a structure template per @GameTest even when the test body
 * does not touch the spawned structure (we just run our own validation arena
 * at y=200).
 *
 * Threading: @GameTest bodies run on the server thread. Sub-tests in
 * 06_rpc_parity / 07_mcp_parity do TCP/HTTP round-trips back into our own
 * server; their handlers marshal work via {@code server.execute()} which only
 * drains on each server tick — so if we hold the server thread we deadlock.
 * Same constraint as {@code AgentDriverCommon.onServerStarted} / {@code cmdTest};
 * see comments there. Resolution: kick validation onto a worker thread, then
 * poll completion from the tick path via {@code startSequence().thenWaitUntil},
 * which is the only API surface that documents proper GameTestAssertException
 * retry semantics — {@code succeedWhen} treats the very first throw as a hard
 * failure, which becomes a race once the validation suite takes longer than
 * the framework's first tick.
 *
 * Timeout: the GameTestServer ticks as fast as it can, so {@code timeoutTicks} is
 * a wall-clock budget compressed by the tick rate (~3000 ticks/s here). Some
 * validation sub-tests sleep in real time — e.g. 49_events waits ~0.5 s for a
 * background condition watcher to fire — so the budget must comfortably exceed the
 * suite's real-time duration, not just its tick count. A passing run still ends the
 * instant {@code result} is set, so a generous ceiling costs nothing.
 */
@GameTestHolder(AgentDriverCommon.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AgentGameTest {
    private AgentGameTest() {}

    // batch: the validation suite marshals every call through onServerThread()
    // with an 8s deadline; sharing a batch with tick-hungry walker/pathfinder
    // arenas (single searches hold the server thread for seconds) starves those
    // calls into uniform 8000ms timeouts (46 false FAILs, 2026-07-06 round 1).
    // A dedicated batch runs it with the server thread to itself.
    @GameTest(template = "empty", timeoutTicks = 100000, batch = "agentValidation")
    public static void agentRpcSmoke(GameTestHelper helper) {
        if (java.lang.System.getenv("AGENT_GT_ONLY") != null && !"agentRpcSmoke".equalsIgnoreCase(java.lang.System.getenv("AGENT_GT_ONLY"))) { helper.succeed(); return; } // gt-filter
        if (AgentDriverCommon.api() == null) {
            helper.fail("AgentApi not initialized — was the mod loaded?");
            return;
        }
        AgentDriverCommon.api().seedTestArea();

        AtomicReference<Integer> result = new AtomicReference<>();
        AtomicReference<Throwable> crash = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { result.set(AgentDriverCommon.runValidation()); }
            catch (Throwable e) { crash.set(e); }
        }, "AgentDriver-GameTest");
        worker.setDaemon(true);
        worker.start();

        helper.startSequence()
                .thenWaitUntil(() -> {
                    Throwable c = crash.get();
                    if (c != null) throw new GameTestAssertException("validation crashed: " + c.getMessage());
                    Integer v = result.get();
                    if (v == null) throw new GameTestAssertException("validation still running");
                    if (v != 0)    throw new GameTestAssertException("validation reported " + v + " failure(s); see server log");
                })
                .thenSucceed();
    }

    /**
     * Deterministic regression guard for the vertical-escape fix to the planner BOXED
     * local-minimum pinch (synthetic {@link net.magicterra.agent.bot.debug.PinchArena}).
     * Pure CPU on an in-memory grid — no server world — so it runs synchronously here.
     * Under search-budget pressure the conservative selector is boxed at the cliff foot;
     * the fix must commit pillar-up segments so the re-plan chain scales the wall and
     * reaches the plateau goal. Logs each budget's trail for diagnosis.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void pinchArena(GameTestHelper helper) {
        if (java.lang.System.getenv("AGENT_GT_ONLY") != null && !"pinchArena".equalsIgnoreCase(java.lang.System.getenv("AGENT_GT_ONLY"))) { helper.succeed(); return; } // gt-filter
        StringBuilder log = new StringBuilder("[pinchArena]\n");
        boolean anyReached = false;
        for (int budget : new int[]{150, 300, 600, 2000, 60000}) {
            net.magicterra.agent.bot.debug.PinchArena.Result r =
                    net.magicterra.agent.bot.debug.PinchArena.run(budget);
            log.append("  maxNodes=").append(budget).append(": ").append(r).append('\n');
            anyReached |= r.reached;
        }
        AgentDriverCommon.LOG.info(log.toString());
        if (!anyReached)
            throw new GameTestAssertException("vertical-escape failed to scale the cliff at every budget; see [pinchArena] log");
        helper.succeed();
    }

    /**
     * Deterministic gate for the receding-horizon early-stop
     * ({@link BotConfig#pathfinderHorizonBlocks}) that fixes the long-haul freeze:
     * a far XZ goal in FULLY-LOADED terrain used to grind the whole node budget per
     * search (→ ~30 s wall-clock freeze between tiny segments). On a flat corridor
     * (GridWorldView, isKnown always true so the chunk-frontier commit can't fire),
     * horizon=48 must expand FAR fewer nodes on the first search than horizon=0 and
     * commit a ~48-block forward hop — yet the re-plan chain must still cover the whole
     * corridor (no loss of forward reach).
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void horizonArena(GameTestHelper helper) {
        if (java.lang.System.getenv("AGENT_GT_ONLY") != null && !"horizonArena".equalsIgnoreCase(java.lang.System.getenv("AGENT_GT_ONLY"))) { helper.succeed(); return; } // gt-filter
        net.magicterra.agent.bot.debug.HorizonArena.Result off =
                net.magicterra.agent.bot.debug.HorizonArena.run(0);
        net.magicterra.agent.bot.debug.HorizonArena.Result on =
                net.magicterra.agent.bot.debug.HorizonArena.run(48);
        AgentDriverCommon.LOG.info("[horizonArena] off={} on={}", off, on);

        // 1) Horizon truncates the search → far cheaper first search (this IS the freeze fix).
        if (!(on.firstExpanded < off.firstExpanded))
            throw new GameTestAssertException("horizon=48 should expand fewer nodes than horizon=0; off="
                    + off + " on=" + on);
        // 2) Horizon commits a bounded ~48-block forward hop; horizon=0 commits a much longer segment.
        if (on.firstEndX < 40 || on.firstEndX > 90)
            throw new GameTestAssertException("horizon=48 first segment should end ~48 blocks out, got x=" + on.firstEndX);
        if (!(off.firstEndX > on.firstEndX))
            throw new GameTestAssertException("horizon=0 should commit a longer segment than horizon=48; off="
                    + off + " on=" + on);
        // 3) The chain still covers the whole corridor — horizon doesn't lose forward reach.
        if (on.chainEndX < HorizonArenaMinReach())
            throw new GameTestAssertException("horizon=48 chain should reach the corridor end (~"
                    + net.magicterra.agent.bot.debug.HorizonArena.CORRIDOR_LEN + "), got x=" + on.chainEndX);

        // 4) Soft-commit early-stop (BOXED case): with the horizon OFF (so it can't
        //    truncate) but a small soft node budget, the search must commit a best-effort
        //    segment after ~softCommitNodes nodes instead of grinding the whole corridor —
        //    yet still chain forward to the corridor end. This is the freeze fix for
        //    obstacles where horizon can't fire.
        net.magicterra.agent.bot.debug.HorizonArena.Result soft =
                net.magicterra.agent.bot.debug.HorizonArena.run(0, 150);
        AgentDriverCommon.LOG.info("[horizonArena] soft={}", soft);
        if (!(soft.firstExpanded < off.firstExpanded))
            throw new GameTestAssertException("softCommit=150 should expand fewer nodes than the full grind; off="
                    + off + " soft=" + soft);
        if (soft.firstExpanded > 400)
            throw new GameTestAssertException("softCommit=150 first search should stop near the soft budget, expanded=" + soft.firstExpanded);
        if (soft.chainEndX < HorizonArenaMinReach())
            throw new GameTestAssertException("softCommit chain should still reach the corridor end, got x=" + soft.chainEndX);
        helper.succeed();
    }

    /**
     * Pure-CPU regression guard for the manual-input clobber fix: the idle client
     * tick must NOT clear the human's movement keybinds unless the bot itself
     * dirtied them. {@link net.magicterra.agent.bot.movement.InputReleaseGate}
     * encodes that decision (no client classes → runs synchronously on the dedi
     * server). The bug was {@code clientTick} calling {@code releaseKeys()} on
     * EVERY idle tick, fighting the player's WASD/space when no agent was driving.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void inputReleaseGate(GameTestHelper helper) {
        if (java.lang.System.getenv("AGENT_GT_ONLY") != null && !"inputReleaseGate".equalsIgnoreCase(java.lang.System.getenv("AGENT_GT_ONLY"))) { helper.succeed(); return; } // gt-filter
        net.magicterra.agent.bot.movement.InputReleaseGate g =
                new net.magicterra.agent.bot.movement.InputReleaseGate();

        // 1) Manual play: the bot never presses a key. No idle tick may ever ask
        //    for a release — otherwise it clobbers the human's held WASD/space.
        for (int t = 0; t < 100; t++) {
            if (g.consumeRelease())
                throw new GameTestAssertException("released with no bot input at idle tick " + t + " (clobbers manual keys)");
        }

        // 2) After the bot drives (dirties the keybinds), exactly ONE release
        //    cleans up its trailing presses; subsequent idle ticks stay quiet.
        g.markDirtied();
        if (!g.consumeRelease())
            throw new GameTestAssertException("no release after the bot dirtied the keybinds");
        if (g.consumeRelease())
            throw new GameTestAssertException("released twice for a single drive burst");

        // 3) A sustained drive burst still collapses to ONE release on stop.
        for (int t = 0; t < 20; t++) g.markDirtied();
        if (!g.consumeRelease())
            throw new GameTestAssertException("no release after a sustained drive burst");
        for (int t = 0; t < 50; t++) {
            if (g.consumeRelease())
                throw new GameTestAssertException("released again while idle after the burst at tick " + t);
        }

        // 4) Re-arming works: drive again → one more release.
        g.markDirtied();
        if (!g.consumeRelease())
            throw new GameTestAssertException("gate did not re-arm for a second drive burst");

        helper.succeed();
    }

    /**
     * Physics-parity gate for {@link ServerPlayerAvatar}: a FakePlayer driven by
     * manual travel()+move() must reproduce vanilla movement — horizontal travel,
     * a jumped +1 step-up, and a standing-jump apex (~1.25). If this fails the
     * harness can't be trusted, so the canopy arena is meaningless.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void physicsParity(GameTestHelper helper) {
        if (java.lang.System.getenv("AGENT_GT_ONLY") != null && !"physicsParity".equalsIgnoreCase(java.lang.System.getenv("AGENT_GT_ONLY"))) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 3, cz = 3, floorY = 220, standY = 221;
        buildFloor(level, cx, cz, floorY);

        // 1) Flat sprint travel (+z) for 20 ticks → meaningful forward distance, stays grounded.
        ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, standY, cz + 0.5);
        var fp = av.fakePlayer();
        for (int i = 0; i < 3; i++) { av.commandMove(0, 0); av.step(); }
        double startY = fp.getY(), z0 = fp.getZ();
        fp.setSprinting(true);
        for (int i = 0; i < 20; i++) { fp.setYRot(0f); av.commandForward(1f); av.step(); }
        double disp = fp.getZ() - z0;
        fp.setSprinting(false);
        if (disp <= 2.0)
            throw new GameTestAssertException("flat travel too small: dz=" + disp + " (expected >2)");
        if (Math.abs(fp.getY() - startY) > 0.4)
            throw new GameTestAssertException("walker left the floor: dy=" + (fp.getY() - startY));

        // 2) Standing jump apex ~1.25.
        av = ServerPlayerAvatar.create(level, cx + 0.5, standY, cz + 0.5);
        fp = av.fakePlayer();
        for (int i = 0; i < 3; i++) { av.step(); }
        double jy0 = fp.getY(), maxY = jy0;
        for (int i = 0; i < 30; i++) {
            av.commandJump(i == 0);
            av.step();
            maxY = Math.max(maxY, fp.getY());
            if (i > 3 && fp.onGround()) break;
        }
        double apex = maxY - jy0;
        if (apex < 1.0 || apex > 1.5)
            throw new GameTestAssertException("jump apex off: " + apex + " (expected ~1.25)");

        // 3) Jumped +1 step-up: a full block ahead is cleared by forward+jump.
        av = ServerPlayerAvatar.create(level, cx + 0.5, standY, cz + 0.5);
        fp = av.fakePlayer();
        for (int dx = -1; dx <= 1; dx++)
            level.setBlockAndUpdate(new BlockPos(cx + dx, standY, cz + 3), Blocks.STONE.defaultBlockState());
        for (int i = 0; i < 3; i++) { av.step(); }
        double su0 = fp.getY();
        fp.setSprinting(true);
        for (int i = 0; i < 30; i++) { fp.setYRot(0f); av.commandForward(1f); av.commandJump(fp.onGround()); av.step(); }
        double climbed = fp.getY() - su0;
        if (climbed < 0.9)
            throw new GameTestAssertException("jumped +1 step-up failed: climbed=" + climbed);

        AgentDriverCommon.LOG.info("[physicsParity] disp={} apex={} climbed={}", disp, apex, climbed);
        helper.succeed();
    }

    /**
     * Gates {@link BotConfig#isUsableBuildBlock} + the LevelWorldView placement-count
     * delegation: the bot must NOT treat bamboo (a thin, non-full-cube block it can't
     * stand on) as a build/support block, even though bamboo {@code blocksMotion()} —
     * the old predicate accepted it and the bot "搭路卡死" placing bamboo it couldn't
     * climb. Also gates the Agent-settable whitelist override.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void buildBlockWhitelistArena(GameTestHelper helper) {
        if (java.lang.System.getenv("AGENT_GT_ONLY") != null && !"buildBlockWhitelistArena".equalsIgnoreCase(java.lang.System.getenv("AGENT_GT_ONLY"))) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 460, cz = 460, floorY = 220;
        buildFloor(level, cx, cz, floorY);

        Set<String> savedWl = BotConfig.buildBlockWhitelist;
        try {
            // Default heuristic: full cube → usable; bamboo (thin column) → NOT usable.
            if (!BotConfig.isUsableBuildBlock(Blocks.DIRT))
                throw new GameTestAssertException("dirt must be a usable build block");
            if (!BotConfig.isUsableBuildBlock(Blocks.COBBLESTONE))
                throw new GameTestAssertException("cobblestone must be a usable build block");
            if (BotConfig.isUsableBuildBlock(Blocks.BAMBOO))
                throw new GameTestAssertException("bamboo must NOT be a usable build block (thin, no footing)");
            if (BotConfig.isUsableBuildBlock(Blocks.SAND))
                throw new GameTestAssertException("sand must NOT be usable (FallingBlock drops over gaps)");

            // LevelWorldView count delegates to the predicate: dirt+bamboo inventory → only dirt counts.
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            FakePlayer fp = av.fakePlayer();
            fp.getInventory().clearContent();
            fp.getInventory().add(new ItemStack(Items.DIRT, 10));
            fp.getInventory().add(new ItemStack(Items.BAMBOO, 20));
            LevelWorldView w = new LevelWorldView(level, fp);
            if (w.placeableBlockCount() != 10)
                throw new GameTestAssertException("placeableBlockCount should ignore bamboo, expected 10 got "
                        + w.placeableBlockCount());

            // Whitelist override: pin to cobblestone only → dirt now rejected, bamboo allowed by explicit id.
            BotConfig.buildBlockWhitelist = Set.of("minecraft:bamboo");
            if (!BotConfig.isUsableBuildBlock(Blocks.BAMBOO))
                throw new GameTestAssertException("whitelisted bamboo must be usable when explicitly listed");
            if (BotConfig.isUsableBuildBlock(Blocks.DIRT))
                throw new GameTestAssertException("dirt must be rejected when whitelist excludes it");
            if (w.placeableBlockCount() != 20)
                throw new GameTestAssertException("whitelist=[bamboo] → count should be the 20 bamboo, got "
                        + w.placeableBlockCount());
        } finally {
            BotConfig.buildBlockWhitelist = savedWl;
        }
        helper.succeed();
    }

    /**
     * JSON round-trip gate for {@link PathArchive}: {@link PathArchive#demo()} →
     * {@link PathArchive#toJson()} → {@link PathArchive#fromJson(String)} must
     * reproduce identical structural sizes for every top-level collection.
     * Pure CPU; no world interaction needed.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void pathArchiveJsonArena(GameTestHelper helper) {
        if (java.lang.System.getenv("AGENT_GT_ONLY") != null && !"pathArchiveJsonArena".equalsIgnoreCase(java.lang.System.getenv("AGENT_GT_ONLY"))) { helper.succeed(); return; } // gt-filter
        PathArchive a = PathArchive.demo();
        String json = a.toJson();
        PathArchive b = PathArchive.fromJson(json);
        helper.assertTrue(b.header().seed() == a.header().seed(), "seed round-trips");
        helper.assertTrue(b.segments().size() == a.segments().size(), "segments round-trip");
        helper.assertTrue(b.trajectory().size() == a.trajectory().size(), "trajectory round-trips");
        helper.assertTrue(b.segments().get(0).nodes().size() == a.segments().get(0).nodes().size(), "nodes round-trip");
        helper.assertTrue(b.envelope().size() == a.envelope().size(), "envelope round-trips");
        // v2: full block-state SNBT + block-entity NBT must survive the round-trip.
        PathArchive.EnvelopeCell ea = a.envelope().get(0);
        PathArchive.EnvelopeCell eb = b.envelope().get(0);
        helper.assertTrue(java.util.Objects.equals(ea.state(), eb.state()),
                "envelope state round-trips: " + ea.state() + " vs " + eb.state());
        helper.assertTrue(java.util.Objects.equals(ea.nbt(), eb.nbt()),
                "envelope nbt round-trips: " + ea.nbt() + " vs " + eb.nbt());
        helper.succeed();
    }

    /**
     * Verifies {@link NodePhysics#compute} returns accurate pose-fit and hazard facts
     * for three representative cells:
     * <ol>
     *   <li>An open 2-high cell — bot can stand, ceiling forces "none".</li>
     *   <li>A 1-high capped pocket — bot cannot stand (ceiling forces crouch/crawl/suffocate).</li>
     *   <li>A cell with lava underfoot — footHazard = "lava".</li>
     * </ol>
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void nodePhysicsArena(GameTestHelper helper) {
        if (java.lang.System.getenv("AGENT_GT_ONLY") != null && !"nodePhysicsArena".equalsIgnoreCase(java.lang.System.getenv("AGENT_GT_ONLY"))) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        // cx=240, cz=240, floorY=179; stand cells at y=180.
        final int cx = 240, cz = 240, floorY = 179;

        // Clear a wide air box first to avoid leftover block collisions.
        for (int dx = -5; dx <= 10; dx++)
            for (int dz = -5; dz <= 5; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.AIR.defaultBlockState());
                for (int dy = 1; dy <= 5; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
            }

        // --- Cell 1: open 2-high stand cell at (240,180,240) ---
        // floor at y=179, air at y=180 and y=181
        level.setBlockAndUpdate(new BlockPos(240, floorY, 240), Blocks.STONE.defaultBlockState());
        // y=180 and y=181 are already air from the clear above.

        // --- Cell 2: 1-high capped pocket at (242,180,240) ---
        // floor at y=179, air at y=180, solid cap at y=181
        level.setBlockAndUpdate(new BlockPos(242, floorY, 240), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(242, floorY + 2, 240), Blocks.STONE.defaultBlockState());

        // --- Cell 3: lava underfoot at (244,179,240), foot=(244,180,240) ---
        level.setBlockAndUpdate(new BlockPos(244, floorY, 240), Blocks.LAVA.defaultBlockState());

        // Assert cell 1: open cell — can stand, ceiling forces "none"
        BlockPos stand = new BlockPos(240, 180, 240);
        NodePhysics.Facts f = NodePhysics.compute(level, stand, null, null);
        helper.assertTrue(f.fitStand() && "none".equals(f.ceilingForces()),
                "open cell should stand; fitStand=" + f.fitStand() + " ceilingForces=" + f.ceilingForces());

        // Assert cell 2: 1-cap pocket — cannot stand
        BlockPos pocket = new BlockPos(242, 180, 240);
        NodePhysics.Facts g = NodePhysics.compute(level, pocket, stand, null);
        helper.assertTrue(!g.fitStand()
                        && ("crouch".equals(g.ceilingForces()) || "crawl".equals(g.ceilingForces())
                            || "suffocate".equals(g.ceilingForces())),
                "1-cap pocket should not stand; fitStand=" + g.fitStand() + " ceilingForces=" + g.ceilingForces());

        // Assert cell 3: lava underfoot is a hazard
        BlockPos lavaFoot = new BlockPos(244, 180, 240);
        NodePhysics.Facts h = NodePhysics.compute(level, lavaFoot, null, null);
        helper.assertTrue("lava".equals(h.footHazard()),
                "lava underfoot should be footHazard=lava; got=" + h.footHazard());

        // --- Edit-aware: the planned toBreak/toPlace change the pose-fit verdict ---
        // The 1-cap pocket (242) cannot stand raw, but if the entering edge BREAKS the
        // y181 cap (as a stairUpBreak does) the standing box fits → no false SUFFOCATE.
        BlockPos cap = new BlockPos(242, floorY + 2, 240);   // (242,181,240)
        NodePhysics.Facts gBroke = NodePhysics.compute(level, pocket, stand, null,
                java.util.List.of(cap), java.util.List.of());
        helper.assertTrue(gBroke.fitStand() && "none".equals(gBroke.ceilingForces()),
                "pocket with toBreak{cap} should stand; fitStand=" + gBroke.fitStand()
                        + " ceilingForces=" + gBroke.ceilingForces());

        // Conversely the open stand cell (240) fits raw, but a planned toPlace into its
        // head cell makes it collide → the place is correctly reflected.
        BlockPos standHead = new BlockPos(240, 181, 240);
        NodePhysics.Facts fPlaced = NodePhysics.compute(level, stand, null, null,
                java.util.List.of(), java.util.List.of(standHead));
        helper.assertTrue(!fPlaced.fitStand(),
                "open cell with toPlace{head} should not stand; fitStand=" + fPlaced.fitStand());

        helper.succeed();
    }

    /**
     * End-to-end capture round-trip for {@link PathArchiveRecorder}.
     *
     * <p>Installs a fresh {@link PathArchiveRecorder} as a {@link MultiTrace} alongside a
     * no-op second sink, then drives a real 8-block Walker goto on flat stone, waits for
     * {@link PathTrace.Outcome#SUCCESS} (or any terminal), and asserts that a JSON archive
     * was written to disk with at least one segment, at least one trajectory tick, and a
     * non-null dimension in the header.</p>
     *
     * <p>Uses the direct-sink approach: the recorder is inserted into
     * {@link PathTraceHolder#SINK} for the duration of this test and restored afterward,
     * so this arena does not depend on {@link PathDebugBootstrap#init()} having run.
     * The file is located via {@link PathArchiveRecorder#lastWrittenPath()}, which avoids
     * any CWD ambiguity.</p>
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void pathArchiveCaptureArena(GameTestHelper helper) {
        if (java.lang.System.getenv("AGENT_GT_ONLY") != null && !"pathArchiveCaptureArena".equalsIgnoreCase(java.lang.System.getenv("AGENT_GT_ONLY"))) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 350, cz = 350, floorY = 220;

        // Build a flat 20x5 stone runway at floorY; bot stands at floorY+1.
        for (int dx = -2; dx <= 20; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
                for (int dy = 1; dy <= 5; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
            }

        // Install a fresh archive recorder alongside a NOOP second sink.
        PathArchiveRecorder archive = new PathArchiveRecorder();
        PathTrace previousSink = PathTraceHolder.SINK;
        PathTraceHolder.SINK = new MultiTrace(archive, PathTrace.NOOP);

        // Save & patch BotConfig: enable archive capture, deterministic planner budget.
        boolean opa = BotConfig.pathArchive;
        boolean ob  = BotConfig.allowBreak, op = BotConfig.allowPlace;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.pathArchive = true;
        BotConfig.allowBreak  = false;
        BotConfig.allowPlace  = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs   = Long.MAX_VALUE / 2;

        try {
            BlockPos goal = new BlockPos(cx + 8, floorY + 1, cz);
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            // Set BotLevelHolder so the recorder can read dimension/seed from the level.
            BotLevelHolder.current = level;
            LevelWorldView w = new LevelWorldView(level, av.fakePlayer());

            Walker walker = new Walker();
            walker.setGoal(new Goal.Block(goal));
            Walker.Step s = Walker.Step.WALKING;
            for (int t = 0; t < 1000 && s == Walker.Step.WALKING; t++) {
                s = walker.tick(av, w);
                av.step();
            }
            AgentDriverCommon.LOG.info("[pathArchiveCaptureArena] terminal step={} pos=({},{},{})",
                    s, av.fakePlayer().getX(), av.fakePlayer().getY(), av.fakePlayer().getZ());
        } finally {
            BotConfig.pathArchive       = opa;
            BotConfig.allowBreak        = ob;
            BotConfig.allowPlace        = op;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs   = omm;
            PathTraceHolder.SINK = previousSink;
        }

        // The write is dispatched to a daemon thread; give it up to 2 s.
        long deadline = System.currentTimeMillis() + 2000;
        while (archive.lastWrittenPath() == null && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(20); } catch (InterruptedException ignored) {}
        }

        String writtenPath = archive.lastWrittenPath();
        if (writtenPath == null)
            throw new GameTestAssertException("pathArchiveCapture: no replay file was written within 2 s");

        Path replayPath = Path.of(writtenPath);
        if (!Files.exists(replayPath))
            throw new GameTestAssertException("pathArchiveCapture: replay file does not exist on disk: " + writtenPath);

        String json;
        try {
            json = Files.readString(replayPath);
        } catch (Exception e) {
            throw new GameTestAssertException("pathArchiveCapture: failed to read replay file: " + e);
        }

        PathArchive a = PathArchive.fromJson(json);
        helper.assertTrue(a.segments().size() >= 1,
                "pathArchiveCapture: expected >= 1 segment, got " + a.segments().size());
        helper.assertTrue(a.trajectory().size() >= 1,
                "pathArchiveCapture: expected >= 1 trajectory tick, got " + a.trajectory().size());
        helper.assertTrue(a.header().dimension() != null && !a.header().dimension().isEmpty(),
                "pathArchiveCapture: header.dimension is null or empty");

        helper.succeed();
    }

    /**
     * End-to-end PROOF of the path archive/replay feature: record a goto archive,
     * then replay that archive and assert the bot reaches the same goal with bounded
     * per-step deviation. This is the behavioural certificate that record → replay
     * round-trips faithfully.
     *
     * <p><b>Replay-trigger approach:</b> the test drives the replay DIRECTLY via
     * {@link Walker#beginReplay} on a fresh Walker (mirroring what {@code ReplayProcess}
     * does) rather than the {@code mc.debug.replay} route / {@code BotApiImpl.startReplay}.
     * The latter run through {@code onClient(...)} and require
     * {@code Minecraft.getInstance().player}, which does not exist on the dedicated
     * GameTest server — so the in-test route is structurally unavailable here. The
     * replay-run capture path is identical either way: we {@link PathArchiveRecorder#armReplay}
     * exactly as {@code BotApiImpl.startReplay} does, so the {@code replay-run-*.json}
     * with per-tick deviation is produced by the same machinery the live tool uses.</p>
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void replayRoundTripArena(GameTestHelper helper) {
        if (java.lang.System.getenv("AGENT_GT_ONLY") != null && !"replayRoundTripArena".equalsIgnoreCase(java.lang.System.getenv("AGENT_GT_ONLY"))) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 380, cz = 380, floorY = 220;

        // Flat 20x7 stone runway; bot stands at floorY+1.
        for (int dx = -2; dx <= 20; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
                for (int dy = 1; dy <= 5; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
            }

        // Install a fresh archive recorder as the active sink.
        PathArchiveRecorder archive = new PathArchiveRecorder();
        PathTrace previousSink = PathTraceHolder.SINK;
        PathTraceHolder.SINK = new MultiTrace(archive, PathTrace.NOOP);

        boolean opa = BotConfig.pathArchive;
        boolean ob  = BotConfig.allowBreak, op = BotConfig.allowPlace;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.pathArchive = true;     // gates sampleTick for BOTH record + replay capture
        BotConfig.allowBreak  = false;
        BotConfig.allowPlace  = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs   = Long.MAX_VALUE / 2;

        final BlockPos goal = new BlockPos(cx + 8, floorY + 1, cz);
        BlockPos recordedArrival;

        try {
            BotLevelHolder.current = level;

            // ---- Phase 1: RECORD a real goto from A to B (== pathArchiveCaptureArena). ----
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            LevelWorldView w = new LevelWorldView(level, av.fakePlayer());

            Walker walker = new Walker();
            walker.setGoal(new Goal.Block(goal));
            Walker.Step s = Walker.Step.WALKING;
            for (int t = 0; t < 1000 && s == Walker.Step.WALKING; t++) {
                s = walker.tick(av, w);
                av.step();
            }
            recordedArrival = av.fakePlayer().blockPosition();
            AgentDriverCommon.LOG.info("[replayRoundTrip] record terminal step={} arrival={}", s, recordedArrival);

            // Give the background plan-archive write up to 2 s.
            long deadline = System.currentTimeMillis() + 2000;
            while (archive.lastWrittenPath() == null && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(20); } catch (InterruptedException ignored) {}
            }
            String planPath = archive.lastWrittenPath();
            if (planPath == null)
                throw new GameTestAssertException("replayRoundTrip: no plan archive written within 2 s");

            String planJson;
            try {
                planJson = Files.readString(Path.of(planPath));
            } catch (Exception e) {
                throw new GameTestAssertException("replayRoundTrip: failed to read plan archive: " + e);
            }
            PathArchive planArchive = PathArchive.fromJson(planJson);
            helper.assertTrue(planArchive.segments().size() >= 1,
                    "replayRoundTrip: plan archive has no segments");

            // ---- Phase 2: REBUILD the concatenated plan + edges (mirrors ReplayTool). ----
            List<BlockPos> plan = new ArrayList<>();
            List<Move.Edge> edges = new ArrayList<>();
            for (PathArchive.Segment seg : planArchive.segments()) {
                List<int[]> segPath = seg.path();
                if (segPath.isEmpty()) continue;
                List<Move.Edge> segEdges = new ArrayList<>(segPath.size());
                segEdges.add(null);   // re-insert the leading start-node sentinel
                for (PathArchive.EdgeRec er : seg.edges()) {
                    List<BlockPos> toBreak = new ArrayList<>();
                    for (int[] c : er.breakCells()) toBreak.add(new BlockPos(c[0], c[1], c[2]));
                    List<BlockPos> toPlace = new ArrayList<>();
                    for (int[] c : er.placeCells()) toPlace.add(new BlockPos(c[0], c[1], c[2]));
                    segEdges.add(new Move.Edge(null, er.cost(), toBreak, toPlace, er.move()));
                }
                while (segEdges.size() < segPath.size()) segEdges.add(null);

                int from = 0;
                if (!plan.isEmpty()) {
                    int[] first = segPath.get(0);
                    BlockPos last = plan.get(plan.size() - 1);
                    if (last.getX() == first[0] && last.getY() == first[1] && last.getZ() == first[2]) from = 1;
                }
                for (int i = from; i < segPath.size(); i++) {
                    int[] n = segPath.get(i);
                    plan.add(new BlockPos(n[0], n[1], n[2]));
                    edges.add(i < segEdges.size() ? segEdges.get(i) : null);
                }
            }
            helper.assertTrue(plan.size() >= 2,
                    "replayRoundTrip: concatenated plan too short: " + plan.size());

            BlockPos startFoot = plan.get(0);
            BlockPos lastNode  = plan.get(plan.size() - 1);

            // ---- Phase 3: REPLAY via a fresh Walker.beginReplay + armed capture. ----
            // Arm the replay recorder BEFORE the first replay tick, exactly as
            // BotApiImpl.startReplay does, so the replay-run + per-tick deviation is captured.
            archive.armReplay(plan, Path.of(planPath).getFileName().toString());

            ServerPlayerAvatar rav = ServerPlayerAvatar.create(
                    level, startFoot.getX() + 0.5, startFoot.getY(), startFoot.getZ() + 0.5);
            LevelWorldView rw = new LevelWorldView(level, rav.fakePlayer());

            Walker replay = new Walker();
            replay.beginReplay(rw, plan, edges, new Goal.Block(lastNode), startFoot);
            Walker.Step rs = Walker.Step.WALKING;
            int maxTicks = 1500;   // guard: a hang FAILS the test rather than spins forever
            int t = 0;
            for (; t < maxTicks && rs == Walker.Step.WALKING; t++) {
                rs = replay.tick(rav, rw);
                rav.step();
            }
            BlockPos replayArrival = rav.fakePlayer().blockPosition();
            AgentDriverCommon.LOG.info("[replayRoundTrip] replay terminal step={} ticks={} arrival={}",
                    rs, t, replayArrival);
            helper.assertTrue(t < maxTicks,
                    "replayRoundTrip: replay did not terminate within " + maxTicks + " ticks");

            // ---- Assert: replay reproduced the route to the same goal (±2 XZ). ----
            int dgx = Math.abs(replayArrival.getX() - goal.getX());
            int dgz = Math.abs(replayArrival.getZ() - goal.getZ());
            helper.assertTrue(dgx <= 2 && dgz <= 2,
                    "replayRoundTrip: replay arrival " + replayArrival + " not within +/-2 XZ of goal " + goal
                            + " (dx=" + dgx + ", dz=" + dgz + ")");

            // ---- Assert: a replay-run-*.json was written with bounded deviation. ----
            long rdeadline = System.currentTimeMillis() + 2000;
            while (archive.lastReplayRunPath() == null && System.currentTimeMillis() < rdeadline) {
                try { Thread.sleep(20); } catch (InterruptedException ignored) {}
            }
            String runPath = archive.lastReplayRunPath();
            if (runPath == null)
                throw new GameTestAssertException("replayRoundTrip: no replay-run file written within 2 s");
            if (!Files.exists(Path.of(runPath)))
                throw new GameTestAssertException("replayRoundTrip: replay-run file missing on disk: " + runPath);

            String runJson;
            try {
                runJson = Files.readString(Path.of(runPath));
            } catch (Exception e) {
                throw new GameTestAssertException("replayRoundTrip: failed to read replay-run file: " + e);
            }
            PathArchive run = PathArchive.fromJson(runJson);
            helper.assertTrue("replay".equals(run.kind()),
                    "replayRoundTrip: expected kind=replay, got " + run.kind());
            helper.assertTrue(!run.trajectory().isEmpty(),
                    "replayRoundTrip: replay-run trajectory is empty");

            double maxDev = 0.0;
            for (PathArchive.Tick tk : run.trajectory()) {
                double d = tk.deviation();
                if (!Double.isNaN(d) && d > maxDev) maxDev = d;
            }
            AgentDriverCommon.LOG.info("[replayRoundTrip] trajectory ticks={} maxDeviation={}",
                    run.trajectory().size(), maxDev);
            helper.assertTrue(maxDev <= 4.0,
                    "replayRoundTrip: max per-tick deviation " + maxDev + " exceeds bound 4.0");

            helper.succeed();
        } finally {
            BotConfig.pathArchive       = opa;
            BotConfig.allowBreak        = ob;
            BotConfig.allowPlace        = op;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs   = omm;
            PathTraceHolder.SINK = previousSink;
        }
    }
}
