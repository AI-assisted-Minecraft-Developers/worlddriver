package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.bot.process.BotProcess;
import net.magicterra.worlddriver.bot.movement.Avatar;
import net.magicterra.worlddriver.bot.sim.JoinedPlayerBodies;
import net.magicterra.worlddriver.bot.sim.ServerAvatarManager;
import net.magicterra.worlddriver.bot.sim.ServerPlayerAvatar;
import net.magicterra.worlddriver.bot.sim.ServerWorldDriver;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;

/**
 * The body, the world and the bookkeeping one journey run shares.
 *
 * <p>A stage scene's whole interaction with the playthrough goes through here: {@link #enter} to
 * join the run (or be turned away because a rung below it never got climbed), {@link #drive} to make
 * the body do something and wait for it, {@link #reach} to claim a rung with evidence.
 *
 * <h2>Four things this solves that a normal scene never has to</h2>
 *
 * <b>1. The body outlives the scene.</b> Every other suite in this repo mints a fresh avatar per
 * scene and discards it in {@code cleanup}. A playthrough cannot: the whole claim is that the same
 * body carried the same inventory from the first log to the dragon. So the avatar is held statically
 * and torn down once, by the verdict scene, on every exit path.
 *
 * <p><b>2. A walking body needs its chunks.</b> The harness force-loads each scene's arena, and the
 * journey leaves that arena immediately — it plays at world spawn and then walks for kilometres.
 * A fake player is not in the player list, so it holds no tickets of its own and would walk straight
 * into unloaded chunks, where blocks read as air and entities do not exist. Worse, that failure is
 * quiet: the body keeps moving and every assertion about what it should have found simply comes back
 * empty. {@link #pinAroundBody} keeps a region ticket travelling with it, refreshed from the await
 * condition so it tracks the body on the same tick cadence the body moves at.
 *
 * <p><b>3. The suite pins a world a playthrough cannot happen in.</b> {@code WorldPin} freezes the
 * clock at midnight and turns mob spawning off, per scene, for excellent reasons that all belong to
 * the other 222 scenes. A playthrough needs night to pass, sheep to exist and blazes to spawn, so
 * stages that need a live world ask for it explicitly through {@link #liveWorld} and hand it back in
 * {@code cleanup} — the arena audit reports a gamerule left flipped as a leak, and it is right to.
 *
 * <p><b>4. A missed rung must be recorded by the run that missed it.</b> A scene that fails or times
 * out never reaches its own last line, so a stage cannot write its own obituary. {@link #enter}
 * registers a cleanup that marks the stage FAILED unless something marked it reached — cleanups run
 * on PASS, FAIL and TIMEOUT alike, so the ledger is complete however the scene ended.
 *
 * <h2>What this rig cannot test, and must not pretend to</h2>
 *
 * The body is a {@code FakePlayer}: on both loaders it is invulnerable, its death is a no-op and it
 * opens no menus. That is not a defect of this rig — it is what a server-side avatar is — but it
 * bounds what a green run means. <b>It shows the API could execute the plan. It does not show a
 * player could survive it.</b> Hunger, fall damage, drowning, mob threat and every container-driven
 * interaction are outside what this track can observe. {@link #bodyIsInvulnerable} states the
 * limitation into the record of every stage, so no green row can be read as more than it is.
 *
 * <p><b>And that bound does not lift on the client topologies.</b> The ladder runs under three of
 * them now ({@code journeyServer} / {@code journeyIntegratedServer} /
 * {@code journeyDedicatedServerWithClient}), and all three climb on the body {@link #spawnBody()}
 * builds — never on the human client's player. What varies is the RUN: whether a client half of the
 * driver is loaded at all, whether packets are really encoded, whether a real player holds chunks.
 * Every rung records {@code journey.topology} and {@code journey.body} so that no two rows can be
 * compared without saying which of those two changed.
 *
 * <p>Driving the real player instead is a documented and unbuilt piece of work, not a switch. The
 * transport is there — under the integrated topology this JVM also holds {@code BotHooks}, so a
 * scene body can reach the client bot through {@code DriverApi.route("mc.bot.*", …)} — but this rig
 * is written against {@link ServerWorldDriver} end to end: {@link #drive} hands a
 * {@code BotProcess} OBJECT to {@code ServerAvatarManager}, and the client side takes verbs and
 * params instead and reports completion only by polling {@code status()}. Two further walls stand
 * behind that one. {@link #breakItWhereItStands} is server-only by construction — {@code
 * Avatar.breakHold} on a client sets a keybind and breaks nothing without a multi-tick {@code
 * continueDestroy}. And {@code DriverApi}'s own {@code awaitMs} / {@code mc.wait.*} sleep the
 * CALLING thread, which from a scene body is the server thread, so the obvious way to wait for a
 * client process stops the server that the client process is waiting on.
 */
public final class JourneyRig {

    /**
     * Keeps the chunks under the travelling body loaded.
     *
     * <p>Distinct from {@code DriverApi}'s test-arena ticket and from the harness's arena window,
     * both of which pin a fixed place. This one moves. Not persisted, so it cannot outlive the
     * process or end up in a saved world.
     */
    private static final TicketType<ChunkPos> JOURNEY_TICKET =
            TicketType.create("worlddriver_journey", Comparator.comparingLong(ChunkPos::toLong));

    /**
     * Chunks either side of the body that must stay loaded.
     *
     * <p>Two is the smallest radius that keeps the body's own chunk ENTITY_TICKING while it walks —
     * the pathfinder reads a few chunks ahead, and an entity search that silently sees nothing is
     * the failure mode this exists to prevent.
     */
    private static final int BODY_CHUNK_RADIUS = 2;

    /**
     * The radius actually in force, which a rung may widen for as long as it needs to SEE further.
     *
     * <p>Two chunks keeps a walking body's own chunk entity-ticking, and for walking that is the
     * right number. It is the wrong number for a rung that searches: entities exist only in loaded
     * chunks, so a scan of 96 blocks around a body with 32 blocks pinned is a scan whose outer two
     * thirds are guaranteed to be empty — and it does not report that, it reports "there are no
     * animals here". Measured: the food rung failed with
     * {@code 方圆 96 格内没有掉落食物的动物} on a swamp that has them, from a body that had walked
     * away from spawn on the rung before.
     *
     * <p>Widened per rung rather than raised for everyone, because every extra chunk is entity
     * ticking the other rungs pay for and none of them need.
     */
    private static int bodyChunkRadius = BODY_CHUNK_RADIUS;

    /** Widen the travelling pin for a rung that searches further than it walks. Never narrows. */
    public static void seeAtLeast(int chunks) {
        bodyChunkRadius = Math.max(BODY_CHUNK_RADIUS, chunks);
    }

    /** Back to the walking radius. Rungs that widen must register this as a cleanup. */
    public static void seeNormally() {
        bodyChunkRadius = BODY_CHUNK_RADIUS;
    }

    // ---- the run's shared state ----

    private static ServerWorldDriver driver;
    private static ChunkPos pinned;
    private static ServerLevel pinnedLevel;

    // ---- this stage's state ----

    private final SceneContext ctx;
    private final JourneyStage stage;
    private final Map<String, Object> evidence = new LinkedHashMap<>();
    /** Keys this stage wrote more than once with DIFFERENT values, in the order the clashes
     *  happened. Rendered into {@code evidence.clash} — see {@link #evidence}. */
    private final List<String> clashes = new ArrayList<>();
    private String note = "未记录原因";
    private boolean claimed;
    /**
     * Non-null once the body has fallen out of the world, holding the reading that proves it.
     *
     * <p>Measured 2026-08-18 on rung 20: the body left the island during the fourth crystal's leg,
     * and the rung then spent <b>five more legs and five more towers — about 15 000 ticks, half its
     * entire budget — issuing orders to a body at y=-1514, then -13270, then -26833, then -40396,
     * then -55767</b>. Every one of those legs ran its full 2999 ticks, every one recorded
     * {@code 脚下=void_air 放了 0 块}, and the rung's verdict was「打不到龙」— a symptom of a body
     * 69 457 blocks from the arena, naming neither the fall nor the leg it happened on.
     *
     * <p>Nothing stops the fall on its own: both bodies override
     * {@code isInvulnerableTo} to {@code true}, so vanilla's {@code Entity.checkBelowWorld} calls
     * {@code onBelowWorld} every tick and the out-of-world damage it deals is refused. A body that
     * leaves this world falls forever.
     */
    private String lostTheWorld;
    private BlockPos lastGrounded;
    private int sinceGrounded;

    private JourneyRig(SceneContext ctx, JourneyStage stage) {
        this.ctx = ctx;
        this.stage = stage;
    }

    /**
     * Join the run at this rung, or leave without attempting it.
     *
     * <p>When the rung below was not climbed this records BLOCKED and {@code skip}s the scene. A
     * skip resolves PASS carrying its reason, which is the right outcome and the one that needs
     * defending: the alternative is N red rows for one root cause, which buries the row that
     * actually says something. The verdict scene is what turns "the run only got this far" into a
     * failure, once, against a floor that was written down deliberately.
     */
    public static JourneyRig enter(SceneContext ctx, JourneyStage stage) {
        // BEFORE the blocked check, so a rung that never gets attempted still says which run it
        // declined to be attempted in. A results row without these two cannot be compared with the
        // same row from another topology, and comparing them is now the whole reason three exist.
        String topology = topology(ctx);
        String body = bodyDescription();
        ctx.record(TOPOLOGY_KEY, topology);
        ctx.record(BODY_KEY, body);
        JourneyStage below = stage.requires();
        if (below != null && !JourneyLedger.has(below)) {
            JourneyLedger.blocked(stage, below, tick(ctx));
            ctx.skip("BLOCKED: 上游阶段 " + below.name() + "(" + below.label() + ") 未达成，"
                    + stage.name() + " 不予尝试");
        }
        JourneyRig rig = new JourneyRig(ctx, stage);
        // Into the LEDGER's evidence map as well, which the results row does not carry: the verdict
        // scene prints the ledger, and a ledger that does not name the run it describes is the same
        // uncomparable row one level up. The raw put rather than `evidence(...)`, because this is the
        // rig's FIRST write of both keys and the value is already in ctx — routing it through the
        // clash detector would only re-record what is there.
        rig.evidence.put(TOPOLOGY_KEY, topology);
        rig.evidence.put(BODY_KEY, body);
        // The obituary. A scene that times out never reaches its own last line, so the only place
        // a missed rung can be written down is a cleanup — those drain on every exit path.
        ctx.cleanup(() -> {
            if (!rig.claimed) {
                JourneyLedger.failed(stage, rig.note, rig.evidence, tick(ctx));
            }
        });
        ctx.record("journey.stage", stage.name() + "(" + stage.label() + ")");
        return rig;
    }

    /** The stage this rig is climbing. */
    public JourneyStage stage() { return stage; }

    /** The scene this rig is running inside. */
    public SceneContext ctx() { return ctx; }

    // ---- the body ----

    /**
     * Create the run's body at world spawn, empty-handed.
     *
     * <p>Called by the SPAWN stage and by nothing else — every later stage inherits whatever this
     * body has become. Loads the spawn chunks to completion first: a body placed into a chunk that
     * has only been requested falls through terrain that has not generated yet.
     */
    public ServerWorldDriver spawnBody() {
        ServerLevel level = ctx.level();
        BlockPos spawn = level.getSharedSpawnPos();
        ChunkPos center = new ChunkPos(spawn);
        level.getChunkSource().addRegionTicket(JOURNEY_TICKET, center, bodyChunkRadius + 2, center);
        for (int cx = center.x - bodyChunkRadius; cx <= center.x + bodyChunkRadius; cx++) {
            for (int cz = center.z - bodyChunkRadius; cz <= center.z + bodyChunkRadius; cz++) {
                level.getChunk(cx, cz);
            }
        }
        pinned = center;
        pinnedLevel = level;

        int surface = level.getHeightmapPos(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn).getY();
        driver = ServerWorldDriver.createIsolated(level,
                spawn.getX() + 0.5, surface, spawn.getZ() + 0.5);
        driver.fakePlayer().getInventory().clearContent();
        // Its own key rather than a second write of `journey.body`. This rung ENTERED without a body
        // and leaves with one, so the two readings are both true and neither contradicts the other —
        // and routing a legitimate change through the clash detector would print a WARN on every
        // single run, which is how a diagnostic teaches its reader to stop looking at it.
        evidence("journey.body.spawned", bodyDescription());
        return driver;
    }

    /** The run's body, or null before SPAWN has made one. */
    public static ServerWorldDriver bodyOrNull() { return driver; }

    /** The run's body, failing the scene when there is none — every stage above SPAWN needs it. */
    public ServerWorldDriver body() {
        if (driver == null) {
            ctx.fail("journey: 还没有身体 —— SPAWN 阶段没有成功创建 avatar，" + stage.name() + " 无从谈起");
        }
        return driver;
    }

    /** The body as a {@link ServerPlayer}, for inventory and progress reads. */
    public ServerPlayer player() { return body().fakePlayer(); }

    /**
     * Whether the body under this rig cannot be hurt — <b>asked, not asserted</b>.
     *
     * <p>This was a literal {@code return true} with a comment saying it is always true on the
     * headless track. It is: both bodies override {@code isInvulnerableTo}. But a hardcoded reading
     * is an evidence row that cannot ever report a change, and this row is recorded into every rung
     * as the bound on what a green climb proves — so the day somebody drops the override for a
     * survival-fidelity run, every row would keep claiming the old world. Asking the body costs one
     * virtual call and makes the row follow the code.
     *
     * <p>{@code generic()} rather than the out-of-world source: it is the damage type the existing
     * scenes already probe invulnerability with ({@code WorldDriverScenes}), and both overrides
     * refuse every source alike, so the two answers cannot differ without the override being gone.
     *
     * <p>True when there is no body yet. RECON runs before SPAWN, and「没有身体所以受得了伤」is not
     * a statement anyone should be able to read off this.
     */
    public boolean bodyIsInvulnerable() {
        ServerWorldDriver d = driver;
        if (d == null) return true;
        ServerPlayer fp = d.fakePlayer();
        return fp.isInvulnerableTo(fp.damageSources().generic());
    }

    // ---- which run this rung climbed in ----

    /** Every rung's row says which of the three ladder topologies produced it. */
    private static final String TOPOLOGY_KEY = "journey.topology";

    /** ...and which body did the climbing. Together they are what makes two rows comparable. */
    private static final String BODY_KEY = "journey.body";

    /**
     * Which of the three ladder topologies this run is, read off the running game.
     *
     * <p><b>Read rather than declared.</b> The run tasks already know — they set the properties —
     * and a row that merely echoed a {@code -D} would be green on a run where the property was set
     * and the thing it promises never happened. That failure has a name in this repo: a client half
     * that crashed in architectury's transformer while the server waited 21 minutes for a player.
     * The two questions below are about the JVM and the world, and neither can be answered wrongly
     * by a launch that did not do what it said.
     *
     * <ul>
     *   <li>{@code isDedicatedServer()} — false only inside a game client hosting its own world.</li>
     *   <li>{@link BotHooks#isAvailable()} — the client half of the DRIVER, registered only by a
     *       loader's client entrypoint. This is the fact that decides whether a rung's failure could
     *       possibly be client-side code at all: on a dedicated server that code is not merely
     *       unexercised, it is absent from the JVM.</li>
     * </ul>
     *
     * <p>The human players are listed with their DIMENSION, and that is the load-bearing part rather
     * than decoration. {@code ServerLevel.players()} is per level, and a client standing at world
     * spawn contributes to the overworld's list and to no other — which is why every ladder topology
     * still arms {@code -Dworlddriver.realPlayerBodies=true}. Rungs 14–15 ask the NETHER's list
     * (BaseSpawner.isNearPlayer) and 19–20 ask the END's (EndDragonFight.tick); a run that read
     * 「有真玩家」and dropped the flag would find neither blazes nor a dragon, silently.
     */
    private static String topology(SceneContext ctx) {
        MinecraftServer server = ctx.server();
        boolean dedicated = server == null || server.isDedicatedServer();
        boolean driverClientHalf = BotHooks.isAvailable();
        List<ServerPlayer> humans = humanPlayers(ctx);
        String kind;
        if (!dedicated) {
            kind = "integratedServer";
        } else if (!humans.isEmpty()) {
            kind = "dedicatedServerWithClient";
        } else {
            kind = "dedicatedServer";
        }
        StringBuilder s = new StringBuilder(kind)
                .append("（真玩家 ").append(humans.size());
        for (ServerPlayer p : humans) {
            BlockPos at = p.blockPosition();
            s.append("：").append(p.getGameProfile().getName())
                    .append('@').append(p.level().dimension().location())
                    // WHERE, because this player is standing in the ladder's world doing nothing and
                    // is still an actor in it: vanilla's `Player.pushEntities` shoves anything it
                    // shares a cell with, and the ladder plays AT world spawn, which is exactly
                    // where a client that entered and never moved is standing. A body that drifted
                    // for no reason the walker trace explains has a second suspect on these
                    // topologies, and it is nameless unless this row says where it was.
                    .append(' ').append(at.getX()).append(',').append(at.getY())
                    .append(',').append(at.getZ())
                    .append(p.isAlive() ? "" : "，已死亡")
                    .append(p.isSpectator() ? "，旁观" : "");
        }
        return s.append("；mc.bot.* 在本 JVM=").append(driverClientHalf).append("）").toString();
    }

    /**
     * Everyone on the server who is not the ladder's own body.
     *
     * <p>{@code ctx.players()} is the whole player list, and with
     * {@code -Dworlddriver.realPlayerBodies=true} the ladder's body is IN it — that is the entire
     * point of the flag. So counting that list would report a human client on the headless topology,
     * which is the exact thing these rows exist to tell apart. {@link JoinedPlayerBodies.JoinedBody}
     * is the type only the driver mints, so the test is exact rather than a name match.
     */
    private static List<ServerPlayer> humanPlayers(SceneContext ctx) {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : ctx.players()) {
            if (p instanceof JoinedPlayerBodies.JoinedBody) continue;
            out.add(p);
        }
        return out;
    }

    /**
     * Which body this run climbs on.
     *
     * <p><b>The same on all three topologies, and saying so is the point.</b> Every rung drives the
     * avatar {@link #spawnBody()} builds, never the human client's player — see the class note for
     * the seam that would be needed and does not exist. So a capability the FAKE body lacks
     * ({@code fallDistance} pinned at 0, {@code isInvulnerableTo} refusing everything, an
     * advancement that is never awarded) is missing on the client topologies too, and a row that
     * named only the topology would invite exactly the wrong conclusion from a difference.
     *
     * <p>{@code inPlayerList} is what the flag actually buys and the one thing vanilla asks before
     * it will spawn a dragon, turn a spawner or spawn anything naturally.
     */
    private static String bodyDescription() {
        ServerWorldDriver d = driver;
        String kind = JoinedPlayerBodies.armed() ? "joined" : "fake";
        if (d == null) return kind + "（SPAWN 之前，本轮还没有身体）";
        ServerPlayer fp = d.fakePlayer();
        return kind + ":" + fp.getClass().getSimpleName()
                + " " + fp.getGameProfile().getName()
                + "（在玩家表=" + fp.level().players().contains(fp)
                + "，免伤=" + fp.isInvulnerableTo(fp.damageSources().generic())
                + "，@" + fp.level().dimension().location() + "）";
    }

    // ---- driving ----

    /**
     * Run a process to completion, then continue.
     *
     * <p>The process is driven by the SERVER's own tick, not by a loop inside this scene's tick.
     * That distinction is the reason this suite exists in the shape it does: every other scene in
     * the repo spins {@code ServerAvatarManager.tickAll()} up to 1500 times inside one server tick,
     * which is fast and correct for testing a pathfinder, and useless for a playthrough — the world
     * does not advance, so no furnace smelts, no crop grows, no mob moves and no portal lights. Here
     * the driver is registered and the scene waits, so a tick of the body is a tick of the world.
     *
     * <p>The chunk pin is refreshed from the wait condition. That is a side effect in a predicate,
     * which is normally worth avoiding; it is done here because the condition is the only code that
     * runs on every tick of the wait, and a pin that updated less often than the body moves would
     * leave it walking into chunks nothing is holding.
     *
     * @param withinTicks how long this leg may take before the scene reports STEP_TIMEOUT
     * @param then        what to assert once the process finishes
     */
    public void drive(BotProcess process, int withinTicks, Runnable then) {
        ServerWorldDriver d = body();
        ServerAvatarManager.register(d.runProcess(process));
        await(() -> doneOrLost(d, process.kind()), withinTicks, then);
    }

    /**
     * Finished, or there is no longer a body to finish anything — the one predicate every wait in
     * this rig must use.
     *
     * <p>Four waits existed; ONE checked whether the body was still in the world. The other three
     * ({@code drive}, {@code mineBlock}, and the mine branch of {@code settle}) waited on the driver
     * alone, so a fall during any of them spent the whole budget on a corpse and then surfaced from
     * the NEXT wait's entry check — which is why the fall report read 「离场时在跑的进程=没记到」
     * and why two arena arms built on the walker trace beside it both passed: they were reproducing
     * a moment that was never the moment. An invariant with sibling paths that ignore it is the
     * shape this repo keeps paying for; the fix is the predicate, not another call site.
     */
    private boolean doneOrLost(ServerWorldDriver d, String what) {
        if (d.finished()) return true;
        if (!bodyLeftTheWorld()) return false;
        if (drivingWhenLost == null) drivingWhenLost = what;
        return true;
    }

    /**
     * Break ONE named block and wait for it, the same way {@link #drive} runs a process.
     *
     * <p>The seam a scripted route needs that no {@code BotProcess} offers: every process picks its
     * own targets, and this suite's whole premise is that the caller knows the seed and the driver
     * only has to execute. {@code ServerWorldDriver.mine} already takes a {@link BlockPos}; this
     * just registers it and waits, so a stage can spell out a dig block by block instead of asking
     * a search verb to rediscover coordinates the survey already knows.
     */
    public void mineBlock(BlockPos target, int withinTicks, Runnable then) {
        ServerWorldDriver d = body();
        ServerAvatarManager.register(d.mine(target));
        await(() -> doneOrLost(d, "mineBlock(" + target.toShortString() + ")"), withinTicks, then);
    }

    /**
     * Break ONE named block, giving up after {@code ticks} and continuing either way.
     *
     * <p>{@link #mineBlock} is {@link #drive}-shaped: its timeout IS the failure, which is right for
     * a dig the rung cannot proceed without. It is wrong for one cell of a larger excavation, and
     * the difference cost a run: the portal rung carved a frame cell by cell with {@code mineBlock},
     * one cell could not be reached, and the rung died on the framework's generic
     * {@code await step exceeded within=900} — with no evidence at all about WHICH cell, because the
     * continuation that would have recorded it never ran. This is the {@link #settle} of digging:
     * the caller checks whether the cell actually opened and decides what that means.
     */
    public void mineCellOrGiveUp(BlockPos target, int ticks, Runnable then) {
        if (breakItWhereItStands(target)) {
            // One tick, so the world gets to react — gravel falls, fluid moves — before the next
            // cell is judged. Not zero: opening a whole alcove inside a single server tick would
            // queue every block update behind the carve and is the shape of「a whole fight in one
            // server tick」this repo has already paid for.
            settle(new HoldStill(1), 4, then);
            return;
        }
        ServerWorldDriver d = body();
        ServerAvatarManager.register(d.mine(target));
        int[] waited = {0};
        // The SAME out-of-world guard settle() has. It was missing here, and the omission was not
        // free: a body that left the world during a mine kept the whole budget running against a
        // corpse in the void, and — because the latch fired later, from the next settle's entry
        // check — the fall was recorded with 「离场时在跑的进程=没记到」. Two paths that wait on the
        // driver, one of them checking whether the driver still has a body to drive, is the shape
        // this repo has paid for before: an invariant with a sibling path that ignores it.
        await(() -> {
            if (d.finished()) return true;
            if (bodyLeftTheWorld()) {
                if (drivingWhenLost == null) {
                    drivingWhenLost = "mine(" + target.toShortString() + ")（第 " + waited[0]
                            + "/" + ticks + " tick）";
                }
                return true;
            }
            return ++waited[0] >= ticks;
        }, ticks + 100, () -> {
            ServerAvatarManager.unregister(d);
            then.run();
        });
    }

    /** Cells opened by {@link #breakItWhereItStands} rather than by a walk, this stage. */
    private int swungInPlace;

    /** How many of this stage's digs never needed a route. Printed by the carve, because「48/67
     *  开了」and「48/67 开了，其中 40 格是就地挥开的」describe different machines. */
    public int swungInPlace() { return swungInPlace; }

    /**
     * If the body can already break this cell, break it — do not route to it.
     *
     * <p><b>{@code canBreak} is the same predicate the break itself enforces</b>, and that is what
     * makes this exact rather than optimistic. {@code ServerPlayerAvatar.canBreak} delegates to
     * {@code canBreakFromHere}, which is EXPOSED (some neighbour is not a full solid face) AND IN
     * RANGE (eye to block centre within {@code blockInteractionRange() + 0.5}) — reach included,
     * measured from the live eye. {@code breakHold(true)} then gates on that same
     * {@code canBreakFromHere} and, with {@code faithfulBreak} off, calls {@code Level#destroyBlock}
     * outright. So a true answer here is not「probably reachable」: it is「this swing lands, now,
     * from exactly where the body is standing」.
     *
     * <p><b>Why this is worth a route.</b> {@code ServerWorldDriver.mine} is a walker goal plus a
     * swing, and the walker carves and pillars its way to the goal. On the real ladder of
     * 2026-08-16 that is what ended rung 12 at its FIRST cell: the mould carve left
     * {@code forge.carved=48/67 格开了，19 格没挖动}, all nineteen in the alcove's upper half
     * (y=59..62), and the diagnostic on the first of them read
     * {@code carve.firstStuck = -9,59,36=granite：身体 -9,56,34，距 3.6 格，canBreak=true} —
     * three and a half blocks away, exposed, breakable, and the 240-tick budget went on walking
     * instead. Worse, the walking is what lost the run: the pillars {@code MineProcess} placed to
     * reach the upper cells ({@code tidy.0} counted ten of them) walked the body out of its own
     * shaft, and {@code cell.0.standMissed = 想站 -9,56,36，停在 -10,66,34 … 脚下 grass_block}
     * put it on the SURFACE, ten blocks above the mould, from which every retry reported
     * {@code canBreak=false} at 12.5 m.
     *
     * <p><b>This does not take the pillars away</b>, deliberately. Cells genuinely out of reach
     * still fall through to {@code mine}, which still places, because building up to the alcove's
     * top row is the only way to reach it — removing that would turn nineteen unopened cells into
     * more. What changes is that reaching is no longer the FIRST answer to every cell.
     *
     * <p><b>The tool is selected first</b>, because {@code destroyBlock(pos, true, fp)} passes the
     * held item to {@code dropResources}: swinging a fist at stone opens the cell and drops
     * nothing, and this rung spends the cobblestone it mines. What is NOT reproduced is
     * {@code MineProcess}'s COLLECT phase — nothing walks to the drop — so a cell opened here is
     * collected only if it falls inside the avatar's own pickup sweep. That is the one thing this
     * trades away, and {@code forge.cobblestone} measures it as a DELTA rather than leaving it to
     * be argued about.
     */
    public boolean breakItWhereItStands(BlockPos target) {
        if (ctx.level().getBlockState(target).isAir()) return true;
        Avatar a = body().avatar();
        if (!a.canBreak(target)) return false;
        a.selectTool(target);
        a.aimAtBlock(target);
        a.breakHold(true);
        a.breakHold(false);
        // THE WORLD, not the call. `breakHold` returns nothing and refuses silently, so the only
        // honest test of「did it open」is the block itself — the same rule every placement in this
        // suite already follows.
        if (!ctx.level().getBlockState(target).isAir()) return false;
        swungInPlace++;
        return true;
    }

    /**
     * Run a process for at most {@code ticks} and continue either way.
     *
     * <p>{@link #drive} is right for a leg that must succeed — its timeout IS the failure. It is
     * wrong for a leg that is one attempt inside a larger plan, because a stalled attempt then
     * kills the rung with the framework's generic "await step exceeded" instead of the plan's own
     * verdict. The scripted shaft is exactly that shape: settling into the hole either happens or
     * it does not, and the step counter that gave up after twelve blocks is the message worth
     * reading. Measured: the stone rung died at 480 ticks on a 400-tick settle, reporting a
     * timeout where the shaft had a diagnosis ready.
     *
     * <p>The counter lives in the predicate because the predicate is the only thing that runs every
     * tick of the wait. The outer bound is deliberately larger so the framework's timeout stays a
     * backstop for a wait that somehow never evaluates, not the normal exit.
     */
    public void settle(BotProcess process, int ticks, Runnable then) {
        settle(process, ticks, null, then);
    }

    /**
     * Something that reads the body on every tick of a {@link #settle}.
     *
     * <p>Its own type rather than a second {@code Runnable} parameter: two adjacent {@code Runnable}s
     * differing only in position is a call site nobody can read, and swapping them would silently
     * run a leg's continuation once per tick.
     */
    public interface TickWatcher { void tick(); }

    /**
     * The same, with something watching the body while it runs.
     *
     * <p>Exists because <b>every reading this suite takes of a failed leg is a snapshot of the
     * aftermath</b>. A body that ends a walk hanging over a cave and a body that ends one having
     * been pushed off a ledge two hundred blocks earlier print the same surroundings line, and the
     * difference is the whole diagnosis. The wait's predicate is the only code that runs on every
     * tick of a leg, so it is the only place a trajectory can be recorded from.
     *
     * <p>The watcher runs BEFORE the completion test, so the tick on which the process first reports
     * finished is a tick the watcher sees — which is what lets it say whether the body was on the
     * ground when the walk decided it was done.
     */
    /** Which process was driving when the body left the world, and how far into its budget.
     *
     *  <p>Needed because the walker trace beside it can be arbitrarily stale: it is written on
     *  WALKER ticks, and this rung spends much of its time under TowerProcess / SwingAt / HoldStill,
     *  none of which tick a walker. A healthy walking line printed next to a fall therefore proves
     *  nothing about the fall — it can be minutes old and from a different leg. Two straight arena
     *  arms built on that line ({@code wd.serverStopsAtTheBridgeHead}, {@code …TurnsAtTheBridgeHead})
     *  both passed, which is what forced this reading into existence. */
    private String drivingWhenLost;

    public String drivingWhenLost() { return drivingWhenLost; }

    public void settle(BotProcess process, int ticks, TickWatcher watcher, Runnable then) {
        // THE one place that runs on every tick of every leg of every rung, which is why the
        // out-of-world check lives here and not at the call sites: there are dozens of settles and
        // a body that has left the world invalidates all of them equally. Same shape as the walker's
        // single `step++` exit — one guard covers every path because there is only one path.
        if (bodyLeftTheWorld()) {
            // Record here too. This entry guard latches the fall for every settle AFTER the one the
            // body actually left during, so when the departure happens somewhere no wait covers,
            // the report reads 「没记到」 and says nothing. Naming the process that was ABOUT to run
            // is not the same fact as naming the one that was running — say which it is.
            if (drivingWhenLost == null) {
                drivingWhenLost = "没在任何 wait 里被发现；下一段本来要跑 " + process.kind()
                        + "（说明坠落发生在两段之间的场景自有代码里，不是在 rig 的等待中）";
            }
            skipSettle(then);
            return;
        }
        ServerWorldDriver d = body();
        ServerAvatarManager.register(d.runProcess(process));
        int[] waited = {0};
        await(() -> {
            if (watcher != null) watcher.tick();
            heartbeat(waited[0], ticks, process);
            if (d.finished()) return true;
            if (bodyLeftTheWorld()) {
                // Latched HERE, where the process that was actually driving is in scope. The walker
                // trace printed beside it only updates on walker ticks, so on a leg driven by a
                // tower or a swing it describes some earlier leg entirely.
                if (drivingWhenLost == null) {
                    drivingWhenLost = process.kind() + "（第 " + waited[0] + "/" + ticks + " tick）";
                }
                return true;
            }
            return ++waited[0] >= ticks;
        }, ticks + 100, () -> {
            ServerAvatarManager.unregister(d);
            then.run();
        });
    }

    /**
     * Whether the body is below the floor vanilla itself calls out-of-world, recording the reading
     * the first time it is.
     *
     * <p>The threshold is {@code getMinBuildHeight() - 64}, taken from {@code Entity.checkBelowWorld}
     * rather than invented here, so「掉出世界」means in this rig exactly what it means in the game.
     * It is dimension-correct without a special case: the End's floor is 0 and the Overworld's is
     * -64, and each answers for itself.
     *
     * <p>Latched, not recomputed: once tripped it stays tripped even if a later read finds the body
     * somewhere else, because the rung is already invalid by then and a body that is teleported or
     * re-created afterwards must not erase the fall that happened.
     */
    private boolean bodyLeftTheWorld() {
        if (lostTheWorld != null) return true;
        if (driver == null) return false;
        ServerPlayer fp = driver.fakePlayer();
        // Remember the last cell the body was standing on, and how long ago. The out-of-world line
        // says where the body ENDED, and a body falling out of the End travels a long way sideways
        // on the way down — measured 2026-08-18, it left the island somewhere around (14,58,-36) and
        // tripped the line at (47,-66,-60), with the 200-tick heartbeat too coarse to have sampled
        // the departure. Where it left the ground is the coordinate the next round needs; where it
        // ended is the one that is easy to print.
        if (fp.onGround()) { lastGrounded = fp.blockPosition(); sinceGrounded = 0; }
        else sinceGrounded++;
        int floor = fp.level().getMinBuildHeight() - 64;
        if (fp.getY() >= floor) return false;
        lostTheWorld = "身体掉出世界：y=" + Math.round(fp.getY()) + " 已低于 "
                + fp.level().dimension().location() + " 的出界线 "
                + fp.level().getMinBuildHeight() + "−64=" + floor
                + "（vanilla Entity.checkBelowWorld 用的同一条线）；位置=" + fp.blockPosition().getX()
                + "," + fp.blockPosition().getY() + "," + fp.blockPosition().getZ()
                + " @ " + fp.level().dimension().location()
                + "；离场时在跑的进程=" + (drivingWhenLost == null ? "没记到" : drivingWhenLost)
                + "；最后一个还有支撑的 tick walker 在做（⚠️只在 walker tick 更新，可能是别的段留下的）="
                + net.magicterra.worlddriver.bot.movement.Walker.lastSupportedTrace
                + "；离场那一 tick walker 在做="
                + net.magicterra.worlddriver.bot.movement.Walker.lastTickTrace
                + "；最后一次站在地上=" + (lastGrounded == null ? "本段从未站稳过"
                        : lastGrounded.getX() + "," + lastGrounded.getY() + "," + lastGrounded.getZ()
                          + "（" + sinceGrounded + " tick 之前 —— 那一格才是要查的地方）")
                + "。⚠️ 这具身体 isInvulnerableTo 恒为 true，所以出界伤害被拒、它会一直掉下去 ——"
                + " 之后每一段行走和每一座塔都是对着虚空下的令，读它们的读数没有意义。";
        evidence("body.leftTheWorld", lostTheWorld);
        return true;
    }

    /** Ticks between heartbeats: ten seconds of a rehearsal running at the server's own rate. */
    private static final int HEARTBEAT_TICKS = 200;

    /**
     * Say where the body is, every {@link #HEARTBEAT_TICKS}, for as long as a leg is running.
     *
     * <p>A rung's evidence map is printed once, at the end. The legs in between are silent unless the
     * walker happens to emit one of its own capped debug lines, and those caps are per body — once a
     * long rung has spent them, it produces <b>no output at all</b>. Measured 2026-08-18: rung 20 ran
     * 30 minutes without a single log line while the server ticked normally at 4.7% CPU, and the only
     * way to learn that the body had vanished from the level was to query the live game over RPC.
     * Distinguishing「still walking」from「wedged」has to be cheaper than that, because a rung whose
     * budget is {@code DUEL_TICKS = 200_000} can otherwise burn hours before saying anything.
     *
     * <p>{@code 在关卡} is the reading that would have answered it in one line: a driver can keep
     * ticking a body that {@code level.players()} no longer contains, and every other row —
     * position, dimension, progress — looks perfectly healthy in that state.
     */
    private void heartbeat(int waited, int ticks, BotProcess process) {
        if (waited == 0 || waited % HEARTBEAT_TICKS != 0 || driver == null) return;
        ServerPlayer fp = driver.fakePlayer();
        WorldDriverCommon.LOG.info(
                "[journey] 心跳 {} {} 第{}/{} tick 身体={},{},{} @{} 在关卡={} 进程完成={}",
                stage.name(), process.kind(), waited, ticks,
                fp.blockPosition().getX(), fp.blockPosition().getY(), fp.blockPosition().getZ(),
                fp.level().dimension().location(), fp.level().players().contains(fp),
                driver.finished());
    }

    /**
     * The reading that says the body left the world, or {@code null} while it has not.
     *
     * <p>A rung reads this to fail with the fall as its verdict instead of with whatever the fall
     * made impossible afterwards.
     */
    public String lostTheWorld() { return lostTheWorld; }

    /**
     * Hand control to the continuation without running the process at all.
     *
     * <p>Deliberately {@link SceneContext#await} and not {@link #await}: the rig's own await pins a
     * region ticket around the body every tick, and around a body in free fall that means loading a
     * fresh column every few hundred blocks for the rest of the run. That pinning is a large part of
     * why the five dead legs took 25 minutes of wall clock rather than being merely pointless.
     */
    private void skipSettle(Runnable then) {
        ctx.await(() -> true).within(2).then(then);
    }

    /**
     * Wait for a condition, keeping the body's chunks pinned while waiting.
     *
     * <p>For the legs whose completion is a property of the WORLD rather than of a process — a
     * furnace that has finished, a portal that has lit, an item that has appeared.
     */
    public void await(BooleanSupplier done, int withinTicks, Runnable then) {
        ctx.await(() -> {
            pinAroundBody();
            return done.getAsBoolean();
        }).within(withinTicks).then(then);
    }

    /** Move the region ticket to the body's current chunk, if it has left the pinned one. */
    public void pinAroundBody() {
        if (driver == null) return;
        ServerPlayer fp = driver.fakePlayer();
        ServerLevel level = fp.serverLevel();
        ChunkPos here = new ChunkPos(fp.blockPosition());
        if (level == pinnedLevel && here.equals(pinned)) return;
        level.getChunkSource().addRegionTicket(JOURNEY_TICKET, here, bodyChunkRadius + 2, here);
        if (pinned != null && pinnedLevel != null) {
            pinnedLevel.getChunkSource().removeRegionTicket(
                    JOURNEY_TICKET, pinned, bodyChunkRadius + 2, pinned);
        }
        pinned = here;
        pinnedLevel = level;
    }

    // ---- the world a playthrough needs ----

    /**
     * Let the world run for the duration of this scene: mobs spawn, and optionally the clock moves.
     *
     * <p>Restores both in {@code cleanup}, because the arena audit treats a gamerule left changed as
     * a leak and would otherwise accuse the next scene. The daylight cycle is better asked for
     * through {@code Scene.withClock(Clock.RUNNING)}, which the harness restores for itself; this
     * covers the half {@code Clock} does not.
     */
    public void liveWorld(boolean mobs) {
        if (!mobs) return;
        GameRules rules = ctx.level().getServer().getGameRules();
        boolean was = rules.getBoolean(GameRules.RULE_DOMOBSPAWNING);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(true, ctx.level().getServer());
        ctx.cleanup(() -> rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(was, ctx.level().getServer()));
    }

    /**
     * The pathfinder budget a cross-country leg needs.
     *
     * <p>The suite's default slice caps a search at a few milliseconds so no scene can stall a tick.
     * A journey leg is a kilometre of real terrain and legitimately needs more, so it is raised
     * here and handed back through {@code BotConfig.pinnedBaseline()} — which snapshots every
     * mutable field, so a stage cannot leave any of it changed for the next one.
     */
    public void generousPathfinding() {
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = 30;
        // Bound by NODES, not by the wall clock — the shape 58 scene sites across nine files
        // already use, and for the reason PathFinder's own comment gives: a millisecond cap makes
        // the same search answer differently depending on how busy the box is that day, while a
        // node budget is deterministic. The hang backstop is CEILING_MS, which is separate and
        // still in force. Measured before changing it: `STOP cause=` appears ZERO times across
        // journey11/12/13's logs against 496 `search-begin` lines, so the 4000 ms cap never once
        // bound a real plan and this is hardening, not a fix for anything observed.
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxNodes = 100_000;
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
    }

    // ---- reading the body ----

    /** How many of an item the body is carrying, across the whole inventory. */
    public int carrying(String itemId) {
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.ResourceLocation.parse(itemId));
        var inventory = player().getInventory();
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    /**
     * How many of an item are lying on the ground within {@code radius} of the body.
     *
     * <p>The counterpart to {@link #carrying}, and it exists because an empty bag has three
     * different causes that read the same. Nothing mined, mined but no drop, dropped but never
     * picked up — one number distinguishes the third from the first two, and the arena sensor that
     * carries the same pair named its failure on the first run rather than the fourth.
     */
    public int dropsNearby(String itemId, double radius) {
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.ResourceLocation.parse(itemId));
        ServerPlayer fp = player();
        int total = 0;
        for (var drop : fp.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                fp.getBoundingBox().inflate(radius))) {
            if (drop.getItem().is(item)) total += drop.getItem().getCount();
        }
        return total;
    }

    /**
     * Where the nearest dropped {@code itemId} is, or null if there is none within {@code radius}.
     *
     * <p>The other half of {@link #dropsNearby}: that one measures the gap between "mined" and
     * "banked", this one lets a scripted route close it by hand. A rung that knows it just broke two
     * ores and can see them lying there does not need a sweep verb to rediscover them — and a leg
     * that walks to a coordinate the scene read straight out of the world keeps the failure
     * attributable, because a pickup that then does not happen cannot be the search's fault.
     */
    public BlockPos nearestDrop(String itemId, double radius) {
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.ResourceLocation.parse(itemId));
        ServerPlayer fp = player();
        BlockPos best = null;
        double bestD2 = Double.MAX_VALUE;
        for (var drop : fp.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                fp.getBoundingBox().inflate(radius))) {
            if (!drop.getItem().is(item)) continue;
            double d2 = drop.distanceToSqr(fp);
            if (d2 < bestD2) { bestD2 = d2; best = drop.blockPosition(); }
        }
        return best;
    }

    /**
     * Where the nearest {@code blockId} is STANDING in the world, or null within {@code radius}.
     *
     * <p>The counterpart {@link #nearestDrop} cannot answer, and the distinction is a real one for
     * anything this ladder places and means to take back. A reclaim can fail two ways: it breaks the
     * block and does not pick the item up — an {@code ItemEntity}, which {@code nearestDrop} sees —
     * or it never breaks it at all, and the thing is still a BLOCK, which no drop search will ever
     * report. Both look identical from the inventory ("we have no crafting table"), and they call
     * for opposite responses: walk over and collect, versus walk over and use it where it stands.
     *
     * <p>Cubic scan, deliberately small radius: this is asked at the moment a craft is about to
     * happen, about something the body itself placed a moment ago and a few blocks away.
     */
    public BlockPos nearestBlock(String blockId, int radius) {
        return nearestBlock(blockId, radius, radius);
    }

    /**
     * The same, searching further sideways than up.
     *
     * <p>A cube is the wrong shape for anything the ladder leaves standing. Stations sit on the
     * ground the body was standing on, so the vertical spread is a few blocks whatever the
     * horizontal one is — and the horizontal one has to be generous, because "where the last craft
     * happened" is a rung ago and a walk away. Measured: a table left at x=84 by the stone rung was
     * invisible to a radius-6 search at the furnace rung, which then bought another one and ran the
     * ladder out of wood.
     */
    public BlockPos nearestBlock(String blockId, int radius, int yRadius) {
        var wanted = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .get(net.minecraft.resources.ResourceLocation.parse(blockId));
        BlockPos centre = player().blockPosition();
        ServerLevel lvl = (ServerLevel) player().level();
        BlockPos best = null;
        double bestD2 = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++)
            for (int dy = -yRadius; dy <= yRadius; dy++)
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos at = centre.offset(dx, dy, dz);
                    if (lvl.getBlockState(at).getBlock() != wanted) continue;
                    double d2 = centre.distSqr(at);
                    if (d2 < bestD2) { bestD2 = d2; best = at; }
                }
        return best;
    }

    /** How many of any of these items the body is carrying, summed. */
    public int carryingAnyOf(java.util.List<String> itemIds) {
        int total = 0;
        for (String id : itemIds) total += carrying(id);
        return total;
    }

    /**
     * The species that actually drop something a player can eat.
     *
     * <p>A whitelist, not {@code instanceof Animal}. The first version took the nearest {@code
     * Animal} and in this swamp that is a <b>frog</b> — which is an Animal, is huntable, and drops
     * nothing edible at all, so the rung killed it and failed on an empty inventory. Naming the
     * species is also the more honest script: an agent that knows this world says "hunt a cow", not
     * "hunt whatever is closest".
     */
    private static final java.util.Set<String> EDIBLE_PREY = java.util.Set.of(
            "minecraft:cow", "minecraft:pig", "minecraft:sheep", "minecraft:chicken",
            "minecraft:rabbit");

    /**
     * The entity id of the nearest animal that drops food, or null when there is none.
     *
     * <p>A species name rather than a position, because that is what survives the walk: see the food
     * stage's note. Searched over the loaded entities near the body, which is honest about what is
     * actually reachable — an animal in an unloaded chunk is one the bot could not fight either.
     */
    public String nearestPrey(int radius) {
        Prey found = nearestPreyTarget(radius);
        return found == null ? null : found.species();
    }

    /** A hunt target: what it is and where it was standing when we looked. */
    public record Prey(String species, BlockPos where, double distance) {}

    /**
     * The nearest food-dropping animal, with its position.
     *
     * <p>The position matters as much as the species, and the reason is a lesson about scripting
     * against a verb's real reach rather than against the world. {@code CombatProcess} scans 32
     * blocks and gives up immediately when nothing matches; a script that located a cow 40 blocks
     * out and handed the verb only its species watched the process finish in two ticks having done
     * nothing, which reads exactly like a broken combat verb. The scripted step has to be what an
     * agent would actually do — walk to it, THEN engage — and that needs somewhere to walk.
     */
    public Prey nearestPreyTarget(int radius) {
        ServerPlayer fp = player();
        var box = fp.getBoundingBox().inflate(radius, radius / 2.0, radius);
        Prey best = null;
        double bestSq = Double.MAX_VALUE;
        for (var entity : fp.serverLevel().getEntities(fp, box)) {
            if (!(entity instanceof net.minecraft.world.entity.animal.Animal animal)) continue;
            if (animal.isBaby()) continue;                     // a calf drops nothing worth the walk
            var key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                    .getKey(animal.getType());
            if (key == null || !EDIBLE_PREY.contains(key.toString())) continue;
            double d = fp.distanceToSqr(animal);
            if (d < bestSq) {
                bestSq = d;
                best = new Prey(key.toString(), animal.blockPosition(), Math.sqrt(d));
            }
        }
        return best;
    }

    /** Every animal species loaded near the body, sorted — what a failed hunt should report instead
     *  of just "nothing found", because "there are only frogs here" is the useful sentence. */
    public java.util.List<String> animalsNearby(int radius) {
        ServerPlayer fp = player();
        var box = fp.getBoundingBox().inflate(radius, radius / 2.0, radius);
        java.util.Set<String> seen = new java.util.TreeSet<>();
        for (var entity : fp.serverLevel().getEntities(fp, box)) {
            if (!(entity instanceof net.minecraft.world.entity.animal.Animal animal)) continue;
            var key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                    .getKey(animal.getType());
            if (key != null) seen.add(key.toString());
        }
        return java.util.List.copyOf(seen);
    }

    /** The dimension the body is standing in, as {@code "minecraft:overworld"}. */
    public String dimension() {
        return player().serverLevel().dimension().location().toString();
    }

    /**
     * Whether the body has completed an advancement.
     *
     * <p>Answers false rather than throwing when the body cannot carry progress at all. A fake
     * player's advancement state is not something this suite may assume works — see the class note
     * on what a headless body is — so a stage asserts on the ITEM it obtained and records the
     * advancement alongside as corroboration. An advancement that never fires is a finding about
     * the avatar, not a reason for the stage to fail.
     */
    public boolean earned(String advancementId) {
        return "earned".equals(advancementStatus(advancementId));
    }

    /**
     * This advancement's state, as {@code "earned"}, {@code "not-earned"}, {@code "UNREGISTERED"} or
     * {@code "UNREADABLE(...)"}.
     *
     * <p>Three outcomes rather than a boolean because they mean different things and a boolean
     * collapses two of them into the same {@code false}. An id nobody registered is a TYPO in this
     * suite — the exact mistake made here first time round, recording {@code story/upgrade_tools}
     * against the wooden pickaxe when that advancement is the STONE one — and a record that reported
     * it as "not earned" would have looked like an engine finding forever. {@code UNREADABLE} is the
     * third: a fake player's advancement state is not something this suite may assume works, so a
     * body that cannot carry progress says so instead of reporting an absence.
     */
    public String advancementStatus(String advancementId) {
        try {
            var holder = ctx.level().getServer().getAdvancements()
                    .get(net.minecraft.resources.ResourceLocation.parse(advancementId));
            if (holder == null) return "UNREGISTERED";
            return player().getAdvancements().getOrStartProgress(holder).isDone() ? "earned" : "not-earned";
        } catch (RuntimeException | LinkageError e) {
            return "UNREADABLE(" + e.getClass().getSimpleName() + ")";
        }
    }

    /** Record an advancement's state as evidence under {@code advancement.<short name>}. */
    public JourneyRig noteAdvancement(String advancementId) {
        String shortName = advancementId.substring(advancementId.lastIndexOf('/') + 1);
        return evidence("advancement." + shortName, advancementStatus(advancementId));
    }

    // ---- writing the outcome ----

    /**
     * Attach a piece of evidence to whatever this stage ends up recording.
     *
     * <h2>A second write under the same key is a FACT TO REPORT, never a conflict to resolve</h2>
     *
     * This was a plain {@code map.put}, so a later write won and nothing said so. Two call sites in
     * {@code JourneyEndRungs} write {@code level.realPlayers} — one at the start of the rung, one at
     * the moment of the failure — and they are a PAIR: the whole reason the second exists is to be
     * read against the first. A put keeps one of them. On 2026-08-17 both happened to read 1, so the
     * loss was invisible; the run where they differ is exactly the run where the lost row mattered.
     *
     * <p>So neither write wins and nothing is chosen:
     *
     * <ul>
     *   <li><b>Same rendering</b> — the value is restated, not contradicted. Left alone; a second
     *       row for it would be noise.</li>
     *   <li><b>Different rendering</b> — both are kept. The first stays under {@code key}, the
     *       second lands on {@code key#2} (then {@code #3}…), a WARN names the key and both values,
     *       and {@code evidence.clash} lists every key it happened to, so a reader of the RESULTS
     *       FILE sees it without going to the log.</li>
     * </ul>
     *
     * <p><b>Rendered strings rather than {@code equals}</b>, because the record is text by the time
     * anyone reads it: {@code 1} and {@code "1"} are one reading and must not be reported as a
     * clash.
     *
     * <p><b>Not a rename of the caller's key.</b> A reader who greps for {@code level.realPlayers}
     * still finds the first write where it has always been; the suffix only ever appears on writes
     * that would otherwise have vanished.
     */
    public JourneyRig evidence(String key, Object value) {
        Slot slot = slotFor(key, value);
        if (slot.clashed()) {
            WorldDriverCommon.LOG.warn(
                    "[journey] {} 同一个 evidence key 被写了两次且值不同：{} —— 旧值 {} ／ 新值 {}；"
                            + "两个都保留了，新值落在 {}",
                    stage.name(), key, evidence.get(key), value, slot.key());
            clashes.add(key + "（旧值在 " + key + "，新值在 " + slot.key() + "）");
            put("evidence.clash", String.join("；", clashes));
        }
        put(slot.key(), value);
        return this;
    }

    /** Where a write landed, and whether landing there meant an existing row was being contradicted. */
    private record Slot(String key, boolean clashed) {}

    /** The raw write. Both {@link #evidence} and its own clash row go through here, so there is one
     *  place that keeps the evidence map and the scene record in step. {@code evidence.clash} is the
     *  one key that SHOULD overwrite itself — it is a running list, not a reading, so it goes through
     *  here rather than through the clash detection it would otherwise trip on every entry. */
    private void put(String key, Object value) {
        evidence.put(key, value);
        ctx.record(key, value);
    }

    /**
     * The row this write belongs in: {@code key} while it is free or already says the same thing,
     * otherwise the first unused {@code key#n}.
     *
     * <p>Deliberately idempotent — a value restated a third time lands back on the row that already
     * holds it rather than growing a new suffix, so a loop that re-records an unchanged reading
     * cannot manufacture clashes.
     */
    private Slot slotFor(String key, Object value) {
        if (!evidence.containsKey(key)) return new Slot(key, false);
        String now = String.valueOf(value);
        for (int n = 1; ; n++) {
            String slot = n == 1 ? key : key + "#" + n;
            if (!evidence.containsKey(slot)) return new Slot(slot, true);
            if (String.valueOf(evidence.get(slot)).equals(now)) return new Slot(slot, false);
        }
    }

    /** Say what this stage would be failing for, if it fails from here on. */
    public JourneyRig attempting(String what) {
        note = what;
        return this;
    }

    /**
     * Claim this rung, with the evidence gathered so far.
     *
     * <p>Also writes the body's invulnerability into the record, so every green row on the headless
     * track carries the bound on what it proves.
     *
     * <p>And says so ON THE PASS ROW when the rung was rehearsed rather than climbed. The evidence
     * map has carried {@code REHEARSAL} all along, but a PASS prints its note and nothing else —
     * so the one line a reader quotes from a green rehearsal, {@code PORTAL_LIT 达成 — …}, was
     * word-for-word the line a real climb prints. That is precisely the confusion the whole
     * rehearsal mode is built to be incapable of.
     */
    public void reach(String detail) {
        claimed = true;
        evidence.put("body.invulnerable", bodyIsInvulnerable());
        int staged = JourneyLedger.stagingCalls().size();
        evidence.put("staging.calls", staged);
        JourneyLedger.reached(stage, detail, evidence, tick(ctx));
        String rehearsing = JourneyRehearsal.target() == null ? ""
                : "【REHEARSAL — not a climb, staging.calls=" + staged + "】";
        ctx.passNote(rehearsing + stage.name() + "(" + stage.label() + ") 达成 — " + detail);
        WorldDriverCommon.LOG.info("[journey] {} REACHED — {} {}", stage.name(), detail, evidence);
    }

    // ---- teardown ----

    /**
     * Discard the body and release its chunks. Called once, by the verdict scene.
     *
     * <p>Idempotent, and safe to call when the run never made a body — the verdict scene has to run
     * whether the journey got anywhere or not, and a teardown that threw on an empty run would
     * replace the verdict with a stack trace.
     */
    public static void teardown() {
        ServerAvatarManager.clear();
        if (driver != null) {
            try { driver.fakePlayer().discard(); } catch (RuntimeException ignored) { /* already gone */ }
            driver = null;
        }
        if (pinned != null && pinnedLevel != null) {
            try {
                pinnedLevel.getChunkSource().removeRegionTicket(
                        JOURNEY_TICKET, pinned, bodyChunkRadius + 2, pinned);
            } catch (RuntimeException ignored) { /* level gone with the run */ }
        }
        pinned = null;
        pinnedLevel = null;
    }

    /** The world's tick count, for stamping ledger entries. */
    private static long tick(SceneContext ctx) {
        ServerLevel level = ctx.level();
        return level == null ? -1L : level.getGameTime();
    }
}
