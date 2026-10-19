package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.worlddriver.api.game.WorldDriver;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts;
import net.magicterra.worlddriver.bot.stagewright.Calls;
import net.magicterra.worlddriver.bot.stagewright.Harness;
import net.magicterra.worlddriver.bot.stagewright.Routes;
import net.magicterra.worlddriver.bot.stagewright.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.Targets;
import net.magicterra.worlddriver.testcontent.TestMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;

/**
 * The bot, the world and the bookkeeping one journey run shares.
 *
 * <p>A stage scene's whole interaction with the playthrough goes through here: {@link #enter} to
 * join the run (or be turned away because a rung below it never got climbed), {@link #drive} to make
 * the bot do something and wait for it, {@link #reach} to claim a rung with evidence.
 *
 * <h2>Four things this solves that a normal scene never has to</h2>
 *
 * <b>1. The bot outlives the scene.</b> Every other suite in this repo mints a fresh avatar per
 * scene and discards it in {@code cleanup}. A playthrough cannot: the whole claim is that the same
 * bot carried the same inventory from the first log to the dragon. So the avatar is held statically
 * and torn down once, by the verdict scene, on every exit path.
 *
 * <p><b>2. A walking bot needs its chunks.</b> The harness force-loads each scene's arena, and the
 * journey leaves that arena immediately — it plays at world spawn and then walks for kilometres.
 * A fake player is not in the player list, so it holds no tickets of its own and would walk straight
 * into unloaded chunks, where blocks read as air and entities do not exist. Worse, that failure is
 * quiet: the bot keeps moving and every assertion about what it should have found simply comes back
 * empty. {@link #pinAroundBody} keeps a region ticket travelling with it, refreshed from the await
 * condition so it tracks the bot on the same tick cadence the bot moves at.
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
 * The bot is a {@code FakePlayer}: on both loaders it is invulnerable, its death is a no-op and it
 * opens no menus. That is not a defect of this rig — it is what a server-side avatar is — but it
 * bounds what a green run means. <b>It shows the API could execute the plan. It does not show a
 * player could survive it.</b> Hunger, fall damage, drowning, mob threat and every container-driven
 * interaction are outside what this track can observe. {@link #bodyIsInvulnerable} states the
 * limitation into the record of every stage, so no green row can be read as more than it is.
 *
 * <p><b>That bound is exactly what the integrated topology lifts.</b> The ladder runs under three
 * of them ({@code journeyServer} / {@code journeyIntegratedServer} /
 * {@code journeyDedicatedServerWithClient}), and on {@code journeyIntegratedServer} it climbs on
 * the CLIENT'S REAL PLAYER — a player that takes fall damage, starves, drowns, dies, respawns and
 * earns advancements, because it is a player who joined. The other two still climb on the headless
 * roster player {@link #spawnBody()} spawns, and the bound above is theirs.
 *
 * <h2>How a real player gets driven, and why it is the same code</h2>
 *
 * A rung names a task — {@link JourneyTasks#goTo}, {@link JourneyTasks#tower}, {@link JourneyTasks#mine} — and
 * hands it to {@link #drive} or {@link #settle}; the task is the {@code mc.bot.*} verb, sent to whichever bot this
 * run climbs with. On the headless topologies that is a roster player ({@code player:sN}) the server ticks; on the
 * integrated one it is {@code self}, the client's real player, whose verbs run on the client's own task chain.
 * Only the bot ref changes, and {@link JourneyHelm} owns the difference.
 *
 * <p><b>What the server thread must never do here is wait.</b> self's calls answer on the client
 * thread, and the caller here is the server thread of a server the client is ticking against. So a
 * start is sent and its reply collected later, and completion is polled from the scene's own await
 * predicate, which is the one thing that already runs on every tick of a task.
 *
 * <p><b>One honest compromise</b>, stated into the record rather than hidden: the joining topology
 * ({@code dedicatedServerWithClient}) keeps the headless roster player on purpose, because the client bot
 * lives in the OTHER process. {@code journey.body} says which of the three a row came from, unconditionally,
 * so no two rows can be compared without it.
 */
public final class JourneyRig {

    /**
     * Keeps the chunks under the travelling bot loaded.
     *
     * <p>Distinct from the driver's test-arena ticket and from the harness's arena window,
     * both of which pin a fixed place. This one moves. Not persisted, so it cannot outlive the
     * process or end up in a saved world.
     */
    private static final TicketType<ChunkPos> JOURNEY_TICKET =
            TicketType.create("worlddriver_journey", Comparator.comparingLong(ChunkPos::toLong));

    /**
     * Chunks either side of the bot that must stay loaded.
     *
     * <p>Two is the smallest radius that keeps the bot's own chunk ENTITY_TICKING while it walks —
     * the pathfinder reads a few chunks ahead, and an entity search that silently sees nothing is
     * the failure mode this exists to prevent.
     */
    private static final int BODY_CHUNK_RADIUS = 2;

    /**
     * The radius actually in force, which a rung may widen for as long as it needs to SEE further.
     *
     * <p>Two chunks keeps a walking bot's own chunk entity-ticking, and for walking that is the
     * right number. It is the wrong number for a rung that searches: entities exist only in loaded
     * chunks, so a scan of 96 blocks around a bot with 32 blocks pinned is a scan whose outer two
     * thirds are guaranteed to be empty — and it does not report that, it reports "there are no
     * animals here". Measured: the food rung failed with
     * "no food-dropping animals within 96 blocks" on a swamp that has them, from a bot that had walked
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

    /** The run's bot and the hand on it, or null before SPAWN has made one. */
    private static JourneyHelm helm;
    private static ChunkPos pinned;
    private static ServerLevel pinnedLevel;

    /**
     * Whose hand is on the controls for this whole run, decided once and never re-asked.
     *
     * <p>{@code TRUE} = the client's real player, driven through its own bot; {@code FALSE} = the
     * headless player entity. Latched rather than recomputed per rung because "which player entity this run
     * climbs with" has to be ONE answer: a run that silently changed helms halfway would produce
     * rows that look comparable and are not, which is the failure these evidence keys prevent.
     */
    private static Boolean realPlayerHelm;

    // ---- this stage's state ----

    private final SceneContext ctx;
    private final JourneyStage stage;
    private final Map<String, Object> evidence = new LinkedHashMap<>();
    /** Keys this stage wrote more than once with DIFFERENT values, in the order the clashes
     *  happened. Rendered into {@code evidence.clash} — see {@link #evidence}. */
    private final List<String> clashes = new ArrayList<>();
    /**
     * What the ledger prints for a rung that failed — see {@link #attempting}, which is the only
     * thing that sets it.
     *
     * <p>The default says where to look rather than "reason not recorded", because that wording
     * was wrong in the way that costs a reader a detour: the reason is NOT missing. {@code ctx.fail(reason)}
     * throws, and the runner writes that text into the scene's own {@code reason} field in the
     * results file — it simply never passes back through this rig. On 2026-08-22 rung 17 printed a
     * full, precise diagnosis into its results row while the verdict's summary said the reason had
     * not been recorded, and the two rows sat in the same file contradicting each other.
     *
     * <p>Routing the real text here needs either 127 call sites converted or a stagewright change
     * (its {@code failureReason} is only filled for await timeouts, not for {@code fail}). Until
     * one of those happens, the honest thing is to name the other row.
     */
    private String note = "this rung never called attempting; the real cause is in the reason field of its row in the results file";
    private boolean claimed;
    /**
     * Non-null once the bot has fallen out of the world, holding the reading that proves it.
     *
     * <p>Measured 2026-08-18 on rung 20: the bot left the island during the walk to the fourth crystal,
     * and the rung then spent <b>five more walks and five more towers — about 15 000 ticks, half its
     * entire budget — issuing orders to a bot at y=-1514, then -13270, then -26833, then -40396,
     * then -55767</b>. Every one of those walks ran its full 2999 ticks, every one recorded "below the
     * feet: void_air, 0 blocks placed", and the rung's verdict was "cannot reach the dragon": a symptom
     * of a bot 69 457 blocks from the arena, naming neither the fall nor the task it happened during.
     *
     * <p>Nothing stops the fall on its own: both player implementations override
     * {@code isInvulnerableTo} to {@code true}, so vanilla's {@code Entity.checkBelowWorld} calls
     * {@code onBelowWorld} every tick and the out-of-world damage it deals is refused. A bot that
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
        String body = bodyDescription(ctx);
        String steer = steerDescription(ctx);
        ctx.record(TOPOLOGY_KEY, topology);
        ctx.record(BODY_KEY, body);
        ctx.record(STEER_KEY, steer);
        JourneyStage below = stage.requires();
        if (below != null && !JourneyLedger.has(below)) {
            JourneyLedger.blocked(stage, below, tick(ctx));
            ctx.skip("BLOCKED: upstream stage " + below.name() + "(" + below.label() + ") was not reached, so "
                    + stage.name() + " is not attempted");
        }
        JourneyRig rig = new JourneyRig(ctx, stage);
        // Into the LEDGER's evidence map as well, which the results row does not carry: the verdict
        // scene prints the ledger, and a ledger that does not name the run it describes is the same
        // uncomparable row one level up. The raw put rather than `evidence(...)`, because this is the
        // rig's FIRST write of both keys and the value is already in ctx — routing it through the
        // clash detector would only re-record what is there.
        rig.evidence.put(TOPOLOGY_KEY, topology);
        rig.evidence.put(BODY_KEY, body);
        rig.evidence.put(STEER_KEY, steer);
        // The obituary. A scene that times out never reaches its own last line, so the only place
        // a missed rung can be written down is a cleanup — those drain on every exit path.
        ctx.cleanup(() -> {
            if (!rig.claimed) {
                JourneyLedger.failed(stage, rig.note, rig.evidence, tick(ctx));
            }
        });
        // A row for the futile census BEFORE anything can wait, so "every rung has one" holds even
        // for a rung that never waits at all — and the placeholder is itself a reading rather than
        // an absence. Overwritten by the first heartbeat; see recordFutileGate for why a cleanup,
        // the obvious home, cannot carry this one.
        rig.recordFutileGate("no wait in this rung yet: the navigator has not been awaited once");
        rig.recordNavigatorCensus("no wait in this rung yet: the navigator has not been awaited once");
        rig.recordBodyVitals(rig.bodyVitalsLine());
        ctx.record("journey.stage", stage.name() + "(" + stage.label() + ")");
        return rig;
    }

    /**
     * A rig for an ARENA scene: a bot to drive and evidence to write, and <b>nothing in the
     * ledger</b>.
     *
     * <p>Exists because three fixes in a row could not be verified. Each is a recovery for a rare
     * failure — a bot left on top of the tower it built, a bot that surfaced still swimming, a
     * craft with nowhere to put its table — and a ladder run can only ever answer "did it happen
     * this time", not "when it happens, does the recovery work". Three consecutive runs stopped at
     * rungs 11, 10 and 6, each on a different cause, and not one of the fixes rode an occasion.
     * Staging the occasion is the only way; see [[three-greens-cannot-see-a-one-in-four]].
     *
     * <p><b>Why not {@link #enter}.</b> Two of its four steps write {@code JourneyLedger}: a stage
     * whose {@code requires()} is unmet records BLOCKED and {@code skip}s the scene outright — an
     * arena scene would never reach its own assertions — and its cleanup records {@code failed} for
     * any rig that never claims a rung, which an arena never does. The ledger is the LADDER's
     * account; an arena scene is not a rung of it.
     *
     * <p>{@code label} is a name for the evidence rows and nothing more. No ledger row is written
     * under it, so it can safely be the stage whose code is under test.
     *
     * <p>The bot is handed in rather than spawned: arena scenes already spawn one through
     * {@code SceneBot.spawn}, and {@link #spawnBody} is the LADDER's policy for obtaining a bot (adopt
     * the client's player, else spawn). The previous helm is restored by a cleanup, so a scene cannot
     * leave the static pointing at a bot that belonged to it — the failure mode of
     * [[a-scene-that-owns-a-global]], which this file would otherwise be the biggest instance of.
     *
     * <p>The arena bot is put on the tick list here, as the ladder's own bot is: between tasks the server
     * ticks it idle, which is what lets it fall, float and pick drops up while a rung waits.
     */
    /** An arena's bot: a parked roster bot standing in {@code foot}, empty-handed, removed when the scene ends. */
    static SceneBot arenaBot(SceneContext ctx, BlockPos foot) {
        SceneBot bot = SceneBot.spawn(ctx, foot);
        bot.player().getInventory().clearContent();
        return bot;
    }

    static JourneyRig forArena(SceneContext ctx, JourneyStage label, SceneBot arenaBot) {
        JourneyHelm hadHelm = helm;
        ctx.cleanup(() -> helm = hadHelm);
        helm = JourneyHelm.serverBot(arenaBot);
        helm.keepTicking();
        JourneyRig rig = new JourneyRig(ctx, label);
        ctx.record("journey.stage", label.name() + "(" + label.label() + ") (arena, not recorded in the ledger)");
        rig.recordFutileGate("no wait in this scene yet: the navigator has not been awaited once");
        rig.recordNavigatorCensus("no wait in this scene yet: the navigator has not been awaited once");
        rig.recordBodyVitals(rig.bodyVitalsLine());
        return rig;
    }

    /**
     * What this rig recorded under {@code key}, or null if it never did.
     *
     * <p>For arena scenes, which have to assert on the rows the code under test WROTE rather than on
     * the world it left behind: "the recovery ran" and "the world happens to look right" are two
     * claims, and a rung that never entered the recovery can satisfy the second. Null-vs-present is
     * the whole point, so this returns the value rather than a formatted string.
     */
    Object evidenceOf(String key) { return evidence.get(key); }

    /**
     * Every value this rig recorded under a key ending in {@code suffix}, in write order.
     *
     * <p>Because some keys carry a RUN-GLOBAL counter that a scene cannot predict: the climb rows are
     * {@code <tag>#<seq>.climb.<course><what>}, and {@code seq} keeps counting across every scene in
     * the suite, so the same arena writes a different key depending on what ran before it. An
     * assertion spelled with an exact key would pass under a filter and go silent in a full gate —
     * which is the [[an-instrument-behind-a-flag-is-not-an-instrument]] shape with the run order
     * playing the part of the flag.
     *
     * <p>A List, not the first hit, because the interesting question is often HOW MANY: one row means
     * a branch fired once, and the difference between "handed off immediately" and "burned eight
     * retries first" is a count, not a value. Each rig owns its own map, so a scene's own rows are
     * the only ones here.
     */
    java.util.List<Object> evidenceEndingWith(String suffix) {
        java.util.List<Object> hits = new java.util.ArrayList<>();
        for (Map.Entry<String, Object> e : evidence.entrySet())
            if (e.getKey().endsWith(suffix)) hits.add(e.getValue());
        return hits;
    }

    /** The stage this rig is climbing. */
    public JourneyStage stage() { return stage; }

    /** The scene this rig is running inside. */
    public SceneContext ctx() { return ctx; }

    // ---- the bot ----

    /**
     * Put the run's bot at world spawn, empty-handed — by ADOPTING the client's player where there
     * is one, and only otherwise by minting a headless one.
     *
     * <p>Called by the SPAWN stage and by nothing else — every later stage inherits whatever this
     * bot has become. Loads the spawn chunks to completion first: a player placed into a chunk that
     * has only been requested falls through terrain that has not generated yet.
     *
     * <h2>Adoption</h2>
     *
     * <p>On the integrated topology a real player is already standing in this world, and a rung that
     * spawned a second, invulnerable player entity beside it would be testing the wrong one: verification on the
     * integrated server is meant to be done by the real player. So the run's bot is the player that is there,
     * {@code self}: every task and every single-shot action goes to it through the same {@code mc.bot.*} verbs a
     * roster bot gets, and every read ({@code player()}, inventory, advancements) is the server's copy of it.
     *
     * <p>Three things are forced rather than trusted. <b>Survival</b>, because a creative player
     * takes no fall damage, needs no food and breaks anything instantly, which would quietly restore
     * exactly the fake-player bound this topology exists to remove. <b>An empty inventory</b>, the
     * same premise the headless run starts from. <b>The position</b>, because the ladder's whole
     * script is written against world spawn on a fixed seed.
     *
     * <p><b>⚠️ Those three writes are irreversible and nothing restores them.</b> Adoption WIPES the
     * adopted player's inventory, overwrites their game mode and teleports them. That is correct for
     * the throwaway client {@code journeyIntegratedServer} launches into a provisioned world, and it
     * is a way to destroy somebody's save if this ever runs against a client a person is using. The
     * ladder is a test topology; do not point it at a world you care about.
     */
    public ServerPlayer spawnBody() {
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
        if (realPlayerHelm(ctx)) {
            List<ServerPlayer> humans = humanPlayers(ctx);
            if (humans.isEmpty()) {
                ctx.fail("journey: this run is driven by a real player, but there was no real player at SPAWN"
                        + " — the client disconnected between the start of the run and this rung");
            }
            ServerPlayer real = humans.get(0);
            real.setGameMode(GameType.SURVIVAL);
            real.getInventory().clearContent();
            // How far that teleport actually moves the player, on the row, every run.
            //
            // `stagingCalls()` does not see this — it counts fixture verbs, and this is the rig
            // placing its own bot before the first rung. So the ladder's headline number can read
            // "zero test setup" while the bot was carried somewhere, and a reader cannot tell a
            // one-block centring nudge from a hundred blocks of walking nobody had to do. That
            // distinction is the whole point: walking is itself one of the things under test.
            //
            // A number rather than a guard, deliberately. The threshold at which a placement nudge
            // becomes stolen walking is not something to invent here — and on a freshly provisioned
            // world vanilla places the client near shared spawn anyway, so the honest first move is
            // to publish the distance and let the next pass decide from a reading.
            net.minecraft.world.phys.Vec3 wasAt = real.position();
            real.teleportTo(level, spawn.getX() + 0.5, surface, spawn.getZ() + 0.5,
                    real.getYRot(), real.getXRot());
            evidence("spawn.teleport", String.format(java.util.Locale.ROOT,
                    "%.1f blocks: %.0f,%.0f,%.0f → %d,%d,%d (world spawn)",
                    wasAt.distanceTo(real.position()),
                    wasAt.x, wasAt.y, wasAt.z, spawn.getX(), surface, spawn.getZ()));
            helm = JourneyHelm.self(real);
        } else {
            // The bot outlives this scene, so it is spawned bare and released by teardown, never by a cleanup.
            SceneBot bot = SceneBot.spawnBare(ctx, level, spawn.getX() + 0.5, surface, spawn.getZ() + 0.5);
            bot.player().getInventory().clearContent();
            helm = JourneyHelm.serverBot(bot);
            // On the tick list from here on, idle or not, as a connected player is ticked by its connection: a
            // meal finishes, health comes back and the bot falls into the hole it dug while no task holds it.
            helm.keepTicking();
        }
        // Its own key rather than a second write of `journey.body`. This rung ENTERED without a bot
        // and leaves with one, so the two readings are both true and neither contradicts the other —
        // and routing a legitimate change through the clash detector would print a WARN on every
        // single run, which is how a diagnostic teaches its reader to stop looking at it.
        evidence("journey.body.spawned", bodyDescription(ctx));
        return helm.player();
    }

    /** The run's bot's player, or null before SPAWN has made one. */
    public static ServerPlayer playerOrNull() { return helm == null ? null : helm.player(); }

    /** The run's helm, failing the scene when there is none — every stage above SPAWN needs it. */
    private JourneyHelm helm() {
        if (helm == null) {
            ctx.fail("journey: no bot player entity yet — SPAWN did not create one, so " + stage.name() + " cannot run");
        }
        return helm;
    }

    /** What every call on the run's bot names: {@code self}, or the roster bot's {@code player:sN}. */
    public String bot() { return helm().ref(); }

    /** The bot as a {@link ServerPlayer}, for inventory and progress reads. */
    public ServerPlayer player() { return helm().player(); }

    /** The roster bot this run climbs with, or null when it climbs on self. For the posed-body calls
     *  ({@code press}, {@code step}) that only a server-side bot takes. */
    public SceneBot serverBotOrNull() { return helm().serverBotOrNull(); }

    /** The navigator of the bot's running task, or of the last one started; for self the last reading that
     *  landed, null before any did. */
    public BotDebugContracts.NavigatorReading navigator() { return helm().navigator(); }

    /** Open a new walk in the bot's navigator telemetry — see {@link JourneyHelm#newLeg}. */
    void newNavigatorLeg() { helm().newLeg(); }

    /** The run's bot's navigator reading, or null before SPAWN has made one or before self's first one landed. */
    static BotDebugContracts.NavigatorReading navigatorOrNull() {
        JourneyHelm h = helm;
        return h == null ? null : h.navigator();
    }

    /** One of the navigator reading's traces, or words saying why there is none. */
    static String navigatorTrace(String name) {
        BotDebugContracts.NavigatorReading r = navigatorOrNull();
        if (r == null) return "no navigator reading (no bot yet, or self's first reading has not landed)";
        String v = r.traces().get(name);
        return v == null ? "null" : v;
    }

    /**
     * One process slot of the run's bot's {@code mc.bot.status} — read now for a roster bot, the last answer that
     * landed for self (a tick or two behind the client). Empty before self's first status landed.
     *
     * <p>One status per bot, whichever helm drives it: the task a rung started on self reports into self's
     * slots, and those are the ones read here. ({@code pathNode}, {@code pathMove}, {@code driveTag} and
     * {@code jumpTag} are not slot fields; they come through {@link #navigator()}'s plan cursor.)
     */
    public Optional<ControlContracts.Slot> slot(Function<ControlContracts.Slots, ControlContracts.Slot> kind) {
        ControlContracts.BotStatus s = helm().status();
        return s == null ? Optional.empty() : Optional.of(kind.apply(s.slots()));
    }

    /** {@code lastError} out of {@link #slot}, or null when that slot has none. */
    public String slotError(Function<ControlContracts.Slots, ControlContracts.Slot> kind) {
        return slot(kind).flatMap(ControlContracts.Slot::lastError).orElse(null);
    }

    /** {@code endReason} out of {@link #slot}, or null when that slot has none. */
    public String slotEnd(Function<ControlContracts.Slots, ControlContracts.Slot> kind) {
        return slot(kind).flatMap(ControlContracts.Slot::endReason).orElse(null);
    }

    /** {@link #slot} by a task's {@link JourneyTasks.Task#kind() kind} ({@code goto}, {@code mine}, …). */
    public Optional<ControlContracts.Slot> slot(String kind) {
        Function<ControlContracts.Slots, ControlContracts.Slot> pick = slotNamed(kind);
        return pick == null ? Optional.empty() : slot(pick);
    }

    /** {@link #slotError} by a task's kind. */
    public String slotError(String kind) {
        return slot(kind).flatMap(ControlContracts.Slot::lastError).orElse(null);
    }

    /** {@link #slotEnd} by a task's kind. */
    public String slotEnd(String kind) {
        return slot(kind).flatMap(ControlContracts.Slot::endReason).orElse(null);
    }

    /** The fight tally the bot's status reports (swings, well-timed, crits, kills), or empty before one landed.
     *  A hunt that banked nothing asks it for its swing and kill counts. */
    public Optional<ControlContracts.CombatTally> combatTally() {
        ControlContracts.BotStatus s = helm().status();
        return s == null ? Optional.empty() : s.reflexes().map(ControlContracts.Reflexes::combat);
    }

    /**
     * Whether the bot under this rig cannot be hurt — <b>asked, not asserted</b>.
     *
     * <p>This was a literal {@code return true} with a comment saying it is always true on the
     * headless track. It is: both player implementations override {@code isInvulnerableTo}. But a
     * hardcoded reading is an evidence row that cannot ever report a change, and this row is recorded
     * into every rung as the bound on what a green climb proves — so the day somebody drops the
     * override for a survival-fidelity run, every row would keep claiming the old world. Asking the
     * player costs one virtual call and makes the row follow the code.
     *
     * <p>{@code generic()} rather than the out-of-world source: it is the damage type the existing
     * scenes already probe invulnerability with ({@code WorldDriverScenes}), and both fake-player
     * overrides refuse every source alike, so the two answers cannot differ without the override
     * being gone. An adopted real player answers {@code false} here, which is the single reading
     * that separates the integrated topology's bound from the other two.
     *
     * <p>True when there is no bot player entity yet. RECON runs before SPAWN, and "there is no bot, so it can be
     * hurt" is not a statement anyone should be able to read off this.
     */
    public boolean bodyIsInvulnerable() {
        JourneyHelm h = helm;
        if (h == null) return true;
        ServerPlayer fp = h.player();
        return fp.isInvulnerableTo(fp.damageSources().generic());
    }

    // ---- who is on the controls ----

    /**
     * Whether this run drives the client's real player instead of a headless server-side player.
     *
     * <p>Three facts, all read off the running game rather than off a {@code -D} that only promises
     * something (the launch that says {@code client} and then dies in architectury's transformer is
     * a shape this repo has already paid three weeks for):
     *
     * <ul>
     *   <li>the server is INTEGRATED — a dedicated server has no client thread to drive, and
     *       on the joining topology the client bot is in another PROCESS;</li>
     *   <li>the driver is up in this JVM ({@link WorldDriver#registry()}), which on an integrated server
     *       it is only once the client half has come up at client init, so {@code self} answers;</li>
     *   <li>a human player is actually in the level. {@code stagewright.awaitPlayer} holds the
     *       suite until one is placed, so by the first rung this is settled.</li>
     * </ul>
     */
    private static boolean realPlayerHelm(SceneContext ctx) {
        Boolean decided = realPlayerHelm;
        if (decided != null) return decided;
        MinecraftServer server = ctx.server();
        boolean integrated = server != null && !server.isDedicatedServer();
        boolean now = integrated && WorldDriver.registry() != null && !SceneBot.humanPlayers(ctx).isEmpty();
        realPlayerHelm = now;
        return now;
    }

    /** {@link #realPlayerHelm(SceneContext)} for this rung's scene. */
    public boolean drivesRealPlayer() { return realPlayerHelm(ctx); }

    /** Which bot the verbs go to this run, and on which tick they run. See {@link #STEER_KEY} for why this is
     *  not folded into {@link #BODY_KEY}. */
    private static String steerDescription(SceneContext ctx) {
        return realPlayerHelm(ctx)
                ? "self (mc.bot.* verbs run on the client tick, with client physics); reflexes as read: " + armedReflexes()
                : "roster bot (mc.bot.* verbs run on the server tick); reflexes as read: " + armedReflexes();
    }

    /**
     * Which survival reflexes are actually armed, read from the settings at the moment the row
     * is written.
     *
     * <p>This half of the row used to be the words "preempted by panic/dodge/combat on the way", printed
     * unconditionally, and it was false on every run: the driver ships {@code autoRetreat},
     * {@code autoFight}, {@code autoDodge}, {@code autoHeal}, {@code autoShield} and
     * {@code autoEquip} all {@code false} — "so a quiet bot stays quiet" — and no rung turns any of
     * them on. The 2026-08-22 ladder read six poison ticks and two witch potions over 756 ticks with
     * every movement task ending "completed" and not one preemption, which a row saying "will be
     * preempted" cannot explain and a row saying "none armed" explains completely.
     *
     * <p>Sibling of {@link JourneyPeace#worldPinReading}: the same file's other hardcoded
     * self-description was falsified by the same run. A rig that describes its own capabilities in
     * prose describes the capabilities it was WRITTEN with, and both of these outlived them.
     */
    private static String armedReflexes() {
        var on = new java.util.ArrayList<String>();
        if (Harness.flag("autoRetreat")) on.add("autoRetreat(HP≤" + (float) Harness.number("retreatHpThreshold") + ")");
        if (Harness.flag("autoFight")) on.add("autoFight(threat≥" + Harness.number("autoFightThreatThreshold") + ")");
        if (Harness.flag("autoDodge")) on.add("autoDodge");
        if (Harness.flag("autoHeal")) on.add("autoHeal(HP≤" + (float) Harness.number("healHpThreshold") + ")");
        if (Harness.flag("autoShield")) on.add("autoShield");
        if (Harness.flag("autoEquip")) on.add("autoEquip");
        return on.isEmpty()
                ? "none armed (autoRetreat/autoFight/autoDodge/autoHeal/autoShield/autoEquip are all false,"
                        + " the shipped defaults, so an attack preempts nothing)"
                : String.join(", ", on);
    }

    /**
     * Start one task — <b>the only place a task is handed to the bot</b>.
     *
     * <p>Four call sites used to start their tasks independently ({@link #drive}, {@link #settle},
     * {@link #mineBlock}, {@link #mineCellOrGiveUp}). That is the shape this repo keeps paying for: an
     * invariant with sibling paths that ignore it. Routing all of them through one method keeps the heartbeat's
     * {@code driving}, the task tick and the endings row true for every task.
     */
    private void startLeg(JourneyTasks.Task task) {
        driving = task.kind();
        legTicks = 0;
        helm().start(task);
    }

    /** Whether the task {@link #startLeg} began has ended: refused, or idle on a status read after it began. */
    private boolean legEnded() {
        return helm().ended();
    }

    /**
     * Release the task: say how it ended and, when it is still running because the caller's budget ran out
     * first, cancel it — an order nobody waits on any more must not keep walking the bot into the next step.
     */
    private void endLeg() {
        driving = null;
        if (!legEnded()) helm().cancel();
        noteLegEnding();
    }

    /**
     * Start a task whose completion the CALLER watches — the public face of {@link #startLeg}.
     *
     * <p>For the tasks {@link #drive} and {@link #settle} cannot express: a fight ends when the
     * target dies, not when the task says so, so those rungs own their own await predicate. Pair
     * with {@link #legDone()} and {@link #legReleased()}.
     */
    public void legStart(JourneyTasks.Task task) { startLeg(task); }

    /** Whether the task {@link #legStart} began has ended. */
    public boolean legDone() { return legEnded(); }

    /** Release the task {@link #legStart} began. Call from the continuation, once. */
    public void legReleased() { endLeg(); }

    /** How every task ended, in order. See {@link #noteLegEnding}. */
    private final List<String> legEndings = new ArrayList<>();

    /**
     * Record HOW the bot let go of the task, not merely that it did.
     *
     * <p>{@code busy} goes false for four different endings — ran to completion, gave up on its own, threw,
     * or was cancelled by a higher-priority chain — and the status's {@code lastProcessEnd} carries the reason
     * for all but the first. Panic (creeper), Dodge (projectile), Combat and Bunker can all preempt the
     * rung's own task. Without this row a task a creeper interrupted reads exactly like a task that finished.
     * A start the verb refused is an ending too, and says so.
     *
     * <p>Through {@link #put} rather than {@link #evidence}: it is a running list, not a reading, so
     * it must overwrite itself — the same exemption {@code evidence.clash} takes.
     */
    private void noteLegEnding() {
        JourneyHelm h = helm();
        String line;
        if (h.refusal() != null) {
            line = h.kind() + "→refused: " + h.refusal();
        } else {
            ControlContracts.BotStatus s = h.status();
            ControlContracts.ProcessEnd end = s == null ? null : s.lastProcessEnd().orElse(null);
            if (end == null) return;
            // The reason may be the task's own give-up as well as a preempt, so the label claims neither.
            line = end.kind() + end.error().map(e -> "→not done: " + e).orElse("→completed");
        }
        // Collapse an unchanged repeat rather than printing "goto→completed" forty times: a rung that
        // settles per cell would otherwise bury its one interesting ending under its own noise.
        int n = legEndings.size();
        if (n > 0 && legEndings.get(n - 1).startsWith(line)) {
            legEndings.set(n - 1, line + " ×" + (repeatOf(legEndings.get(n - 1)) + 1));
        } else {
            legEndings.add(line);
        }
        put("journey.helm.endings", String.join("; ", legEndings));
    }

    private static int repeatOf(String line) {
        int at = line.lastIndexOf(" ×");
        if (at < 0) return 1;
        try { return Integer.parseInt(line.substring(at + 2)); } catch (NumberFormatException e) { return 1; }
    }

    // ---- which run this rung climbed in ----

    /** Every rung's row says which of the three ladder topologies produced it. */
    private static final String TOPOLOGY_KEY = "journey.topology";

    /** ...and which player did the climbing. Together they are what makes two rows comparable. */
    private static final String BODY_KEY = "journey.body";

    /**
     * ...and which HELM drove it, as a third field of its own.
     *
     * <p><b>Because the integrated topology changes two variables at once</b>, and the entire
     * reason three topologies exist is to tell "a fake-player gap" from "a real defect". That run swaps
     * the PLAYER (fake → real) and the STEER (the server tick's {@code BotTicker} → the
     * client's user-task chain over a {@code ClientPlayerBody}) in the same step. A row carrying
     * only topology and player would let a divergence be explained equally well by either, which is
     * the conjunction this repo keeps paying for: two arms are only readable when they differ in
     * ONE variable. Recording the steer separately does not create the fourth arm that would
     * actually separate them — a dedicated server driving a real player, or an integrated one
     * driving a fake — but it does say which side of the pair a given row sits on.
     */
    private static final String STEER_KEY = "journey.steer";

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
     *   <li>whether the driver's CLIENT half is in this JVM: on an integrated server the driver comes up
     *       at client init, with that half; on a dedicated server there is no client half at all. This is
     *       the fact that decides whether a rung's failure could possibly be client-side code at all: on a
     *       dedicated server that code is not merely unexercised, it is absent from the JVM.</li>
     * </ul>
     *
     * <p>The human players are listed with their DIMENSION, and that is the load-bearing part rather
     * than decoration. {@code ServerLevel.players()} is per level, and a client standing at world
     * spawn contributes to the overworld's list and to no other — which is why the ladder's own bot
     * has to be a joined player on every topology that mints it. Rungs 14–15 ask the NETHER's list
     * (BaseSpawner.isNearPlayer) and 19–20 ask the END's (EndDragonFight.tick), and a human at world
     * spawn answers neither.
     */
    private static String topology(SceneContext ctx) {
        MinecraftServer server = ctx.server();
        boolean dedicated = server == null || server.isDedicatedServer();
        boolean driverClientHalf = !dedicated && WorldDriver.registry() != null;
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
                .append(" (real players ").append(humans.size());
        for (ServerPlayer p : humans) {
            BlockPos at = p.blockPosition();
            s.append(": ").append(p.getGameProfile().getName())
                    .append('@').append(p.level().dimension().location())
                    // WHERE, because this player is standing in the ladder's world doing nothing and
                    // is still an actor in it: vanilla's `Player.pushEntities` shoves anything it
                    // shares a cell with, and the ladder plays AT world spawn, which is exactly
                    // where a client that entered and never moved is standing. A bot that drifted
                    // for no reason the navigator trace explains has a second suspect on these
                    // topologies, and it is nameless unless this row says where it was.
                    .append(' ').append(at.getX()).append(',').append(at.getY())
                    .append(',').append(at.getZ())
                    .append(p.isAlive() ? "" : ", dead")
                    .append(p.isSpectator() ? ", spectator" : "");
        }
        return s.append("; mc.bot.* in this JVM=").append(driverClientHalf).append(")").toString();
    }

    /**
     * Everyone on the server who is not the ladder's own bot.
     *
     * <p>{@code ctx.players()} is the whole player list, and the ladder's bot is IN it — every
     * server-side bot player joins. So counting that list would report a human client on the headless topology,
     * which is the exact thing these rows exist to tell apart. {@link SceneBot#humanPlayers} leaves out every
     * player the roster lists as a bot, so the test is exact rather than a name match.
     */
    private static List<ServerPlayer> humanPlayers(SceneContext ctx) {
        return SceneBot.humanPlayers(ctx);
    }

    /**
     * Which player this run climbs on, and who is driving it.
     *
     * <p><b>Not the same on all three topologies any more, and saying WHICH is the whole point.</b>
     * The integrated run adopts the client's real player; the headless and joining runs mint a
     * joined one. So the capabilities the minted player lacks — {@code fallDistance} pinned at 0,
     * {@code isInvulnerableTo} refusing every source, a death that is a no-op — are present on one
     * topology and absent on the other two, and a row that named only the topology would invite
     * exactly the wrong conclusion from a difference.
     *
     * <p>{@code invulnerable} is read from the player entity rather than assumed, so this row reports the change
     * the day it happens instead of restating what was true when it was written.
     *
     * <p>{@code inPlayerList} is what joining buys the minted fake player, and the one thing vanilla asks before
     * it will spawn a dragon, turn a spawner or spawn anything naturally. A real player is in it by
     * construction.
     */
    private static String bodyDescription(SceneContext ctx) {
        boolean helm = realPlayerHelm(ctx);
        String kind = helm ? "real (driven by the client bot)" : "joined (driven by the server tick)";
        JourneyHelm h = JourneyRig.helm;
        if (h == null) return kind + ": before SPAWN, this run has no bot player entity yet";
        ServerPlayer fp = h.player();
        return kind + ":" + fp.getClass().getSimpleName()
                + " " + fp.getGameProfile().getName()
                + " (inPlayerList=" + fp.level().players().contains(fp)
                + ", invulnerable=" + fp.isInvulnerableTo(fp.damageSources().generic())
                + ", mode=" + (fp.gameMode == null ? "?" : fp.gameMode.getGameModeForPlayer().getName())
                + ", @" + fp.level().dimension().location() + ")";
    }

    // ---- driving ----

    /**
     * Run a task to completion, then continue.
     *
     * <p>The task is driven by the game's own tick, not by a loop inside this scene's tick.
     * That distinction is the reason this suite exists in the shape it does: every other scene in
     * the repo spins the bots up to 1500 times inside one server tick, which is fast and correct for
     * testing a pathfinder, and useless for a playthrough — the world does not advance, so no furnace
     * smelts, no crop grows, no mob moves and no portal lights. Here the verb is sent and the scene
     * waits, so a tick of the bot is a tick of the world.
     *
     * <p>The chunk pin is refreshed from the wait condition. That is a side effect in a predicate,
     * which is normally worth avoiding; it is done here because the condition is the only code that
     * runs on every tick of the wait, and a pin that updated less often than the bot moves would
     * leave it walking into chunks nothing is holding.
     *
     * @param withinTicks how long this task may take before the scene reports STEP_TIMEOUT
     * @param then        what to assert once the task finishes
     */
    public void drive(JourneyTasks.Task task, int withinTicks, Runnable then) {
        startLeg(task);
        await(() -> doneOrLost(task.kind()), withinTicks, () -> {
            noteLegEnding();
            then.run();
        });
    }

    /**
     * Finished, or there is no longer a bot to finish anything — the one predicate every wait in
     * this rig must use.
     *
     * <p>Four waits existed; ONE checked whether the bot was still in the world. The other three
     * ({@code drive}, {@code mineBlock}, and the mine branch of {@code settle}) waited on the task
     * alone, so a fall during any of them spent the whole budget on a corpse and then surfaced from
     * the NEXT wait's entry check — which is why the fall report read "process running at exit: not recorded"
     * and why two arena arms built on the navigator trace beside it both passed: they were reproducing
     * a moment that was never the moment. An invariant with sibling paths that ignore it is the
     * shape this repo keeps paying for; the fix is the predicate, not another call site.
     */
    private boolean doneOrLost(String what) {
        if (legEnded()) return true;
        if (!bodyLeftTheWorld()) return false;
        if (drivingWhenLost == null) drivingWhenLost = what;
        return true;
    }

    /**
     * Break ONE named block and wait for it, the same way {@link #drive} runs a task.
     *
     * <p>The seam a scripted route needs that the searching verbs do not offer: {@code mc.bot.mine} by block
     * id picks its own targets, and this suite's whole premise is that the caller knows the seed and the
     * driver only has to execute. {@code mc.bot.mine} with a {@code pos} breaks exactly that cell, walking
     * within two blocks of it first unless it is already in reach, so a stage can spell out a dig block by
     * block instead of asking a search verb to rediscover coordinates the survey already knows. Both helms
     * take the same verb.
     */
    public void mineBlock(BlockPos target, int withinTicks, Runnable then) {
        startLeg(JourneyTasks.mineCell(target));
        await(() -> doneOrLost("mineBlock(" + target.toShortString() + ")"), withinTicks, () -> {
            noteLegEnding();
            then.run();
        });
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
        if (helm().isSelf()) {
            mineCellOnSelf(target, ticks, then);
            return;
        }
        if (swingNow(target)) {
            // One tick, so the world gets to react — gravel falls, fluid moves — before the next
            // cell is judged. Not zero: opening a whole alcove inside a single server tick would
            // queue every block update behind the carve, the "whole fight in one server tick" failure
            // this repo has already paid for.
            hold(1, 4, () -> { sayIfStillThere(target, "swung once in place"); then.run(); });
            return;
        }
        // The engine's own one-cell mine, which owns its walking. The SAME out-of-world guard settle()
        // has, through settleLeg: it was missing here once, and a bot that left the world during a mine
        // kept the whole budget running against a corpse in the void, recorded later as "process running
        // at exit: not recorded".
        settleLeg(JourneyTasks.mineCell(target), ticks, null, "mine(" + target.toShortString() + ")", () -> {
            sayIfStillThere(target, "server helm: ran the engine's own one-cell mine");
            then.run();
        });
    }

    /**
     * {@link #mineCellOrGiveUp} on self, whose swing is the client's own: a dig held over as many ticks as the
     * block takes, not a server-side write within one call.
     *
     * <p>In reach, the one-cell mine digs where the bot stands and walks nowhere. Out of reach, the walk comes
     * first, and it walks with {@code breakPolicy=never}, because THIS WALK IS NOT ALLOWED TO MINE ITS OWN WAY:
     * A* is asked for a route while {@code allowBreak} is globally true, so the cheapest route to a cell the bot
     * cannot swing at is regularly a tunnel — and the rock it tunnels through belongs to whatever the rung has
     * already built.
     *
     * <p>Measured, rung 12's client rehearsal 2026-08-22: {@code stairs.asCut} reported all 11 steps intact
     * before the carve, {@code forge.stairsBroken} 4 of 11 broken after it; the broken cells are (-4,61,20)
     * (-3,60,20) (-2,59,20) (-1,58,20) — x+1/y−1 each step, which is the flight's own shape translated one row
     * down. Something walked the length of it, one row under the treads, opening a cell per step. The cost of
     * forbidding it is measured, not assumed: that same run recorded {@code forge.swung=64/67} beside
     * {@code forge.carved=64/67} — THE WALK OPENED ZERO CELLS. The give-up contract is what makes that safe: a
     * cell this walk can no longer reach becomes a {@code carve.stuck} row, which the carve has always been
     * allowed to carry. Only self walks this way; a roster bot's one-cell mine owns its walking, an asymmetry
     * left alone because the client player is the one the ladder is judged on.
     */
    private void mineCellOnSelf(BlockPos target, int ticks, Runnable then) {
        if (ctx.level().getBlockState(target).isAir()) { then.run(); return; }
        if (canBreak(target)) {
            digInPlaceOnSelf(target, ticks, "client helm: in reach, dug in place with mc.bot.mine", then);
            return;
        }
        JourneyTasks.Task walk = JourneyTasks.goTo(Targets.near(target, 2), Routes.plain().noBreak().build());
        settleLeg(walk, ticks, null, "mine(" + target.toShortString() + ") (real player)", () -> {
            if (canBreak(target)) {
                digInPlaceOnSelf(target, ticks, "client helm: walked within 2 blocks with breakPolicy=never, then dug in place", then);
                return;
            }
            sayIfStillThere(target, "client helm: walked within 2 blocks with breakPolicy=never and was still out of reach");
            then.run();
        });
    }

    /** self's swing in place: the one-cell mine, started in reach so it does not walk, bounded by {@code ticks}. */
    private void digInPlaceOnSelf(BlockPos target, int ticks, String how, Runnable then) {
        settleLeg(JourneyTasks.mineCell(target), ticks, null, "mine(" + target.toShortString() + ") (real player)", () -> {
            if (ctx.level().getBlockState(target).isAir()) swungInPlace++;
            sayIfStillThere(target, how);
            then.run();
        });
    }

    /**
     * Say so when a give-up dig left the cell exactly as it found it.
     *
     * <p>{@link #mineCellOrGiveUp}'s contract is that a cell it cannot open is the caller's problem,
     * and until now it kept that contract SILENTLY — three exits, none of them recording whether the
     * cell opened. So a caller's own after-the-fact survey could name a cell that was still there and
     * nothing in the run could say why: the rung-12 rehearsal of 2026-08-25 asked for three doorway
     * cells, got two, and reported {@code portal.doorway} still blocked at 4, 57, 19 with no row between
     * "clear three cells" and "one cell still blocked". Two failures that want opposite remedies — the bot never
     * got within reach, or it was in reach and the swing did nothing — are the same absence of
     * evidence.
     *
     * <p><b>Only when it did not open.</b> The common case is a cell that opened, an alcove sweep is
     * twenty of them, and twenty rows of "opened" would bury the one that matters — the same asymmetry
     * {@code climb.*.hand} already uses. The caller knows how many cells it handed over, so no row
     * means it opened; this is the one reading whose absence is not ambiguous.
     *
     * <p>The BOT's level, not the scene's: after rung 19 the two are different worlds, and a block
     * read from the scene's level would answer about overworld stone at nether coordinates.
     *
     * <p><b>The gate's own answer, not a distance that resembles it.</b> The row carries
     * {@code canBreak} — the single condition the in-place swing consults — and only then the two halves
     * that decide it. A distance alone cannot be read: the authority measures EYE to block CENTRE against
     * {@code blockInteractionRange + 0.5}, which sits about 1.1 blocks of eye height away from the
     * bot-cell-to-target-cell number a reader would compute, so a band of readings near the limit means
     * nothing on its own. Worse, the authority also demands an EXPOSED face, so an entombed cell is refused
     * at zero distance and "very close yet not opened" would have looked like a broken swing. Splitting it
     * means a {@code false} names its own half.
     *
     * <p>For a roster bot {@code canBreak} is the driver's own reading ({@code mc.debug.body}'s reach) and the
     * halves are re-derived here beside it, so a run where they disagree has caught this row drifting away
     * from the rule it describes. For self the driver has no such reading, and the row says the answer was
     * re-derived from the server's copy of the player.
     *
     * @param how what this call ALREADY DID, in the past tense — never an imperative. The row this
     *     writes carries every measurement the reader needs (eye distance against its ceiling, cell
     *     distance, canBreak, exposure), and on 2026-08-26 the one thing that stopped those numbers
     *     from being read together was this field: it said "walk within 2 blocks, then swing", which
     *     parses as advice for a next step, so a cell-centre distance of 4.00 three clauses earlier — the direct
     *     contradiction of it — went unexamined for a whole rehearsal. An imperative in an evidence
     *     row is read as a TODO; a past-tense one is read as a record, and only the record invites
     *     the reader to check it against the numbers beside it.
     */
    private void sayIfStillThere(BlockPos target, String how) {
        ServerLevel lvl = (ServerLevel) player().level();
        boolean fluid = !lvl.getFluidState(target).isEmpty();
        if (lvl.getBlockState(target).isAir() && !fluid) return;
        ServerPlayer p = player();
        BlockPos at = p.blockPosition();
        boolean exposed = exposedFace(lvl, target);
        double reach = p.blockInteractionRange() + 0.5;
        double eyeDist = p.getEyePosition().distanceTo(
                net.minecraft.world.phys.Vec3.atCenterOf(target));
        evidence("mineCell." + target.toShortString(), "not opened: still "
                + lvl.getBlockState(target).getBlock()
                + (fluid ? " (also holds " + (lvl.getFluidState(target).isSource() ? "a fluid source" : "flowing fluid") + ")" : "")
                + "; bot at " + at.toShortString()
                + ", canBreak=" + canBreak(target)
                + (helm().isSelf() ? " (re-derived from the server's copy of the real player)" : "")
                + " (exposedFace=" + exposed + ", eye distance "
                + String.format(java.util.Locale.ROOT, "%.2f", eyeDist) + " / limit "
                + String.format(java.util.Locale.ROOT, "%.2f", reach) + ")"
                + ", cell-centre distance "
                + String.format(java.util.Locale.ROOT, "%.2f", Math.sqrt(target.distSqr(at)))
                + " blocks; " + how);
    }

    /**
     * Whether the bot can break {@code target} from where it stands: a face open to air and the block's centre
     * within reach of the eye. A roster bot answers with the driver's own reading; self's is re-derived from the
     * server's copy of the player, the same two halves, since the driver reads no such thing for self.
     */
    public boolean canBreak(BlockPos target) {
        SceneBot bot = helm().serverBotOrNull();
        if (bot != null) return bot.reach(target).canBreak();
        ServerPlayer p = player();
        double eye = p.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(target));
        return exposedFace(p.level(), target) && eye <= p.blockInteractionRange() + 0.5;
    }

    private static boolean exposedFace(net.minecraft.world.level.Level lvl, BlockPos target) {
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            BlockPos n = target.relative(d);
            if (!lvl.getBlockState(n).isSolidRender(lvl, n)) return true;
        }
        return false;
    }

    /** Cells opened by a swing in place rather than by a walk, this stage. */
    private int swungInPlace;

    /** How many of this stage's digs never needed a route. Printed by the carve, because "48/67
     *  opened" and "48/67 opened, 40 of them by swinging in place" describe different machines. */
    public int swungInPlace() { return swungInPlace; }

    /**
     * If the roster bot can already break this cell, break it — do not route to it.
     *
     * <p><b>{@code canBreak} is the same predicate the break itself enforces</b>, and that is what
     * makes this exact rather than optimistic: EXPOSED (some neighbour is not a full solid face) AND IN
     * RANGE (eye to block centre within {@code blockInteractionRange() + 0.5}), measured from the live eye.
     * The held dig then gates on that same check and, with {@code faithfulBreak} off, destroys the block
     * outright. So a true answer here is not "probably reachable": it is "this swing lands, now, from exactly
     * where the bot is standing".
     *
     * <p><b>Why this is worth a route.</b> The one-cell mine walks within reach first, and the navigator
     * carves and pillars its way there. On the real ladder of 2026-08-16 that is what ended rung 12 at its
     * FIRST cell: the mould carve left {@code forge.carved} at 48 of 67 cells opened and 19 not dug, all
     * nineteen in the alcove's upper half (y=59..62), and {@code carve.firstStuck} on the first of them read
     * -9,59,36=granite with the bot at -9,56,34, 3.6 blocks away, {@code canBreak=true} — three and a half
     * blocks away, exposed, breakable, and the 240-tick budget went on walking instead. Worse, the walking is
     * what lost the run: the pillars placed to reach the upper cells walked the bot out of its own shaft, and
     * {@code cell.0.standMissed} put it on the SURFACE, ten blocks above the mould, from which every retry
     * reported {@code canBreak=false} at 12.5 m.
     *
     * <p><b>This does not take the pillars away</b>, deliberately. Cells genuinely out of reach still fall
     * through to the mine, which still places, because building up to the alcove's top row is the only way to
     * reach it. What changes is that reaching is no longer the FIRST answer to every cell.
     *
     * <p><b>The tool is selected first</b>, because the held item decides the drops: swinging a fist at stone
     * opens the cell and drops nothing, and this rung spends the cobblestone it mines. What is NOT reproduced
     * is the mine's COLLECT phase — nothing walks to the drop — so a cell opened here is collected only if it
     * falls inside the bot's own pickup sweep, and {@code forge.cobblestone} measures it as a DELTA.
     *
     * <p>A roster bot only: self's swing is the client's multi-tick dig, see {@link #mineCellOnSelf}.
     */
    private boolean swingNow(BlockPos target) {
        if (ctx.level().getBlockState(target).isAir()) return true;
        SceneBot bot = helm().serverBotOrNull();
        if (!canBreak(target)) return false;
        var cell = Targets.pos(target);
        bot.press(bot.keys().toolFor(cell).aimAt(cell).attack(true));
        bot.press(bot.keys().attack(false));
        // THE WORLD, not the call. The press answers what the bot holds, not whether a block gave, so the only
        // honest test of "did it open" is the block itself — the same rule every placement in this suite
        // already follows.
        if (!ctx.level().getBlockState(target).isAir()) return false;
        swungInPlace++;
        return true;
    }

    /**
     * Run a task for at most {@code ticks} and continue either way.
     *
     * <p>{@link #drive} is right for a task that must succeed — its timeout IS the failure. It is
     * wrong for a task that is one attempt inside a larger plan, because a stalled attempt then
     * kills the rung with the framework's generic "await step exceeded" instead of the plan's own
     * verdict. The scripted shaft is exactly that shape: settling into the hole either happens or
     * it does not, and the step counter that gave up after twelve blocks is the message worth
     * reading. Measured: the stone rung died at 480 ticks on a 400-tick settle, reporting a
     * timeout where the shaft had a diagnosis ready.
     *
     * <p>The counter lives in the predicate because the predicate is the only thing that runs every
     * tick of the wait. The outer bound is deliberately larger so the framework's timeout stays a
     * backstop for a wait that somehow never evaluates, not the normal exit. A task still running when
     * the count runs out is cancelled.
     */
    public void settle(JourneyTasks.Task task, int ticks, Runnable then) {
        settle(task, ticks, null, then);
    }

    /**
     * Something that reads the bot on every tick of a {@link #settle}.
     *
     * <p>Its own type rather than a second {@code Runnable} parameter: two adjacent {@code Runnable}s
     * differing only in position is a call site nobody can read, and swapping them would silently
     * run a task's continuation once per tick.
     */
    public interface TickWatcher { void tick(); }

    /** Which task was driving when the bot left the world, and how far into its budget.
     *
     *  <p>Needed because the navigator trace beside it can be arbitrarily stale: it is written on
     *  NAVIGATOR ticks, and this rung spends much of its time under towers, swings and holds,
     *  none of which tick a navigator. A healthy walking line printed next to a fall therefore proves
     *  nothing about the fall — it can be minutes old and from a different task. Two straight arena
     *  arms built on that line ({@code wd.serverStopsAtTheBridgeHead}, {@code …TurnsAtTheBridgeHead})
     *  both passed, which is what forced this reading into existence. */
    private String drivingWhenLost;

    public String drivingWhenLost() { return drivingWhenLost; }

    /**
     * The same, with something watching the bot while it runs.
     *
     * <p>Exists because <b>every reading this suite takes of a failed task is a snapshot of the
     * aftermath</b>. A bot that ends a walk hanging over a cave and a bot that ends one having
     * been pushed off a ledge two hundred blocks earlier print the same surroundings line, and the
     * difference is the whole diagnosis. The wait's predicate is the only code that runs on every
     * tick of a task, so it is the only place a trajectory can be recorded from.
     *
     * <p>The watcher runs BEFORE the completion test, so the tick on which the task first reports
     * finished is a tick the watcher sees — which is what lets it say whether the bot was on the
     * ground when the walk decided it was done.
     */
    public void settle(JourneyTasks.Task task, int ticks, TickWatcher watcher, Runnable then) {
        settleLeg(task, ticks, watcher, task.kind(), then);
    }

    /** {@link #settle}, naming the task for the out-of-world row as {@code what}. */
    private void settleLeg(JourneyTasks.Task task, int ticks, TickWatcher watcher, String what, Runnable then) {
        // THE one place that runs on every tick of every task of every rung, which is why the
        // out-of-world check lives here and not at the call sites: there are dozens of settles and
        // a bot that has left the world invalidates all of them equally. Same shape as the navigator's
        // single `step++` exit — one guard covers every path because there is only one path.
        if (bodyLeftTheWorld()) {
            // Record here too. This entry guard latches the fall for every settle AFTER the one the
            // bot actually left during, so when the departure happens somewhere no wait covers,
            // the report reads "not recorded" and says nothing. Naming the task that was ABOUT to run
            // is not the same fact as naming the one that was running — say which it is.
            if (drivingWhenLost == null) {
                drivingWhenLost = "not detected in any wait; the next movement task was going to run " + what
                        + " (so the fall happened in the scene's own code between two tasks, not in a rig wait)";
            }
            skipSettle(then);
            return;
        }
        startLeg(task);
        int[] waited = {0};
        await(() -> {
            if (watcher != null) watcher.tick();
            if (legEnded()) return true;
            if (bodyLeftTheWorld()) {
                // Latched HERE, where the task that was actually driving is in scope. The navigator
                // trace printed beside it only updates on navigator ticks, so on a task driven by a
                // tower or a swing it describes some earlier task entirely.
                if (drivingWhenLost == null) {
                    drivingWhenLost = what + " (tick " + waited[0] + "/" + ticks + ")";
                }
                return true;
            }
            return ++waited[0] >= ticks;
        }, ticks + 100, () -> {
            endLeg();
            then.run();
        });
    }

    /**
     * Stand still for {@code ticks} ticks and let physics happen, then continue — at most {@code budget} ticks.
     *
     * <p>Sounds like nothing; it is how this bot falls into the hole it just dug without a task steering it
     * off the column. Every walking verb STEERS, and steering is exactly what ruins a shaft: "be at y=60" is
     * satisfied anywhere, and "stand on the cell just emptied" still carried the bot sideways one cell at a
     * time on open ground ({@code 72 → 73 → 74}). So the hold asks for no movement at all: the bot's task is
     * cancelled, which lets go of every key a previous task latched (a leftover forward impulse would walk the
     * bot off the hole just as surely as a goal would), and the bot is left to the game's own tick.
     *
     * <h2>Waiting longer is not a safer hold, and neither is "wait until it lands"</h2>
     *
     * <p>Every ray query in {@link JourneyFill} wants the bot at rest, but a wait of "until the position stops
     * changing, capped at ten ticks" hit the cap on essentially every call in the rehearsal (2026-08-16): the
     * recovery happens in an alcove the rung has just flooded, and flowing water pushes an idle player entity
     * every tick, so "at rest" is a state this rung never reaches. The wait also moves the bot: eight extra
     * ticks of falling put it on the alcove floor, from which {@code standToFill} found no spot and the fill
     * walked. A hold that lets the bot travel is not a hold. Two ticks is therefore the number for the fills,
     * whose fix for a stale aim is to re-aim AFTER those two ticks rather than to spend more of them — see
     * {@code JourneyFill.scoop}.
     */
    public void hold(int ticks, int budget, Runnable then) {
        hold(ticks, budget, null, then);
    }

    /** {@link #hold} with something watching the bot on every tick of it. */
    public void hold(int ticks, int budget, TickWatcher watcher, Runnable then) {
        if (bodyLeftTheWorld()) {
            if (drivingWhenLost == null) {
                drivingWhenLost = "not detected in any wait; the next movement task was going to be a hold"
                        + " (so the fall happened in the scene's own code between two tasks, not in a rig wait)";
            }
            skipSettle(then);
            return;
        }
        driving = "holdStill";
        legTicks = 0;
        helm().cancel();
        int stop = Math.min(ticks, budget);
        int[] waited = {0};
        await(() -> {
            if (watcher != null) watcher.tick();
            if (bodyLeftTheWorld()) {
                if (drivingWhenLost == null) drivingWhenLost = "holdStill (tick " + waited[0] + "/" + stop + ")";
                return true;
            }
            return ++waited[0] >= stop;
        }, budget + 100, () -> {
            driving = null;
            then.run();
        });
    }

    /**
     * Whether the bot is below the floor vanilla itself calls out-of-world, recording the reading
     * the first time it is.
     *
     * <p>The threshold is {@code getMinBuildHeight() - 64}, taken from {@code Entity.checkBelowWorld}
     * rather than invented here, so "fell out of the world" means in this rig exactly what it means in the game.
     * It is dimension-correct without a special case: the End's floor is 0 and the Overworld's is
     * -64, and each answers for itself.
     *
     * <p>Latched, not recomputed: once tripped it stays tripped even if a later read finds the bot
     * somewhere else, because the rung is already invalid by then and a bot that is teleported or
     * re-created afterwards must not erase the fall that happened.
     */
    private boolean bodyLeftTheWorld() {
        if (lostTheWorld != null) return true;
        if (helm == null) return false;
        ServerPlayer fp = helm.player();
        // Remember the last cell the bot was standing on, and how long ago. The out-of-world line
        // says where the bot ENDED, and a bot falling out of the End travels a long way sideways
        // on the way down — measured 2026-08-18, it left the island somewhere around (14,58,-36) and
        // tripped the line at (47,-66,-60), with the 200-tick heartbeat too coarse to have sampled
        // the departure. Where it left the ground is the coordinate the next round needs; where it
        // ended is the one that is easy to print.
        if (fp.onGround()) { lastGrounded = fp.blockPosition(); sinceGrounded = 0; }
        else sinceGrounded++;
        int floor = fp.level().getMinBuildHeight() - 64;
        if (fp.getY() >= floor) return false;
        lostTheWorld = "the bot fell out of the world: y=" + Math.round(fp.getY()) + " is below the out-of-world line of "
                + fp.level().dimension().location() + ", "
                + fp.level().getMinBuildHeight() + "−64=" + floor
                + " (the same line vanilla Entity.checkBelowWorld uses); position=" + fp.blockPosition().getX()
                + "," + fp.blockPosition().getY() + "," + fp.blockPosition().getZ()
                + " @ " + fp.level().dimension().location()
                + "; process running at exit=" + (drivingWhenLost == null ? "not recorded" : drivingWhenLost)
                + "; navigator activity on the last supported tick (⚠️ updated only on navigator ticks, may be from another task)="
                + navigatorTrace("lastSupportedTrace")
                + "; navigator activity on the tick of departure="
                + navigatorTrace("lastTickTrace")
                + "; last on the ground=" + (lastGrounded == null ? "never stood firmly during this task"
                        : lastGrounded.getX() + "," + lastGrounded.getY() + "," + lastGrounded.getZ()
                          + " (" + sinceGrounded + " ticks earlier — that cell is the one to inspect)")
                + ". ⚠️ "
                // ASKED, not asserted. This sentence used to state "always true" unconditionally, and
                // it stopped being true the day the integrated topology started adopting the
                // client's real player: a real player takes the out-of-world damage and DIES, which
                // is a different diagnosis from falling forever and must not be printed as the same
                // one.
                + (bodyIsInvulnerable()
                        ? "this player entity has isInvulnerableTo=true, so out-of-world damage is refused and it falls forever"
                          + " — every later walk and pillar is an order issued into the void, and their readings are meaningless."
                        : "this player entity can be hurt (isInvulnerableTo=false), so out-of-world damage kills it"
                          + " — later readings describe a corpse or a respawn and are equally not this rung's result.");
        evidence("body.leftTheWorld", lostTheWorld);
        return true;
    }

    /** Ticks between heartbeats: ten seconds of a rehearsal running at the server's own rate. */
    private static final int HEARTBEAT_TICKS = 200;

    /**
     * Ticks waited since the last heartbeat, <b>counted across tasks, not within one</b>.
     *
     * <p>It used to be {@code waited % HEARTBEAT_TICKS == 0} against the per-task counter, which
     * silences exactly the phases that need it most. Measured 2026-08-22, rung 17: the descent into
     * the portal room is {@code digDownTo}, and every step of it is
     * a 40-tick hold inside a 60-tick budget — 114 tasks, none longer than 60 ticks, so no task
     * ever reached 200 and the counter went back to zero at each one. The result was <b>5.7 minutes
     * of total silence</b> from a run that was working perfectly, and telling that apart from a wedge
     * cost a thread dump and two CPU samples. A phase built out of many short tasks is not a quiet
     * phase; it is a phase whose clock keeps getting reset.
     */
    private int sinceHeartbeat;

    /** Whether the head ever went under, how low the air got, and who held the channel while it did
     *  — the reading that separates "a reflex held and still could not surface" from "a reflex never
     *  got the channel". Its own class because this file is at its budget; see it for why the
     *  latch is per-tick and this heartbeat's 200 could not have seen the window. */
    private final JourneyDrownWatch drownWatch = new JourneyDrownWatch();

    /** What is driving right now, and how long the current wait has run — set wherever a task begins,
     *  read only by {@link #heartbeat}. */
    private String driving;
    private int legTicks;

    /** The last row {@link #evidence} was asked to write, and the task tick it was written at — read
     *  only by {@link #bodyDied}, which turns the pair into {@code death.leg}. */
    private String lastEvidenceKey;
    private int lastEvidenceLegTicks = -1;

    /**
     * Say where the bot is, every {@link #HEARTBEAT_TICKS} ticks of waiting, across however many
     * tasks those ticks fall in — see {@link #sinceHeartbeat} for why that distinction is the point.
     *
     * <p>A rung's evidence map is printed once, at the end. The tasks in between are silent unless the
     * navigator happens to emit one of its own capped debug lines, and those caps are per bot — once a
     * long rung has spent them, it produces <b>no output at all</b>. Measured 2026-08-18: rung 20 ran
     * 30 minutes without a single log line while the server ticked normally at 4.7% CPU, and the only
     * way to learn that the bot had vanished from the level was to query the live game over RPC.
     * Distinguishing "still walking" from "wedged" has to be cheaper than that, because a rung whose
     * budget is {@code DUEL_TICKS = 200_000} can otherwise burn hours before saying anything.
     *
     * <p>{@code inLevel} is the reading that would have answered it in one line: a driver can keep
     * ticking a player that {@code level.players()} no longer contains, and every other row —
     * position, dimension, progress — looks perfectly healthy in that state.
     *
     * <p><b>Called from {@link #await}, which is the only place any of this rig waits.</b>
     *
     * <p>It used to be called from {@link #settle} alone, and {@code settle} is not the only waiter:
     * {@link #mineCellOrGiveUp} runs its own {@code await} loop, and the comment beside that loop
     * already names the hazard — "an invariant with a sibling path that ignores it". A clock wired
     * into one of two waiting paths measures one of two waits. Wiring it into the choke point they
     * share is what makes the reading total rather than merely usual.
     */
    private void heartbeat(int budget) {
        JourneyHelm h = helm;
        if (h == null) return;
        // EVERY TICK, above the throttle — see JourneyDrownWatch for why a 200-tick sampler cannot
        // see the 100-tick window it is aimed at. self's status is the last that landed, so before the
        // first one did the chain is unreadable rather than "no chain".
        ControlContracts.BotStatus status = h.status();
        drownWatch.tick(h.player(), () -> status.reflexes().flatMap(ControlContracts.Reflexes::activeChain).orElse(null),
                status != null);
        if (++sinceHeartbeat < HEARTBEAT_TICKS) return;
        sinceHeartbeat = 0;
        recordFutileGate(futileGateLine());
        recordNavigatorCensus(navigatorCensus.line());
        recordBodyVitals(bodyVitalsLine());
        recordDrown(drownWatch.line());
        ServerPlayer fp = h.player();
        TestMod.LOG.info(
                "[journey] heartbeat {} {} {} task tick {}/{} bot={},{},{} @{} inLevel={} busy={}",
                stage.name(), driving == null ? "waiting (no task)" : driving, legPlanLine(), legTicks, budget,
                fp.blockPosition().getX(), fp.blockPosition().getY(), fp.blockPosition().getZ(),
                fp.level().dimension().location(), fp.level().players().contains(fp),
                status == null ? "no status landed yet" : status.busy());
    }

    /**
     * Where this task is trying to go, and how far along its plan the bot is.
     *
     * <p>The heartbeat used to print only {@code driving} ("goto") and the bot's cell. Two such rows
     * ten seconds apart prove the bot MOVED; they cannot say whether it moved along a plan or away
     * from one, and rung 12's post-mortem turned on exactly that. A task whose sampled path was 57
     * blocks for 9 blocks of net displacement reads identically as "walking the long way around the
     * lava rim" (correct — {@code cast8.rimTax} prices the rim at 300/cell so a 30-cell detour is
     * cheaper than stepping on it) and as "the plan keeps being replaced" (a defect). Three separate
     * readings were fitted to that one ambiguity in a single sitting before anyone noticed the
     * heartbeat could not decide it. {@code pathStep}/{@code pathLen} decides it: climbing toward
     * pathLen is the detour, resetting is the churn.
     *
     * <p>Read through {@link #slot}, the run's bot's own status, so a real-player helm reports the
     * client's navigator — the one actually driving the ladder.
     */
    private String legPlanLine() {
        if (driving == null) return "no movement task";
        Function<ControlContracts.Slots, ControlContracts.Slot> pick = slotNamed(driving);
        if (pick == null) return driving + " has no status slot";
        Optional<ControlContracts.Slot> s = slot(pick);
        if (s.isEmpty()) return driving + " slot has no reading";
        Object t = s.get().target().isPresent() ? s.get().target().get() : s.get().goal().orElse(null);
        return "target=" + (t == null ? "none" : t) + " path=" + s.get().pathStep() + "/" + s.get().pathLen();
    }

    /** The status slot a task kind reports into, or null for one that has none (a hold). */
    private static Function<ControlContracts.Slots, ControlContracts.Slot> slotNamed(String kind) {
        return switch (kind) {
            case "goto" -> ControlContracts.Slots::goTo;
            case "mine" -> ControlContracts.Slots::mine;
            case "builder" -> ControlContracts.Slots::builder;
            case "craft" -> ControlContracts.Slots::craft;
            case "smelt" -> ControlContracts.Slots::smelt;
            case "combat" -> ControlContracts.Slots::combat;
            case "escape" -> ControlContracts.Slots::escape;
            default -> null;
        };
    }

    /**
     * The reading that says the bot left the world, or {@code null} while it has not.
     *
     * <p>A rung reads this to fail with the fall as its verdict instead of with whatever the fall
     * made impossible afterwards.
     */
    public String lostTheWorld() { return lostTheWorld; }

    /**
     * Hand control to the continuation without running the task at all.
     *
     * <p>Deliberately {@link SceneContext#await} and not {@link #await}: the rig's own await pins a
     * region ticket around the bot every tick, and around a bot in free fall that means loading a
     * fresh column every few hundred blocks for the rest of the run. That pinning is a large part of
     * why the tasks issued after the fall took 25 minutes of wall clock rather than being merely pointless.
     */
    private void skipSettle(Runnable then) {
        ctx.await(() -> true).within(2).then(then);
    }

    /**
     * Wait for a condition, keeping the bot's chunks pinned while waiting.
     *
     * <p>For the tasks whose completion is a property of the WORLD rather than of a task — a
     * furnace that has finished, a portal that has lit, an item that has appeared.
     *
     * <p>A roster bot is on the tick list from SPAWN on, so the server ticks it through every wait, idle or
     * not, as a connected player is ticked; nothing here steps it. self's status and navigator readings are
     * kept in flight from here, so what the heartbeat and the guards read is at most a tick or two old.
     */
    public void await(BooleanSupplier done, int withinTicks, Runnable then) {
        ctx.await(() -> {
            if (helm != null) helm.refresh();
            // A baseline self could not give at the rig's birth is the first reading that lands.
            if (futileAtStart == null) futileAtStart = futileSnapshot();
            navigatorCensus.baselineIfMissing();
            drainLandedAnswers();
            pinAroundBody();
            legTicks++;
            heartbeat(withinTicks);
            noteHurt();
            // THE BOT MUST BE ALIVE — checked here and only here, because this is the one place
            // this rig ever waits. A per-rung check would be a check each rung has to remember;
            // this one is total by construction. See bodyDied() for what it cost to learn that.
            return bodyDied() || done.getAsBoolean();
        }).within(withinTicks).then(() -> {
            if (diedOf != null) { ctx.fail(diedOf); return; }
            then.run();
        });
    }

    /**
     * Every DROP in health, with what the bot was standing in when it took it.
     *
     * <h2>Why only drops, and why unconditional</h2>
     *
     * The rung-14 death of 2026-08-26 was readable down to "terrain fire, not a blaze" and no further,
     * because the only health readings in the whole run were four numbers {@code lavaEscape} happened
     * to print on its own trigger lines: {@code hp=11.0 ×10, hp=10.0 ×7, hp=1.0 ×2, hp=0.0}. Four
     * samples cannot tell "lost 6 points in one fall into lava" from "on fire, losing 1 point every half
     * second for over ten seconds", and those want opposite fixes: path cost versus heal/extinguish.
     *
     * <p><b>Drops only.</b> A full ladder is an hour, and natural regeneration ticks a heal every few
     * seconds — thousands of rows that say nothing. Damage events are tens. Heals are counted, not
     * listed, which keeps the row readable while still showing whether anything was healing at all.
     *
     * <p><b>No flag.</b> The gate that never gets switched on is the failure mode this repo has paid
     * for repeatedly, so this writes every time, and {@link #put} rather than {@code evidence} because
     * the row is rewritten in place — an evidence key rewritten a hundred times would arrive as a
     * hundred {@code #N} clashes instead of one trace.
     *
     * <p>Capped: past {@link #HURT_ROWS} drops the trace stops growing and says so. A run that takes
     * two hundred separate hits has already answered the question the trace was asked.
     */
    private void noteHurt() {
        ServerPlayer p = player();
        if (p == null) return;
        float now = p.getHealth();
        if (hpLast < 0) { hpLast = now; return; }
        if (now > hpLast) { hpHeals++; hpLast = now; return; }
        if (now == hpLast) return;
        float lost = hpLast - now;
        hpLast = now;
        hpDrops++;
        if (hurt.size() < HURT_ROWS) {
            ServerLevel lvl = (ServerLevel) p.level();
            BlockPos at = p.blockPosition();
            hurt.add(String.format(java.util.Locale.ROOT, "t%d −%.1f→%.1f @%s below=%s in=%s%s%s",
                    legTicks, lost, now, at.toShortString(),
                    lvl.getBlockState(at.below()).getBlock().toString().replace("Block{minecraft:", "").replace("}", ""),
                    lvl.getBlockState(at).getBlock().toString().replace("Block{minecraft:", "").replace("}", ""),
                    p.getRemainingFireTicks() > 0 ? " onFire" + p.getRemainingFireTicks() + "t" : "",
                    p.isInLava() ? " inLava" : ""));
        }
        put("hp.trace", (hurt.size() >= HURT_ROWS ? "(first " + HURT_ROWS + " only) " : "")
                + hpDrops + " drops, " + hpHeals + " heals; " + String.join(" | ", hurt));
    }

    /** How many separate drops the trace lists before it stops growing. */
    private static final int HURT_ROWS = 60;

    private float hpLast = -1;
    private int hpDrops;
    private int hpHeals;
    private final List<String> hurt = new ArrayList<>();

    /** Set the first time the bot is found dead, and never cleared: a rung does not recover from it. */
    private String diedOf;

    /** The death that ended this run, or {@code null} while the bot is alive. */
    public String diedOf() { return diedOf; }

    /**
     * Is the bot dead? Asked every tick of every wait, and the answer ends the rung on the spot.
     *
     * <h2>Why this exists</h2>
     *
     * Measured 2026-08-22, real-client ladder, seed 5471. At 01:17:47 the FOOD rung's walk began; the
     * bot was killed by a witch two ticks in. <b>The ladder drove the corpse for the next fifteen
     * minutes</b> — 10 000+ ticks of pressing forward and jump against a {@code DeathScreen}, with the
     * heartbeat still reporting the position 37,62,83 {@code @minecraft:overworld} every 200 ticks, because
     * position is exactly the reading a corpse still answers. Everything downstream was a lie told
     * fluently: {@code hSpd=0.009} (a dead player does not move), {@code attack=false} (a dead player
     * does not mine), the whole inventory {@code empty} (it dropped on death), and — the one that would have
     * sent the next reader somewhere else entirely — {@code world.isSolid(36,63,83)=false} for a cell
     * the live game reports as {@code grass_block}. The rung would have ended in
     * {@code TIMEOUT: await step exceeded}, i.e. "the navigator could not get there", which is true and
     * useless. <b>The verdict has to name the death, not what the death made impossible.</b>
     *
     * <p>Sibling of {@link #bodyLeftTheWorld}, and the same lesson twice: a bot that stops being a
     * live participant keeps answering every question you were already asking.
     *
     * <h2>What it does not fire on</h2>
     *
     * A {@code FakePlayer} is invulnerable on both loaders, so its health never leaves 20 and this
     * costs the two headless topologies one comparison per tick and nothing else. It is the integrated
     * topology — the one climbing on the client's real player — that can die, and that is precisely
     * the topology whose readings are worth having.
     */
    private boolean bodyDied() {
        if (diedOf != null) return true;
        if (helm == null) return false;
        ServerPlayer fp = helm.player();
        rememberTheBlow(fp);
        if (fp.getHealth() > 0f && !fp.isDeadOrDying()) return false;
        String how = fp.getCombatTracker().getDeathMessage().getString();
        // READ BEFORE THIS METHOD WRITES ANYTHING, or the answer is `death.cause`.
        String leg = lastEvidenceKey == null ? "no evidence row written during this task yet"
                : lastEvidenceKey + " (written at task tick " + lastEvidenceLegTicks + ")";
        evidence("death.cause", how);
        evidence("death.blow", blows.isEmpty() ? "no damage recorded during this task — the health loss did not go through hurt()"
                : String.join("; ", blows) + (blowsDropped == 0 ? ""
                        : ". ⚠️ " + blowsDropped + " earlier blows not listed (only the latest " + BLOWS_KEPT
                          + " kept); read hp.trace for all of them"));
        evidence("death.food", fp.getFoodData().getFoodLevel() + "/20, saturation "
                + String.format(java.util.Locale.ROOT, "%.1f", fp.getFoodData().getSaturationLevel()));
        evidence("death.standingIn", deathCell(fp));
        // FLUSH THE DROWNING LATCH AT THE DEATH, not at the next heartbeat that will never come.
        // The 200 ticks before a death are this instrument's whole subject, and `reach` — the other
        // writer — is precisely what a rung that died never gets to. Reading a latch through a row
        // stamped up to 200 ticks earlier is how a lagging reading becomes the crime scene.
        recordDrown(drownWatch.line());
        evidence("death.at", fp.blockPosition().toShortString());
        evidence("death.stage", stage.name());
        evidence("death.legTicks", legTicks);
        evidence("death.driving", driving == null ? "no driver" : driving);
        // WHICH MOVEMENT TASK DIED — `death.driving` answers the verb (`goto`) and never the occasion,
        // and the death chain CROSSES TASKS. Measured 2026-08-26 on the taxed rehearsal: the fire was lit
        // 1519 ticks into one task (`hp.trace`: t1519 −4.0 at -14, 59, 14, on fire 300t, in lava) and
        // the bot died 38 ticks into the NEXT one (`death.legTicks = 38`), so "which movement task
        // walked into the lava" was answered by neither row. Deciding whether the change under test had priced that
        // route cost a round of geometry against the pour column's coordinates; the task's own
        // evidence tag is one row and says it outright. A write tick LARGER than the death tick is
        // the tell that the last deliberate row belongs to the previous movement task.
        evidence("death.leg", leg + "; died at task tick " + legTicks + " — a write tick larger than this means the last"
                + " evidence row belongs to the previous movement task, and the cause of death spans both");
        // WHY THE STRIDE GUARD SAID NOTHING, on every rung, because death is where it matters.
        // The buckets first shipped hanging off JourneyFlight.report(), and JourneyFlight is
        // constructed only by JourneyNetherRungs — so rung 12, which burned to death walking into
        // a source pool, could never print them. Here is rig-level: every rung's death passes
        // through this method, which is exactly the set of occasions the reading exists for.
        evidence("death.strideGuard", strideGuardLine());
        diedOf = "the bot died: " + how + " (rung " + stage.name() + ", position "
                + fp.blockPosition().toShortString() + ", task tick " + legTicks + ", driver "
                + (driving == null ? "none" : driving) + ") — every reading after death describes a corpse: the inventory has dropped,"
                + " key presses no longer move it, digging stops, and the navigator's result message reports all of it as 'cannot reach'.";
        TestMod.LOG.error("[journey] {}", diedOf);
        return true;
    }

    /**
     * The stride guard's fires and its six skip buckets, run-cumulative.
     *
     * <p>Cumulative on purpose, unlike the per-walk deltas {@code JourneyFlight} reports: this is
     * written once, at a death, and a death is the end of the run — so "this run" and "up to here"
     * are the same window, and a delta would need a baseline nobody took.
     *
     * <p><b>All-zero has two readings and the line says so.</b> Either the guard was never called
     * on this run, or the navigator ticked so little that it never got the chance; the sum IS the
     * number of ticks it ran over, so a zero sum is the second and a zero in one bucket with a
     * non-zero sum is a real share. Naming the buckets as the navigator reading names them rather than by
     * index keeps this row and the code filling it from drifting apart.
     */
    private String strideGuardLine() {
        BotDebugContracts.NavigatorReading t = navigatorOrNull();
        if (t == null) return "stride guard: no navigator reading (self's navigator answers on the client thread, and none had landed)";
        long sum = 0;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : countersUnder(t, STRIDE_SKIPS).entrySet()) {
            sum += e.getValue();
            sb.append(sb.isEmpty() ? "" : ", ").append(e.getKey()).append('=').append(e.getValue());
        }
        return "stride guard fired "
                + t.counter("strideGuardFires")
                + " times over the run; did not fire for " + sum + " ticks"
                + (sum == 0 ? " (total 0 — the guard was never called on this run, so the problem is not inside"
                              + " the guard; check first whether the navigator ticked)" : ", by reason: " + sb);
    }

    /** The prefixes {@code mc.debug.navigator} files the stride guard's skip buckets and the futile gate's buckets under. */
    static final String STRIDE_SKIPS = "strideGuardSkips.";
    static final String FUTILE_GATE = "futileGate.";

    /** {@code reading}'s counters named {@code prefix<bucket>}, by bucket, in the reading's order; empty for no reading. */
    static Map<String, Long> countersUnder(BotDebugContracts.NavigatorReading reading, String prefix) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (reading == null) return out;
        for (Map.Entry<String, Long> e : reading.counters().entrySet())
            if (e.getKey().startsWith(prefix)) out.put(e.getKey().substring(prefix.length()), e.getValue());
        return out;
    }

    /**
     * The futile-search gate's nine buckets for THIS rung — a delta, unlike {@link #strideGuardLine}.
     *
     * <p>The counters belong to the run's bot and every rung of a run shares it, so a
     * cumulative reading on rung 9 would be rungs 1–9 added together and no rung's row could be read
     * on its own. One rig per rung ({@code enter} builds exactly one) makes the rig's own birth the
     * right baseline. {@link JourneyFlight} takes its stride deltas the same way. On self the first
     * reading lands a tick or two after the rig is built, so the baseline is the first one that does.
     *
     * <p>Reported on every outcome — see {@link #recordFutileGate} for where from, and for why the
     * obvious home does not work. Not only on a death like the stride row, and not only on a
     * failure: the run this instrument was built for ended with the rung <b>passing</b> while one of
     * its walks churned 318 searches, so a census that only spoke when something failed would have
     * missed exactly the case that motivated it.
     */
    private Map<String, Long> futileAtStart = futileSnapshot();

    private static Map<String, Long> futileSnapshot() {
        BotDebugContracts.NavigatorReading t = navigatorOrNull();
        return t == null ? null : countersUnder(t, FUTILE_GATE);
    }

    /**
     * Put this rung's census where the results file will actually see it.
     *
     * <p><b>A cleanup cannot carry this row, and that is not obvious.</b> {@code ctx.cleanup} looked
     * like the right home — the harness calls it "Single confluence point for every outcome" and it
     * drains on PASS/FAIL/TIMEOUT alike. But the harness calls {@code record(...)} <b>before</b>
     * {@code teardown(...)} on every one of those paths, and {@code record} is what serialises
     * {@code ctx.records()} into the results file. A row written in a cleanup is therefore written
     * after the only reader has already read — present in the log and in the ledger, absent from the
     * file every verdict is judged from. Found by asking "will this row exist?" before the run rather
     * than after, which is the entire reason criteria get pre-registered.
     *
     * <p>So it is written from {@link #heartbeat} instead — {@code await} is the one choke point
     * every wait passes through — and again from {@link #reach}, which makes a PASS exact rather
     * than up to one heartbeat stale. TIMEOUT keeps the stale value, because a scene that times out
     * never reaches a line of its own; the row says which it is.
     *
     * <p>Both maps, both plain puts, deliberately bypassing {@code evidence()}'s clash detector:
     * this key is REWRITTEN with a new value on purpose, and a guard meant to catch two authors
     * disagreeing would file every heartbeat as {@code futileGate#2}, {@code #3}, … The same
     * bypass, for the same reason, as the topology keys in {@link #enter}.
     */
    private void recordFutileGate(String line) {
        evidence.put("futileGate", line);
        ctx.record("futileGate", line);
    }

    /** The executor's side of the same tick. Constructed HERE so its baseline is the rig's birth,
     *  which is what makes its numbers this rung's rather than the run's — the identical reasoning
     *  as {@link #futileAtStart}, one field above. */
    private final NavigatorCensus navigatorCensus = new NavigatorCensus();

    /** Written on exactly the same terms as {@link #recordFutileGate}: the same two choke points,
     *  the same deliberate bypass of {@code evidence()}'s clash detector (this key is REWRITTEN
     *  every heartbeat on purpose), for the same reasons documented there. */
    private void recordNavigatorCensus(String line) {
        evidence.put("navigatorCensus", line);
        ctx.record("navigatorCensus", line);
    }

    /** Health the bot walked INTO this rung with, or -1 before the first reading.
     *
     *  <p>Taken lazily rather than in the constructor: {@code enter} builds the rig before the
     *  stage's own PREP has run, and on a BLOCKED rung there is no bot to ask. */
    private float hpEnteringRung = -1f;

    /**
     * What the bot has left to spend on this rung — carried in from the last one.
     *
     * <p><b>The reading rung 10 needed and no rung produced.</b> On 2026-08-26 the iron rung fell
     * three times down its own shaft (−4, −7, −3, every row with the bot's cell = air), banked its three
     * ingots and passed: its assertion asks for ingots, not for a bot able to continue. The
     * gravel rung then aborted on its FIRST tick — {@code MineProcess} refuses to mine at or below
     * {@code MINE_HP_CRITICAL}=4 and the bot arrived at exactly 4.0 — so {@code broke 0/64},
     * no gravel, no flint, and a rung that reported "10% drop rate, relies on volume, not luck" about a die it never
     * rolled. Every number in that chain existed; none of them sat on the same row, and the rung
     * that PASSED is where the damage was taken.
     *
     * <p>So this is a DELTA across the rung boundary, not a snapshot: "entered at X, now Y" says
     * both what this rung spent and what the last one left behind. A rung that starts low is a
     * complaint about its predecessor, and only this row can tell that from a rung that hurt itself.
     *
     * <p>Hunger rides along because it is the other resource a rung silently inherits and the one
     * that decides whether health comes back at all — vanilla regenerates nothing below 18, and
     * this run's two {@code hp.trace} rows both read "0 heals" with five raw beef in the bag.
     */
    private String bodyVitalsLine() {
        if (helm == null) return "no bot player entity (this rung has no bot yet)";
        ServerPlayer fp = helm.player();
        float hp = fp.getHealth();
        if (hpEnteringRung < 0) hpEnteringRung = hp;
        return String.format("health %.1f→%.1f (max %.1f)", hpEnteringRung, hp, fp.getMaxHealth())
                + ", food " + fp.getFoodData().getFoodLevel() + "/20"
                + String.format(" (saturation %.1f)", fp.getFoodData().getSaturationLevel())
                + (hp <= 4f ? " ⚠️ health ≤ MineProcess's absolute floor of 4, digging aborts on the first tick" : "")
                + (fp.getFoodData().getFoodLevel() < 18 ? " ⚠️ food <18, natural regeneration will not happen" : "");
    }

    /** Same two choke points and the same clash-detector bypass as {@link #recordNavigatorCensus}. */
    private void recordBodyVitals(String line) {
        evidence.put("body.vitals", line);
        ctx.record("body.vitals", line);
    }

    /** Same two choke points and the same clash-detector bypass as {@link #recordNavigatorCensus}. */
    private void recordDrown(String line) {
        evidence.put("water.drown", line);
        ctx.record("water.drown", line);
    }


    /** This rung's share of the futile gate, bucket by bucket. Buckets 0-5 are searches the gate
     *  never judged; 6-8 are what it did with the ones it judged. */
    private String futileGateLine() {
        Map<String, Long> now = futileSnapshot();
        if (now == null) return "no navigator reading yet (self's navigator answers on the client thread, and none had landed)";
        if (futileAtStart == null) futileAtStart = now;
        long sum = 0;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : now.entrySet()) {
            long v = e.getValue() - futileAtStart.getOrDefault(e.getKey(), 0L);
            sum += v;
            sb.append(sb.isEmpty() ? "" : ", ").append(e.getKey()).append('=').append(v);
        }
        return "this rung completed " + sum + " searches"
                + (sum == 0 ? " (none: the navigator did not search in this rung; do not read the zeros below as a result)"
                            : ", gate outcomes: " + sb);
    }

    /** How many recent blows the death row carries. Enough to tell one big hit from a slow drain. */
    private static final int BLOWS_KEPT = 8;

    /** The last few times this bot lost health, newest last. Never cleared — a rung does not recover. */
    private final java.util.List<String> blows = new java.util.ArrayList<>();

    /**
     * Blows dropped off the front to stay inside {@link #BLOWS_KEPT}, so the row can say so.
     *
     * <p>Silently is how it used to go, and the run of 2026-08-28 shows what that costs: nine drops
     * in {@code hp.trace}, eight rows in {@code death.blow}, and the one that fell off was
     * {@code −8.0→12.0@161} — <b>the largest single hit of the run</b>, the one that started the
     * decline the other eight merely finished. A reader comparing the two rows had no way to know
     * one was a subset, because the shorter row did not say it was short.
     *
     * <p>The end that gets dropped is the OLDEST, which is where an initiating hit lives, so the
     * clause is not decoration — it is the pointer that sends a reader to {@code hp.trace}, which
     * keeps them all. Same discipline as {@code JourneyFireCensus}'s "N more not listed": a reading
     * that truncates has to announce it, or every later reader treats a subset as the whole.
     */
    private int blowsDropped = 0;

    /** Health as of the previous tick, so a DROP can be noticed at the tick it happens. */
    private float healthSeen = Float.NaN;

    /**
     * Note it every tick the bot loses health, because both vanilla readings expire before the
     * poll that notices the death can ask for them.
     *
     * <h2>Why polling for the cause does not work</h2>
     *
     * The IRON rung ended 2026-08-22 with {@code death.cause = Player118 died} — the bot dead at
     * {@code 94,40,89} and the verdict naming no killer at all. That row is not broken: it calls
     * {@code getCombatTracker().getDeathMessage()}, which is exactly the right question. It came back
     * generic because {@link net.minecraft.world.damagesource.CombatTracker#recheckStatus} clears
     * every entry the moment {@code !mob.isAlive()}, and that runs inside the same server tick as the
     * death. <b>By the time a per-tick poll sees health at zero, the tracker it wants to read has
     * already been emptied by the death it is reacting to.</b> {@code getLastDamageSource()} expires
     * on its own timer too — it nulls itself 40 ticks after the hit.
     *
     * <p>So the reading has to be taken while the bot is still alive: sample health every tick and
     * name the source at the tick it drops. Falling 8 blocks and starving to death both end with a
     * corpse at the bottom of a shaft; {@code fall −3.0→17.0@2711} and eight rows of
     * {@code starve −1.0} are different worlds, and the difference is not recoverable afterwards.
     *
     * <p>Same family as {@link #bodyDied} itself: a state that stops existing keeps answering, only
     * this time what answers is the shape of a cleared record rather than a corpse's position.
     */
    private void rememberTheBlow(ServerPlayer fp) {
        float now = fp.getHealth();
        if (Float.isNaN(healthSeen) || now >= healthSeen) { healthSeen = now; return; }
        var src = fp.getLastDamageSource();
        String named = src == null ? "source expired (older than 40 ticks)" : src.getMsgId()
                + (src.getEntity() == null ? "" : "(" + src.getEntity().getName().getString() + ")");
        blows.add(String.format(java.util.Locale.ROOT, "%s −%.1f→%.1f@%d", named, healthSeen - now, now, legTicks));
        while (blows.size() > BLOWS_KEPT) { blows.remove(0); blowsDropped++; }
        healthSeen = now;
    }

    /**
     * What the bot was standing in when it died — the half of the cause the damage type leaves out.
     *
     * <p>{@code inFire} does not say whether the fire was lava, and {@code fall} does not say whether
     * the landing was into water the bot then drowned in. The cell is also the only reading that
     * survives being wrong about the mechanism entirely.
     *
     * <p>{@code fallDistance} is included as corroboration. Until 2026-09-14 it was worth nothing on a
     * server-side player, which reported 0 for it always ({@code fakeplayer-falldistance-is-always-zero});
     * since {@code JoinedBody.pump} runs {@code doCheckFallDamage} it counts there as it does on the
     * client player, so a death row from before that date still reads it as nothing.
     */
    private String deathCell(ServerPlayer fp) {
        var level = fp.serverLevel();
        var at = fp.blockPosition();
        return "feetCell=" + level.getBlockState(at) + ", below=" + level.getBlockState(at.below())
                + ", headCell=" + level.getBlockState(at.above())
                + ", fallDistance=" + String.format(java.util.Locale.ROOT, "%.1f", fp.fallDistance);
    }

    /**
     * Force a chunk in somewhere the bot is NOT, on the ladder's own ticket, for the rest of this
     * scene.
     *
     * <p>{@link #pinAroundBody} moves the one travelling pin and is the wrong tool for this: every
     * entity query in this rig centres on the bot, and the two callers that do not — locating a
     * generation-placed hostile, auditing a landmark the run has not reached — need a second cell
     * held without letting go of the first. Separate ticket instance, same type, so the bot's pin
     * is untouched.
     *
     * <p>Released in {@code ctx.cleanup}, because the arena audit treats a ticket left behind as a
     * leak and would accuse the next scene of it.
     *
     * <p>The radius is in the units {@code addRegionTicket} takes, where the resulting chunk level
     * is {@code 33 - radius}: 2 is the first value that reaches ENTITY_TICKING, and 3 leaves a ring
     * of margin so the section holding an entity on a chunk border is inside it too. Loading is
     * still not instantaneous — see {@code JourneyPeace} for the beat that has to follow.
     */
    public void pinDistant(BlockPos at, int radius) {
        ServerLevel level = ctx.level();
        ChunkPos cp = new ChunkPos(at);
        level.getChunkSource().addRegionTicket(JOURNEY_TICKET, cp, radius, cp);
        ctx.cleanup(() -> level.getChunkSource().removeRegionTicket(JOURNEY_TICKET, cp, radius, cp));
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.getChunk(cp.x + dx, cp.z + dz);
            }
        }
    }

    /** Move the region ticket to the bot's current chunk, if it has left the pinned one. */
    public void pinAroundBody() {
        if (helm == null) return;
        ServerPlayer fp = helm.player();
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
     * The pathfinder budget a cross-country walk needs.
     *
     * <p>The suite's default slice caps a search at a few milliseconds so no scene can stall a tick.
     * A journey walk is a kilometre of real terrain and legitimately needs more, so it is raised
     * here and handed back through {@code Harness.pin()} — which snapshots every
     * mutable field, so a stage cannot leave any of it changed for the next one.
     */
    public void generousPathfinding() {
        Harness.pin(ctx);
        // ⚠️ AND THEN UNDO ITS TABLE. `pinnedBaseline()` does two things — it snapshots the
        // pre-scene config (keep that: it is what isolates one rung from the next) and it applies
        // `applyGameTestBaseline()`, a flag set frozen for the ARENA suite, whose assertions were
        // authored against the historical default-OFF values. This ladder is not the arena suite.
        // It asks whether the driver can finish the GAME, so it must run the configuration the
        // driver SHIPS — and `applyCompiledDefaults()`'s own javadoc names exactly this case:
        // "scenes whose contract is live behaviour (not the §78 legacy arena baseline) call this
        // right after pinnedBaseline()". The pin still restores on close.
        //
        // 38 flags differed. Measured 2026-08-22 on the real client, seed 5471: with the arena table
        // in force there was NO dig arbitration at all (`navigatorDigAimPriority=false`), so the travel
        // drive and the water climb-out each ran startDestroyBlock on their own cell every ~35 ticks
        // while vanilla tracks exactly ONE destroy target — every switch threw the other's progress
        // away, neither cell ever passed 0.16 of the 1.0 it needed, and rung 3 timed out at 8000
        // ticks having broken nothing, three runs running. Fourteen more of the 38 are the water and
        // bank-dig recoveries — futile-bank-dig release, floating-bank bob freeze, swim-ashore
        // pillar, drowning escape — i.e. the ladder was asking a bot to climb out of the spawn
        // swamp with that whole subsystem's fixes switched off.
        //
        // The tell was a ZERO: `dig-aim RELEASE` never appeared, not once in 8022 ticks. Read as
        // "the hold never expired" it bought two wrong fixes; it actually meant the guard was never
        // ENTERED.
        //
        // ⚠️ AND THEN THE SHIPPING TABLE WAS MEASURED, AND IT IS WORSE HERE. `applyCompiledDefaults()`
        // ran on this line for exactly one run. Rung 3, same seed, one variable:
        //
        //     arena baseline + digAimPriority :  13 logs,  2 914 ticks, 2 trees   → PASS
        //     shipping compiled defaults      :   6 logs, 13 899 ticks, 4 trees   → FAIL (bill is 8)
        //
        // Named cause, not a shrug: `pathfinderLogBreakTax` ships at 3.0 and the baseline pins it to
        // 1.0, and ClientWorldView multiplies a LOG cell's break cost by it. Reaching the fifth log
        // of a trunk means breaking the four under it, so at 3× those paths price out — 49 rows of
        // `[mine] no approach to stand` and a first tree that yielded 4 instead of 7.
        // `pathfinderBreakCostMultiplier` (2.5 vs 1.0) pushes the same way. Both taxes exist to stop
        // the navigator chewing through trees it is merely PASSING, and rung 3 is the one rung whose
        // whole job is to chew through a tree.
        //
        // ✅ AND THAT SENTENCE IS NOW OVER. The trunk tax has a scope: `pathfinderLogBreakTax` is
        // waived while a MineProcess is working a log and charged in full on every other walk, so the
        // rung whose whole job is to chew through a tree may plan through the four logs under the
        // fifth, while a walk merely PASSING a forest still pays the 3× that tax was added for. That
        // is strictly better than the bisect this comment used to promise, which was to restore the
        // tax to 1.0 globally and throw the travel protection away with it.
        //
        // So this line pins what the driver SHIPS, which is what the ladder was always supposed to
        // measure. The other tax, `pathfinderBreakCostMultiplier` (2.5 vs 1.0), is deliberately NOT
        // touched: it pushes the same way but applies to every break, and waiving it would also make
        // "tunnel through stone" cheap. One variable at a time — if rung 3 still comes up short with
        // the no-approach rows collapsed, that multiplier is the named suspect and will have evidence
        // behind it rather than a guess. TODO.md carries the 38-flag delta and the reading criteria.
        Harness.compiledDefaults(ctx);
        // Off by default because a journey walk is thousands of ticks and this logs per-tick, but
        // openable, because the things it prints are the only account of what the CLIENT helm is
        // doing to the player. `AutoSwim`'s shore search — the code that owns a submerged real player
        // — logs nothing at all without it, and the integrated topology's ladder dies in water at
        // spawn without a single line explaining why. Turn on with:
        //     ./gradlew :fabric:runJourneyIntegratedServer -PnavigatorDebug=true
        // Empty when not asked for, default here in Java: the build file holding a lever
        // permanently open is a mistake this block has made once already (see the rehearse budget).
        Harness.set("navigatorDebug", "true".equals(System.getProperty("worlddriver.journey.navigatorDebug", "")));
        // FIVE TIMES THE SHIPPED DEFAULT (6 ms), on the axis the freeze complaint is about — and
        // until now the only line here without a reason. 6 ms is chosen to keep a search well
        // inside one 16 ms frame WHILE WALKING; 30 ms is what the product spends only when the
        // bot is IDLE and waiting (pathfinderIdleSliceMs), where a hitch is cheaper than a stall.
        // This line spends the idle figure on every walking tick, i.e. up to 60% of a 50 ms tick
        // on A*, on the render thread.
        //
        // That matters because the freeze the user watched was watched HERE: whatever share of it
        // comes from this line is a property of the rig, not of the shipped client, and no amount
        // of Navigator work will remove it. It is left at 30 deliberately — changing it mid-series
        // would confound the rung timings the ladder is being read for — but it must be one arm of
        // the A/B when the repeat-search suppression lands, or that fix will be credited with a
        // change this line could have made on its own.
        Harness.untimed(ctx);   // no wall-clock budget, as below; it lifts the slice too, which the next line sets
        Harness.set("pathfinder.sliceMs", 30);
        // Bound by NODES, not by the wall clock — the shape 58 scene sites across nine files
        // already use, and for the reason PathFinder's own comment gives: a millisecond cap makes
        // the same search answer differently depending on how busy the box is that day, while a
        // node budget is deterministic. The hang backstop is CEILING_MS, which is separate and
        // still in force.
        //
        // ⚠️ THIS BOUGHT NOTHING MEASURABLE, and the row that would have said so is easy to skip.
        // Across a whole ladder run on 2026-08-21 — 6289 `search-begin` lines — `STOP cause=` ZERO
        // times: no search has ever been cut off by either cap. So this is a determinism argument,
        // not a fix, and nothing here should be cited as having unblocked a rung.
        //
        // ⚠️ AND IT DOES NOT REACH EVERY SEARCH. These two fields are read by `PathFinder`'s
        // BotConfig-defaulting constructors only. `NavigatorSettings.withSearchBudget(nodes, ms)` overrides them
        // per navigator, and `Navigator.newPathFinder` prefers that override unconditionally whenever it is
        // positive — so a process that sets one never sees this. `MineProcess` does, in an instance
        // initialiser (8000/400, for its own documented reason: deep searches chopped the client's
        // frame rate after every break). Same run, by channel: 5154 searches on THESE values (82%),
        // 208 on MineProcess's, 927 on a 600/80 pair nobody has yet traced. A rung whose task is a
        // mine is therefore NOT running with the budget set here, and a change to these two lines
        // can never explain a change in its behaviour.
        Harness.set("pathfinder.maxNodes", 100_000);
        // Both are compiled-default ON, so these two are re-assertions rather than overrides. They
        // stay written out because a playthrough without them is not a playthrough, and a reader
        // asking "is this ladder allowed to mine?" should find the answer here, not two files away.
        Harness.set("allowBreak", true);
        Harness.set("allowPlace", true);
    }

    // ---- reading the bot ----

    /**
     * Melee weapons this ladder knows, best first — swords, then axes, then pickaxes as a last
     * resort. A pickaxe is a bad weapon and belongs at the bottom, but a pickaxe in the hand still
     * beats a fist, and the ladder owns one from rung 4 onward.
     */
    private static final List<String> WEAPONS = List.of(
            "minecraft:netherite_sword", "minecraft:diamond_sword", "minecraft:iron_sword",
            "minecraft:golden_sword", "minecraft:stone_sword", "minecraft:wooden_sword",
            "minecraft:netherite_axe", "minecraft:diamond_axe", "minecraft:iron_axe",
            "minecraft:stone_axe", "minecraft:wooden_axe",
            "minecraft:netherite_pickaxe", "minecraft:diamond_pickaxe", "minecraft:iron_pickaxe",
            "minecraft:stone_pickaxe", "minecraft:wooden_pickaxe");

    /**
     * Put the best weapon this bot owns into its main hand, and say what that turned out to be.
     *
     * <h2>Why every fight must call this</h2>
     *
     * {@code CombatProcess} swings whatever is SELECTED — <b>it has no weapon picker of its own</b> —
     * and the bot arrives at a fight holding whatever the last dig left in the slot. A fight lost
     * bare-handed and a fight lost to a broken combat loop read identically afterwards unless the hand
     * is on the record, which is why the return value is written to a {@code weapon} evidence row at
     * every call site.
     *
     * <h2>Why it lives here</h2>
     *
     * It was written twice — {@code JourneyNetherRungs} and {@code JourneyEndRungs} each had a private
     * copy, and they had already drifted: one fell through to the next weapon when the hold failed,
     * the other committed to the first one it owned and reported a weapon it was not holding. Worse,
     * the rungs that fight FIRST — FOOD (rung 6) and BED (rung 7) — could reach neither copy, so the
     * ladder hunted its first animals with a pickaxe or a fist while every later fight was equipped.
     * One implementation, reachable from every rung, with the better of the two behaviours: fall
     * through on a failed hold, and name what is actually in the hand when nothing can be held.
     */
    public String holdBestWeapon() {
        String bestOwned = null;
        for (String id : WEAPONS) {
            if (carrying(id) < 1) continue;
            if (bestOwned == null) bestOwned = id;
            // Both players, server and client: the swing that follows runs server-side, so a weapon
            // held only on the client is not the weapon that hits. See JourneyHands.holdBoth.
            if (JourneyHands.holdBoth(this, item(id))) return id;
        }
        if (bestOwned == null) return "bare hands (no weapon in the inventory)";
        return "bare hands (best in the inventory is " + bestOwned + ", but it could not be held; holding " + heldItemId() + ")";
    }

    /** The best weapon in the bag, or {@code "bare hands"} — a read, without touching the hand. */
    public String bestWeaponOwned() {
        for (String id : WEAPONS) if (carrying(id) > 0) return id;
        return "bare hands";
    }

    /** The id of whatever is in the main hand right now — the reading that tells a failed hold apart
     *  from an empty bag. */
    public String heldItemId() {
        return String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(
                player().getMainHandItem().getItem()));
    }

    /**
     * An item id, resolved. Lives here because four call sites across two classes had written the
     * same two lines out by hand, and one of them was private in a rung class where a second class
     * that needed it could not reach it.
     */
    public static net.minecraft.world.item.Item item(String itemId) {
        return lookup(BuiltInRegistries.ITEM, itemId).orElseThrow(() -> new IllegalArgumentException("no item " + itemId));
    }

    /** {@code id} in {@code registry}, or empty when it does not parse or names nothing there. */
    static <T> Optional<T> lookup(net.minecraft.core.Registry<T> registry, String id) {
        ResourceLocation rl = id == null ? null : ResourceLocation.tryParse(id);
        return rl == null ? Optional.empty() : registry.getOptional(rl);
    }

    /** How many of an item the bot is carrying, across the whole inventory. */
    public int carrying(String itemId) {
        var item = item(itemId);
        var inventory = player().getInventory();
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    /**
     * How many of an item are lying on the ground within {@code radius} of the bot.
     *
     * <p>The counterpart to {@link #carrying}, and it exists because an empty bag has three
     * different causes that read the same. Nothing mined, mined but no drop, dropped but never
     * picked up — one number distinguishes the third from the first two, and the arena sensor that
     * carries the same pair named its failure on the first run rather than the fourth.
     */
    public int dropsNearby(String itemId, double radius) {
        var item = item(itemId);
        ServerPlayer fp = player();
        int total = 0;
        for (var drop : fp.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                fp.getBoundingBox().inflate(radius))) {
            if (drop.getItem().is(item)) total += drop.getItem().getCount();
        }
        return total;
    }

    /**
     * Every dropped {@code itemId} stack within {@code radius} of the bot, as entity id → count.
     *
     * <p>{@link #dropsNearby} totals the same stacks, and a total is the wrong instrument for
     * "did this kill produce a drop?". The radius is inflated around the BOT, so the window travels
     * with it: a rod dropped at one fight leaves the window once the bot walks to the next, and a
     * before/after difference of two such totals goes NEGATIVE — {@code rods.perKill} printed
     * {@code 0,0,1,0,0,×notKilled,0,1,0,-1,…} on 2026-08-22, after an earlier double-count in the same
     * row had already been fixed. A count of things coming into existence cannot be negative; the
     * row was measuring a moving window and calling it creation.
     *
     * <p>With ids, the question becomes answerable exactly: sum the POSITIVE per-id differences.
     * A new stack contributes its whole count, a stack that grew (vanilla merges nearby identical
     * item entities into one, keeping one id) contributes the growth, and a stack that drifted out
     * of the window contributes nothing rather than a negative. See {@link #gained} for the sum.
     */
    public java.util.Map<Integer, Integer> dropStacks(String itemId, double radius) {
        var item = item(itemId);
        ServerPlayer fp = player();
        var out = new java.util.HashMap<Integer, Integer>();
        for (var drop : fp.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                fp.getBoundingBox().inflate(radius))) {
            if (drop.getItem().is(item))
                out.merge(drop.getId(), drop.getItem().getCount(), Integer::sum);
        }
        return out;
    }

    /**
     * Everything lying on the ground within {@code radius} of the bot, as {@code id×n, id×n}.
     *
     * <p>The other three drop readers ({@link #dropsNearby}, {@link #dropStacks},
     * {@link #nearestDrop}) all take the item id as an argument, so all three can only answer
     * "is THIS here?". That is the wrong question when a kill banks nothing: the bed rung read
     * {@code pickup.left=0} for eight rounds running with {@code PICKUP_RADIUS} at 32 blocks, which
     * proves no wool was on the ground and says nothing at all about whether the loot table rolled.
     * Mutton without wool means the sheep died sheared; nothing at all means it did not die, or died
     * outside the loot path. Those are opposite repairs, and no id-taking reader can separate them.
     */
    public String dropCensus(double radius) {
        ServerPlayer fp = player();
        var tally = new java.util.TreeMap<String, Integer>();
        for (var drop : fp.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                fp.getBoundingBox().inflate(radius))) {
            tally.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(drop.getItem().getItem()).toString(), drop.getItem().getCount(), Integer::sum);
        }
        if (tally.isEmpty()) return "nothing on the ground (within " + (int) radius + " blocks)";
        var sb = new StringBuilder();
        tally.forEach((id, n) -> sb.append(sb.isEmpty() ? "" : ", ").append(id).append("×").append(n));
        return sb.toString();
    }

    /**
     * How many {@code itemId} came into existence since {@code beforeStacks} / {@code beforeBag}
     * were read — non-negative by construction.
     *
     * <p>Both halves matter: a drop the bot walked over mid-fight has left the ground and is in the
     * bag, so counting only stacks would lose it, and counting only the bag reads zero for every
     * kill once collection moved to after the fight. See {@link #dropStacks} for why the ground half
     * cannot be a plain total.
     */
    public int gained(String itemId, java.util.Map<Integer, Integer> beforeStacks, int beforeBag, double radius) {
        int n = Math.max(0, carrying(itemId) - beforeBag);
        for (var e : dropStacks(itemId, radius).entrySet())
            n += Math.max(0, e.getValue() - beforeStacks.getOrDefault(e.getKey(), 0));
        return n;
    }

    /**
     * Where the nearest dropped {@code itemId} is, or null if there is none within {@code radius}.
     *
     * <p>The other half of {@link #dropsNearby}: that one measures the gap between "mined" and
     * "banked", this one lets a scripted route close it by hand. A rung that knows it just broke two
     * ores and can see them lying there does not need a sweep verb to rediscover them — and a walk
     * to a coordinate the scene read straight out of the world keeps the failure
     * attributable, because a pickup that then does not happen cannot be the search's fault.
     */
    public BlockPos nearestDrop(String itemId, double radius) {
        var item = item(itemId);
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
     * The nearest drop of <b>any</b> of {@code itemIds} — for a haul that is not one item.
     *
     * <p>A hunt does not drop one thing. Kill a cow and a pig on the way and the ground holds beef
     * AND porkchop, and a collect keyed to a single id walks past half of it. The single-id
     * {@link #nearestDrop} is right for a vein (one ore, one item) and wrong for a kill.
     */
    public BlockPos nearestDropOfAny(java.util.Collection<String> itemIds, double radius) {
        BlockPos best = null;
        double bestD2 = Double.MAX_VALUE;
        ServerPlayer fp = player();
        for (String id : itemIds) {
            BlockPos p = nearestDrop(id, radius);
            if (p == null) continue;
            double d2 = fp.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(p));
            if (d2 < bestD2) { bestD2 = d2; best = p; }
        }
        return best;
    }

    /**
     * {@link #collectByHand} for a haul of several item kinds — walk to the nearest of ANY of them.
     *
     * <p><b>The third caller of the same missing walk.</b> {@code collectByHand}'s note below records
     * the ore rungs and rung 14 (seven blazes killed, zero rods banked, two lying on the floor). Rung
     * 6 is the third: it read {@code rawFood} the tick {@code CombatProcess} finished and never went
     * to fetch anything, so a rehearsal on 2026-08-23 banked 4 beef off 4 cows with
     * {@code kill.onGround=1 item (minecraft:beef×1)} beside the PASS. It passed because the rung only
     * needs one — the leak was invisible to its own assertion, and the same leak at rung 9 (iron) or
     * rung 14 (rods) is fatal, because there every single item is counted.
     *
     * <p>Walks are shared across kinds rather than per kind: three walks total, each to whatever is
     * nearest. Per-kind walks would let one abundant drop spend the whole budget while a single
     * porkchop two blocks away is never fetched.
     */
    public void collectAnyOf(java.util.Collection<String> itemIds, int legs, int total,
                             String key, Runnable then) {
        if (legs <= 0) { leftOnTheGroundAnyOf(itemIds, key, then); return; }
        BlockPos drop = nearestDropOfAny(itemIds, PICKUP_RADIUS);
        if (drop == null) { leftOnTheGroundAnyOf(itemIds, key, then); return; }
        evidence(key + ".pickup.walks", total - legs + 1);
        evidence(key + ".pickup.target", drop.toShortString());
        settle(JourneyTasks.goToBlock(drop), 600,
                () -> hold(30, 50,
                        () -> collectAnyOf(itemIds, legs - 1, total, key, then)));
    }

    /** {@link #leftOnTheGround} over a set: what the collect could not get, summed over the kinds,
     *  read AFTER it stops for the same reason. */
    private void leftOnTheGroundAnyOf(java.util.Collection<String> itemIds, String key, Runnable then) {
        int left = 0;
        for (String id : itemIds) left += dropsNearby(id, PICKUP_RADIUS);
        evidence(key + ".pickup.left", left);
        then.run();
    }

    /**
     * Walk to what this rung just dropped and pick it up, by hand, in up to {@link #MAX_PICKUP_LEGS}
     * walks.
     *
     * <h2>Why this lives here and not in one rung</h2>
     *
     * It was written once, for the ore rungs, and sat private in {@code WorldDriverJourneyScenes} —
     * so rung 14 could not reach it and simply did not collect. On 2026-08-21 that rung killed seven
     * blazes in a room it had built, and banked <b>zero</b> rods with {@code dropsNearby=2} rods left
     * on the floor, not picked up, sitting right beside the verdict. The loot gate was never the problem: the dedicated
     * sensor {@code wd.serverEarnsABlazeRod} reports {@code blaze.killed=24/24, rods.total=11}, so
     * this bot's kills register as player kills and the drops roll normally. <b>Nobody went and got
     * them.</b>
     *
     * <p>Two rungs needing the same walk is what a shared rig is for, and {@link #dropsNearby} /
     * {@link #nearestDrop} — the pair this completes — already live here.
     *
     * <p>The beat after each walk is not padding: vanilla gives a fresh drop a ten-tick pickup delay,
     * and the pickup magnet only fires while something is ticking the player. Arriving and immediately
     * asking the next question walks away from an item that was about to be collectible.
     */
    public void collectByHand(String itemId, int legs, Runnable then) {
        int slash = itemId.indexOf(':');
        collectByHand(itemId, legs, slash < 0 ? itemId : itemId.substring(slash + 1), then);
    }

    /**
     * As above, under a caller-chosen evidence key.
     *
     * <p>The key is not decoration. Evidence entries overwrite by name, so two veins collecting the
     * same item wrote {@code pickup.walks} / {@code pickup.target} over each other and the surviving
     * pair described only the LAST vein. A run then read {@code vein1.raw_iron=0,
     * vein1.raw_iron.onGround=2} — two ingots' worth lying where the bot had just been — beside a
     * {@code pickup.target} ten blocks away at the other vein, which says nothing about whether
     * vein 1's collect ever walked anywhere.
     */
    public void collectByHand(String itemId, int legs, String key, Runnable then) {
        collectByHand(itemId, legs, legs, key, then);
    }

    /**
     * As above, carrying the walk TOTAL so the walk index is right whatever the caller asked for.
     *
     * <p>The index used to be {@code MAX_PICKUP_LEGS - legs + 1}, which is only correct while every
     * caller starts from that one constant. It stopped being true the moment a caller needed more:
     * the blaze rung fights up to 24 rounds and the dead blazes fall all over the room, so three walks
     * banked four rods and left two lying — {@code blaze.pickup.left 2} beside
     * {@code blaze_rod.quota 4/6}, i.e. the two missing rods were the two nobody walked to.
     */
    public void collectByHand(String itemId, int legs, int total, String key, Runnable then) {
        if (legs <= 0) { leftOnTheGround(itemId, key, then); return; }
        BlockPos drop = nearestDrop(itemId, PICKUP_RADIUS);
        if (drop == null) { leftOnTheGround(itemId, key, then); return; }
        evidence(key + ".pickup.walks", total - legs + 1);
        evidence(key + ".pickup.target", drop.toShortString());
        int had = carrying(itemId);
        settle(JourneyTasks.goToBlock(drop), 600,
                () -> hold(30, 50, () -> {
                    if (carrying(itemId) == had) whyNothingWasPickedUp(itemId, key, drop);
                    collectByHand(itemId, legs - 1, total, key, then);
                }));
    }

    /**
     * Why a walk that ended on the drop came back with nothing.
     *
     * <p><b>Instrument only — this changes no behaviour</b>, and it exists because one measured walk
     * could not be explained from the rows it already wrote. j34's furnace rung:
     * {@code craftingTable.broke=64,61,62} (1 on the ground after breaking, bot at 64,61,63) →
     * {@code pickup.walks=1} → {@code pickup.target=64,61,62} → {@code pickup.left#2=1}, with the
     * rung ending {@code craftingTable=0} and the bot standing at {@code 64,61,62} — the drop's own
     * cell. The walk arrived, the 30-tick hold ran, and the item stayed on the ground.
     *
     * <p>Two explanations survive that evidence and they want opposite repairs: <b>no free slot</b>
     * (vanilla's pickup simply does not add what will not fit, and the same run picked up raw_iron
     * fine — which stacks into a slot that already exists, while a crafting_table with none in the
     * bag needs a fresh one), or <b>the bot and the item never actually touched</b> (a {@code Goal.Block}
     * ending one cell off, an item that drifted, a pickup delay still running). The inventory count
     * reads 0 for both, which is why this asks the world instead of guessing.
     *
     * <p>Silent on the happy path on purpose: a row written on every successful collect would bury
     * the one occasion it was written for.
     */
    private void whyNothingWasPickedUp(String itemId, String key, BlockPos walkedTo) {
        ServerPlayer fp = player();
        var inv = fp.getInventory();
        int free = 0;
        for (int s = 0; s < inv.items.size(); s++) if (inv.items.get(s).isEmpty()) free++;
        var item = item(itemId);
        String nearest = "this drop is no longer within range";
        for (var drop : fp.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                fp.getBoundingBox().inflate(PICKUP_RADIUS))) {
            if (!drop.getItem().is(item)) continue;
            nearest = String.format("entity at %.2f,%.2f,%.2f (%.2f blocks away), pickupDelayActive=%s, count %d",
                    drop.getX(), drop.getY(), drop.getZ(), Math.sqrt(drop.distanceToSqr(fp)),
                    drop.hasPickUpDelay(), drop.getItem().getCount());
            break;
        }
        // WHICH WAY THE WALK ENDED, in the same row. Measured j39: this row's first firing read
        // 29/36 free slots and a distance of 7.45 blocks — the bag had room and the bot was seven blocks short, so
        // the walk had not arrived at all. `settle` runs its callback when the budget is spent
        // exactly as it does when the process finishes, and nothing here told them apart; the same
        // trap has a fix one file over (`JourneyCast.approachAndPour`'s `cast.walk`), and this
        // family never got it.
        evidence(key + ".pickup.empty", String.format(
                "walked to %s and stood 30 ticks but picked up nothing — free slots %d/%d, bot at %.2f,%.2f,%.2f; %s; this walk %s",
                walkedTo.toShortString(), free, inv.items.size(),
                fp.getX(), fp.getY(), fp.getZ(), nearest, JourneyLeg.navigatorEnd(this)));
    }

    /** What the collect could not get, read after it stops rather than before it starts. A drop
     *  count taken only up front cannot tell "the walk reached it" from "it despawned while the
     *  bot was at the next vein" — five minutes is a short life for an item and this ladder's
     *  mines are long. */
    private void leftOnTheGround(String itemId, String key, Runnable then) {
        evidence(key + ".pickup.left", dropsNearby(itemId, PICKUP_RADIUS));
        then.run();
    }

    /** How many hand-walked pickup walks a rung gets. Three, because the ladder's mines break a
     *  handful of blocks and a drop that two walks cannot reach is a finding, not a budget problem. */
    public static final int MAX_PICKUP_LEGS = 3;

    /** How far a collect looks. The same number on the walk and on the leftover count, so "walked
     *  there" and "how many are left" are answered about the same set of drops. */
    private static final double PICKUP_RADIUS = 32;

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
     * happen, about something the bot itself placed a moment ago and a few blocks away.
     */
    public BlockPos nearestBlock(String blockId, int radius) {
        return nearestBlock(blockId, radius, radius);
    }

    /**
     * The same, searching further sideways than up.
     *
     * <p>A cube is the wrong shape for anything the ladder leaves standing. Stations sit on the
     * ground the bot was standing on, so the vertical spread is a few blocks whatever the
     * horizontal one is — and the horizontal one has to be generous, because "where the last craft
     * happened" is a rung ago and a walk away. Measured: a table left at x=84 by the stone rung was
     * invisible to a radius-6 search at the furnace rung, which then bought another one and ran the
     * ladder out of wood.
     */
    public BlockPos nearestBlock(String blockId, int radius, int yRadius) {
        var wanted = lookup(BuiltInRegistries.BLOCK, blockId)
                .orElseThrow(() -> new IllegalArgumentException("no block " + blockId));
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

    /** How many of any of these items the bot is carrying, summed. */
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
     * stage's note. Searched over the loaded entities near the bot, which is honest about what is
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
            if (!animal.isAlive()) continue;                    // a corpse mid-death-animation
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

    /**
     * The nearest prey's species, <b>health</b> and distance — the row that says whether hits landed.
     *
     * <p>{@link #nearestPrey} answers "is there still something to hunt", which cannot tell a cow
     * that was never touched from one left at 2 HP. A hunt that banked nothing while the nearest cow
     * sits below its max was landing damage and failing to finish; one that ends with every cow at
     * full health never connected. Those are different defects in different files, and this is the
     * one field that separates them.
     *
     * <p>Null when nothing edible is loaded within {@code radius} — which is itself the answer to a
     * third question, and why the caller prints this string rather than branching on it here.
     */
    public String nearestPreyVitals(int radius) {
        ServerPlayer fp = player();
        var box = fp.getBoundingBox().inflate(radius, radius / 2.0, radius);
        net.minecraft.world.entity.animal.Animal best = null;
        double bestSq = Double.MAX_VALUE;
        for (var entity : fp.serverLevel().getEntities(fp, box)) {
            if (!(entity instanceof net.minecraft.world.entity.animal.Animal animal)) continue;
            // A CORPSE IS NOT PREY. A killed animal stays in the entity list through its ~20-tick
            // death animation, and this row caught one: ladder-11's rung 6 recorded
            // `minecraft:cow 0.0/10.0 HP` at 4.0 blocks beside `kill.preyLeft=minecraft:cow` — "there is
            // still a cow" was a corpse the rung had just killed. That ambiguity is the whole reason
            // the log-grep verdict was retracted, and it must not come back through this row.
            if (!animal.isAlive()) continue;
            if (animal.isBaby()) continue;
            var key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                    .getKey(animal.getType());
            if (key == null || !EDIBLE_PREY.contains(key.toString())) continue;
            double d = fp.distanceToSqr(animal);
            if (d < bestSq) { bestSq = d; best = animal; }
        }
        if (best == null) return null;
        var key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(best.getType());
        // Says what it measured, NOT what it means. "Full health" only implies "never connected" on
        // a hunt that banked nothing — on a successful hunt the nearest animal is a different,
        // untouched one, and a row asserting "never hit once" there would be false and greppable.
        // The caller knows the outcome; this does not.
        return String.format(java.util.Locale.ROOT, "%s %.1f/%.1f HP at %.1f blocks%s",
                key, best.getHealth(), best.getMaxHealth(), Math.sqrt(bestSq),
                best.getHealth() < best.getMaxHealth() ? " (this one has lost health)" : " (this one is at full health)");
    }

    /** Every animal species loaded near the bot, sorted — what a failed hunt should report instead
     *  of just "nothing found", because "there are only frogs here" is the useful sentence. */
    public java.util.List<String> animalsNearby(int radius) {
        ServerPlayer fp = player();
        var box = fp.getBoundingBox().inflate(radius, radius / 2.0, radius);
        java.util.Set<String> seen = new java.util.TreeSet<>();
        for (var entity : fp.serverLevel().getEntities(fp, box)) {
            if (!(entity instanceof net.minecraft.world.entity.animal.Animal animal)) continue;
            if (!animal.isAlive()) continue;   // same reason as nearestPreyTarget: a corpse is not one
            var key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                    .getKey(animal.getType());
            if (key != null) seen.add(key.toString());
        }
        return java.util.List.copyOf(seen);
    }

    /** A wool source: one sheep, where it stands, and the item it will actually drop. */
    public record Woolly(int entityId, BlockPos where, double distance, String woolId, String colour) {}

    /**
     * Every sheep near the bot that would really drop wool, nearest first.
     *
     * <p><b>The colour is part of the answer, not a detail.</b> A bed wants three wool of ONE
     * colour, so a haul of white + brown + black is worth exactly nothing — the same shape as the
     * plank-variant trap the craft resolver taught this ladder once already. Which sheep to kill is
     * therefore a decision that has to be made BEFORE the kill, and that is also why the entity id
     * comes back: {@link net.magicterra.worlddriver.bot.behavior.CombatProcess} accepts one, so the
     * rung can name the individual instead of naming the species and hoping the nearest match is
     * the colour it needed.
     *
     * <p>Lambs and already-sheared sheep are left out. Both are sheep and neither drops wool, so a
     * rung that walked to one would report "killed a sheep, got no wool" — a sentence that reads
     * like a broken drop table and is really a bad choice of target.
     *
     * <p><b>And so are corpses, since 2026-08-22.</b> The filter used to ask only about lambs and
     * shearing, so a sheep with its health at zero — still in the level for the twenty ticks its
     * death animation runs, still unsheared, still an adult — counted as a candidate and could be
     * picked again. The bed rung then reported {@code flock} = 6 shearable, unchanged across five rounds
     * that each claimed a kill, and the round that "killed" a sheep already at zero banked nothing.
     * A method whose first line of documentation promises sheep <i>that would really drop wool</i>
     * cannot leave the one question out that decides it. Count the corpses with
     * {@link #deadSheepNearby} rather than hiding them: how many are lying around is itself the
     * reading that separates "the flock is thin" from "the same sheep is being re-killed".
     */
    public java.util.List<Woolly> woolNearby(int radius) {
        ServerPlayer fp = player();
        java.util.List<Woolly> out = new java.util.ArrayList<>();
        for (var sheep : sheepNearby(radius)) {
            if (sheep.isBaby() || sheep.isSheared() || !sheep.isAlive()) continue;
            String colour = sheep.getColor().getName();
            out.add(new Woolly(sheep.getId(), sheep.blockPosition(),
                    Math.sqrt(fp.distanceToSqr(sheep)), "minecraft:" + colour + "_wool", colour));
        }
        out.sort(java.util.Comparator.comparingDouble(Woolly::distance));
        return java.util.List.copyOf(out);
    }

    /** Every sheep in the box around the bot, alive or not — the one scan both readers share. */
    private java.util.List<net.minecraft.world.entity.animal.Sheep> sheepNearby(int radius) {
        ServerPlayer fp = player();
        var box = fp.getBoundingBox().inflate(radius, radius / 2.0, radius);
        var out = new java.util.ArrayList<net.minecraft.world.entity.animal.Sheep>();
        for (var entity : fp.serverLevel().getEntities(fp, box))
            if (entity instanceof net.minecraft.world.entity.animal.Sheep sheep) out.add(sheep);
        return out;
    }

    /**
     * How many sheep near the bot are dead — the ones {@link #woolNearby} now drops.
     *
     * <p>Kept as a separate reading rather than folded into the candidate list, because the two
     * numbers answer different questions and a rung that only ever sees the filtered one cannot tell
     * a flock that has been hunted out from a flock whose corpses it kept walking back to.
     */
    public int deadSheepNearby(int radius) {
        int n = 0;
        for (var sheep : sheepNearby(radius)) if (!sheep.isAlive()) n++;
        return n;
    }

    /** The dimension the bot is standing in, as {@code "minecraft:overworld"}. */
    public String dimension() {
        return player().serverLevel().dimension().location().toString();
    }

    /**
     * Whether the bot has completed an advancement.
     *
     * <p>Answers false rather than throwing when the player cannot carry progress at all. A fake
     * player's advancement state is not something this suite may assume works — see the class note
     * on what a headless server-side player is — so a stage asserts on the ITEM it obtained and records the
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
     * player that cannot carry progress says so instead of reporting an absence.
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
        // WHICH TASK IS LIVE, remembered here because this is the funnel every DELIBERATE row goes
        // through — and the per-tick rows do not: `futileGate`, `navigatorCensus` and `body.vitals`
        // write into the map directly from #heartbeat, so a reading that would otherwise be
        // overwritten every 200 ticks survives as the task's own tag. See death.leg.
        lastEvidenceKey = key;
        lastEvidenceLegTicks = legTicks;
        Slot slot = slotFor(key, value);
        if (slot.clashed()) {
            TestMod.LOG.warn(
                    "[journey] {} evidence key written twice with different values: {} — old {} / new {};"
                            + " both kept, the new value is under {}",
                    stage.name(), key, evidence.get(key), value, slot.key());
            clashes.add(key + " (old value under " + key + ", new value under " + slot.key() + ")");
            put("evidence.clash", String.join("; ", clashes));
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
     * <p>Also writes the bot's invulnerability into the record, so every green row on the headless
     * track carries the bound on what it proves.
     *
     * <p>And says so ON THE PASS ROW when the rung was rehearsed rather than climbed. The evidence
     * map has carried {@code REHEARSAL} all along, but a PASS prints its note and nothing else —
     * so the one line a reader quotes from a green rehearsal, {@code PORTAL_LIT reached — …}, was
     * word-for-word the line a real climb prints. That is precisely the confusion the whole
     * rehearsal mode is built to be incapable of.
     */
    public void reach(String detail) {
        claimed = true;
        recordFutileGate(futileGateLine());
        recordNavigatorCensus(navigatorCensus.line());
        recordBodyVitals(bodyVitalsLine());
        // ON THE PASS TOO. A rung that drowned nobody is the control this reading is judged
        // against, and a control that only the failures carry is not a control.
        recordDrown(drownWatch.line());
        evidence.put("body.invulnerable", bodyIsInvulnerable());
        int staged = JourneyLedger.stagingCalls().size();
        evidence.put("staging.calls", staged);
        JourneyLedger.reached(stage, detail, evidence, tick(ctx));
        String rehearsing = JourneyRehearsal.target() == null ? ""
                : "[REHEARSAL — not a climb, staging.calls=" + staged + "] ";
        ctx.passNote(rehearsing + stage.name() + "(" + stage.label() + ") reached — " + detail);
        TestMod.LOG.info("[journey] {} REACHED — {} {}", stage.name(), detail, evidence);
    }

    // ---- teardown ----

    /**
     * Let the bot go and release its chunks. Called once, by the verdict scene.
     *
     * <p>Idempotent, and safe to call when the run never made a bot — the verdict scene has to run
     * whether the journey got anywhere or not, and a teardown that threw on an empty run would
     * replace the verdict with a stack trace.
     */
    public static void teardown() {
        JourneyHelm h = helm;
        if (h != null) {
            // A roster bot leaves the world through the roster. NEVER the adopted player: on the integrated
            // topology the bot IS the client's player, and removing it would delete the entity out from under a
            // live connection. self is handed back instead — its task cancelled, its keys let go — so a task still
            // held by the client's chain does not keep walking the player after the suite has stopped watching.
            // The cancel is sent, not waited for: this runs on the server thread during teardown, the one moment
            // the client may already be tearing itself down.
            try { h.release(); } catch (RuntimeException ignored) { /* already gone, or the client is */ }
            helm = null;
        }
        realPlayerHelm = null;
        if (pinned != null && pinnedLevel != null) {
            try {
                pinnedLevel.getChunkSource().removeRegionTicket(
                        JOURNEY_TICKET, pinned, bodyChunkRadius + 2, pinned);
            } catch (RuntimeException ignored) { /* level gone with the run */ }
        }
        pinned = null;
        pinnedLevel = null;
    }

    // ---- answers that land later ----

    /** Answers self has not given yet, by the evidence key each is written under when it lands. */
    private final Map<String, java.util.concurrent.CompletableFuture<?>> pendingAnswers = new LinkedHashMap<>();

    /**
     * Write {@code answer} as evidence under {@code key} — now when it has landed (every roster bot call), else
     * on the first tick of a rig wait after it does (self's, which answer on the client thread).
     */
    void recordWhenLanded(String key, java.util.concurrent.CompletableFuture<?> answer) {
        if (answer.isDone()) { evidence(key, landed(answer)); return; }
        pendingAnswers.put(key, answer);
    }

    /**
     * Hand {@code answer} to {@code then} once it has landed: within the call for a roster bot, for self on the
     * first rig tick after the client answered. A failed call, or one that has not landed within
     * {@code withinTicks}, fails the scene.
     */
    <T> void whenLanded(String what, java.util.concurrent.CompletableFuture<T> answer, int withinTicks,
                        java.util.function.Consumer<T> then) {
        Runnable deliver = () -> {
            if (!answer.isDone()) { ctx.fail("journey: " + what + " did not answer within " + withinTicks + " ticks"); return; }
            then.accept(Calls.now(answer));
        };
        if (answer.isDone()) { deliver.run(); return; }
        await(answer::isDone, withinTicks, deliver);
    }

    private void drainLandedAnswers() {
        if (pendingAnswers.isEmpty()) return;
        var it = pendingAnswers.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (!e.getValue().isDone()) continue;
            it.remove();
            evidence(e.getKey(), landed(e.getValue()));
        }
    }

    /** {@link #landed} for an answer already in (every roster bot call), else says self's is still on its way. */
    static String landedNow(java.util.concurrent.CompletableFuture<?> f) {
        return f.isDone() ? landed(f) : "pending (self answers on the client thread)";
    }

    /**
     * Press keys on the run's bot through {@code mc.test.input.press}, and leave them so: a roster bot's within
     * the call, self's on the client thread, its answer written under {@code actuator.press} once it lands — a
     * client with no {@code LocalPlayer} answers that it has none.
     */
    void press(java.util.function.UnaryOperator<net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Press> keys) {
        var press = keys.apply(Harness.keys(bot()));
        if (helm().serverBotOrNull() != null) { Harness.press(press); return; }
        recordWhenLanded("actuator.press", Harness.pressLater(press));
    }

    /** Press ({@code down}) or release the use key and leave it so — a bite, a drawn bow — on the run's bot. */
    void holdUseKey(boolean down) {
        press(k -> k.use(down));
    }

    /**
     * Drive the bot from the scene itself, {@code eachTick} once per tick, for at most {@code ticks}, then
     * {@code then}: a scene-written task (a push into a portal, a swing, the dragon fight), its inputs pressed
     * through {@link #press}. The bot's own task is cancelled first, as starting the task this replaces once
     * replaced it, so the scene's inputs are the only ones; {@code kind} names it in the heartbeat. Ends when
     * {@code eachTick} says so, when the bot leaves the world, or when the ticks run out. self's presses land on
     * the client a tick or so after the server tick that sent them.
     */
    void runEachTick(String kind, BooleanSupplier eachTick, int ticks, Runnable then) {
        driving = kind;
        legTicks = 0;
        helm().cancel();
        int[] waited = {0};
        await(() -> bodyLeftTheWorld() || eachTick.getAsBoolean() || ++waited[0] >= ticks, ticks + 100, then);
    }

    /** A landed answer as a row: a use's interaction result, a hold's item, a refusal's words, or the failure. */
    private static String landed(java.util.concurrent.CompletableFuture<?> f) {
        if (f.isCompletedExceptionally()) {
            Throwable t = f.exceptionNow();
            var a = net.magicterra.worlddriver.api.schema.ApiException.find(t);
            return "the call failed: " + (a != null ? a.error() : t);
        }
        Object r = f.join();
        if (r instanceof net.magicterra.worlddriver.api.methods.shared.Responses.Used u) return u.result().name();
        if (r instanceof net.magicterra.worlddriver.api.methods.shared.Responses.Held h) return h.held();
        if (r instanceof net.magicterra.worlddriver.api.methods.shared.Responses.Refused<?> no) {
            return "refused: " + no.reason().wireName() + ": " + no.message();
        }
        return String.valueOf(r);
    }

    /** The world's tick count, for stamping ledger entries. */
    private static long tick(SceneContext ctx) {
        ServerLevel level = ctx.level();
        return level == null ? -1L : level.getGameTime();
    }
}
