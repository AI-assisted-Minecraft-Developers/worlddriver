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

    /** Max horizontal distance (blocks) a single mine command may DRIFT from the
     *  position where it started. Each SEARCH phase re-scans within searchRadius of
     *  the bot's CURRENT cell, so without this cap the bot chains 16-block hops
     *  toward scattered matches and can walk dozens of blocks — even straight across
     *  open ocean chasing red_sand — stranding a survival bot far from start. The
     *  scan rejects any candidate beyond this radius from the start anchor, bounding
     *  a whole mine command to a fixed bubble. Set <=0 to disable the cap. */
    public static volatile int mineMaxDriftFromStart = 32;

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

    /** Per-tick compute slice (ms) used ONLY while the bot is IDLE waiting for a
     *  path — it has consumed its committed best-effort segment (or has no path yet)
     *  and is standing still until the next search lands. The normal {@link
     *  #pathfinderSliceMs} (~6 ms) protects the frame-rate WHILE WALKING, but at a
     *  far-goal segment boundary that thin slice means a 3 s CPU search drags out to
     *  ~27 s of wall-clock (6 ms of 50 ms per tick) — and the bot is FROZEN that whole
     *  time ("行动→冻住→重算→行动" long-haul stutter). When there's no movement to keep
     *  smooth, a few frame hitches are far cheaper than the wait, so spend much more of
     *  each idle tick on the search to finish it ~5× sooner. Kept responsive (the
     *  client still renders between slices). */
    public static volatile long pathfinderIdleSliceMs = 30;

    /** A* wall-clock cap, ms. Default mirrors {@code PathFinder.DEFAULT_MAX_MS}. */
    public static volatile long pathfinderMaxMs =
            PathFinder.DEFAULT_MAX_MS;

    /** Weighted-A* heuristic multiplier (W in {@code f = g + W·h}). W>1 makes the
     *  search greedier toward the goal (bounded-suboptimal A*). The best-effort
     *  selection still uses the RAW (unweighted) {@code h}, so segment commitment is
     *  unaffected.
     *
     *  DEFAULT 1.0 (optimal A*) — A/B-DISPROVEN as a default for hilly/jungle terrain
     *  (2026-06-06): at W=1.3 the greedy frontier drives the best-effort segment UP a
     *  hill/canopy that is "toward the goal" but a dead end, and the bot PERMANENTLY
     *  STALLS there (live test: 5+ consecutive repaths stuck at the same hilltop cell,
     *  never recovered), whereas W=1.0 stays low, routes around, and reaches the goal
     *  (~171 s). Admissible W=1.0 correctly prices the cost of climbing vs going
     *  around; inflating h breaks that. Kept as an exposed knob
     *  ({@code mc.bot.setting{pathfinder.heuristicWeight}}) because greedy search can
     *  still help in OPEN terrain — opt in per use, don't ship it on. */
    public static volatile double pathfinderHeuristicWeight = 1.0;

    /** Collision-SHAPE-aware solidity/passability (vs the coarse {@code blocksMotion()}
     *  boolean). When true the pathfinder reads each block's actual collision
     *  {@code VoxelShape}: a cell is a valid FLOOR only if its collision top is a full
     *  1×1 face ({@code Block.isFaceFull(shape, UP)} — keeps full blocks/leaves/slabs/
     *  snow, EXCLUDES cocoa pods / fences / partial attachments the player can't truly
     *  stand on), and a cell is PASSABLE if the player's body column doesn't intersect
     *  the collision shape (so a cocoa pod offset to one side, panes, etc. stop being
     *  treated as full-cube walls). Fixes the jungle "cocoa 挡路 / phantom foothold on a
     *  pod → walker can't execute → stuck/oscillation" class of bugs. Off = legacy
     *  {@code blocksMotion()} model. */
    public static volatile boolean collisionAwarePathing = true;

    /** Per-search blockstate memoisation in {@link net.magicterra.agent.bot.ClientWorldView}.
     *  ON = cache getBlockState within a search slice (static-world assumption); the
     *  Walker's per-tick reads always bypass it. Exposed as a knob purely so the
     *  cache's search-throughput contribution can be A/B-measured live (set false to
     *  read straight through). Default true. */
    public static volatile boolean pathfinderCacheEnabled = true;

    /** Obstacle-aware goal-distance heuristic ({@link net.magicterra.agent.bot.pathfinder.CoarseGoalField}).
     *  When ON, each search builds a coarse, symmetric, goal-rooted cost-to-goal
     *  field over the loaded region and uses it (max'd with the Euclidean
     *  estimate) as the A* heuristic, so the fine search routes AROUND a concave
     *  pinch instead of expanding into the wall and committing a backward
     *  best-effort segment (the "走回头路" oscillation). Degrades to the plain
     *  Euclidean heuristic wherever the field has no value, so it can never be
     *  worse than off. Phase-0 premise test: default OFF until A/B-validated. */
    public static volatile boolean pathfinderGoalField = false;

    /** Coarse-grid cell edge in blocks for {@link #pathfinderGoalField} (bigger =
     *  cheaper to build, coarser routing). */
    public static volatile int goalFieldCellSize = 4;

    /** Horizontal half-extent (blocks) of the goal-field box around the search
     *  start — keep within the client render distance so cells map to known
     *  terrain. */
    public static volatile int goalFieldRadius = 64;

    /** Vertical half-extent (blocks) of the goal-field box around the search start. */
    public static volatile int goalFieldVerticalRadius = 32;

    /** Anti-basin-dive heuristic term (cost units per block). For an XZ goal the
     *  estimate is Y-agnostic, so the search treats DESCENDING as free progress
     *  and dives down a slope into a low valley/basin that is a dead-end toward
     *  the goal, then gets boxed in (the deterministic root cause the mc.debug.plan
     *  harness surfaced for the spawn pinch). This adds {@code penalty × max(0,
     *  searchStartY − {@link #pathfinderDepthSlack} − nodeY)} to a node's heuristic
     *  — an ASYMMETRIC bias: descending below where THIS search started costs
     *  extra, climbing is free, so A* prefers a level/high route that can actually
     *  progress. The reference is the per-search start Y (it drifts down on a
     *  legitimate long descent, so a genuine downhill journey isn't over-charged).
     *  Inadmissible by design (like weighted A*); keep modest or it refuses needed
     *  descents and stalls on hilltops. Default 6 — a modest, calculated default:
     *  harness A/B showed it is harmless when the path is already good (still
     *  reaches, 0 backward segments) and biases the route a few blocks HIGHER /
     *  smoother (better for a livestream camera), while plausibly countering the
     *  XZ-goal basin-dive. NOT validated on the actual failure case (it is not
     *  reliably reproducible via the live client — the search only sees loaded
     *  chunks, which vary per session). Set 0 to disable; tune via the
     *  mc.debug.plan chain harness. */
    public static volatile double pathfinderDepthPenalty = 6;

    /** Blocks of descent below the search start that are free before {@link
     *  #pathfinderDepthPenalty} kicks in (so a normal step-down / small dip isn't
     *  charged). Shared with {@link #pathfinderDescendCost}. */
    public static volatile int pathfinderDepthSlack = 4;

    /** REAL g-cost (not heuristic) charged per block for descending IN WATER or by
     *  BREAKING a block, below {@code searchStartY − {@link #pathfinderDepthSlack}}.
     *  Unlike {@link #pathfinderDepthPenalty} (a heuristic bias that only reorders
     *  the search and cannot change which reachable path is cheapest), this adds to
     *  the edge cost, so it actually makes a path that dives down COST MORE than one
     *  that climbs ashore. It fixes the deep-water-bowl "卡上岸" root cause: at a
     *  sheer-walled water pit an XZ (Y-agnostic) goal let A* reach the target column
     *  more cheaply by SwimDown-ing to the bottom and break-tunnelling DOWN into the
     *  ground (end Y 50-53) than by break-climbing the +6 bank — so the bot drilled
     *  underground (or, for a far goal, committed nothing and bobbed). Charging the
     *  watery/breaking descent its true cost flips the balance so the climb-ashore
     *  path wins (verified via mc.debug.plan: goal east-bank end Y 50→71).
     *  <p>TRIPLE-GATED: (1) only for Y-agnostic XZ goals (see {@link Goal#ignoresY()})
     *  — a Y-aware pos/block goal (seabed monument, shipwreck) is NEVER taxed, so deep
     *  ocean diving is unaffected; (2) only a water-involved descent (in-water SwimDown
     *  OR a fall/MLG into water) or a block-breaking descent — a dry STEPPED descent
     *  over solid ground (StepDown/Fall/DiagonalDescend, no break, not water) is a
     *  legitimate downhill walk and pays NOTHING, so normal terrain descent and
     *  `mc.bot.mine` (its own process, not goto) are unaffected; (3) only the part
     *  below the slack threshold. Both water-entry and in-water descent are charged
     *  because at a water bowl the cheapest dive-and-tunnel uses fall-INTO-water rungs
     *  — taxing only already-submerged steps left A* a tax-free back-door. Only
     *  the part of a step that lies below the slack threshold is charged, and only
     *  when going down (climbing is free), so shallow wading across a river isn't
     *  penalised. Set 0 to disable. Default {@value} — tuned via the mc.debug.plan
     *  water-pit A/B so the +6 climb-ashore beats the dive-and-tunnel. Default 40:
     *  the value that flips the real spawn +6-bank water bowl from dive-and-tunnel
     *  (end Y 50) to climb-ashore (end Y 71) in the mc.debug.plan A/B. The usual
     *  over-charge worry (deep dives) does NOT apply because the tax is gated to
     *  Y-agnostic XZ goals only — a seabed-monument/shipwreck dive uses a Y-aware
     *  pos/block goal and pays nothing. Set 0 to disable. */
    public static volatile double pathfinderDescendCost = 40;

    /** Per-water-cell g-cost added to EVERY move that enters a water cell, for
     *  Y-agnostic (XZ) goals only — on top of the base {@code waterDangerPenalty}.
     *  An XZ goal makes swimming at depth read as free progress (each stroke shrinks
     *  the XZ distance for ~one edge.cost), so A* threads long underwater corridors /
     *  dives back into the water it just climbed out of, oscillating against the
     *  executor's climb-out (live round70/71: from a +2 bank A* committed a y58-61
     *  water route the bot bob-stalled / pillared / re-dove forever). A PER-CELL tax
     *  (unlike a one-time entry tax, which a bot ALREADY in the water never pays)
     *  makes a long water route cost ∝ its length, so A* takes an available LAND route
     *  even when starting submerged (verified via mc.debug.plan: from an in-water cave
     *  start it routes UP to the y84 land and stays dry). A genuinely shorter / sole
     *  water crossing is still taken (cost beats the land detour or there is none);
     *  Y-aware pos/block goals (a seabed dive) are exempt exactly like descendTax, and
     *  Goal.Block GameTest water arenas are unaffected. Default 35. Set 0 to disable. */
    public static volatile double pathfinderWaterCellCost = 35;

    /** EXTRA per-cell g-cost charged (on top of {@link #pathfinderWaterCellCost}) when
     *  the entered water cell is SUBMERGED — i.e. it has water directly above it, so a
     *  surface-cruising bot would have to DIVE UNDER to thread it. Surface water cells
     *  (air/non-water overhead, where the bot swims at the top) pay only the base tax,
     *  so an ordinary surface crossing is unchanged. This biases A* to keep a water
     *  route ON THE SURFACE instead of dropping onto the seafloor / a seagrass corridor
     *  it then can't climb out of (live round75: an XZ goal routed the bot along the
     *  seabed through tall seagrass and it churned ~80 s "未能上浮" — the per-cell water
     *  tax alone is uniform with depth, so once submerged there was no incentive to
     *  surface). Same TRIPLE-GATE as {@link #pathfinderWaterCellCost}: Y-agnostic XZ
     *  goals only (a seabed dive uses a Y-aware goal and pays nothing), water cells
     *  only, so dry terrain and Goal.Block GameTest arenas are unaffected. Default 40
     *  ≈ doubles the cost of a submerged cell vs a surface cell. Set 0 to disable. */
    public static volatile double pathfinderSubmergedWaterCost = 40;

    /** TOTAL g-cost of one {@code bridgePlace} edge — placing a block into an air
     *  gap and walking onto it. Aerial bridging is SLOW (sneak-place ~1 block/15
     *  ticks), RISKY (overshoot off the fresh 1-wide block) and consumes inventory,
     *  so it must be a LAST resort, not a casual default: when it is too cheap the
     *  planner happily commits a ~30-block aerial bridge straight over a deep valley
     *  instead of descending and crossing the floor — a route the Walker can barely
     *  execute, so the bot freezes (the "速度陡降 / 被挡" stall on mountain terrain).
     *  A high cost makes A* prefer any ground route (descend a valley, go around) and
     *  bridge only a genuinely unavoidable short gap. Does NOT touch the depth penalty,
     *  so basin-dive protection is unchanged. Old hard-coded value was 30 (walk 10 +
     *  {@link net.magicterra.agent.bot.pathfinder.Move#PLACE_COST 20}). Default {@value}. */
    public static volatile double pathfinderBridgeCost = 80;

    /** Max DRY (no-water) fall the planner will take as a plain {@code Fall} move,
     *  in blocks. Default 3 = Baritone's no-fall-damage cap (current behaviour;
     *  {@code Fall(4)/Fall(5)} are catalogued but inert). Raising it (≤5) lets the
     *  search descend a steep dry slope by taking a small-damage drop (4 blocks ≈
     *  1.5 hearts, 5 ≈ 2) instead of building a dirt "天梯" staircase with
     *  {@code BridgePlace} — the smooth-jungle-descent lever. A higher fall is
     *  cheaper than a place-bridge (Fall(5)=35 vs BridgePlace≈80), so once enabled
     *  A* prefers the natural drop. Survival-sensitive (the bot takes the damage),
     *  so it ships OFF (3) and is opt-in via mc.bot.setting. Capped at 5 (≈2 hearts);
     *  taller no-bucket descents stay {@link net.magicterra.agent.bot.pathfinder.moves.FallIntoWater}
     *  (into water) or place-bridges.
     *  <p>Default raised 3→4 (2026-06-09): a 4-block fall is the MINIMUM non-zero
     *  fall (0.5♥, what a vanilla player takes constantly) and an A/B over a vine
     *  jungle canopy HALVED place-bridges (23→11) and cleared a descent the cap-3
     *  bot wedged on for 90 s — the dirt "天梯" was the long-haul start wedge. fall5
     *  (1♥) stays catalogued-but-inert at 4 (raise to 5 to enable). autoEat/regen
     *  absorb the small drip; survival can lower it back to 3 via mc.bot.setting. */
    public static volatile int pathfinderMaxDryFall = 4;

    /** Max collision-box height (blocks) of a floor-resting obstacle the body
     *  STEPS or SWIMS over, so the pathfinder treats it as passable rather than a
     *  wall. Fixes "被浮萍/荷叶挡住": a lily pad (collision ≈0.094 high) — and other
     *  thin water-surface plants — sits as a low slab at the bottom of the surface
     *  (head) cell over water; the collision-aware {@link
     *  net.magicterra.agent.bot.ClientWorldView#isPassable} otherwise intersects it
     *  with the full-height player column and walls off the swimmable water cell
     *  below, so the bot can't path through lily-pad-covered water. A block is
     *  passable when its collision shape STARTS at the cell floor (minY≈0) AND rises
     *  no higher than this — vanilla auto-step (0.6) clears it on land and a
     *  swimming body slides under it. Kept conservative (below a 0.5 slab / 0.875
     *  soul sand) so genuine half-blocks still block; raise toward 0.6 to also walk
     *  through slab cells. 0 = off (legacy strict column intersection). */
    public static volatile double pathfinderThinObstacleHeight = 0.2;

    /** Segmented planning to the loaded-chunk frontier. The client only knows
     *  chunks within render distance, so a far goal lies beyond loaded space; the
     *  search can't path into unloaded chunks (no floor) and stops at the boundary.
     *  When ON, a budget-/boundary-truncated search that can't reach the goal
     *  commits toward the goal-WARD edge of known terrain (the reachable node
     *  nearest the goal that borders an unloaded chunk) instead of the conservative
     *  near-start best-effort — so the bot walks to the frontier, new chunks load,
     *  and the next search extends the plan. This is what makes a long journey
     *  chain smoothly across the horizon instead of committing a backward segment
     *  and oscillating. Falls back to the normal best-effort when the goal-ward
     *  frontier isn't reachable (a real wall in loaded terrain) so it composes with
     *  go-around behaviour. Pairs with the Walker re-searching fresh on arrival at a
     *  frontier (a stale eager continuation computed before arrival can't see the
     *  newly-loaded chunks).
     *  <p>EARLY-STOP (the lever that makes this worthwhile): the search STOPS the
     *  instant it pops the first goal-ward frontier node (A* pops by f, so that node
     *  is the optimal path to the loaded-chunk edge) rather than burning the whole
     *  budget grinding toward an out-of-render goal. This is the literal "plan only
     *  as far as loaded chunks reach" — a far goal that used to expand the full
     *  node/ms budget (~200k nodes / the maxMs timeout) now returns in a few thousand
     *  nodes / tens of ms, committing the same goal-ward segment. Excluded for
     *  in-water starts (the bestAshore climb-out takes priority there). Default ON:
     *  with early-stop it is a pure latency win on far goals and a no-op on near ones
     *  (goal reached before any frontier node is popped). */
    public static volatile boolean pathfinderFrontierCommit = true;

    /** Receding-horizon early-stop distance, in BLOCKS of goal-ward progress (0 =
     *  OFF). Generalises {@link #pathfinderFrontierCommit}: that one only truncates a
     *  far search at an UNLOADED-chunk edge, so in fully-loaded terrain (e.g. the
     *  spawn mountains) a far XZ goal grinds the entire {@link #pathfinderMaxNodes}
     *  budget (~60k nodes / ~3.6 s CPU) only to commit a tiny ~5-block best-effort
     *  segment — then re-searches at the next boundary, so the bot walks ~5 blocks and
     *  FREEZES ~seconds, over and over ("行动→冻住→重算→冻住"). With a horizon set, the
     *  search STOPS the instant A* pops a node that has reduced the goal heuristic by
     *  ≥ this many blocks (A* pops by f, so that node is ~optimal to the horizon),
     *  committing a long forward segment cheaply (a few hundred–thousand nodes) and
     *  leaving plenty of walk-time to hide the next search → no freeze. Self-disables
     *  when the real goal is within the horizon (then h can't drop that far → the
     *  search runs to the actual goal). Only fires on genuine goal-ward progress, so a
     *  pinch/wall (no forward node) falls through to the unchanged best-effort backoff
     *  — same go-around/vertical-escape behaviour. Excluded for in-water starts
     *  (bestAshore climb-out wins). Converted to cost units at ~10/block (matching
     *  {@code MIN_FRONTIER_GAIN}=50≈5 blocks). */
    public static volatile int pathfinderHorizonBlocks = 48;

    /** Soft node-budget early-commit (0 = OFF). The {@link #pathfinderHorizonBlocks}
     *  early-stop only fires when the search can make {@code horizon} blocks of forward
     *  progress; when the bot is BOXED at an obstacle (cliff/wall/canopy) no such node
     *  appears, so the search grinds the entire hard {@link #pathfinderMaxNodes} budget
     *  (~60k nodes / ~3.4 s CPU) before committing a short best-effort segment — the
     *  bot still FREEZES ~seconds at every obstacle. This caps that: once a search has
     *  expanded this many nodes AND already has a committable best-effort segment (a
     *  bestSoFar node past MIN_DIST_PATH, an ashore climb-out, or a vertical-escape
     *  climb), it STOPS and commits instead of grinding to the hard cap — trading a
     *  slightly shorter segment for a far shorter freeze (~0.3 s vs ~3.4 s of compute).
     *  The hard {@link #pathfinderMaxNodes} still applies when NO segment exists yet
     *  (a deep pinch still hunting its first viable move / vertical escape), so hard
     *  reachability is unchanged. Pairs with horizon: open terrain commits fast via
     *  horizon, obstacles commit fast via this. */
    public static volatile int pathfinderSoftCommitNodes = 6000;

    /** PROGRESSIVE quick-start stub (0 = OFF). While a full re-plan is still
     *  time-slicing in the background (a hard obstacle search can take seconds),
     *  the bot has no path and stands frozen — the visible "inter-segment gap"
     *  stall. When that gap opens, the Walker spends this many nodes on a tiny
     *  SYNCHRONOUS best-effort search and starts walking the resulting short
     *  segment toward the goal immediately; the big search's result replaces
     *  the stub when it lands (adoptPath fast-forwards past the overlap, so no
     *  walking backward). Sized to finish within roughly one frame — the frame
     *  is frozen anyway while the bot has nothing to walk. */
    public static volatile int pathfinderQuickNodes = 600;

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

    /** Stream-grade camera guarantee (AIRI). When on, EVERY bot camera write is
     *  rate-limited at a single chokepoint ({@code LookController.apply} at the end
     *  of the client tick) so NO actuator — Walker, swim, pillar look-down, the
     *  build/bunker/farm processes, the reflexes — can snap the view; the worst a
     *  snap-style write does is begin a smooth multi-tick pan. Unlike
     *  {@link #smoothLook} (which only shapes the Walker/lookAt TARGET and is opt-in),
     *  this is a global post-write clamp and is ON by default. Functional exact aims
     *  (camera-raycast mine/attack, a leap's takeoff heading) bypass it for one tick
     *  via {@code LookController.requestSnap()}. Inert headless (no client tick). */
    public static volatile boolean cameraSlew = true;

    /** Max yaw degrees/tick for the {@link #cameraSlew} clamp. 30°/tick ≈ 600°/s →
     *  a 180° turn takes ~6 ticks (~0.3 s): smooth on stream yet responsive. */
    public static volatile float cameraSlewDegPerTick = 30f;

    /** Max pitch degrees/tick for the {@link #cameraSlew} clamp. Pitch sweeps are
     *  smaller (look-down to place/dig ≈ 90°), so a slightly gentler rate reads well. */
    public static volatile float cameraPitchSlewDegPerTick = 20f;

    /** Extra block ids the pathfinder treats as hazardous (in addition to the
     *  built-in HAZARD_BLOCKS set in BotApiImpl). Mutable via
     *  {@code mc.bot.setting{blocksToAvoid:[id,...]}}. Read on every WorldView
     *  query so changes apply immediately. Stored as an immutable Set; writers
     *  replace the whole reference. */
    public static volatile Set<String> extraHazardBlocks = Set.of();

    /** Block ids the bot is ALLOWED to place as build/support blocks (pillar, bridge,
     *  parkour-place footing). When EMPTY (default) the {@link #isUsableBuildBlock}
     *  heuristic applies: any non-falling FULL collision cube. When non-empty it
     *  OVERRIDES the heuristic — only these exact ids may be placed, so the Agent can
     *  pin building to known-good blocks via {@code mc.bot.setting{buildBlockWhitelist:[id,...]}}.
     *  Whole-list replace; pass [] to clear. */
    public static volatile Set<String> buildBlockWhitelist = Set.of();

    /** Whether {@code block} may be used as a PLACED build block (pillar/bridge/parkour
     *  footing). Default heuristic: a non-{@link net.minecraft.world.level.block.FallingBlock}
     *  whose default state is a FULL collision cube — this rejects thin/partial blocks
     *  (bamboo, slabs, fences, saplings) the bot would otherwise grab from its inventory
     *  and "搭路卡死" on because they form no walkable surface. When
     *  {@link #buildBlockWhitelist} is non-empty it overrides the heuristic: only its ids
     *  pass (still requiring a non-falling, motion-blocking block for safety). Dist-neutral:
     *  callable from both client and dedicated-server WorldViews. */
    public static boolean isUsableBuildBlock(net.minecraft.world.level.block.Block block) {
        if (block instanceof net.minecraft.world.level.block.FallingBlock) return false;
        net.minecraft.world.level.block.state.BlockState st = block.defaultBlockState();
        if (!st.blocksMotion()) return false;
        Set<String> wl = buildBlockWhitelist;
        if (!wl.isEmpty()) {
            net.minecraft.resources.ResourceLocation id =
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
            return id != null && wl.contains(id.toString());
        }
        // Accept any non-falling, motion-blocking block with a STURDY top face the bot
        // can place against and STAND on — not only geometric full cubes. The old
        // isCollisionShapeFullBlock rejected mud / soul_sand / soul_soil (collision box
        // 14/16 tall) though they are perfectly standable, leaving a bot carrying ONLY
        // those (live round69: 17 mud + 9 sand + 37 gravel, all rejected — sand/gravel
        // FallingBlocks above, mud here) with NO usable foothold, so the water +2
        // climb-out place never engaged and it hard-deadlocked at the bank. isFaceSturdy
        // (UP) still rejects bottom-slabs / fences / carpets / non-standable shapes.
        return st.isFaceSturdy(
                net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO,
                net.minecraft.core.Direction.UP);
    }

    /** Event types muted from the live PUSH channel via
     *  {@code mc.bot.setting{mutedEvents:[type,...]}}. By default EVERY driver event
     *  pushes to the MCP/WS channel; listing a type here suppresses ONLY its push — the
     *  event is still recorded and retrievable via mc.wait.event / replay. Empty = push
     *  everything. Whole-list replace; pass [] to clear. Persisted across relaunch. */
    public static volatile Set<String> mutedEvents = Set.of();

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
     *  short debounce. OFF by default — 挖三填一 stays predominantly an Agent-invoked
     *  action (mc.bot.bunker); enable only for fully autonomous survival runs. When it
     *  DOES auto-fire it pushes a warning-level {@code duskSecure.triggered} event so an
     *  unattended dig is never silent. Below the USER band, so any active task suppresses it. */
    public static volatile boolean autoSecureAtDusk = false;

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

    /** Flee-escape break: when a {@code RunAwayProcess} is actively fleeing, the
     *  pathfinder may mine LEAVES that box the bot in — even when the general
     *  {@link #allowBreak} is off. Sibling of {@link #allowSwimEscapeBreak} (water)
     *  for the canopy case: a naked bot sniped inside a tree's leaves used to have
     *  no flee path (leaves collide, allowBreak off → A* "no path") and died in
     *  place. Leaves are hardness-0.2 (near-instant), gated to an active flee +
     *  leaves only, so normal demo-safe movement still never breaks anything. Read
     *  by WorldView.breakCost when the search is a flee. Set false to forbid it. */
    public static volatile boolean allowFleeBreak = true;

    /** Max height (blocks) of a tall bank the pathfinder will break-CLIMB out of
     *  deep water with the {@link net.magicterra.agent.bot.pathfinder.moves.SwimBankClimbBreak}
     *  staircase. The horizontal escape twins ({@code SwimAshoreBreak}) only stay
     *  "in water-escape context" for ~2 vertical steps (water leaves the ring
     *  below the feet), so a bank taller than +2 rising from deep water was
     *  unreachable — A* returned "no path" to any elevated far shore. This move
     *  keeps the break-climb in context as long as water lies straight below
     *  within this many blocks (through the continuous bank face), letting the
     *  bot carve a staircase up a tall river/ocean cliff onto an elevated plateau.
     *  Gated on the same {@link #allowSwimEscapeBreak}; 0 disables it. Bounded so
     *  A* can't carve an arbitrarily tall shaft. Read by SwimBankClimbBreak.eval. */
    public static volatile int swimBankClimbMaxHeight = 12;

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
     *  treats lava as near-impassable rather than a mild nudge.
     *  <p>Default raised 80 → 300 (live A/B 2026-06-09, mountains lava-falls
     *  terrain): at 80 a ~680-block journey skimmed lava-adjacent cells and the
     *  body's physical drift brushed INTO lava ≥6 times across three lava arms
     *  (survivable only with fire resistance — a naked survival bot dies); the
     *  reverse run at 300 crossed the same arms with ZERO lava contacts. 300 ≈
     *  a 30-block detour per lava neighbour, which the journey absorbed without
     *  losing reachability (still arrived, ~same pace). */
    public static volatile double lavaDangerPenalty = 300;

    /** Cost added per <em>contact-damage</em> block (cactus, sweet-berry bush,
     *  wither rose, magma block, powder snow) adjacent to a candidate stand
     *  position. Small — these only graze you on overlap, not from the next cell
     *  — so it just discourages hugging them when an equal route exists. */
    public static volatile double contactDangerPenalty = 12;

    /** Cost added when a candidate stand position sits at the lip of a drop
     *  deeper than the bot can survive (a lethal cliff / void edge), when
     *  {@link #avoidDanger} is on. The scan only counts a drop as dangerous when
     *  it exceeds {@code survivableFall(health)} (see ClientWorldView) — a
     *  step-down the bot would walk away from unharmed is never penalised
     *  (lethal-only refinement, 2026-06-06), so harmless descents stay cheap.
     *  <p><b>Default 15 (on).</b> NOTE (validated 2026-06-06): this penalty is
     *  load-bearing — it keeps the planner on the traversable ridge instead of
     *  committing a best-effort segment that DIVES into a deep ravine "toward
     *  the goal". With it at 0 the bot fell ~29 blocks into a pit at the spawn
     *  pinch and then oscillated forever between the high lip and the pit floor
     *  (climb-out → re-dive), never reaching the goal. {@link HazardField} +
     *  {@code lethalEdgeBrake} guard against <em>walking off</em> a lethal edge,
     *  but they do NOT stop the SEARCH from routing a staircase/fall down into
     *  an unescapable concave pit — that is exactly what this soft cost prevents.
     *  Applied once per cell regardless of how many sides are open, so it nudges
     *  toward an equal-length interior route without blocking a narrow bridge
     *  that is the only way through. Set 0 only for flat/open worlds with no
     *  ravines. */
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

    /** Cost for FLOWING water (a current), on top of {@link #waterDangerPenalty}.
     *  Vanilla water pushes the body ~0.014/tick per flow unit, so crossing a
     *  current drifts the bot off the planned line and fighting it upstream is
     *  slow — neither was modelled (flowing water was costed like still water).
     *  Applied two ways, both scaled by this and the local flow magnitude:
     *  (a) a flat per-cell drift penalty in {@code dangerCost} so A* minimises time
     *  in a current (prefers a bridge / the narrowest crossing / still water);
     *  (b) a directional upstream penalty in {@code directionalCost} so a route that
     *  heads INTO the flow costs more than one going across or with it. Additive and
     *  ≥0 (admissible): a sole crossing is still taken. Set 0 to disable. */
    public static volatile double waterFlowPenalty = 18;

    /** Cost for STANDING ON a leaf block (canopy-walking), when {@link #avoidDanger}
     *  is on. Leaves block motion, so A* treats the canopy as a walkable floor and
     *  happily routes the bot ACROSS the bumpy tree-tops — where the irregular
     *  block-by-block surface snags the hitbox and the bot wedges (the wooded-
     *  mountain stall). This biases the planner onto the ground / around the tree,
     *  or to break straight through, instead of tightrope-walking the canopy.
     *  Additive (≥0, admissible), not a ban: a route with no alternative still walks
     *  the leaves, just at a cost. Set 0 to disable. */
    public static volatile double leafSnagPenalty = 20;


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

    /** Walker sneak-brake guard: while walking, if a LETHAL drop (fall deeper than
     *  the bot can survive at its current HP) is one step ahead in the heading, hold
     *  sneak so vanilla's ledge-guard stops the body at the block edge instead of
     *  letting the controller drift off the cliff. Fixes DEATH #8 (RetreatChain/
     *  RunAwayProcess flee path ran along a lip and the controller overshot off a
     *  23-block drop). The planner can't prevent it: PathFinder caps planned falls at
     *  survivableFall, so a lethal fall is pure controller drift, never a planned
     *  move — hence lethal-only here never blocks a legitimate planned step-down. */
    public static volatile boolean lethalEdgeBrake = true;

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

    /** Master gate for path-debug capture. When false the recorder early-returns and
     *  the chart tool renders whatever (empty) session exists. Default off — debug only. */
    public static volatile boolean pathDebug = false;
    /** Cap on stored A* candidate nodes per search (reservoir-downsampled above this). */
    public static volatile int pathDebugMaxNodes = 4000;
    /** Cap on stored per-tick trajectory samples (ring buffer; oldest dropped). */
    public static volatile int pathDebugMaxSamples = 6000;
    /** When true, auto-write a chart on every goto terminal outcome (success and failure). */
    public static volatile boolean pathChartAutoDump = false;

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
