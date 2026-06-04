package net.magicterra.agent.bot;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import net.magicterra.agent.bot.pathfinder.PathFinder;
import static net.magicterra.agent.AgentDriverCommon.LOG;

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

    /** Survival reflex (ROADMAP Phase B, scheduler RetreatChain) — when on, the
     *  bot disengages and flees once its health drops to {@link #retreatHpThreshold},
     *  preempting whatever user task is running and resuming it once HP recovers.
     *  Off by default so a quiet bot stays quiet (a scripted scenario that wants
     *  the bot to hold ground isn't overridden). */
    public static volatile boolean autoRetreat = false;

    /** Health (half-hearts, 0–20) at or below which {@link #autoRetreat} triggers
     *  a flee. Default 6 (3 hearts) — enough margin to escape most mobs before a
     *  follow-up hit is lethal. The chain's priority ramps as HP falls further
     *  below this, so a near-death bot flees harder. */
    public static volatile float retreatHpThreshold = 6f;

    /** Phase B hand/equipment reflexes (ambient, run concurrently with movement,
     *  arbitrating the use key shield > heal > eat). All off by default so a
     *  quiet bot stays quiet. */
    public static volatile boolean autoTotem  = false;   // keep a totem in the offhand
    public static volatile boolean autoShield = false;   // raise shield vs incoming
    public static volatile boolean autoHeal   = false;   // drink/eat to heal when low

    /** HP (half-hearts) at or below which {@link #autoHeal} consumes a healing
     *  item (healing/regen potion, golden apple). Default 12 (6 hearts). */
    public static volatile float healHpThreshold = 12f;

    /** Phase B movement-channel reflex: dodge incoming projectiles / creeper
     *  blast / dragon-breath clouds (PanicChain + DodgeChain). Off by default. */
    public static volatile boolean autoDodge = false;

    /** Phase C active-combat toggle (scheduler CombatChain). When on, the bot
     *  auto-engages nearby hostiles (ENGAGE mode) whenever a threat scores at or
     *  above {@link #autoFightThreatThreshold} — preempting the user task at
     *  priority {@code COMBAT} (60) and handing it back once the area is clear.
     *  Off by default so a quiet bot stays quiet (the explicit {@code
     *  mc.bot.combat} verb works regardless of this flag). */
    public static volatile boolean autoFight = false;

    /** ThreatScanner score (0–1) a hostile must reach to trigger {@link #autoFight}.
     *  Low default — any visible hostile within scan range is worth engaging when
     *  auto-fight is deliberately turned on. */
    public static volatile double autoFightThreatThreshold = 0.05;

    /** Melee engage distance (blocks, centre-to-centre). The combat loop closes to
     *  within this before swinging; vanilla attack reach is ~3.0. */
    public static volatile double combatReach = 3.0;

    /** Ranged kite distance (blocks). With a bow/crossbow the combat loop keeps the
     *  target near this range — backs up when closer, advances when farther. */
    public static volatile double kiteDistance = 8.0;

    /** Phase F T0 reflex: while the combat chain is engaged, ensure the best armor
     *  is worn and the best weapon is in hand (sibling of {@link #autoTool}). Off by
     *  default; the explicit {@code mc.bot.equip} verb works regardless. */
    public static volatile boolean autoEquip = false;

    /** Equipped items below this remaining-durability fraction (0–1) are flagged in
     *  {@code mc.bot.equip}'s {@code lowDurability} report so the agent can go repair
     *  / swap before they break mid-fight. Default 0.1 (10%). */
    public static volatile double equipDurabilityThreshold = 0.1;

    /** Jump before a melee swing so the descending hit lands a 1.5× critical
     *  (vanilla crit needs the attacker airborne + falling). On by default — it's
     *  free extra damage and the pre-jump is timed to the attack cooldown so it
     *  doesn't stall the swing rhythm. */
    public static volatile boolean combatCrit = true;

    /** A creeper within this many blocks (and swelling) makes PanicChain sprint
     *  the bot away from it. ~3.5 = just outside the lethal blast core. */
    public static volatile double creeperKeepDistance = 3.5;

    /** Radius within which an incoming projectile predicted to hit triggers a
     *  DodgeChain sidestep. */
    public static volatile double projectileDodgeRadius = 12.0;

    /** Hold jump while fully submerged so the bot floats toward the surface
     *  instead of drowning. Released the moment head is in air. Yields to
     *  active processes that own keyJump (mine/build BREAKING/PLACING). */
    public static volatile boolean autoSwim = false;

    /** Suffocation backstop (sibling of {@link #autoSwim}): when a solid block —
     *  classically falling SAND collapsing into the head while digging up/down a
     *  disturbed pit — overlaps the bot's eyes, break that block so it can't be
     *  suffocated to death mid-dig (a naked bot went HP 17→11 in seconds this way,
     *  dig-out oscillating with no progress). Needs {@link #allowBreak}. Default ON.
     *  Read only by the {@code AntiSuffocate} client-tick reflex — never the planner. */
    public static volatile boolean antiSuffocate = true;

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

    /** Proactive idle-only dusk shelter (DuskSecureChain, priority IDLE_SECURE=40):
     *  when the bot is sky-exposed at dusk/night, idle (no user task running), and
     *  no threat is within 12 blocks, dig a "挖三填一" bunker (BunkerProcess) after a
     *  short debounce. Off by default; enable for fully autonomous survival runs.
     *  Note: this is below the USER priority band, so any active task suppresses it. */
    public static volatile boolean autoSecureAtDusk = true;

    /** Emergency "挖三填一" bunker reflex (BunkerChain): when cornered — low HP
     *  AND several hostiles right next to the bot, where fleeing just runs into
     *  more mobs — dig straight down a couple of blocks and seal the roof with the
     *  dug blocks, making a 1×1 pocket mobs can't reach. The canonical no-gear
     *  survival move for a swarm; needs no items (digging supplies the seal block).
     *  Off by default (it modifies the world); enable for autonomous survival. */
    public static volatile boolean autoBunker = false;
    /** HP at/below which the bunker reflex may trigger (when also surrounded).
     *  Default 10 (not lower): digging+sealing a 2-deep pocket by hand takes a few
     *  seconds during which the swarm keeps hitting, so the bot needs a buffer to
     *  finish before it dies — triggering at HP 8 was too late in live tests. */
    public static volatile double bunkerHpThreshold = 10;
    /** A hostile within this many blocks counts as "surrounding" for the trigger.
     *  Default 7 (not 5): a real moving swarm clusters at 5–8 blocks and rarely puts
     *  two mobs inside a 5-block bubble at once — a tight radius never fired in live
     *  tests while the bot was beaten to death just outside it. */
    public static volatile double bunkerTriggerRadius = 7;
    /** How many surrounding hostiles (within {@link #bunkerTriggerRadius}) it takes
     *  to decide fleeing is hopeless and to dig in instead. */
    public static volatile int bunkerMinHostiles = 2;
    /** How many blocks straight down the bunker digs before sealing the roof. */
    public static volatile int bunkerDepth = 2;

    /** Baritone {@code allowBreak} analogue — the pathfinder may mine
     *  obstructing blocks as part of a route (tunnel through a wall, dig
     *  straight down). The break time (tool-aware) is folded into the move
     *  cost so A* only tunnels when detouring would cost more. Off by default
     *  so {@code goto}/{@code follow}/{@code explore} never modify the world
     *  unless explicitly enabled — keeps livestream/demo runs non-destructive.
     *  Read every {@code TraverseBreak}/{@code DownBreak}.eval + WorldView.breakCost. */
    public static volatile boolean allowBreak = false;

    /** Water-escape break: when the bot is stuck IN water (a flooded pit, a
     *  high-banked lake/ocean shore it can't {@code stepUp} out of), the
     *  pathfinder may mine the obstructing BANK blocks to climb ashore — even
     *  when the general {@link #allowBreak} is off. Unlike allowBreak, this is
     *  on by default and tightly scoped: the escape-break moves only ever fire
     *  from a water-edge context (feet in water, or water in the ring directly
     *  below the feet), so dry-land routes are never affected — the bot won't
     *  start tunnelling through hills, only out of the water it's drowning in.
     *  Read by {@code SwimAshoreBreak}/{@code SwimTraverseBreak}.eval +
     *  WorldView.escapeBreakCost. Set false to forbid all autonomous mining. */
    public static volatile boolean allowSwimEscapeBreak = true;

    /** Sibling of {@link #allowSwimEscapeBreak} but for PLACING instead of
     *  breaking. A floating bot cannot swim-jump onto a bank whose top sits
     *  ABOVE the water surface (swim-up tops out AT the surface, ~0.6 short of
     *  the step-up grab) — it bob-cycles forever against the bank. When the
     *  Walker detects that stall it places ONE throwaway block on the surface
     *  against the bank to get GROUNDED, after which the ordinary dry climb
     *  finishes the +1/+2. Default ON as a water-escape safety net (independent
     *  of the conservative general {@link #allowPlace}); needs a placeable block
     *  in the hotbar. Read only by the Walker's climb-out actuator — never by the
     *  pathfinder, so land/route planning is byte-for-byte unchanged. */
    public static volatile boolean allowSwimEscapePlace = true;

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

    /** Cost added per node where the bot's foot is in water, when {@link
     *  #avoidDanger} is on. Makes A* prefer a dry-land route over swimming —
     *  the planner used to happily route straight across the ocean (slow, and
     *  a drowning risk), the "寻路太蠢/走进海里" complaint. Additive, not a ban:
     *  a short ford or a sole water crossing is still taken, just at a cost, so
     *  a long open-water swim loses to any reasonable land detour. Set 0 to
     *  disable. */
    public static volatile double waterDangerPenalty = 12;

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

    public static volatile int rangedAvoidRadius = 16;   // wider berth for ranged mobs (skeleton/witch) — Baritone Avoidance, AltoClef-style ranged split
    public static volatile double fleeDangerBoost = 8;   // during an active flee, water/ledge danger ×this so the flee won't dive into water or off a cliff (F2)
    public static volatile boolean fleeActive = false;   // RUNTIME flee-context flag (a RunAwayProcess ticked this frame); NOT persisted, NOT in MCP schema

    /** Agent-supplied danger zones to route AROUND — each row is
     *  {@code [x, y, z, radius]}. Set via {@code mc.bot.setting{avoidPoints:[...]}}
     *  (or cleared with {@code []}) right before a goto. Unlike {@link #mobAvoidPenalty}
     *  (which only knows the live per-search mob snapshot within ~64 blocks), these
     *  are explicit regions the AGENT marked from observing the surroundings — e.g. a
     *  mob-filled cave/tunnel mouth it can see is dangerous even when the mobs are
     *  underground / out of the snapshot. The A* dangerCost adds {@link #avoidZonePenalty}
     *  ramping to 0 at the zone radius, so the planner detours — the no-gear move of
     *  "when freshly spawned and unarmed, take the long way around the monster tunnel".
     *  Empty = none. */
    public static volatile double[][] avoidZones = new double[0][];
    /** Peak cost (at a zone's centre) of an {@link #avoidZones} region; ramps to 0 at
     *  the zone's radius. High (default 250) so the planner really does detour around
     *  a marked tunnel rather than thread it — the Agent scales/marks more aggressively
     *  when the bot is poorly equipped. */
    public static volatile double avoidZonePenalty = 250;

    /** Prefer-surface penalty: cost added PER BLOCK of depth for a foot that sits
     *  ≥{@link #deepDarkMinDepth} below the world-surface HEIGHTMAP at its x,z.
     *  Caves/ravines below the surface are where mobs survive daylight and a naked
     *  bot gets swarmed; without this A* dives into one as a shortcut on an overland
     *  trek (observed: bot routed y70→y22 into a cave, died — the old guard keyed off
     *  the SEARCH ORIGIN, which RESET once the bot was already underground, so going
     *  deeper stopped being penalized). Keying off the heightmap is position-
     *  independent → always penalizes "how far underground". Additive + depth-scaled
     *  (×min(depth,64)), NOT a ban — A* still descends when there's genuinely no
     *  surface path or for a short deliberate dig, but a deep cave route becomes
     *  prohibitively expensive vs any surface detour. Set 0 to disable. Read in
     *  {@code ClientWorldView.dangerCost} (gated on {@link #avoidDanger}). */
    public static volatile double deepDarkPenalty = 25;
    /** Min blocks below the surface heightmap before {@link #deepDarkPenalty} applies
     *  — so a normal valley/river dip, a 1–2 block step-down, or a short dig for
     *  surface stone isn't penalized, only a genuine descent toward a deep cave. */
    public static volatile int deepDarkMinDepth = 8;

    /** Diagnostic — when on, the Walker logs its per-tick decision (flight,
     *  onGround, descending, foot, step, branch, repath outcome) to the
     *  {@code AgentDriver} logger. Off by default; toggle via
     *  {@code mc.bot.setting{walkerDebug:true}} when chasing a movement bug. */
    public static volatile boolean walkerDebug = false;

    // --- WorldModel / HazardField (perception slice) ---
    /** Chebyshev radius (blocks) of the HazardField grid recomputed each
     *  decimated tick by WorldModel.update. Larger = wider situational awareness,
     *  higher per-tick cost. Default 12 keeps the grid under 625 cells. */
    public static volatile int hazardGridRadius = 12;

    /** WorldModel only recomputes the HazardField every N client ticks (unless
     *  the player moved to a new block). 1 = every tick (max freshness, higher
     *  CPU); 4 = ~4.8 Hz (good default). Must be ≥ 1. */
    public static volatile int hazardGridDecimateTicks = 4;

    /** Water depth (blocks) at or above which a water cell is lethal in the
     *  HazardField (the bot would drown before climbing out). Default 2. */
    public static volatile int deepWaterMax = 2;

    /** {@code mc.observe.scene} radius clamp (blocks). Requests larger than
     *  this are truncated with a truncated:true report. Default 32. */
    public static volatile int sceneQueryMaxRadius = 32;

    /** Diagnostic — when on, the elytra flight process validates the
     *  {@link net.magicterra.agent.bot.elytra.ElytraPhysics} simulator
     *  tick-by-tick against the live client (predicted vs observed
     *  {@code deltaMovement}) and logs per-tick error plus a summary on stop.
     *  Used to prove the simulator is tick-exact before the reactive controller
     *  is built on it. Off by default; toggle via
     *  {@code mc.bot.setting{elytraDebug:true}}. */
    public static volatile boolean elytraDebug = false;

    // ===================== persistence (survives a restart) =====================
    // The "重启游戏需要重新配置" pain: every relaunch reset these volatiles to their
    // defaults, so survival toggles (autoSwim, autoRetreat, …) had to be re-applied
    // by hand each time — and a default-off autoSwim drowned the bot on reload before
    // it could be set. Now a successful mc.bot.setting apply writes every scalar (+ the
    // hazard-block Set) to a properties file via reflection, and the file is reloaded
    // at startup. Reflection means new tunables persist automatically with no per-field
    // code. Opt-in via -Dagent.persistConfig=true (set by the dev runClient/runServer
    // launch, NOT the headless validation/GameTest tasks) so the suites stay
    // deterministic on pure defaults. avoidZones (a transient per-goto double[][]) is
    // intentionally NOT persisted.

    private static Path persistPath() {
        return Path.of("config", "agent_driver_bot.properties");
    }

    /** Persistence is opt-in so headless validation / GameTest never read or write
     *  the file (their setting tests must run on pristine defaults). */
    private static boolean persistEnabled() {
        return Boolean.getBoolean("agent.persistConfig")
                && !Boolean.getBoolean("agent.runValidation");
    }

    /** Write every persistable field to disk. Called after a successful
     *  {@code mc.bot.setting} apply. Best-effort: a failure is logged, not thrown. */
    public static synchronized void save() {
        if (!persistEnabled()) return;
        try {
            Properties props = new Properties();
            for (Field f : BotConfig.class.getDeclaredFields()) {
                if (!persistable(f)) continue;
                Object v = f.get(null);
                if (v == null) continue;
                if (v instanceof Set<?> set) {
                    StringBuilder sb = new StringBuilder();
                    for (Object o : set) { if (sb.length() > 0) sb.append(','); sb.append(o); }
                    props.setProperty(f.getName(), sb.toString());
                } else {
                    props.setProperty(f.getName(), String.valueOf(v));
                }
            }
            Path path = persistPath();
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            try (var w = Files.newBufferedWriter(path)) {
                props.store(w, "agent-driver bot settings — auto-saved by mc.bot.setting");
            }
        } catch (Exception e) {
            LOG.warn("[config] save failed: {}", e.toString());
        }
    }

    /** Reload persisted settings at startup, before any tick reads them. Missing
     *  file → defaults stand; a malformed individual key is skipped (a partial/old
     *  file still applies what it can). */
    public static synchronized void load() {
        if (!persistEnabled()) return;
        try {
            Path path = persistPath();
            if (!Files.exists(path)) return;
            Properties props = new Properties();
            try (var r = Files.newBufferedReader(path)) { props.load(r); }
            int n = 0;
            for (Field f : BotConfig.class.getDeclaredFields()) {
                if (!persistable(f)) continue;
                String s = props.getProperty(f.getName());
                if (s == null) continue;
                try { assign(f, s); n++; }
                catch (Exception ex) { LOG.warn("[config] skip {}: {}", f.getName(), ex.toString()); }
            }
            LOG.info("[config] loaded {} persisted bot setting(s) from {}", n, path.toAbsolutePath());
        } catch (Exception e) {
            LOG.warn("[config] load failed: {}", e.toString());
        }
    }

    /** Fields excluded from persistence even though their type is persistable:
     *  pure RUNTIME state that must NOT survive a restart. {@code fleeActive} is
     *  a per-frame flee-context flag (set true by RunAwayProcess.tick, reset each
     *  clientTick) — if saved it would reload {@code true} and wrongly boost every
     *  goto's terrain cost. Keep this in sync with any other transient scalar. */
    private static final Set<String> NON_PERSISTED = Set.of("fleeActive");

    /** A static, non-final field of a scalar type (or the hazard-block Set) — the
     *  set we round-trip. Arrays (avoidZones), runtime-only flags ({@link
     *  #NON_PERSISTED}), and anything else are excluded. */
    private static boolean persistable(Field f) {
        int m = f.getModifiers();
        if (!Modifier.isStatic(m) || Modifier.isFinal(m)) return false;
        if (NON_PERSISTED.contains(f.getName())) return false;
        Class<?> t = f.getType();
        return t == boolean.class || t == int.class || t == long.class
                || t == double.class || t == float.class
                || t == String.class || t == Set.class;
    }

    private static void assign(Field f, String s) throws IllegalAccessException {
        Class<?> t = f.getType();
        if (t == boolean.class) f.setBoolean(null, Boolean.parseBoolean(s.trim()));
        else if (t == int.class) f.setInt(null, Integer.parseInt(s.trim()));
        else if (t == long.class) f.setLong(null, Long.parseLong(s.trim()));
        else if (t == double.class) f.setDouble(null, Double.parseDouble(s.trim()));
        else if (t == float.class) f.setFloat(null, Float.parseFloat(s.trim()));
        else if (t == String.class) f.set(null, s);
        else if (t == Set.class) {
            Set<String> set = new LinkedHashSet<>();
            for (String part : s.split(",")) { part = part.trim(); if (!part.isEmpty()) set.add(part); }
            f.set(null, Set.copyOf(set));
        }
    }
}
