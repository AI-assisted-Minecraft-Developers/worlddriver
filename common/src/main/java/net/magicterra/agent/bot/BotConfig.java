package net.magicterra.agent.bot;
import java.util.Set;
import net.magicterra.agent.bot.pathfinder.PathFinder;

/**
 * Mutable tuning values exposed via {@code mc.bot.setting}. Read by Walker,
 * MineProcess, ClearAreaProcess every tick — no caching of the previous value
 * elsewhere, so a write applies immediately to the next tick.
 *
 * Volatile to make cross-thread writes (RPC handler thread → client tick thread)
 * visible without synchronization. Range bounds are enforced in
 * {@link BotApiImpl#setting} so this class doesn't need its own validation.
 */
public final class BotConfig {
    private BotConfig() {}

    /** Walker repath cadence. Lower = more responsive to obstacles, higher = less CPU. */
    public static volatile int walkerRepathEveryTicks = 200;

    /** Walker total-tick safety budget; if no progress within this many ticks, fail. */
    public static volatile int walkerTotalTickBudget = 1200;

    /** Yaw delta below this is not written each tick — reduces jitter when already aligned. */
    public static volatile float walkerYawHysteresisDeg = 5f;

    /** Vertical band (+/-) of mine scans around the player's foot Y. */
    public static volatile int mineSearchVerticalRadius = 8;

    /** Ticks the breaker waits before blacklisting a stuck block. */
    public static volatile int breakTimeoutTicks = 200;

    /** Baritone-style survival toggles — each tick the bot driver checks these
     *  and may inject a single client-side action (hold keyUse for autoEat,
     *  call player.respawn() for autoRespawn). Off by default so a quiet bot
     *  stays quiet. */
    public static volatile boolean autoEat = false;

    /** Food level at or below which autoEat will hold useItem on a food item.
     *  Stops when food fills to 20. Baritone default = 18 (one bite of room). */
    public static volatile int autoEatFoodThreshold = 18;

    /** Click the Respawn button (via player.respawn()) the moment a
     *  DeathScreen is shown. Without this, a dead bot sits on the death
     *  overlay until a human intervenes. */
    public static volatile boolean autoRespawn = false;

    /** Hold jump while fully submerged so the bot floats toward the surface
     *  instead of drowning. Released the moment head is in air. Yields to
     *  active processes that own keyJump (mine/build BREAKING/PLACING). */
    public static volatile boolean autoSwim = false;

    /** A* node cap surfaced as a tunable knob — Baritone's
     *  {@code pathTimeoutMS} analogue. Maps directly to
     *  {@link net.magicterra.agent.bot.pathfinder.PathFinder} default. */
    public static volatile int pathfinderMaxNodes =
            PathFinder.DEFAULT_MAX_NODES;

    /** Per-tick compute slice (ms) for the time-sliced A* search. The Walker
     *  advances an in-flight search by at most this much each client tick, so a
     *  big search spreads over frames instead of blocking the render thread in
     *  one go (no stutter). The total search is still bounded by
     *  {@link #pathfinderMaxNodes}/{@link #pathfinderMaxMs}. ~6 ms keeps a tick
     *  well under one 16 ms frame. */
    public static volatile long pathfinderSliceMs = 6;

    /** A* wall-clock cap, ms. Default mirrors {@code PathFinder.DEFAULT_MAX_MS}. */
    public static volatile long pathfinderMaxMs =
            PathFinder.DEFAULT_MAX_MS;

    /** Y plane targeted by {@code mc.bot.goto{axis:true}} — Baritone's
     *  {@code axisHeight} setting (default 120, the classic "highway" Y). Read
     *  when an Axis goal is constructed. */
    public static volatile int axisHeight = 120;

    /** Camera smoothing for stream/demo scenarios. When on, the pathfinding
     *  Walker and the {@code mc.bot.lookAt} verb rotate toward their target by
     *  at most {@link #smoothLookDegPerTick} per tick instead of snapping
     *  instantly. Off by default so headless/scripted behavior (and the
     *  validation suite) is unchanged. Functional aiming that gates an
     *  immediate raycast — attack, place, break, build face — always snaps
     *  regardless, since a lagged crosshair would make those actions miss. */
    public static volatile boolean smoothLook = false;

    /** Max degrees the camera turns per tick while {@link #smoothLook} is on.
     *  20°/tick ≈ 400°/s → a 180° turn takes ~9 ticks (~0.45 s). Lower = more
     *  cinematic, higher = snappier. Read every tick. */
    public static volatile float smoothLookDegPerTick = 20f;

    /** Extra block ids the pathfinder treats as hazardous (in addition to the
     *  built-in HAZARD_BLOCKS set in BotApiImpl). Mutable via
     *  {@code mc.bot.setting{blocksToAvoid:[id,...]}}. Read on every WorldView
     *  query so changes apply immediately. Stored as an immutable Set; writers
     *  replace the whole reference. */
    public static volatile Set<String> extraHazardBlocks = Set.of();

    /** Baritone {@code allowParkour4} analogue — enables 4-block cardinal
     *  leaps in A*. Off by default because the leap is at the edge of vanilla
     *  sprint-jump physics; turning it on without jump-boost / Speed makes
     *  the bot pick unreachable goals. Read every Move.Parkour4.valid call. */
    public static volatile boolean allowParkour4 = false;

    /** Baritone {@code autoTool} analogue — when on, the bot swaps to the
     *  best hotbar tool whenever the player crosshair points at a breakable
     *  block (and no process owns hotbar selection). Default off so
     *  scripted hotbar layouts aren't fighting the bot for the selected
     *  slot. Read every clientTick via maybeAutoTool. */
    public static volatile boolean autoTool = false;

    /** Baritone {@code BackfillProcess} analogue — when on, the bot tracks
     *  cells it walked through and auto-fills them with {@link #autoBackfillBlock}
     *  whenever no other process owns the keys. Useful for sealing mine
     *  tunnels behind a {@code mc.bot.mine} pass. Off by default. */
    public static volatile boolean autoBackfill = false;

    /** Block id used when {@link #autoBackfill} fires. Must be a vanilla block
     *  the player has in their inventory (creative skips the inventory check
     *  via auto-pickItem). */
    public static volatile String autoBackfillBlock = "minecraft:cobblestone";

    /** Chebyshev radius around the player within which {@link #autoBackfill}
     *  considers tracked-air cells for filling. Larger = more aggressive
     *  but more pathing per tick. */
    public static volatile int autoBackfillRadius = 6;

    /** Baritone {@code allowBreak} analogue — the pathfinder may mine
     *  obstructing blocks as part of a route (tunnel through a wall, dig
     *  straight down). The break time (tool-aware) is folded into the move
     *  cost so A* only tunnels when detouring would cost more. Off by default
     *  so {@code goto}/{@code follow}/{@code explore} never modify the world
     *  unless explicitly enabled — keeps livestream/demo runs non-destructive.
     *  Read every {@code TraverseBreak}/{@code DownBreak}.eval + WorldView.breakCost. */
    public static volatile boolean allowBreak = false;

    /** Baritone {@code allowPlace} analogue — the pathfinder may place a
     *  throwaway block to bridge a one-block gap as part of a route. Requires a
     *  BlockItem in the hotbar (creative skips the check). Off by default for
     *  the same non-destructive reason. Read every {@code BridgePlace}.eval +
     *  WorldView.canPlace. */
    public static volatile boolean allowPlace = false;

    /** Baritone {@code maxFallHeightBucket} analogue — the pathfinder may plan a
     *  fall taller than the no-water cap (3 blocks) when the bot has a water
     *  bucket in its hotbar, placing a water source on the landing block to
     *  break the fall (MLG) and scooping it back. Off by default for the same
     *  non-destructive reason as {@link #allowPlace} (it places a water source).
     *  Read every {@code WaterBucketFall}.valid + WorldView.canWaterBucketFall. */
    public static volatile boolean allowWaterBucketFall = false;

    /** Baritone {@code allowParkourPlace} analogue — the pathfinder may cross a
     *  gap with a sprint-jump onto a block placed mid-air (instead of two slow
     *  sneak-bridges) when the landing cell has a pre-existing solid neighbour to
     *  place against. Requires a placeable block in the hotbar (creative skips the
     *  check). Off by default for the same non-destructive reason as
     *  {@link #allowPlace}. Read every {@code ParkourPlace}.eval +
     *  WorldView.canParkourPlace. */
    public static volatile boolean allowParkourPlace = false;

    /** Tallest drop (blocks) the bot will commit to with a water-bucket fall when
     *  {@link #allowWaterBucketFall} is on — Baritone {@code maxFallHeightBucket}
     *  (default 20). Above this, A* finds another way down. */
    public static volatile int maxWaterBucketFall = 20;

    /** After an MLG fall, scoop the placed water source back into the bucket so
     *  the world is left clean and the bucket is reusable for the next fall.
     *  Off → the water source is left in place (single-use bucket). On by default. */
    public static volatile boolean waterBucketScoop = true;

    /** Baritone avoidance analogue — when on, A* adds a soft cost penalty for
     *  standing next to lava/fire so routes keep a one-block buffer from
     *  hazards instead of skimming them (it will still thread a lava-lined
     *  corridor if that's the only way). Purely makes paths safer, so on by
     *  default. Read every WorldView.dangerCost call. */
    public static volatile boolean avoidDanger = true;

    /** Cost added per <em>fire</em> cell adjacent to a candidate stand position
     *  when {@link #avoidDanger} is on. ~3 walk-steps of detour per hazard
     *  neighbour — enough to route around it, not so much that a forced
     *  corridor becomes unreachable. Lava is weighted separately (and heavier)
     *  via {@link #lavaDangerPenalty}; contact plants via {@link
     *  #contactDangerPenalty}. */
    public static volatile double dangerPenaltyPerCell = 30;

    /** Cost added per <em>lava</em> cell adjacent to a candidate stand position
     *  when {@link #avoidDanger} is on. Lava contact is lethal (burning persists
     *  after you step off), so it weighs far more than fire — the planner will
     *  pay a long detour rather than skim one block from open lava, while still
     *  threading a lava-lined corridor that is the only route. Baritone likewise
     *  treats lava as near-impassable rather than a mild nudge. */
    public static volatile double lavaDangerPenalty = 80;

    /** Cost added per <em>contact-damage</em> block (cactus, sweet-berry bush,
     *  wither rose, magma block, powder snow) adjacent to a candidate stand
     *  position. Small — these only graze you on overlap, not from the next cell
     *  — so it just discourages hugging them when an equal route exists. */
    public static volatile double contactDangerPenalty = 12;

    /** Cost added when a candidate stand position sits at the lip of a drop at
     *  least {@link #ledgeDangerMinDrop} blocks deep (a cliff / void edge), when
     *  {@link #avoidDanger} is on. Mild and applied once per cell regardless of
     *  how many sides are open — it nudges the planner toward an equal-length
     *  interior route ("rather detour than graze the edge") without forcing a
     *  detour around every ledge or blocking a narrow bridge that is the only
     *  way. Set 0 to disable edge avoidance entirely. */
    public static volatile double ledgeDangerPenalty = 15;

    /** Minimum empty blocks below an open neighbour for it to count as a real
     *  cliff for {@link #ledgeDangerPenalty} (so a harmless 1–2 block step-down
     *  next to the path isn't treated as a void edge). */
    public static volatile int ledgeDangerMinDrop = 4;

    /** Baritone mob-avoidance analogue — when on, A* adds a distance-ramped cost
     *  for standing near a hostile mob (snapshotted once per search), so routes
     *  give creepers/zombies a berth when they can. Off by default: it changes
     *  pathing noticeably and is only wanted when survival threats matter. Read
     *  every WorldView.beginSearch / dangerCost. */
    public static volatile boolean avoidMobs = false;

    /** Radius (blocks) within which a hostile mob contributes a {@link #avoidMobs}
     *  penalty; the cost ramps linearly from {@link #mobAvoidPenalty} at the mob
     *  to 0 at the edge. */
    public static volatile double mobAvoidRadius = 6;

    /** Peak cost (at zero distance) of a single avoided mob when {@link #avoidMobs}
     *  is on; ramps down to 0 at {@link #mobAvoidRadius}. */
    public static volatile double mobAvoidPenalty = 40;

    /** Diagnostic — when on, the Walker logs its per-tick decision (flight,
     *  onGround, descending, foot, step, branch, repath outcome) to the
     *  {@code AgentDriver} logger. Off by default; toggle via
     *  {@code mc.bot.setting{walkerDebug:true}} when chasing a movement bug. */
    public static volatile boolean walkerDebug = false;

    /** Diagnostic — when on, the elytra flight process validates the
     *  {@link net.magicterra.agent.bot.elytra.ElytraPhysics} simulator
     *  tick-by-tick against the live client (predicted vs observed
     *  {@code deltaMovement}) and logs per-tick error plus a summary on stop.
     *  Used to prove the simulator is tick-exact before the reactive controller
     *  is built on it. Off by default; toggle via
     *  {@code mc.bot.setting{elytraDebug:true}}. */
    public static volatile boolean elytraDebug = false;
}
