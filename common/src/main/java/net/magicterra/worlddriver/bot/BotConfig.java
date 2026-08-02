package net.magicterra.worlddriver.bot;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import net.magicterra.worlddriver.bot.pathfinder.PathFinder;
import static net.magicterra.worlddriver.WorldDriverCommon.LOG;

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

    /** Consecutive completed A* searches with NO goal-distance improvement AND no bot
     *  displacement (>2 blocks) before the Walker fails the journey as unreachable
     *  (gap #49-③). The tick budget above bounds the same loop, but it counts TICKS
     *  while each churn cycle burns a full search (live: 129 searches / ~36 s of A* CPU
     *  inside the 62 s wait; an arena with big slices stretched the same loop past
     *  9 minutes) — this counts the searches themselves, ends the journey in seconds,
     *  and reports a reason the agent can tell apart from a transient stall ("no route
     *  progress" vs "no progress for N ticks"). Real journeys reset the counter every
     *  time the bot moves >2 blocks or the best goal distance improves. Each futile
     *  search also arms a short exponential repath backoff so the wait itself stops
     *  burning CPU. 0 disables both. */
    public static volatile int walkerFutileSearchCap = 5;

    /** Stride floor-guard (gap #53, the 2026-07-12 survival death; same family as #51):
     *  while GROUNDED and dry, if the cell one stride ahead along the drive heading has no
     *  floor within {@link #pathfinderMaxDryFall}+1 below — a drop the planner can never
     *  have routed (Fall.valid caps at maxDryFall), so the exposure is always UNPLANNED —
     *  hold vanilla sneak (its maybeBackOffFromEdge pins the body at the edge) and, when a
     *  placeable is in inventory and allowPlace is on, plug the well mouth so the crossing
     *  becomes real (backfill-as-you-go, the Baritone MovementPillar behaviour). The live
     *  death: stairUpBreak dug two hollow columns and routed an UP node across the first
     *  one's open mouth; the #36 brake only checks the drive TARGET's dY (+1, up), never
     *  the real drop under the stride — the body fell 10 blocks and died. Planned descents
     *  (waypoint below foot in the stride column) and parkour launches are exempt. */
    public static volatile boolean walkerStrideFloorGuard = true;

    /** Floor-gate the UNAIMED recovery hops (stuck-wiggle jump, unstuck displacement-burst
     *  jump): skip the jump when a LETHAL drop column sits within hop range (Chebyshev ≤2)
     *  of the foot — {@code WalkerGeometry.lethalDropWithinHopRange}. Those hops launch a
     *  ballistic arc along whatever the current (often mid-slew) heading is; on 1-wide
     *  elevated footing that arc clears the deck and the stride floor-guard cannot help
     *  (it only sees GROUNDED velocity — the jump rewrites the trajectory after launch).
     *  Bridge-battery t0 2026-07-20: every shed (breach@t=81/609) was a wiggle-window
     *  sprint-jump from a floored cell one stride inside the rim. AIMED jumps (stepUp,
     *  parkour, riser breakers) are untouched — hurdle-on-a-bridge legitimately jumps.
     *  Suppression degrades to a grounded stall → futile cap → honest repath/FAILED. */
    public static volatile boolean walkerRecoveryHopFloorGate = true;

    /** Discard a FROM-END best-effort continuation that makes no goal progress past the
     *  committed end (its best node's estimate doesn't beat commitEnd's): with the goal
     *  sealed, that continuation is the escape-farthest fallback walking BACKWARD from
     *  commitEnd, and adopting it U-turns the bot into a forward/backward ping-pong at
     *  the segment start (bridge stop-family livelock, t0 2026-07-20: maxX 3.9 of a
     *  reachable 11). Discarding degrades it to the empty-continuation path, which ends
     *  the journey cleanly at the farthest reachable point. Genuine detours survive
     *  (some node beats commitEnd); water is exempt (anti-spin owns afloat churn).
     *  <p>Default OFF pending a replay A/B: the clean give-up preempts the churn-escape /
     *  planner-escalation machinery that BOXED pockets rely on to physically get out
     *  (t0 2026-07-20: boxedChurnEscalate ended "frontier-giveup" inside its pocket with
     *  zero escapes). The bridge battery's sealed-goal scenes opt in per-scene. */
    public static volatile boolean walkerFromEndNoProgressDiscard = false;

    /** Directional best-effort tail consumption: the tail-overshoot resync used a raw
     *  distance gate (cur2 > overshoot), which cannot tell a tail the bot BLEW PAST from
     *  a tail still FAR AHEAD — and string-pulled best-effort segments routinely end in
     *  a >10-block final leg (the land quick-start stub is literally [start, far-tail]),
     *  so segments self-consumed on their first tick and journeys ended at their start
     *  (bridge stop-family livelock, t0 2026-07-20). When ON, overshoot also requires
     *  the foot to be beyond the tail along the incoming leg's direction (while step
     *  progress is healthy; a stalled approach falls back to the distance consume).
     *  <p>Default OFF pending a replay A/B: the r14 t0 net showed the distance-consume is
     *  LOAD-BEARING in coupled machinery — entityLeash's hold rides on segment
     *  truncation, boxedChurn's escapes feed on the consume/repath cycle, descentYaw
     *  stalled — flipping this needs its own corpus campaign, not a drive-by. The
     *  bridge battery's scenes opt in per-scene (their quick-start stub is the
     *  canonical far-ahead-tail victim). */
    public static volatile boolean walkerTailConsumeDirectional = false;

    /** Corner-clearance repulsion in the walk drive: pure-pursuit cuts corners by design,
     *  and the 0.6-wide body then grazes solid corners the carrot line passes within
     *  half-width of — the flat wall-corner wedge (§39's corner-cut variant; bridge
     *  battery bypass trio wedged on a barrier corner at z offset 0.91 with recovery
     *  hops floor-gated). Blends a small capped push away from any solid body-height
     *  cell whose closest face point is within 0.45 of the body centre — PREVENTING the
     *  graze instead of escaping the wedge. Self-limiting: cell-centred walking beside a
     *  wall sits at ≥0.5 (no push) and corridor pushes cancel. */
    public static volatile boolean walkerCornerClearance = true;

    /** Yaw delta below this is not written each tick — reduces jitter when already aligned. */
    public static volatile float walkerYawHysteresisDeg = 5f;

    /** Decouple camera from movement on a dry descent: aim the CAMERA at a stable far-ahead
     *  path heading (kills the 下山转圈 yaw-wind) while the body keeps driving the immediate node.
     *  See {@code Walker.DESCENT_CAM_FAR_DIST}. Toggle for A/B. */
    public static volatile boolean descentCameraDecouple = true;

    /** Extend {@link #descentCameraDecouple} to ALL dry launches (parkourDescend / parkour / fall):
     *  the camera SLEWS smoothly through the turn instead of snapping ~180° to the landing node
     *  (the 下落/起跳转圈). Safe because main-block leaps drive via commandMove (camera-decoupled),
     *  so the slewing camera never misses the landing — parkourPlace (commandForward) keeps its snap.
     *  Ground-truth replay A/B on the RENDERED camera (LookController): render-snaps≥30° 13→0,
     *  ≥90° 7→0; both runs reach the goal + all parkour/fall GameTest arenas pass. */
    public static volatile boolean descentDecoupleLaunches = true;

    /** Sustain a vine climb on a FREE-HANGING (wall-less) vine — one that hangs from leaf
     *  canopy above with NO solid horizontal neighbour at climb height (live -711,67: an oak-leaf
     *  draped vine over a 1-deep water pocket). Vanilla vine ASCENT only forces {@code vy=+0.2}
     *  while {@code (horizontalCollision || jumping) && onClimbable} (LivingEntity.travel); on a
     *  WALL-BACKED vine the forward press into the wall keeps {@code horizontalCollision} live AND
     *  pins the body in the 1-wide column. On a wall-less vine there is no wall to ram, so the
     *  forward press (aimed at the path-ahead node) instead WALKS the body horizontally OUT of the
     *  column — it rises ~0.2, leaves {@code onClimbable}, gravity resumes, and it DETACHES into the
     *  pocket below (live: ~0.6 rise → fall → ~122 s / totStuck 2400+ bob-churn in the pocket). With
     *  this ON, a wall-less ascent instead JUMPS continuously (every tick → {@code jumping} sustains
     *  the +0.2 climb) and CENTER-SEEKS the vine column (aim/press toward the cell centre so any
     *  residual horizontal drive pushes INTO the column, re-centring, never ejecting) until it tops
     *  out level with the path-ahead exit, then steps off. Wall-BACKED vines keep the existing
     *  {@code vineWallYaw} wall-press path untouched (so the vineClingFidelityProbe is unaffected).
     *  Default ON; toggle for the deterministic vine-over-water A/B (mc.bot.setting). */
    public static volatile boolean walkerVineFreeHangClimb = true;

    /** Grab a FREE-HANGING vine at the parkour LANDING apex — the "land-on-vine handoff". A
     *  {@code parkourAscend} that LANDS the bot ON a climbable vine still carries {@code parkourEdge=true}
     *  at the apex, so the plain {@code !parkourEdge} gate on the vine handler leaves NOTHING driving the
     *  cling at the one instant it matters: the body touches the vine (foot=vine, climbable) but, with no
     *  handler engaged, gravity immediately pulls it PAST the vine into the 1-deep water pocket BELOW the
     *  curtain (live -711,67: parkourAscend2 onto the vine y63 UNDERSHOOTS to the pocket floor y60-61, where
     *  {@code foot=water} is not climbable so {@link #walkerVineFreeHangClimb} can NEVER re-engage → the bot
     *  must slowly swim back UP to the vine before the climb fix kicks in → ~40 s of pocket churn). With this
     *  ON, the vine handler is permitted on a parkour edge the moment the bot has LANDED on the climbable
     *  column — foot=vine, airborne (!onGround), and arrested horizontally over the landing node (XZ within
     *  {@code OVERSHOOT_RESYNC_SQ}) — so the grab + {@link #walkerVineFreeHangClimb} sustain fire on the
     *  SAME tick the foot first becomes the vine, before gravity pulls it past. It CANNOT fire mid-leap (over
     *  a parkour GAP the foot is air, not climbable). Strictly inert when the bot has NOT landed on a vine
     *  (identical to the old {@code !parkourEdge} gate), so non-parkour vine traversal + wall-backed vines are
     *  unaffected. Default ON; toggle for the deterministic vine-LANDING A/B (mc.bot.setting). */
    public static volatile boolean walkerVineLandGrab = true;

    /** Release the vine CLING when the immediate committed node is NOT a climb — a "descend off the
     *  hanging curtain" gate. The vine handler's {@code climbUp} is computed from the {@code step+2}
     *  look-ahead node ({@code ahead.y >= foot.y}); when the path skims a bank/inlet at one Y while a
     *  vine curtain HANGS over that bank, {@code ahead.y} sits exactly at the buoyant body's bob floor,
     *  so {@code climbUp} OSCILLATES with the y-bob (foot.y 64↔65, ahead.y 64): the bot jumps UP at the
     *  low tick, slides DOWN at the high tick, pinned ON the vine with ZERO XZ progress toward the
     *  actual node — the live -672,64,311 inlet bob (≈200 ticks / 10 s; a {@code stepDown} node just
     *  WEST of a z311 vine curtain over a shallow water inlet, the body bobbing y64↔65 in the vine while
     *  the committed node is the level/down step off the bank). With this ON, the cling forces
     *  {@code climbUp=false} whenever the IMMEDIATE node {@code wp} is at/below the foot ({@code wp.y <=
     *  foot.y}), so the body drops off the vine onto the bank / into the inlet and the normal
     *  walk/stepDown resumes (the bank below the bob is solid → it grounds → the {@code !onGround} gate
     *  ends the cling). SCOPED to a non-climb intent: a genuine vine ASCENT always has the next node
     *  ABOVE the foot ({@code wp.y > foot.y}) — the {@link #walkerVineFreeHangClimb} free-hang curtain
     *  climb (-711) and the {@code vineOverWaterClimbArena} climb-out both ascend, so {@code wp.y >
     *  foot.y} there and the cling is untouched. Strictly inert when OFF, and even ON it only fires when
     *  the body is on a vine with a non-ascending immediate node. Default ON; flip OFF via
     *  {@code mc.bot.setting} for the inlet vine-bob A/B. */
    public static volatile boolean walkerVineDescentDrop = true;

    /** Break back a crafting table {@code mc.bot.craft} placed itself, once the craft ends
     *  (gap #276). Without this the table is abandoned where it stood: 4 planks burned at
     *  every craft site, and the world littered with tables. Only ever breaks a table THIS
     *  craft placed — a table found already standing (village, player base) is borrowed and
     *  left exactly as it was. Reclaim is best-effort: if it can't finish, the craft's own
     *  result stands unchanged. Default ON; turn OFF to leave placed tables as landmarks. */
    public static volatile boolean craftReclaimTable = true;

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

    /** Post-kill drop sweep (live 2026-07-21 01:32): melee kites away from the
     *  kill spot, so a "successful" hunt left its meat rotting blocks behind the
     *  bot — six kills banked one porkchop. When a KILL/ENGAGE combat ends with
     *  the area clear, walk over the {@code ItemEntity} drops within 8 blocks of
     *  the last kill position (vanilla pickup is automatic), hard-capped at 100
     *  ticks so an unreachable drop can't wedge the suspended user task. DEFEND
     *  skips the sweep — it exists to resume the task fast, not to loot. */
    public static volatile boolean combatCollectDrops = true;

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

    /** Contact-damage escape reflex (death #14, live 2026-07-20): a goto across
     *  desert hugged a cactus cluster and contact damage ground a healthy bot
     *  11→0 HP in ~20 s — the block re-damages every ~10 ticks, faster than any
     *  LLM/planner turn, and the entity-attribution reflexes (hurt-entry
     *  retreat, gap#55/#65) never fire for BLOCK damage sources. When the last
     *  damage is a fresh step-away-able contact type (cactus / sweet berry /
     *  in-fire / magma floor), the {@code ContactDamageEscape} client-tick
     *  reflex faces away from the touching hazard block and walks out of
     *  contact. Default ON. Read only by the reflex — never the planner. */
    public static volatile boolean contactDamageEscape = true;
    /** Walk away from an adjacent FLOWING lava front (devil-bench deaths #27/#29/#30). */
    public static volatile boolean lavaProximityEscape = true;

    /** gap#70 (live death #18): an IDLE bot (no movement process) that sinks in
     *  deep water gets ZERO self-rescue — {@link #autoSwim}'s lift/beach steer is
     *  deliberately idle-gated OFF ({@code AutoSwim.tick}'s own doc: "with NO
     *  command … force-surfacing … is exactly what must NOT happen"), and
     *  Walker's {@code drowningEscape} only runs inside an active Walker, which an
     *  idle bot has none of. A bot tp'd into a ~29-block-deep river with no task
     *  drowned air 16→0 in ~40 s with both flags on and zero action taken.
     *  <p><b>Controller ruling (written into code, not just this comment — see
     *  {@code AutoSwim.drowningSentinel}/{@code AutoSwim.tick}):</b> the "driver
     *  idle must be passive" contract was always about forbidding UNCOMMANDED
     *  HORIZONTAL movement/beaching, never about letting the bot drown. P1
     *  already established that the reflex layer (lethal-threat response) stays
     *  active while idle (hurt-entry retreat reacts to being hit at rest); a pure
     *  VERTICAL float-to-surface is the same kind of survival reflex, not
     *  "autonomous movement" — no forward key, no turning, no beach-steer, ever.
     *  Independent of {@link #autoSwim} on purpose: this is a bare survival
     *  reflex (AntiSuffocate's pattern), not the autoSwim movement/beach feature,
     *  so it can be toggled without touching autoSwim's active-process
     *  lift/beach semantics. Default ON. */
    public static volatile boolean autoFloatWhenDrowning = true;

    /** Air-supply threshold (ticks; vanilla max 300, drown damage starts at 0)
     *  at/below which {@link #autoFloatWhenDrowning} takes over — 240 ≈ 12s of air
     *  still in reserve when the float starts (raised from 100, final-review M2):
     *  the death-#18 reproducer was a 29-block-deep river, where a 100-tick (5s)
     *  reserve left only ~15s total survival budget against a 15-29s ascent — marginal
     *  to insufficient at the incident's own depth. The float is pure-vertical and
     *  harmless (matrix case (b) still protects "merely diving with plenty of air"),
     *  so triggering earlier costs nothing and buys real margin at depth. gap#70. */
    public static volatile int drownFloatAirThreshold = 240;

    /** ACTIVE-process drowning-escape reflex chain (gap#76, live death #25):
     *  {@code DrownEscapeChain} (priority 500) PREEMPTS the movement channel when
     *  the bot is underwater with {@code air <= drownEscapeAirThreshold} and floats
     *  it straight up (pure vertical — hold jump, zero horizontal/turn; breaks a
     *  solid lid overhead when {@link #allowBreak} allows). Complements gap#70's
     *  idle-only {@link #autoFloatWhenDrowning}: that one deliberately never runs
     *  under an active process, and the in-process {@code AutoSwim.tick} backstop
     *  shares the input channel with the process, whose per-tick dig/steer drive
     *  suppresses it — live death #25 had mine still breaking blocks on the tick
     *  the bot drowned. Default ON: it saves the bot's life and touches nothing
     *  but its own vertical motion. */
    public static volatile boolean autoDrownEscape = true;

    /** Air-supply threshold (ticks; vanilla max 300) at/below which
     *  {@link #autoDrownEscape} preempts an ACTIVE process. Deliberately far
     *  BELOW {@link #drownFloatAirThreshold} (240): an active goto/mine crossing
     *  water on purpose must not be interrupted while it still has a healthy
     *  reserve; 100 ≈ 5s of air left, and the preemption stops the process's own
     *  drive entirely so the whole reserve goes to the ascent. gap#76. */
    public static volatile int drownEscapeAirThreshold = 100;

    /** Air-supply level (ticks) at/above which {@code DrownEscapeChain} releases
     *  the channel back to the preempted task — WIDE hysteresis above the 100
     *  entry so the latch cannot oscillate around the trigger threshold (the
     *  frail-gate no-hysteresis precedent). The gate also releases early once the
     *  head is OUT of the water and air is measurably recovering. Values above
     *  vanilla max 300 are clamped by the gate. gap#76. */
    public static volatile int drownEscapeReleaseAir = 280;

    /** task#97c learned stuck-risk edge tax (ml/costmodel): when ON, A* adds
     *  {@code risk(move,terrain) * riskBiasScale / 100} cost units to edges of
     *  the table-listed move families (swim-, bridgePlace-, parkour-family), steering
     *  around patterns that historically precede walker wedges. PURE additive
     *  g-tax — no prune, no capability change; OFF = byte-identical search.
     *  Default OFF until the journey+bench A/B goes GREEN (walkerAscendMovement
     *  precedent). */
    public static volatile boolean riskBias = false;

    /** Max extra cost (in A* cost units, 10 = one walk block) a risk-100 edge
     *  pays under {@link #riskBias}. 40 ≈ a 4-block detour breaks even against
     *  a certain wedge — deliberately conservative; A/B sweeps {20,40,80}. */
    public static volatile int riskBiasScale = 15;

    /** A* node cap surfaced as a tunable knob — Baritone's
     *  {@code pathTimeoutMS} analogue. Maps directly to
     *  {@link net.magicterra.worlddriver.bot.pathfinder.PathFinder} default. */
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

    /** Per-search blockstate memoisation in {@link net.magicterra.worlddriver.bot.ClientWorldView}.
     *  ON = cache getBlockState within a search slice (static-world assumption); the
     *  Walker's per-tick reads always bypass it. Exposed as a knob purely so the
     *  cache's search-throughput contribution can be A/B-measured live (set false to
     *  read straight through). Default true. */
    public static volatile boolean pathfinderCacheEnabled = true;

    /** Obstacle-aware goal-distance heuristic ({@link net.magicterra.worlddriver.bot.pathfinder.CoarseGoalField}).
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

    /** Extra g-cost charged on a DRY {@link net.magicterra.worlddriver.bot.pathfinder.moves.DiagonalAscend}
     *  (diagUp, a diagonal +1 step). Default 0 = base cost (19) unchanged. The executor CANNOT reliably
     *  mount a diagonal +1 riser: the cardinal sprint-bunny-hop (early-jump ≤1.7 + sprint, Walker:4194) was
     *  A/B-DISPROVEN for diagonals (Walker:4198), and diagAscent drops sprint (Walker:4810), so a diagUp
     *  mounts only by a slow late-jump grind that bistably WEDGES on steep terrain (live -711, REGRESSION.md
     *  §30 — the "上坡跳不上方块" residual). A cardinal stepUp+walk L-shape covers the same ascent and the
     *  executor mounts it reliably (the proven sprint-bunny-hop). When >0, this penalty makes A* prefer that
     *  L-shape on dry land, routing AROUND the unmountable diagUp instead of committing it. Validate on the
     *  steep corpus (878/815) with the hardened K≥6 P(wedge) gate; too high over-charges + lengthens paths. */
    public static volatile double pathfinderDiagAscendPenalty = 0;

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

    /** Per-cell g-cost charged for a move whose destination stands ON a leaf canopy (floor =
     *  #minecraft:leaves) or pushes the head INTO leaves. Leaves are full-collision, so A* treats a
     *  leaf top as ordinary standable ground and routes the bot UP onto a tree canopy as a climb
     *  shortcut — where a buoy-free bot bobs/slides on the irregular leaf surfaces and rams the dense
     *  head-height leaves forward (hCol): the "树下撞树叶" canopy-climb jank (live 2026-06-24 at
     *  -810,87 oak canopy — foot on oak_leaves, forward leaves blocking the climb, a ~52-tick / 2.6 s
     *  bob-thread). A human weaves between trunks on the GROUND instead of climbing the canopy; this
     *  SOFT per-cell tax tips A* onto the ground route around/under the tree. A sole canopy route is
     *  still taken (the penalty decays into the move cost, it does not forbid). Default 25; set 0 to
     *  disable. */
    public static volatile double pathfinderLeafCellCost = 25;

    /** EXTRA per-cell g-cost charged on a SURFACE-WATER cell whose cell-above is a thin breakable
     *  obstruction — canonically a lily pad. The planner treats a pad's thin shape as passable (a fast
     *  prone swim slides under), but the moment a surface swimmer SLOWS the upright treading body rams
     *  the pad's collision box at head height — hCol=true, hSpd→0 — and the break-actuator can't reliably
     *  punch the overhead pad (raycast grazes the thin box), so the bot bobs in place for seconds (live
     *  2026-06-24 lowland lake at -896,63 lily_pad: a ~66-75-tick / 3.5 s freeze, yaw winding while it
     *  digs nothing). A human swims AROUND a lone pad. This SOFT per-cell tax tips A* onto the adjacent
     *  clear-water route — pads are sparse on most lakes, so it almost always routes around. A fully
     *  pad-covered crossing is still taken (the penalty decays into the move cost; the break-actuator
     *  remains the fallback). Gated to swim routes (XZ goal) + actual water cells, so dry-land grass
     *  overhead is never taxed. Default 20; set 0 to disable. */
    public static volatile double pathfinderLilyPadCellCost = 20;

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
     *  only, so dry terrain is unaffected. ALSO reused by {@code PathFinder.submergedTax}
     *  for LAND-target (Y-aware) goals — there it prices DESCENDING into a submerged cell
     *  so a {@code goto pos} keeps the buoyant bot on the surface instead of routing it
     *  onto the riverbed it can't follow (the deep-water churn root cause); only descents,
     *  so the ascending climb-out arenas are unaffected. Default 80 — live A/B on a
     *  166-block water-valley journey: 40→80 cut total stall 14 s→7 s (cleared a forced
     *  submerged-descent stall) with no new churn; a higher penalty is directionally safe
     *  because it taxes only DIVING, which a buoyant bot can't do anyway. Set 0 to disable. */
    public static volatile double pathfinderSubmergedWaterCost = 80;

    /** Planner surface-bias: also charge {@link #pathfinderSubmergedWaterCost} on a HORIZONTAL (or
     *  rising) edge that enters a DEEP floating-submerged water cell ({@link WorldView#isFloatingWater}
     *  — water at the cell AND below it, ≥2 deep, no foothold — with water also directly ABOVE it, so
     *  a surface swimmer would have to stay UNDER to thread it) for a Y-AWARE goal ({@code goto pos}/
     *  {@code Near}). {@link PathFinder#submergedTax} already prices the DESCENT into such a cell, but
     *  once the bot ENTERS deep water already submerged (a Fall/dive landed it one below the surface,
     *  or the search start is submerged), the rest of the crossing is HORIZONTAL — never a fresh
     *  descent — so the descent tax never fires and A* threads the WHOLE crossing one cell below the
     *  surface (cheaper than a swimUp), where the floating body cannot follow: it bobs at the surface
     *  ABOVE the y-1 path and jams until a repath happens to re-route on top (live #47 R3 seg0:
     *  {@code Near[-862,62,300]}, a 35-block diag run routed at y61 one below the y62 surface →
     *  ~2.8-3.4 s bob-jam at -832,355). XZ goals already pay this via {@link PathFinder#waterCellTax}'s
     *  submerged overhead, so this clause is Y-aware-goals-ONLY (no double-charge). A deliberate dive
     *  to an UNDERWATER target ({@code diveGoal}) stays exempt — a sunken-ship/monument {@code goto pos}
     *  threads water freely. SHALLOW grounded wading (solid floor below → not floating water) is exempt
     *  by the {@code isFloatingWater} gate. A TAX, never a forbid: if the only route is submerged (a
     *  roofed tunnel with no surface), A* still pays it and threads through — nothing becomes
     *  unreachable. Reuses {@link #pathfinderSubmergedWaterCost} as the per-cell price.
     *  <p><b>Default OFF</b> — byte-identical no-op until validated; flip ON only on a clean live A/B
     *  win (the parent does live acceptance). Wired to {@code mc.bot.setting} so a live run can flip it. */
    public static volatile boolean pathfinderFloatingSurfaceCross = false;

    /** EXTRA per-cell g-cost charged on a SURFACE-WATER traversal cell whose BODY/HEAD column carries a
     *  hanging-VINE or LEAF obstruction over the water — a tree-canopy (oak_leaves + draped vines, often
     *  with lily pads) growing IN/over a lake or river. The planner sees the foot cell as ordinary
     *  surface water (air-like overhead) and threads a horizontal crossing node STRAIGHT THROUGH it,
     *  because none of the existing taxes price the obstruction: {@code waterCellTax}/{@code submergedTax}
     *  look only at the water cell and its directly-above/below (the foot+1 vine / foot+2 leaf are
     *  neither), {@code leafCellTax} checks {@code isLeaves(above)} but the body cell is a VINE (not in
     *  #minecraft:leaves) and the leaf sits TWO up, and {@code padCellTax} needs a COLLIDING instabreak
     *  block above (a vine has no collision shape → it isn't an {@code isBreakableObstruction}). So a
     *  floating bot pushed onto such a node rams the vine/leaf wall at body height — hCol=true, X pins, Z
     *  creeps, hSpd 0.02-0.06 — a ~5-6 s near-zero-net-XZ bob-jam until a repath detours around (live #47
     *  R3 at ~-780,339: oak_leaves y64-65 + hanging vines y63-64 + lily pads over deep water, the route
     *  -760,300→-845,388 clips it). A human swims AROUND the tree. This SOFT per-cell tax — the
     *  leaf-canopy / lily-pad planner-tax family extended to vine/leaf-OVER-WATER columns — tips A* onto
     *  the adjacent clear-water route around the cluster. Scoped to ACTUAL water cells (the foot is water)
     *  so dry canopy (already {@code leafCellTax}'d on land) and open water are untouched, and to the
     *  BODY/HEAD cells (foot+1 / foot+2) so a legitimate vine-CLIMB up out of the water — whose vine
     *  starts AT the foot — is not penalised. A TAX, never a forbid: if the only route is through the
     *  tree (a fully-canopied channel), A* still pays it and threads through — nothing becomes
     *  unreachable. Reuses {@link #pathfinderLeafCellCost} as the per-cell price.
     *  <p><b>Default OFF</b> — byte-identical no-op until validated; flip ON only on a clean live A/B win
     *  (the parent does live acceptance). Wired to {@code mc.bot.setting} so a live run can flip it. */
    public static volatile boolean pathfinderVineOverWaterTax = false;

    /** EXTRA per-cell g-cost charged on a SURFACE-WATER traversal cell whose FOOT+1 (body) cell holds a
     *  thin breakable obstruction — canonically a SINGLE SPARSE lily pad over deep OPEN water. The existing
     *  {@code padCellTax} ({@link #pathfinderLilyPadCellCost}) prices this same geometry, BUT it is gated to
     *  XZ ({@code goal.ignoresY()}) goals — it mirrors {@code waterCellTax}'s triple-gate so it fires only on
     *  a bare-column swim goal (the dense 睡莲池 pool A/B that validated it used an XZ crossing). A real
     *  {@code mc.bot.goto x,y,z} resolves to a Y-AWARE goal ({@code Goal.Block}/{@code Goal.Near}), for which
     *  {@code padCellTax} returns 0 — so over an OPEN-water corridor dotted with sparse single pads, A* prices
     *  a 1-pad instabreak dig (cheaper than a 1-block detour) and threads a crossing node STRAIGHT THROUGH
     *  each pad. A floating bot at the surface then rams + hand-digs the pad in its body cell (foot+1) —
     *  hCol=true, X pins, hSpd→0, attack=true — a ~5-15 s near-zero-net-XZ bob-jam per pad (live #47: the
     *  corridor x[-875,-706] z[280,390] dig-stalls at -830,363 / -817,298 / -849,364; the lily_pad inventory
     *  climbed 27→41 across runs). This is the SAME structural gap that {@link #pathfinderVineOverWaterTax}
     *  plugs for vines/leaves (also goal-type-NEUTRAL), but a lily pad is neither {@code isLeaves} nor
     *  {@code isClimbable} — it is a breakable obstruction with a thin floor collision shape — so the vine
     *  tax misses it. This SOFT, goal-type-NEUTRAL per-cell tax reuses the exact {@code padCellTax} predicate
     *  ({@code isWater(foot) && isBreakableObstruction(foot+1)}) WITHOUT the XZ gate, so a sparse pad over a
     *  Y-aware-goal crossing is priced too, tipping A* onto the adjacent clear water around each lone pad. No
     *  cluster/pool requirement — a single isolated pad trips it. The foot cell itself is never tested (a pad
     *  implies water below), and the obstruction is the BODY cell ({@code foot+1}) where a floating bot's
     *  collision lives. A TAX, never a forbid: a fully pad-covered field with no clear lane still threads
     *  through (the break-actuator remains the fallback); nothing becomes unreachable, so no stranding.
     *  Reuses {@link #pathfinderLilyPadCellCost} as the per-cell price (same obstacle as {@code padCellTax}).
     *  <p><b>Default OFF</b> — byte-identical no-op until validated; flip ON only on a clean live A/B win (the
     *  parent does live acceptance). Wired to {@code mc.bot.setting} so a live run can flip it. */
    public static volatile boolean pathfinderPadOverWaterTax = false;

    /** CLUSTER refinement of {@link #pathfinderPadOverWaterTax}: when a pad-over-water cell is ADJACENT (in
     *  its foot+1 4-neighbourhood) to MORE pad-over-water cells, scale the per-pad tax by 1 + the count of
     *  adjacent pads ({@code 20·(1+N)}), so A* detours around the whole cluster instead of digging through it.
     *  <p>The flat {@code pathfinderPadOverWaterTax} (a constant {@link #pathfinderLilyPadCellCost} per pad)
     *  tips A* around a LONE pad — a 1-block side-deflection (Diagonal +4 in / +4 out = +8 of extra path)
     *  beats the +20 dig. But for an ADJACENT pad PAIR / cluster the cheapest CLEAR lane sits ≥2 cells off the
     *  crossing line (the natural 1-cell deflection lands on the SIBLING pad, itself +20), so the wider full
     *  detour costs MORE than digging ONE pad — and the flat tax (a tax, NOT a forbid) lets A* pick the lesser
     *  evil: it threads, and the floating bot rams + hand-digs, one pad of the pair (~4 s, attack=true; the
     *  live #47 adjacent pads -850/-851,323 / -750/-751,334-335 in the 23-pad scatter). This is the sole
     *  remaining 1/12 jank of the #47 silky-pathfinding acceptance — a refinement of the just-shipped
     *  single-pad fix. Scaling the tax with the adjacent-pad count makes a 2-cell-wider detour around the
     *  whole cluster cheaper than digging through it; a LONE pad (N=0) keeps the flat 20, so sparse single-pad
     *  routing is byte-identical (no over-detour). Still a TAX, never a forbid: a fully pad-covered field with
     *  no clear lane carries the same scaled tax on every cell, so A* threads the shortest line through —
     *  nothing becomes unreachable (no stranding; the break-actuator stays the fallback). Reuses
     *  {@link #pathfinderLilyPadCellCost} per adjacent pad; requires {@code pathfinderPadOverWaterTax} ON.
     *  <p><b>Default OFF</b> — byte-identical no-op until validated; flip ON only on a clean live A/B win (the
     *  parent does live acceptance). Wired to {@code mc.bot.setting} so a live run can flip it. */
    public static volatile boolean pathfinderPadClusterTax = false;

    /** PER-RISE g-cost charged when an edge CLIMBS OUT of water onto a higher bank —
     *  i.e. {@code from} is a water cell, {@code to} is a non-water cell ABOVE it
     *  ({@code to.y > from.y}). The other water taxes price ENTERING water
     *  ({@link #pathfinderWaterCellCost}) and DESCENDING into it ({@link #pathfinderDescendCost}),
     *  but the climb-OUT edge pays neither (its {@code to} is dry, so no water tax; it
     *  rises, so no descend tax). That left A* free to pick a TALL near-bank exit over a
     *  smoother one: a buoyant bot at the surface caps its swim-up at ~surface+0.2 and
     *  CANNOT step onto a +1/+2 ledge without a foothold, so every such exit forces the
     *  Walker's bank-dig climb-out (functional but bob-stuttery, ~1-2 s of mining + the
     *  occasional anti-stuck burst — the live "卡在土墙 / 反复挖同一土块 / 横跳" windows). Pricing
     *  the climb-out ∝ its RISE biases A* toward the LOWEST available exit: a surface-level
     *  bank (rise 0, a plain Walk/StepDown — taxed nothing) is preferred over a +1, which is
     *  preferred over a +2. It does NOT forbid tall exits (a shoreline with only +2 banks
     *  still climbs out — the Walker handles it), it just stops A* choosing one when a
     *  gentler exit lies a few cells along the shore. Same TRIPLE-GATE as the other water
     *  taxes: Y-agnostic XZ goals only (a Y-aware pos/block dive-and-surface guides its own
     *  exit and pays nothing → Goal.Block GameTest water arenas are unaffected), and only
     *  when {@code from} is water. Default 40 ≈ one water cell per block of rise. Set 0 to
     *  disable. */
    public static volatile double pathfinderWaterClimbOutCost = 40;

    /** Multiplier on {@link #pathfinderWaterClimbOutCost} ADDED to a climb-out whose SOURCE
     *  water cell is FLOATING (water directly below the foot → buoyant bot, no solid floor to
     *  push off). A floating exit even at a LOW +1..+3 rise can't be swim-jumped or pillared —
     *  it forces the bob-stutter underwater bank-DIG (live -705,67 bay exit: rise 2, 299 dig
     *  ticks ≈15s; -711,67 pocket sink). The {@code rise>3} surcharge in {@code climbOutTax}
     *  catches only TALL exits and misses these low floating ones, so this prices them up so A*
     *  tips onto a GROUNDED/shallow exit (solid floor under the foot → fast flush stepUp/walk)
     *  where the shoreline offers one. Reuses {@link WorldView#isFloatingWater}; a single-exit
     *  floating climb-out still paths (just dearer). Same TRIPLE-GATE as pathfinderWaterClimbOutCost
     *  (XZ goals, from-water). Default 1.5 (floating rise-2 = 80+120=200 vs grounded 80). Set 0 to disable.
     *  ⚠ REVERTED TO 0 (2026-06-25): live replay2 of the round-3 N journey showed 1.5 makes pathing
     *  FRAGILE in CONSTRAINED water terrain (the -711 vine-wall-over-water): it fires too BROADLY (on
     *  EVERY floating climb-out, common in water), inflating the whole water search so A* exhausts the
     *  30000-node cap (goalReached=false, finalCost 15519) and returns bad partial paths → bot stuck in
     *  the bay, never arrives — WORSE than the pre-fix slow-but-arriving dig. (Replay1 arrived, replay2
     *  failed → ~50% fragile.) Unlike the rare rise>3 surcharge, this hits common low exits and confuses
     *  A*'s distance heuristic. The genre needs a NARROWER trigger or a non-cost approach (fresh cycle). */
    public static volatile double pathfinderFloatingClimbOutMult = 0;

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
     *  {@link net.magicterra.worlddriver.bot.pathfinder.Move#PLACE_COST 20}). Default {@value}. */
    public static volatile double pathfinderBridgeCost = 80;

    /** Base cost of a {@link net.magicterra.worlddriver.bot.pathfinder.moves.PillarUp} rung
     *  (was the hard-coded {@code Move.PILLAR_COST} 30). Placement consumes inventory,
     *  and the old price made a single pillar rung BEAT a ~6-walk stair detour — the
     *  bot spent build blocks where a free walk route existed (wd.bridgeStepTwoBypassNoPlace,
     *  2026-07-20; the 独木桥 battery's "bypass exists → don't spend materials" contract).
     *  150 prices in material scarcity AND execution reality the way
     *  {@link #pathfinderBridgeCost} does for horizontal bridging: a pillar rung live
     *  is a failure-prone jump-place (deep-water takeovers measured ~27s) plus a spent
     *  block, so a ~10-walk stair detour should win; a pillar with NO walkable
     *  alternative (water climb-outs, sealed pits) is still far cheaper than futile.
     *  Live-tunable via mc.bot.setting. */
    public static volatile double pathfinderPillarCost = 150;


    /** Max DRY (no-water) fall the planner will take as a plain {@code Fall} move,
     *  in blocks. Default 3 = Baritone's no-fall-damage cap (current behaviour;
     *  {@code Fall(4)/Fall(5)} are catalogued but inert). Raising it (≤5) lets the
     *  search descend a steep dry slope by taking a small-damage drop (4 blocks ≈
     *  1.5 hearts, 5 ≈ 2) instead of building a dirt "天梯" staircase with
     *  {@code BridgePlace} — the smooth-jungle-descent lever. A higher fall is
     *  cheaper than a place-bridge (Fall(5)=35 vs BridgePlace≈80), so once enabled
     *  A* prefers the natural drop. Survival-sensitive (the bot takes the damage),
     *  so it ships OFF (3) and is opt-in via mc.bot.setting. Capped at 5 (≈2 hearts);
     *  taller no-bucket descents stay {@link net.magicterra.worlddriver.bot.pathfinder.moves.FallIntoWater}
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
     *  net.magicterra.worlddriver.bot.ClientWorldView#isPassable} otherwise intersects it
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

    /** Steep-barrier escalation: armed by Walker when a boxed goal-reaching churn is
     *  detected, so the planner suppresses the receding horizon, deepens the soft-commit,
     *  and raises the depth penalty to find a climb-OVER route instead of re-committing a
     *  cheap shallow/cave segment. Off by default → all searches behave exactly as before.
     *  PURE RUNTIME STATE — must NOT be persisted (not in any config save/load list). */
    public static volatile boolean pathfinderBoxedEscalate = false;
    public static int    pfHorizonBlocks()   { return pathfinderBoxedEscalate ? 0 : pathfinderHorizonBlocks; }
    public static int    pfSoftCommitNodes() { return pathfinderBoxedEscalate ? Math.max(pathfinderSoftCommitNodes, 35000) : pathfinderSoftCommitNodes; }
    public static double pfDepthPenalty()    { return pathfinderBoxedEscalate ? Math.max(pathfinderDepthPenalty, 25) : pathfinderDepthPenalty; }

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

    /** PROGRESSIVE PATHFINDING (渐进式寻路): when ON, the Walker overlaps search with
     *  movement more aggressively at the start-of-segment gap — in addition to the
     *  water bee-line stub it greedily marches over safe STANDABLE land toward the
     *  goal as a zero-search coarse-direction stub, so the bot starts moving almost
     *  instantly instead of holding frozen while the big sliced A* runs (the visible
     *  startup / inter-segment churn). The big search supersedes the stub when it
     *  lands (adoptPath fast-forwards past the overlap). Default ON — gated so the
     *  GameTest suite (which exercises the non-progressive commit modes) is byte-for-
     *  byte unchanged until explicitly enabled; flipped ON after live A/B.
     *  2026-06-19: flipped ON by default after the proactive-pinch-escalation A/B at the
     *  deepwater bay (start edge-churn eliminated, crossing ~54 s vs ~162 s reactive) —
     *  the proactive arm only fires on a genuinely struggling best-effort commit and the
     *  land bee-line only on a clear flat run, so a healthy journey is untouched; GameTest
     *  re-verified 109/109 with it ON. */
    public static volatile boolean pathfinderProgressive = true;

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

    /** Human/bot mouse coexistence: while the bot drives, release the cursor to the OS
     *  so the player's physical mouse stops fighting the bot's aim. The mouse-side
     *  companion to the E1 keyboard work ({@code InputReleaseGate}) — {@code MouseHandler}
     *  is a shared object exactly like {@code mc.options.keyXXX} was, and a human nudging
     *  the mouse writes the same yaw/pitch the Walker/LookController write every tick.
     *  Double-tap ESC takes the cursor back for the rest of the drive burst; the bot going
     *  idle hands it back automatically. Client-only — a dedicated server never grabs a
     *  cursor, so this is inert there. See {@code MouseYieldGate} for the state machine. */
    public static volatile boolean mouseYield = true;

    /** Draw the "bot is driving" badge (top-right) while the bot holds the body, naming
     *  the chain/process and who owns the mouse. Separated from {@link #mouseYield} so a
     *  capture/stream run can keep the ownership indicator without the cursor behaviour,
     *  or vice versa. */
    public static volatile boolean mouseYieldHud = true;

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
        // Interactive blocks are resources, not dirt. Placing one both spends a
        // crafted station as filler AND booby-traps every later place-click against
        // it: right-click on a menu block OPENS ITS GUI instead of placing, and an
        // open screen swallows all movement input (gap #57/#58 — live death #3:
        // the walker plugged with the bot's fresh furnace, re-clicked it, and the
        // FurnaceScreen paralysed the engine while a zombie chewed). Safety-class
        // rejection: applies even under a buildBlockWhitelist.
        if (isInteractiveBlock(block)) return false;
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

    /** A block whose use-click opens a GUI (block-entity holders + the menu-opening
     *  work-station family). Shared by {@link #isUsableBuildBlock} (never place one
     *  as filler) and the walker's place actuator (never CLICK one as a support —
     *  the click opens the GUI instead of placing; gap #57/#58). */
    public static boolean isInteractiveBlock(net.minecraft.world.level.block.Block block) {
        return block instanceof net.minecraft.world.level.block.EntityBlock
                || block instanceof net.minecraft.world.level.block.CraftingTableBlock
                || block instanceof net.minecraft.world.level.block.SmithingTableBlock
                || block instanceof net.minecraft.world.level.block.CartographyTableBlock
                || block instanceof net.minecraft.world.level.block.FletchingTableBlock
                || block instanceof net.minecraft.world.level.block.LoomBlock;
    }

    /** Like {@link #isUsableBuildBlock} but ALSO accepts FallingBlocks (sand/gravel) — for a
     *  strictly VERTICAL pillar-up where the placed block rests ON the solid rung directly
     *  below it (supported, so it never falls). {@link #isUsableBuildBlock} excludes falling
     *  blocks because a BRIDGE places them over a gap (unsupported → they drop); that hazard
     *  does not exist for an in-place pillar. A bot carrying ONLY sand/gravel (deserts, beaches,
     *  rivers — very common) otherwise has NO usable foothold and bob-stalls a +2/+3 ascent ram
     *  it could trivially pillar out of (live 2026-06-24 -1987,111: holdPlaceable rejected the
     *  bot's 11 sand + 8 gravel → 332-tick stall). Use ONLY where the placement is provably
     *  supported below (the pillar-recovery actuator); never for bridges/parkour-place. */
    public static boolean isUsablePillarBlock(net.minecraft.world.level.block.Block block) {
        if (isUsableBuildBlock(block)) return true;
        if (!(block instanceof net.minecraft.world.level.block.FallingBlock)) return false;
        net.minecraft.world.level.block.state.BlockState st = block.defaultBlockState();
        if (!st.blocksMotion()) return false;
        Set<String> wl = buildBlockWhitelist;
        if (!wl.isEmpty()) {
            net.minecraft.resources.ResourceLocation id =
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
            return id != null && wl.contains(id.toString());
        }
        return st.isFaceSturdy(
                net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO,
                net.minecraft.core.Direction.UP);
    }

    /** Resources the bot deliberately gathered — must not be spent as disposable
     *  pillar/scaffold filler (gap#81). Deliberately NARROW (wood family, the
     *  observed waste); extend by adding tags if a run surfaces another wasted
     *  resource — do not speculate now. */
    public static boolean isValuablePlacementBlock(net.minecraft.world.level.block.Block block) {
        net.minecraft.world.level.block.state.BlockState st = block.defaultBlockState();
        return st.is(net.minecraft.tags.BlockTags.LOGS) || st.is(net.minecraft.tags.BlockTags.PLANKS);
    }

    /** Pure ItemStack-level core of a "throwaway" support block — a usable build block (see
     *  {@link #isUsableBuildBlock}) that is NOT a gathered resource (see
     *  {@link #isValuablePlacementBlock}); gap#81. Hosted here (not in
     *  {@code BotInteract}, the client-facing caller) so it stays dist-neutral: {@code
     *  BotInteract} mixes in unrelated client-only methods (LocalPlayer/Minecraft), and the
     *  NeoForge RuntimeDistCleaner refuses to load THAT class at all on a dedicated server
     *  (confirmed live via GameTestServer — "Attempted to load class LocalPlayer for invalid
     *  dist DEDICATED_SERVER" — even though this predicate itself never touches a client type),
     *  so the gametest matrix calls this dist-neutral entry point instead. {@code
     *  BotInteract.isThrowawaySupportBlock} delegates here for production use. */
    public static boolean isThrowawaySupportBlock(net.minecraft.world.item.ItemStack stk) {
        if (stk.isEmpty() || !(stk.getItem() instanceof net.minecraft.world.item.BlockItem bi)) return false;
        return isUsableBuildBlock(bi.getBlock()) && !isValuablePlacementBlock(bi.getBlock());
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

    /** gap#68-⑪: ticks {@link net.magicterra.worlddriver.bot.auto.AutoTool} yields the hotbar
     *  selection after detecting an external actor (setHotbarSlot RPC / human scroll)
     *  changed the slot it last wrote — without this, AutoTool re-clobbers the slot on
     *  the very next tick whenever the crosshair is on a breakable block, so an external
     *  slot switch right before a placement/useItem call silently loses the race.
     *  Values <=1 degrade to a single-tick grace (the arm clamps to >=1, so a zero or
     *  negative live setting can never wedge the yield loop). */
    public static volatile int manualSlotGraceTicks = 100;

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

    /** gap#68-⑧: ticks after a respawn during which autoFight does NOT re-engage and
     *  autoBackfill does NOT auto-start — a freshly-respawned naked bot must not resume
     *  lethal intents (combat kept hunting / orphan digging executed post-respawn). */
    public static volatile int respawnGraceTicks = 60;

    /** gap#68-②: HP at or below which
     *  {@link net.magicterra.worlddriver.bot.scheduler.CombatChain#frailBlocked} refuses to
     *  ENTER a fight — autoFight simply won't bid, and an explicit {@code mc.bot.combat}
     *  order is refused loudly (lastError) unless the caller passes {@code force:true}.
     *  A live fight already in progress under auto-fight is also abandoned (mid-fight
     *  disengage) if HP drops to/below this while fighting; an explicit intent never
     *  abandons mid-fight (its gate is only at entry — the agent already knew the risk). */
    public static volatile float combatFrailThreshold = 6f;

    /** Proactive idle-only dusk shelter (DuskSecureChain, priority IDLE_SECURE=40):
     *  when the bot is sky-exposed at dusk/night, idle (no user task running), and
     *  no threat is within 12 blocks, dig a "挖三填一" bunker (BunkerProcess) after a
     *  short debounce. OFF by default — 挖三填一 stays predominantly an Agent-invoked
     *  action (mc.bot.bunker); enable only for fully autonomous survival runs. When it
     *  DOES auto-fire it pushes a warning-level {@code duskSecure.triggered} event so an
     *  unattended dig is never silent. Below the USER band, so any active task suppresses it. */
    public static volatile boolean autoSecureAtDusk = false;

    /** gap#68-⑨: allow DuskSecureChain to escalate above the user task when exposed at
     *  night and unsheltered. Off = legacy idle-only (40) behaviour. */
    public static volatile boolean duskUrgent = true;
    /** First-night canary: when true the escalated tier only LOGS/emits (bid stays 40),
     *  so the new preemption path can be observed before it is allowed to act. */
    public static volatile boolean duskUrgentDryRun = false;

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
    public static volatile boolean allowBreak = true;

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
     *  deep water with the {@link net.magicterra.worlddriver.bot.pathfinder.moves.SwimBankClimbBreak}
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
    public static volatile boolean allowPlace = true;

    /** Baritone {@code maxFallHeightBucket} analogue — the pathfinder may plan a
     *  fall taller than the no-water cap (3 blocks) when the bot has a water
     *  bucket in its hotbar, placing a water source on the landing block to
     *  break the fall (MLG) and scooping it back. Off by default for the same
     *  non-destructive reason as {@link #allowPlace} (it places a water source).
     *  Read every {@code WaterBucketFall}.valid + WorldView.canWaterBucketFall. */
    public static volatile boolean allowWaterBucketFall = true;

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
     *  position. Raised 12 → 60 (death #14, live 2026-07-20): at 12 a desert
     *  descent hugged a cactus cluster — the executor's body drift overlaps a
     *  neighbouring cactus on a hugged edge, and repeated contact killed a
     *  full-health bot. 60 ≈ a 6-block detour per hazard neighbour, so routes
     *  stop skimming cacti wherever any alternative exists (same treatment as
     *  {@link #lavaDangerPenalty}'s 80→300, scaled down because a graze is
     *  survivable and the {@code ContactDamageEscape} reflex now backstops
     *  actual contact). */
    public static volatile double contactDangerPenalty = 60;

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
     *  ravines.
     *
     *  Raised 15 → 200 (2026-06-15): a LETHAL cliff lip is also where the executor
     *  WEDGES — A* routes a descent/climb across a sheer multi-block face, the bot
     *  overshoots and free-falls far off the route, and every re-search returns the
     *  SAME unclimbable cliff (deterministic relapse: live reverse leg2 diagDown
     *  (2431,97,2165) / step (2435,97,2177), foot slid 16-23 below the route, stuck
     *  1277-1491 / ~64-74 s). Executor-side charges chase the wedge along a
     *  contiguous cliff (wrong granularity) — the fix is to make A* AVOID the lethal
     *  face up front. At 200 (≈ a 20-block detour budget per lip cell) the planner
     *  routes around the cliff via gentler terrain: live leg2 max stuck 1491 → 144,
     *  >300-tick stalls 0. Still additive (a sole cliff route is taken), and only
     *  LETHAL lips count, so survivable hillside descents are unaffected. */
    public static volatile double ledgeDangerPenalty = 200;

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
    public static volatile boolean walkerDigActive = false; // RUNTIME dig-context flag (the Walker held a block-break this frame); NOT persisted, NOT in MCP schema. Read by AutoSwim so the in-process drowning backstop yields to an active dig while air is healthy (2026-07-21 live: deep-ascent had NO air gate and fought every underwater dig from full lungs, resetting destroyProgress each bob).

    /** Walker sneak-brake guard: while walking, if a LETHAL drop (fall deeper than
     *  the bot can survive at its current HP) is one step ahead in the heading, hold
     *  sneak so vanilla's ledge-guard stops the body at the block edge instead of
     *  letting the controller drift off the cliff. Fixes DEATH #8 (RetreatChain/
     *  RunAwayProcess flee path ran along a lip and the controller overshot off a
     *  23-block drop). The planner can't prevent it: PathFinder caps planned falls at
     *  survivableFall, so a lethal fall is pure controller drift, never a planned
     *  move — hence lethal-only here never blocks a legitimate planned step-down. */
    public static volatile boolean lethalEdgeBrake = true;

    /** Health at/below which the Walker goes CAREFUL: sprint is suppressed (sprint
     *  momentum is the drift amplifier behind every unplanned fall) and the
     *  lethal-edge sneak pin is KEPT across planned descents instead of releasing
     *  (the release bets the body lands exactly on the planned cell; at low HP that
     *  bet is fatal — survivableFall shrinks to 3-4 blocks, so residual walk/jump
     *  drift past the lip onto a deeper drop kills). A pinned descent stalls, the
     *  stuck detector repaths, the bot lives. Survival DEATH #3 (2026-07-11):
     *  HP=1 flee through cave terrain, executor drift, "hit the ground too hard".
     *  0 disables. */
    public static volatile double lowHealthCareful = 6.0;

    /** VERTICAL step-pointer re-sync — the vertical analogue of the horizontal
     *  OVERSHOOT_RESYNC. When the bot is GROUNDED but its step-pointer node is beyond a
     *  single jump vertically (|foot.y − node.y| ≥ 2, EITHER sign), the foot is laterally
     *  in the step-advance dead-zone (cur2 ∈ (REACH_DIST_SQ, OVERSHOOT_RESYNC_SQ) — too far
     *  for {@code within}, too near for the horizontal re-sync), there is NO
     *  horizontalCollision, and the stall has persisted — the existing recovery family has a
     *  hole: {@code ascentRamSlide} needs ≥2 ABOVE, {@code descentRamStuck} needs EXACTLY 1
     *  below + hCol, {@code fellOffPath} needs |Δy| &gt; maxJumpUp+2 (=3), and
     *  {@code crossedDescendNode}/{@code passed} are blocked by their reachability gates. A
     *  descent-OVERSHOOT (bot grounded 2 above a stepDown/fall node it drifted past) or an
     *  ascent SLIDE-BACK sitting 2 below the pointer therefore only escapes via the slow
     *  ~5 s WEDGE_TICKS burst (which yanks the bot the wrong way). When on, such a confirmed
     *  dead-zone stall folds into the existing {@code fellOffPath} recovery (repath + node
     *  blacklist) so it re-syncs at once. STRICT extension: outside that exact state the bot
     *  behaves identically. pillarUp/parkour incoming edges are excluded (own handling).
     *  <p><b>Default OFF.</b> The dead-zone is a real, code-confirmed hole in the recovery
     *  family, but the grounded-2-above state with cur2≈2.5 + hCol=false is TRANSIENT — it is
     *  not a stable terrain configuration and could NOT be reproduced deterministically by
     *  {@code descentOvershootResyncArena} (four geometries + a hand-injected step-pointer all
     *  resolve in ~10 ticks: the descent drive walks the bot to the node and it drops; the
     *  ~80-tick live oscillation needs the un-braked 3-D shoulder momentum that does not
     *  synthesize on a flat arena, matching the earlier ridgeOvershootArena finding). With no
     *  deterministic repro there is no clean A/B proving this fix HELPS the live wedge, so per
     *  the "live is truth, never ship an unproven fix" rule it ships OFF — a reviewed, ready
     *  opt-in lever to A/B the next time the intermittent wedge is caught live. Wired to
     *  {@code mc.bot.setting} so a live run can flip it ON. */
    public static volatile boolean walkerVerticalResync = false;

    /** Walker JUMP for a y-mislabeled +1 riser at a LEVEL-labeled node (the dominant steep-climb
     *  jitter). A* can emit a walk/step node whose Y equals the foot's ({@code wp.y==foot.y}) yet
     *  the approach from the bot's current grounded cell is blocked by a +1 SOLID riser one block
     *  ahead in the heading — a stepped ridge/corner where the destination column is standable (so
     *  {@code canStandAt} passed, the move is legal) but a block sits between the body and it.
     *  Because the node is not ABOVE the foot ({@code upDy==0}), the executor's stepUp path is dead:
     *  {@code needJumpForStep}/{@code dryStepUp}→{@code stepUpJump} all need {@code wp.y>foot.y}, and
     *  {@code pivotForStepUp}/{@code stepUpFreeze} gate themselves off the same way. So the forward
     *  key rams the riser flush (hCol=true, hSpd≈0), vanilla bounces the body back ~0.1-0.3 blk, it
     *  re-approaches and rams again — the visible climb hesitation, worst case a multi-second freeze.
     *  No existing recovery catches a LEVEL ram: {@code fellOffPath} needs |Δy|&gt;3, {@code
     *  ascentRamSlide} needs ≥2 above, {@code descentRamStuck} needs a node BELOW + hCol.
     *  <p>When ON, a CONFIRMED grounded level-node ram (horizontalCollision + the wedge timer past
     *  {@code STEPUP_FREEZE_TICKS}) whose forward foot-cell holds a mountable +1 riser
     *  ({@code forwardRiserMountable}: solid top ahead, foot+1 &amp; foot+2 clear) forces a grounded
     *  jump up-and-over — exactly the {@code stepUpFreeze} breaker, for the node that breaker can't
     *  see. Forward already drives at the riser column (driveF=1 here), so ONLY the jump is added.
     *  STRICT extension / INERT off the bug: a clean level walk over flat ground has AIR in the
     *  forward foot cell → {@code forwardRiserMountable} false → never fires (no bunny-hop), and a
     *  normally-advancing walk never lets the wedge timer climb. Targets ONLY the mislabeled-riser
     *  ram; the general stepUp jitter on truly sheer terrain (real {@code wp.y>foot.y} steps) is
     *  unchanged. Wired to {@code mc.bot.setting} so a live run can flip it. */
    public static volatile boolean walkerLevelRiserJump = false;

    /** Walker LATERAL-pad break for a floating bot rammed against a lily pad the body OVERLAPS but the
     *  head-on pad scan misses. A surface swimmer crossing a lily-pad pond rams a pad and the existing
     *  in-water pad-break (Walker drive: {@code p.isInWater() && p.horizontalCollision}) punches it —
     *  BUT that scan only samples the single cell directly toward the WAYPOINT at the eye plane
     *  ({@code BlockPos.containing(p.x ± dir, p.y+1, p.z)}). When the colliding pad sits in an ADJACENT
     *  column the body's AABB (half-width 0.3) overlaps — not the heading cell — {@code BlockPos.containing}
     *  floors the body centre into a DIFFERENT cell and the pad is never found, so attack stays false and
     *  the bot bobs against the pad until A* repaths (~13.5 s). Reproduced DETERMINISTICALLY (2026-06-25,
     *  live -771 crossing, replay-0004): node {@code -771,62,318}, bot frozen at p≈(-770.10,62.10,317.76)
     *  with {@code hCol=true} 96 % of ticks, {@code hSpd≈0.04}, {@code attack=false} every tick; a
     *  {@code minecraft:lily_pad at -770,63,318} (east, toward the bank) blocks the body while the head-on
     *  scan looks at the -771 water cell toward the node. (Natural pond — a lily pad does not
     *  {@code blocksMotion} so {@link #isUsableBuildBlock} rejects it; the bot never places pads.)
     *  <p>When ON, a CONFIRMED in-water ram (horizontalCollision + the wedge timer
     *  {@code noStepProgressTicks} past {@code PAD_RAM_STALL_TICKS}) that the head-on scan left unbroken
     *  scans the four body-overlap columns at the surface (head) cell for a {@code isBreakableObstruction}
     *  (instabreak-by-hand: lily pad, surface plant — destroySpeed 0, never solid terrain) and aims+breaks
     *  the nearest one. STRICT extension / INERT off the bug: a head-on pad is already cleared by the
     *  existing scan (this only runs when {@code pad==null}); a real WALL (dirt/stone) is not breakable
     *  ({@code isBreakableObstruction} false) so it is never touched; dry land ({@code !isInWater}) and a
     *  pad-free crossing (no breakable cell) never fire; and a normally-advancing swim never lets
     *  {@code noStepProgressTicks} climb. Wired to {@code mc.bot.setting} so a live run can flip it. */
    public static volatile boolean walkerPadRamBreak = false;

    /** Walker step-advance guard for ASCENDING parkour leaps. A rising parkour edge
     *  ({@code parkourAscend*}, or a {@code parkour2d}/{@code parkour3} whose landing is
     *  higher than the launch) is the ONLY jumped move that lacks a "don't advance the
     *  step-pointer until the leap has actually been executed" gate — {@code pillarUp},
     *  {@code parkourPlace} and {@code parkourDescend} all have one. Without it the
     *  {@code within}/{@code passed} pure-pursuit re-sync CONSUMES the leap node before the
     *  bot reaches the launch and jumps, locking the pointer onto the NEXT node — which, for
     *  a water climb-out, is a flat {@code walk} node sitting +2 above the water. The bot then
     *  sinks into the pocket and bob-stalls on the underwater bank-dig (~25× slow) until a
     *  repath stumbles onto a gentler exit. When ON, the Walker holds the step on a rising
     *  parkour node until the feet have risen to its Y (grounded ±0.5), exactly like the
     *  pillarUp height-gate, so the parkour actuator owns the launch and a too-far approach
     *  can't skip onto the unreachable landing.
     *  <p>Reproduced DETERMINISTICALLY (2026-06-25, live -870 climb-out, replay-0005 R1
     *  journey): float the bot at the water cell and goto the far bank — A* plans the
     *  parkourAscend2 climb-out and the step skips 5→6→7 at tick 1 every run, dropping the
     *  bot into the y62 pocket. STRICT extension: a parkour leap mid-arc is already below its
     *  landing and airborne, so the gate is inert on a clean leap (which holds via the airborne
     *  state regardless); only the premature drift-skip is suppressed. Wired to
     *  {@code mc.bot.setting} so a live run can flip it. */
    public static volatile boolean walkerParkourAscendHold = true;

    /** Walker SPRINT brake for descending/walking ALONG a DEEP-water edge. The sibling
     *  of {@link #lethalEdgeBrake}/{@code steepDescentNear} for a water hazard instead of
     *  a dry cliff: when the bot is grounded and a DEEP floating-water cell
     *  ({@link WorldView#isFloatingWater}: water with water below — ≥2 deep, no floor in
     *  jump range, so a buoyant body that drifts in cannot climb back out) borders the
     *  foot — directly, or at the bottom of a short open fall column off a neighbour —
     *  DROP sprint (no sneak-pin, so a planned descent still proceeds) so sprint momentum
     *  can't carry the body laterally off the dry staircase into the pocket.
     *  <p>Why the existing brakes miss it: {@code dropAdjacentExceeds} (behind both the
     *  lethal sneak-pin and the steep-descent sprint brake) treats a water-bottomed fall
     *  column as a harmless splash ({@code continue} on {@code isWater}) and only counts
     *  DRY drops, so a descent that borders deep water keeps full sprint. The buoyant
     *  climb-out is the catastrophic part (tens of seconds of bob-stall), so the cell the
     *  momentum drifts into matters more than a survivable dry drop, not less.
     *  <p>Reproduced DETERMINISTICALLY (2026-06-25, replay-0008 -870 lake west bank): a dry
     *  descending staircase (-871,68 → -868,63) runs along the SW deep pocket (x-866..-868,
     *  z383-388, ≥4 deep against a y62→y74 dry bank); A* commits the bridge/step-down chain,
     *  but the bot SPRINTS the stepDown/diagDown and overshoots ~1.7 blk past the path line
     *  (foot x-870.5 → -866.3) off the dry edge into the y62 pocket, then bob-stalls frozen at
     *  x-867.35 for 800+ ticks (peak per-node stuck 345, 1168 inW ticks).
     *  <p>Scoped to NOT block deliberate water entry: it never fires when the planned next
     *  node {@code wp} is itself deep water (a river/lake the path means to enter — that path
     *  WANTS the bot in the water), and a 1-deep shallow splash (solid floor below →
     *  {@code isFloatingWater} false) is inert. Parkour leaps and in-water swim-approaches keep
     *  their momentum (gated off, like {@code steepDescentNear}). Drop-sprint only, so it is a
     *  byte-identical no-op on any tick that wouldn't have sprinted, and a legitimate flush
     *  shore-walk / river crossing is merely walked (not sprinted) along the deep edge.
     *  <p>Default decided by live A/B at the replay-0008台. Flip via {@code mc.bot.setting}. */
    public static volatile boolean walkerDeepWaterDriftBrake = true;

    /** DRY sibling of {@link #walkerDeepWaterDriftBrake} (task#36): latches the
     *  {@code steepDescentNear} sprint-drop across the airborne sub-arcs of a step-down descent.
     *  The raw brake is gated onGround, so on the airborne half of each step sprint re-arms and
     *  the accumulated FORWARD momentum walks the body off a survivable-but-deep (&gt;4,
     *  &lt;survivableFall) lip into a fatal cumulative fall — live 2026-07-11 Mountains massif,
     *  telemetry-confirmed (grounded sprint=false at the lip, onG=false→sprint=true on every fall
     *  tick, body crept over a 19-block lip to death). With the latch on, sprint stays suppressed
     *  through the whole descent so momentum can't build over the lip. Drop-sprint only (no pin),
     *  so a gentle ≤4 staircase (no deep drop adjacent) is a byte-identical no-op. Flip via
     *  {@code mc.bot.setting} for live A/B. */
    public static volatile boolean walkerSteepDescentLatch = true;

    /** Descent step-skip SNEAK-brake (task#36). The planner is HARD-capped at
     *  {@link #pathfinderMaxDryFall} per single node, so a grounded body can never legitimately
     *  have its drive target (wp = path.get(step)) more than that many blocks below the foot —
     *  read-only {@code mc.debug.plan} of the fatal Mountains descent routes a clean ≤4 staircase
     *  all the way down (maxStepDrop=4). Yet live the executor advances {@code step} DOWN the
     *  staircase ahead of the body (arc-length advance runs the pointer forward along the
     *  descending path while the feet are still up top): grounded foot y94 while wp=y83 (11 below).
     *  The drive then aims the body forward+DOWN at that far node and residual sprint momentum
     *  LAUNCHES it off the stair edge into a cumulative fatal fall (2026-07-11 Mountains,
     *  deterministic). Unlike the sprint-only {@link #walkerSteepDescentLatch}, this holds vanilla
     *  SNEAK (maybeBackOffFromEdge clamps the whole movement delta so the body cannot step off a
     *  block edge, yet still steps DOWN one block at a time).
     *  <p><b>REPURPOSED — AIRBORNE forward-drift clamp (task#36, 2026-07-12).</b> The original
     *  grounded gate was proven a literal NO-OP (the far-below wp only appears airborne; grounded
     *  max(foot.Y-wp.Y) is exactly 4.0). The flag now gates the ISOLABLE BACKUP half of the #36
     *  fix: when the steep-descent latch ({@link #walkerSteepDescentLatch}) is armed and the body is
     *  AIRBORNE over a descent, zero the forward drive ({@code driveF→0}, Walker ~line 4259) so the
     *  body drops onto the near tread instead of sailing off the lip on {@code driveF=1}. The arming
     *  half (cumulative path-lookahead → kills sprint) lives under {@code walkerSteepDescentLatch};
     *  this clamp is the driveF-side backup.
     *  <b>Default ON (2026-07-12).</b> Live Mountains A/B (regen off, same start/goal) settled it:
     *  RED (both off) = 13 descent fall-damage (Fall A launch=8); arming-only (this OFF) = 9 (Fall A
     *  STILL launched for 5 — sprint-kill alone sails off the lip); arming+clamp (this ON) = 2, no
     *  launch. So the clamp is the decisive lever, not a backup — enabled by default. Airborne-only →
     *  gravity still drops the body, so it can never stall/deadlock a descent (A/B: bot crossed every
     *  lip and kept descending, no stall-at-lip). Flip OFF via {@code mc.bot.setting} to isolate the
     *  arming half. */
    public static volatile boolean walkerDescentStepSkipBrake = true;

    /** Walker drive fix for the "移动中向后跳 / 下坡往回看" backward lurch on a DISCRETE drop.
     *  The dry descent flip-rejection holds the steady trend heading while a fall/stepDown node
     *  sits sharply (&gt;120°) behind the body, but every {@code WATER_DRIVE_MAX_REJECT+1}=5th tick
     *  it ESCAPES by driving the real (backward) node for one tick to guard against a stale-heading
     *  strand. On a DISCRETE drop that backward escape is counter-productive: the bot landed ONE
     *  block ABOVE the fall node and slightly SHORT of it in XZ, sitting in the step-advance
     *  dead-zone (cur2 ∈ (0.45,4), |Δy|=1, no hCol) where NO pointer-advance fires — and the
     *  recurring backward drive pushes it AWAY from the node it must reach, so cur2 GROWS and it
     *  drifts backward until the wedge timer repaths (live #47 replay-0004 -742,77 fall2: camera
     *  steady ~-118 while driveYaw flips to +57..+69 every 5 ticks, foot drifts x-741→-740.3 = the
     *  visible 180° back-hop; pathChart maxYawErr≈180°). When ON, a DISCRETE descent (fall/stepDown)
     *  whose node is behind the trend holds the trend CONTINUOUSLY (no backward escape), so the body
     *  keeps moving forward down-path and the position-based overshoot-advance / pure-pursuit consumes
     *  the node. Continuous slopes ({@code diagDown}/{@code parkourDescend}) KEEP the bounded escape
     *  (they legitimately ride the immediate node for trend-camera smoothing and could strand without
     *  it), and the safetyRepath ({@code stuckTicks}) still rescues a genuine wedge, so the
     *  stale-heading-strand guard the escape provided is preserved everywhere it was load-bearing.
     *  STRICT extension: only the backward-escape tick of a discrete-descent rejection changes; on
     *  any other tick (and with the flag OFF) the drive is byte-identical.
     *  <p><b>Default OFF.</b> The mechanism is code-confirmed (the t= telemetry above shows the
     *  recurring backward-escape drive), and the live A/B at the -746→-706 controlled climb台
     *  trended the peak drive-flip DOWN (maxDriveDYaw 176-178° OFF → 142/153/151° ON in 3 of 4
     *  runs), BUT it was NOT a CLEAN win: the climb's backward-motion is dominated ~5:1 by the
     *  separate stepUp-ram-snap-back (40 of 48 backward ticks per run), the discrete-descent flip is
     *  only ~17 % of it, and high path-variance (A* re-derives a different staircase each run) swamped
     *  the signal — one ON run still hit 172° with a churny 41 s outcome. No GameTest arena exercises
     *  a DISCRETE-descent overshoot back-hop ({@code descentYawArena} is a continuous diagDown SLOPE
     *  this fix deliberately excludes, so it is inert there and stays GREEN). Per "live is truth, never
     *  default ON without a clean A/B" it ships OFF — a reviewed, ready opt-in lever to A/B the next
     *  time a discrete-descent back-hop is caught live (mirrors {@link #walkerVerticalResync}). Wired
     *  to {@code mc.bot.setting} so a live run can flip it. */
    public static volatile boolean walkerDescentFlipHold = false;

    /** Walker step-advance fix for a {@code stepDown} (or short discrete descent) whose target
     *  foothold is a SHALLOW WATER-SURFACE cell — water at the node, a SOLID floor one block below,
     *  and a non-water (air / vine / lily-pad) head above (a 1-deep splash at a bank edge, NOT the
     *  deep {@link WorldView#isFloatingWater} pocket).
     *  <p><b>The bug</b> (live #47, ground-truth walker telemetry at node -809,62,350; water y62,
     *  dirt floor y61, lily-pad/vine at y63): the bot steps down into the 1-deep cell and GROUNDS
     *  there ({@code onG=true}, foot y62.00, {@code |dY|=0.00} — vertically EXACTLY at the node), but
     *  settles pinned at {@code cur2≈0.546}, just 0.10 OVER the {@code REACH_DIST_SQ=0.45} reach gate
     *  (~0.74 b short of the node CENTRE in X, {@code hCol}, x frozen at -807.76). It cannot walk the
     *  last centring fraction in: buoyancy + the water-climb jump ({@code up=true}, because on each
     *  down-bob {@code wp.y ≥ foot.y} reads as a climb-out) keep lifting the foot off the floor and
     *  ramming it, and the prone sprint-swim can't nudge the buoyant body that final 0.1 b. So
     *  {@code within} (needs {@code cur2 < 0.45}) NEVER fires, the step-pointer freezes, and the bot
     *  bob-rams for 12 s+ until a safety repath happens to find a gentler exit. {@code floatOverSubmerged}
     *  does NOT cover it (that gate needs the node BELOW the foot, {@code dyNode < -0.5}; here the node
     *  is AT the foot, {@code dyNode≈0}). A DRY step-down to the same XZ would just walk onto the node
     *  centre and advance — buoyancy at the water-surface foothold is the whole difference.
     *  <p><b>The fix</b> (mirrors {@code floatOverSubmerged}/{@code deepWaterRise}/{@code crossedWalkNode}):
     *  when the bot has ARRIVED at such a water-surface step-down node — vertically aligned
     *  ({@code |dyNode| < 1.2}), in/at the water, and STALLED there ({@code noStepProgressTicks} past a
     *  gate) — treat reaching the surface cell as reaching the node and ADVANCE the step at a RELAXED
     *  horizontal reach ({@code WATER_STEPDOWN_REACH_SQ}, well under {@code OVERSHOOT_RESYNC_SQ=4} so it
     *  can't cut a live corner). Like {@code crossedWalkNode} it is gated on a CONFIRMED stall, so a
     *  clean approach still advances via the tight {@code within}/{@code passed} FIRST; this only
     *  rescues an already-pinned bob-stall.
     *  <p>STRICTLY scoped: the incoming edge must be a {@code stepDown} (or a discrete {@code fall}/
     *  {@code diagDown}) AND the node must be a shallow water-surface foothold. A DRY step-down, a
     *  deep-water ({@code isFloatingWater}) landing, a fully-submerged node, and any climb/walk/parkour
     *  edge are all byte-identical INERT, so dry descents and deep crossings are unchanged.
     *  <p><b>Default ON</b> — the live A/B this note gated on has landed; flip OFF to A/B against it. Historic note: byte-identical no-op while OFF, which was the pre-flip state, and remains true if you flip it back
     *  win (the parent does the live acceptance). Wired to {@code mc.bot.setting} so a live run can
     *  flip it. */
    public static volatile boolean walkerWaterStepDownFloat = true;

    /** Advance past a +1 stepUp/diagUp CREST node a dry buoyancy-free body has TOPPED OUT on but
     *  ORBITS, so the diagonal-staircase crest hiccup closes fast instead of bob-orbiting ~1.25-2.55 s.
     *  <p><b>The bug</b> (live -633,80,318, a +2 diagonal-staircase plateau lip; DETERMINISTIC,
     *  byte-identical across runs): topping the crest the foot reaches the node's Y at the apex bob
     *  ({@code |dyNode|≈0}, py 79.0↔80.25 across node y80) but a tight ±0.5 b lateral orbit (pz
     *  317.7↔318.7 around node z318.5) keeps {@code cur2} pinned at ~0.49-1.2 — just over the tight
     *  {@code REACH_DIST_SQ=0.45} (floor 0.492). So {@code within} ({@code cur2<0.45}) NEVER fires, and
     *  while CIRCLING the next node never reads STRICTLY closer so {@code passed} never fires either —
     *  the step-pointer freezes ~25-51 ticks until the orbit drift happens onto a {@code passed} boundary.
     *  NO actuator catches it: {@code ascentRamSlide} needs the node {@code ≥2 ABOVE} a GROUNDED foot
     *  (here {@code +1} above a bobbing/airborne foot), {@code descentRamStuck} needs the node 1 BELOW +
     *  hCol, and {@code stepUpFreeze} needs a grounded riser-RAM (the bot is airborne-orbiting, no hCol).
     *  <p><b>The fix</b> (mirrors {@code walkerWaterStepDownFloat}/{@code crossedWalkNode}): once the bot
     *  has reached the crest node's Y ({@code |dyNode| < 0.5}) and STALLED there orbiting
     *  ({@code noStepProgressTicks} past a gate), treat the apex arrival as reaching the node and ADVANCE
     *  the step at a RELAXED horizontal reach ({@code STEPUP_CREST_REACH_SQ=1.3}, well under
     *  {@code OVERSHOOT_RESYNC_SQ=4} so it can't cut a live corner). Like {@code crossedWalkNode} it is
     *  gated on a CONFIRMED stall, so a clean stepUp tops out and advances via the tight {@code within}/
     *  {@code passed} in &lt;12 ticks FIRST; this only rescues an already-pinned crest orbit.
     *  <p>STRICTLY scoped: the incoming edge must be {@code stepUp}/{@code diagUp} AND the foot must have
     *  ALREADY risen to the node ({@code |dyNode|<0.5} — a node still being climbed from a full block
     *  below has {@code |dyNode|≥0.5} and is never skipped) AND the bot must be on DRY land (in-water
     *  ascents are owned by {@code floatOverSubmerged}/the in-water climb gate). A non-ascent edge, a
     *  not-yet-topped climb, a fast clean stepUp, and every in-water case are all byte-identical INERT.
     *  <p><b>Default OFF</b> — byte-identical no-op until validated; flip ON only on a clean live A/B win
     *  (the parent does the live acceptance). Wired to {@code mc.bot.setting} so a live run can flip it. */
    public static volatile boolean walkerStepUpCrestReach = false;

    /** Walker WATER-SURFACE walk relaxed-advance: the turn / terminal / WALL-CORNER freeze breaker for a
     *  buoyant surface swimmer. A flat {@code walk} water-surface node sits the bot ~0.67 b out (cur2 floor
     *  ~0.455, just over the tight {@link Walker#REACH_DIST_SQ}=0.45) so {@code within} never closes; a
     *  straight crossing advances each node via {@code passed} (forward momentum carries the body past), but at
     *  a TURN / terminal / wall-corner node the bot is not crossing toward the next node so {@code passed}
     *  can't fire either — the flat water walk node then has NO relaxed-advance and the bot orbits / freezes
     *  against the corner (live deep-water bay corner: cur2 1.142 FROZEN 321 ticks, {@code within}=0, drive yaw
     *  swinging 403° — the "贴墙卡住 / 在水里卡住" the goal names; {@code attack}=0, NOT digging — the dig was a
     *  video misread). When ON, a CONFIRMED in-water stall ({@code noStepProgressTicks} — HORIZONTAL-only in
     *  water so the bob can't fake-reset it — past {@code WATER_WALK_STALL_TICKS}=24) at a flat {@code walk}
     *  water node within {@code WATER_WALK_REACH_SQ}=1.3 advances the step (next node {@code |Δy|<1.2} to bar a
     *  climb-skip) so the segment continues / repaths from here. STRICT extension / INERT off the bug: a clean
     *  crossing advances via {@code passed} in 1-2 ticks (noStepProgress stays low, never trips the stall gate);
     *  a dry walk and every non-walk edge are byte-identical inert. Wired to {@code mc.bot.setting} so a live
     *  run can flip it. */
    public static volatile boolean walkerWaterWalkReach = true;

    /** Walker JITTER-IMMUNE +2/+3 ascent-ram recovery: the deep fix for the steep-climb-failure stall where
     *  the bot drifts off a √2 diagUp and slides 2-3 blocks BELOW the ascent node — that node becomes an
     *  effective +2/+3 ram too tall for a single jump, yet under the fellOffPath >3 fall threshold. The
     *  existing {@link Walker} ascentRamSlide folds exactly this into fellOffPath, but its stall gate hangs on
     *  {@code noStepProgressTicks}, which the slide-back's re-approach / vertical bob zeroes on every 3D
     *  new-low — so on a WIDE ram the gate never fills and the bot bob-rams 150+ ticks before the slow
     *  WEDGE_TICKS(100) burst yanks it back (live J3b -877,75,241: diagUp node 3 above the foot, cur2 3.2-4.1,
     *  反复横跳, 156 t / 7.8 s). When ON, a node >=2 above the foot (always a slide-back — no PLANNED move
     *  places one above a foot) that has DWELT {@code rawStepDwellTicks} (bob-immune, resets only on
     *  step-advance) past the bar folds into fellOffPath so the fresh foot-search blacklists the unreachable
     *  node and re-routes from the actual lower position NOW — with NO onGround requirement, so it also catches
     *  the airborne-bob ram the grounded ascentRamSlide misses. STRICT extension: DRY only (water
     *  bank-climb-out owns its dig recovery); pillarUp/parkour keep their handling; a clean climb advances via
     *  within/passed in 1-3 ticks (step changes, dwell resets) and never trips it. Wired to
     *  {@code mc.bot.setting}. */
    public static volatile boolean walkerAscentRamJitterImmune = false;

    /** Phase-0 of the arc-length pursuit refactor (#55): a SHADOW computation that projects the continuous
     *  foot XZ onto the path polyline, reports the projected segment, cumulative arc-length s, horizontal
     *  perpendicular distance, and the tangent heading at s+lookahead, and counts backward-snap events.
     *  DRIVES NOTHING — logged only (walkerDebug). It exists to prove, via replay on the existing stall
     *  fixtures, that arc-length projection is monotonic and its projected segment tracks the live {@code step}
     *  on clean runs BEFORE Phase 1 (walkerArcLengthAdvance) drives the step pointer off it. The structural
     *  replacement for the ~8 instantaneous step-advance gates + the bob-immune dwell zoo that the buoyancy
     *  bob defeats one per-tick gate at a time. Wired to {@code mc.bot.setting}. Default OFF (zero behaviour
     *  change). */
    public static volatile boolean walkerArcLengthShadow = false;

    /** Phase-1 of the arc-length pursuit refactor (#55): drive the {@code step} pointer from the path
     *  PROJECTION instead of the per-tick instantaneous gates. When ON, the step-advance decision becomes
     *  "the foot's forward projection has reached a later segment" ({@code arcProj.segIdx > step}) plus the
     *  legitimate close-node arrival ({@code within}); the seven bob-compensating gates (passed, tailConsumed,
     *  crossedDescendNode, crossedWalkNode, waterStepDownFloat, stepUpCrestReach, waterWalkReach) are bypassed
     *  — they only ever existed to fire an advance the buoyancy bob stopped {@code within}/{@code passed} from
     *  firing, and the projection is bob-immune by construction. Live P0 shadow proof (2026-06-27): at the
     *  stalls the bot's projSeg led the frozen live step by 2-5 segments — exactly the advance this drives.
     *  The edge-execution holds (pillar/parkour/bridge not yet executed) still gate advancement. Requires
     *  {@link #walkerArcLengthShadow}'s projector (computed whenever either flag is on). Default ON. */
    public static volatile boolean walkerArcLengthAdvance = true;

    /** Phase-2 of the arc-length pursuit refactor (#55): aim BOTH the camera and the body at the bob-immune
     *  path TANGENT ahead of the projection ({@code arcProj.tangentYaw}) instead of the immediate-node bearing.
     *  The node bearing flips ~180° the instant the foot overshoots the node — the backward-jump / 反复横跳 /
     *  facing-the-wall dead-corner stall (live P1 proof: with the step pointer correctly tracked the body still
     *  stalled on-path with dYaw up to 129°, the camera/drive pointing nearly opposite the path). A tangent at
     *  s+lookahead never reverses, so it eliminates that whole failure mode AND makes the descent flip-rejection
     *  / smoothWaterDriveYaw / trendCam bandaid family unnecessary. Skipped for launches (a parkour/MLG leap
     *  snaps at its specific landing) and inside the aim dead-zone. Requires the projector (auto-computed when
     *  this, {@link #walkerArcLengthShadow} or {@link #walkerArcLengthAdvance} is on). Default ON. */
    public static volatile boolean walkerTangentAim = true;

    /** Phase-3 of the arc-length pursuit refactor: a bob/jitter-IMMUNE ram-wedge recovery. When the arc-length
     *  projection s makes no forward progress (|ds| &lt; 0.05/tick) while horizontalCollision for ARC_WEDGE_TICKS
     *  (~1.5 s), fold into the existing fellOffPath recovery (fresh foot-search that blacklists the un-advanceable
     *  node + re-routes from here). This is the structural replacement for the descentRamStuck / ascentRamSlide /
     *  verticalResync detector zoo, which all hang on noStepProgressTicks — a 3D-new-low counter that the ram's
     *  sub-block vertical jitter (and the buoyancy bob) zero every tick, so the gate never fills and the bot bobs
     *  ~5 s against a wall before the slow 100-tick wedge fires (live -672,94: descentRamStuck's exact node-1-below
     *  + hCol case, yet no recovery for 34+ t). The horizontal s is immune to that vertical noise. Requires the
     *  projector (auto-computed when this or another arc-length flag is on). Default ON. */
    public static volatile boolean walkerArcLengthWedge = true;

    /** Phase-3b: NET arc-length progress over a WINDOW (catches an OSCILLATING limit cycle the per-tick ram wedge
     *  {@link #walkerArcLengthWedge} and the anti-churn net-XZ both miss). A steep-face diagUp churn (live 2026-06-28
     *  -815, deterministically reproduced via replay-0006) bob-jumps AIRBORNE (no hCol → ram wedge resets) making
     *  small per-tick FORWARD ds then sliding back: net arc-s ≈ 0 over the cycle, yet per-tick |ds| > 0.05 (ram wedge
     *  resets) and net-XZ swings ~35 blocks laterally (anti-churn fooled). arc-s is the projection ONTO the path,
     *  immune to both the lateral swing and the vertical bob, so requiring ≥ ARC_PROG_MIN net arc-s over ARC_PROG_WINDOW
     *  cleanly flags "no path progress despite motion" and folds into the SAME fellOffPath recovery (fresh foot-search
     *  blacklists the un-advanceable node + re-routes). No hCol/onGround gate; excludes water + a legit barrierHit hold.
     *  A healthy walk clears ~8 blocks of arc / 2 s so legit travel never trips it. Default OFF (byte-identical).
     *  Validate via the deterministic replay-0006 A/B. */
    public static volatile boolean walkerArcProgressWedge = false;

    /** Fell-below align breaker: the diagUp limit-cycle ROOT (deeper than {@link #walkerArcProgressWedge}'s repath
     *  recovery). When the bot drifts below the route onto a +1 node, the freeze-breaker's LOOSE stepCol2&lt;1.6 gate
     *  fires a forced grounded jump from a lateral offset (~1.0-1.6) that lands BACK below the node, relaunches the
     *  bob, and repeats — a futile-jump cycle (live replay-0006 -809: onG 39:1 false, ~97% airborne, mount machinery
     *  starved). This SUPPRESSES the jump while {@link Walker} arcProgStall is set AND the lateral offset is in the
     *  loose-but-not-tight band (&gt; FELL_BELOW_TIGHT_SQ), letting the bot SETTLE to ground so the forward-drive pulls
     *  it to tight alignment; the jump re-engages once in close and mounts cleanly (mirrors the pillarUp off-column
     *  align). Unlike apw (repath = re-commit the same staircase) this fixes the MOUNT in place. Default OFF
     *  (byte-identical: arcProgStall resets in water → dry-only; INERT unless the futile-jump signature is present).
     *  Validate via the deterministic replay-0006 A/B (and the replay-corpus regression). */
    public static volatile boolean walkerFellBelowAlign = false;

    /** Bob-immune DRY wedge timer at +1 climb nodes. The {@code noStepProgressTicks} wedge timer counts ticks
     *  where the closest-approach distance² ({@code wd2}) stops improving; on dry land wd2 uses the CONTINUOUS
     *  {@code p.getY()} vertical term, so the jump/buoyant bob at a node ABOVE the foot (p.y oscillates ~0.1
     *  TOWARD the node every tick) manufactures a wd2 new-low each tick → the timer resets → a STALLED +1 climb
     *  (a stepUp ramming the riser without mounting, or a stairUpBreak whose break never completes because the
     *  aim wanders off the target block) never trips recovery and churns indefinitely (corpus-steep-822
     *  water-edge climb-out: 760+ churn ticks clustered at -812..-817 / y60-66, replay-diagnosed 2026-06-29).
     *  When ON, at an above-node ({@code node.y > foot.y}) the vertical term uses the QUANTIZED {@code foot.getY()}
     *  so only a REAL climb (the foot rising a whole block) counts as vertical progress — an in-place bob does
     *  not, so the wedge timer accumulates and the existing recovery fires. Default OFF (byte-identical unless
     *  the bob-defeated-timer signature is present: dry, above-node, non-mounting). Validate via the hardened
     *  K=3-median replay-corpus gate (baseline_robust.json). */
    public static volatile boolean walkerDryWedgeFootY = false;

    /** Wall-corner node-aim handoff. With walkerTangentAim the body steers along the bob-immune path TANGENT,
     *  which is right for a clean trend-cruise but wrong at a CORNER: when the immediate node sits well off the
     *  tangent AND a wall is on the tangent heading, the body rams the wall (horizontalCollision) and only
     *  creeps across as drift sweeps the geometry — the "贴墙卡住" stall (replay-diagnosed 2026-06-29 on
     *  corpus-dry-627 start: body yaw frozen 91° while the node bearing was 122°, hCol=true, ~350-tick churn).
     *  When ON, a ram with the node > WALL_CORNER_AIM_DEG off the tangent yields the aim back to the direct
     *  node bearing so the body turns off the wall onto the node. Gated on hCol, so a no-wall trend-cruise keeps
     *  the tangent byte-identical. Default OFF; validate via the hardened K=3-median replay-corpus gate. */
    public static volatile boolean walkerWallCornerNodeAim = false;

    /** Overshoot re-aim at a cliff base. A dry walk node OVERSHOOT (foot blew PAST it, cur2 > 4) whose NEXT
     *  node is the climb (>1 up, so the overshoot-resync `passed` reachability gate can't advance onto it)
     *  leaves the body aimed the wrong way (backward into a wall) and ram-frozen — even safetyRepath at
     *  stuck>60 re-commits the same path (journey 2026-06-29 -558,82: yaw -179, yawErr -120, hCol, cur2 6.28,
     *  260-tick wedge = the "略微后退/贴墙卡住" signature). When ON, after OVERSHOOT_REAIM_STUCK wedged ticks
     *  the aim points BACK at the overshot node so the body walks onto it and relaunches the climb from the
     *  aligned base. Dry + grounded + next-too-high + long-stuck only. Default OFF; validate via hardened gate. */
    public static volatile boolean walkerOvershootReaim = false;

    /** Dry repath-rechurn breaker (the COMMON wedge amplifier, REGRESSION.md §21). The 5 single-mechanism
     *  executor point-fixes all failed because the catastrophic wedges are HETEROGENEOUS per terrain
     *  (failed-parkour on steep-822, airborne-diagUp on crest-815, corner-block at a path start) yet share
     *  ONE amplifier: a move fails → the foot is far OFF-PATH → safetyRepath (stuck>60) re-commits a path that
     *  is ALSO unexecutable from the off-path spot → the body oscillates toward it (node-orbit yaw-thrash) →
     *  re-fails → repath, and totStuck ratchets to 800-3000+. The existing anti-spin/anchor machinery
     *  (CHURN_REPATH_CAP, repathsNoProgress, reCentre) is IN-WATER gated or fires only when the foot is off
     *  the spine cell with the previous node not behind — none of it catches the dry far-off-path churn.
     *  When ON: dry + a sustained stall (stuckTicks > DRY_REANCHOR_STUCK) + the foot well off the current
     *  node (dist² > DRY_REANCHOR_OFFPATH_SQ) deterministically aims at the last cleanly-passed node centre
     *  (path[step-1]) — a FIXED point whose bearing doesn't thrash as the bot closes — walking the body back
     *  ONTO the path before it resumes, instead of letting the repath loop re-churn. Default ON; validate via
     *  the hardened K≥6 same-build P(wedge>800) gate (K=3 median is bistable-noise-corrupted, §17). */
    public static volatile boolean walkerDryReanchor = true;

    /** Lateral lane-keep centering for a dry DIAGONAL DESCEND (diagDown), mirroring the diagUp centering that
     *  already exists (Walker:4262). The strafe lane-keep covers waterClimb/diagUp/cardinal lanes but NOT
     *  diagDown — a diagonal step-down (both axes change) falls into no branch, gets ZERO cross-axis
     *  correction, drifts off the descend diagonal, and RAMS the perpendicular corner (hCol, large yawErr):
     *  the close diagDown-ram wedge (live -671, REGRESSION.md §26). Unlike the DryReanchor anchor-back
     *  re-aim that OSCILLATED this case worse (totStuck 6009, §26 reverted), this is a LATERAL strafe (not an
     *  aim change) that pulls the body onto the diagonal line so the corner clears. Default OFF; validate via
     *  the hardened K≥6 P(wedge>800) gate on the diagDown-bearing archives (steep-822 run6 corner-block). */
    public static volatile boolean walkerDiagDownCenter = false;

    /** Wall-corner fast-churn recovery. The dry boxed-pocket churn detector (net XZ displacement < 8 blocks
     *  over a 20s window → blacklist the stuck nodes + escalate the planner + back off) is the ONLY recovery
     *  that catches a §39 wall-corner stall (rocky/dirt/water-boundary 贴墙卡住), because the node-relative
     *  counters (totStuck, noStepProgressTicks) get RESET by the orbit's node-churn so safetyRepath/UnstuckChain
     *  never fire. But the 20s window is too slow — live journey-A ground through 20-45s wall-stalls. When ON,
     *  a SUSTAINED horizontalCollision (≥4s continuous, the unambiguous wall-ram signature) shortens that window
     *  to ~8s so the same blacklist+escalate fires ~2.5× sooner. Gated HARD on sustained collision so legitimate
     *  slow-but-moving terrain (hCol=false, still net-progressing) keeps the full 20s window — that targeting is
     *  why this can succeed where the unconditional walkerFasterChurnRepath (reverted, false-fired on slow climbs)
     *  could not. Default ON; validate LIVE on the journey-A repro (corpus totStuck-based gate is BLIND to this
     *  net-progress stall, REGRESSION.md §37). */
    public static volatile boolean walkerWallCornerFastChurn = true;

    /** Drowning-escape reflex (LETHAL water climb-out deadlock, live 2026-06-29 New World (-21,60,-42)):
     *  the pillar↔repath deadlock below can pin a submerged bot under a bank lip until its air runs out —
     *  Peaceful does NOT prevent drowning (bot died at hp 3→0 while climbout-place spun). When ON: once the
     *  bot is underwater with air below ~3s (getAirSupply ≤ 60), LATCH a surface-for-air override that
     *  preempts every climb/dig/pillar actuator — hold the swim-up jump and, if a solid lip caps the head
     *  (or a wall blocks the rise), drive BACKWARD off the bank so buoyancy finds open surface. Released
     *  once air recovers (≥ 240) or the bot leaves water; the interrupted climb then resumes fresh. A
     *  survival reflex, not a pathfinding fix: it turns any unknown submerged deadlock from a death into a
     *  breathe-retry loop. Default ON. */
    public static volatile boolean walkerDrowningEscape = true;

    /** Make the water climb-out "pillar gave up" latch STICKY across repaths (the 2026-06-29 lethal loop):
     *  climbPillarGaveUp latches when the buoyant place proves futile (bob can't clear the surface fill
     *  cell, PILLAR_FUTILE_TICKS=50), but every repath swaps the climb node (-20,61,-43 ↔ -21,61,-42) which
     *  RESETS the climb context and clears the latch → the proven-futile pillar re-engaged 26× (~2.5s each)
     *  until the bot drowned, and the bank-dig fallback never got a full turn. When ON, the latch survives
     *  context resets while the foot stays within 3 blocks of where the pillar proved futile (15s TTL), so
     *  the dig/recovery actually takes over. Default ON. */
    public static volatile boolean walkerClimbGaveUpSticky = true;

    /** StepUp mount BACKOFF-RETRY (the #47 problem-6 grind, live 2026-06-29 node(14,58,102) ~1400 ticks):
     *  the cardinal early-jump gate (ascendJumpReady: aligned && flatDist<=1.7) assumes the launch carries
     *  sprint momentum from the approach — but once a mount attempt slides back, the bot re-jumps from a
     *  STANDING start pressed against the riser (flatDist~0.8, hSpd~0.05): a near-vertical hop that clips
     *  the riser lip and slides back again, a self-sustaining limit cycle (jump-graze-slide loop; even
     *  cardinal Z step-ups grind). When ON: a GROUNDED, stuck (>15t), pressed-close (flatDist<0.9),
     *  momentum-less (hSpd<0.1) dry stepUp triggers an 8-tick straight-BACK drive (camera-frame commandMove,
     *  no yaw slam, no jump) that opens ~1.5 blocks of runway, then the normal approach re-accelerates and
     *  the early jump launches WITH momentum over the riser. 40t cooldown between triggers. Default ON;
     *  A/B on replay-0016 (deterministic reproduction of the grind). */
    public static volatile boolean walkerStepUpBackoffRetry = true;

    /** Carrot pursuit shrink under sustained wall collision (the jungle-trunk friction, C5-J1 live
     *  + replay-0018 maxStuck ~284: the interpolated look-ahead carrot steers the BODY at a diagonal
     *  slit between two trunks that only the LOS RAY threads — losWalkable is a ray test, the 0.6-wide
     *  hitbox snags, bear-to-node 34 vs carrot aim -80, hCol pinned ~15 s until slow recoveries fire).
     *  When ON and horizontalCollision has been sustained HCOL_RAM_TICKS-ish (>=8t), carrotPoint
     *  collapses the pursuit distance to the immediate committed node: the A* node CHAIN is
     *  body-walkable by construction, the interpolated shortcut is not. Releases the tick the
     *  collision clears (hColRamTicks resets), restoring the smooth far carrot.
     *  <p><b>A/B-DISPROVEN 2026-07-02</b> (replay-0018 K=3v3: OFF 382/236/236 vs ON 179/513/516,
     *  ON median WORSE): the far carrot's off-node bearing IS the string-pulled detour around the
     *  trunk; collapsing to the node aims the body at the trunk FACE. The trunk friction is not an
     *  aim bug — keep OFF permanently; the fix lane is a body-width-aware LOS (losWalkable corridor
     *  test), not pursuit shrink. */
    public static volatile boolean walkerCarrotHColShrink = false;

    /** Carrot LOS body-width honesty (the §52-identified true fix lane for the jungle-trunk
     *  friction): carrotPoint's line-of-sight gate switches from the centre-line RAY test to
     *  {@code PathSmoothing.losWalkableBody} — a 0.6-wide corridor probe (4-corner AABB per
     *  interpolated sample). The far carrot then refuses bearings whose corridor clips a trunk
     *  column, stopping the pursuit at the last body-walkable node instead of steering the
     *  hitbox into a slit only the ray fits. Path smoothing keeps the cheap ray. Default ON. */
    public static volatile boolean walkerCarrotBodyLos = true;

    /** §90 diagonal string-pull (the residual-zigzag lane of #15 path smoothing). stringPull
     *  historically straightens ONLY axis-aligned corridors — collapsing a zigzag into a long
     *  diagonal fabricated corner-cuts the walker couldn't thread (spawn-maze stall) back when
     *  the merge guard was the centre-line ray. With {@code losWalkableBody} (0.6-wide corridor
     *  probe, same primitive walkerCarrotBodyLos trusts for pursuit) a diagonal merge can be
     *  admitted honestly: the merged segment is kept only when the BODY corridor clears. Kills
     *  the cardinal staircase residue on open diagonal terrain — the last structural source of
     *  corner-scrape micro-slowdowns. Default OFF. */
    public static volatile boolean walkerDiagonalStringPull = false;

    /** §91 steep-ascent chain tax (#15). Ascending edge whose landing faces another
     *  2-high wall (the climb continues immediately) costs this much extra — the
     *  slow-map measured continued climbs at ~5s/block real execution (climb-2-slide-1
     *  churn) while A* priced them 15 vs walk 10. Taxing the continued-climb shape
     *  steers the planner onto switchbacks/detours that execute at full walk speed.
     *  0 = off. Trial value 12 (≈ one extra walk block per taxed climb block). */
    public static volatile double pathfinderSteepAscentTax = 0.0;

    /** §92 chain-mount (#15 steep-climb executor lane). Consecutive same-direction +1
     *  steps loosen the stepUp square-up gate (sideDist 0.2→0.45, lateral 0.1→0.25,
     *  launch 1.7→2.0) so the staircase is ridden on landing momentum instead of
     *  stall-recentre-jump per step — the recentre window on a slope is exactly the
     *  slide-back window (slow-map y sawtooth). Direction changes keep the strict
     *  gate. Default OFF. */
    public static volatile boolean walkerChainMount = false;

    /** task#82: weave the thin per-move AscendMovement episode tracker over ascent edges
     *  (stepUp/stairUpBreak/diagUp; B1 adjudication — the machine never actuates, PREP/RUNNING/
     *  SUCCESS fall through to the legacy drive; it owns only the per-edge episode + the dig-aware
     *  72t dead-zone watchdog whose UNREACHABLE folds into forceFellOffPath→re-route).
     *  Default ON since 2026-07-20 (B1-3): replay A/B 2×2 over the ascent-heavy corpus subset —
     *  the only arrival in 16 case-runs was an ON leg (steep-822, lowest peak stuck of its four),
     *  10 watchdog fires all landed on genuine dead-zone poses (zero false trips on progressing
     *  climbs), churn deltas stayed inside the identical-flag chaos envelope (×4-5 per-archive
     *  swings), and every ON-leg churn pocket was a pre-existing OFF family. OFF restores the
     *  byte-identical legacy branch. Wired to mc.bot.setting. */
    public static volatile boolean walkerAscendMovement = true;

    /** §93 commit-tail platform retreat (#15 final lane). Best-effort segments whose
     *  tail lands mid-slope (fewer than 2 same-Y standable cardinal neighbours) retreat
     *  up to 8 nodes to the nearest platform node — the half-mounted commit tail plus
     *  periodic repath is what turns complex steep terrain into a 100s climb-fall grind
     *  while the same terrain runs clean standalone (§92b CSI). Default OFF. */
    public static volatile boolean walkerCommitTailPlatform = false;

    /** Repath route-oscillation damper (the C16 dry-land churn, REGRESSION §55 final autopsy):
     *  two near-equal-cost A* routes (a dig-through and a detour) alternate across periodic
     *  repaths — each adoption U-TURNS the bot onto the other route, and it runs both at full
     *  speed in a net-progress loop that totStuck cannot see (live (-252,70,209): y66
     *  traverseBreak route vs y63 walk route, 90s churn, two journeys back-to-back). When ON:
     *  a fresh search result is REJECTED (current path kept) iff the bot is walking its current
     *  path healthily (noStepProgressTicks < 20) and the new route's near-term direction
     *  (3rd node vs the current path's step+3 node, dot < 0) points BEHIND — i.e. adopting it
     *  would U-turn a working walk. A genuinely stuck bot (noStepProg >= 20) always adopts, so
     *  real reroutes (danger, dead end) are never starved; the next periodic repath re-offers
     *  the alternative anyway. Default ON. */
    public static volatile boolean walkerRouteHysteresis = true;

    /** Dig-commit repath hold (the water-bank dig-vs-repath starvation, replay-0004 CLEAN-K5
     *  2026-07-02): an underwater bank dig takes 100-200t (25x mining penalty) but the periodic
     *  repath re-routes faster than that, and each route adoption discards the held break —
     *  vanilla resets the block's progress to zero, so the dig NEVER completes ([expect]
     *  DIG-dropped at 11-19t by adoption + DIG-slow 200t regrinding the re-chosen riser, then
     *  ADVANCE-deadzone starvation = the water-bank churn loop). When ON: while a committed
     *  bank dig's break is actually held (waterClimbDigRiser latched + breakHeld), a fresh
     *  search result is rejected and the current path kept. The futile-dig release
     *  (walkerFutileBankDigRelease) still abandons a hopeless dig, which drops the hold and
     *  lets the next repath adopt normally — so this cannot starve real reroutes. Default ON. */
    public static volatile boolean walkerDigCommitHoldRepath = true;

    /** Water-surface pillar crest-place (the deterministic water-bank pillarUp deadlock,
     *  rig tp(371.5,62,348.5)→goto(378,65,347) 2026-07-02): a pillarUp whose destination
     *  cell is SURFACE water (air above) hit the flooded-shaft float-only branch, but a
     *  buoyant body cannot float above the waterline (bob ceiling +0.08 vs the +0.9 the
     *  next rung needs) — while the crest ticks that COULD place (feet ≥ fill.y+1.0,
     *  ~1-2 ticks/bob) were spent in that branch not placing, and the separate climbout-
     *  place takeover clicked only below +1.0 where vanilla silently rejects the
     *  overlapping AABB. The two place paths missed each other's height windows forever.
     *  When ON: a water shaft cell with air above is treated as the dry-crest case —
     *  jump and place the support at the bob peak. Default ON. */
    public static volatile boolean walkerPillarSurfacePlace = true;

    /** Descending-bridgePlace lip anchor (task#4, replay-0013, live 2026-07-20 23:11
     *  badlands): a bridgePlace node BELOW the foot means the body stands at a lip
     *  about to bridge DOWN into a gap. The drive-phase bridge sneak-brake is
     *  deliberately gated {@code !plannedDescent} (a sneak pin across a planned
     *  step-down deadlocks — vanilla's ledge-guard refuses every edge), and the
     *  break/place actuator only zeroes the drive inputs — so residual walk momentum
     *  slid the body off the lip during the 9 place-aim ticks (x 89.81→89.28), the
     *  place never landed, and the climb-back/slide/repath loop wedged 74×/80 s.
     *  When ON: while a descending place is still PENDING (support cell below the
     *  foot not yet solid), hold sneak — the ledge-guard arrests the slide AT the
     *  lip, the place lands, and the pin releases the moment the support exists, so
     *  the planned step-down proceeds exactly as before ("place first, step second").
     *  A same-level bridgePlace is byte-identical (cell not below the foot). */
    public static volatile boolean walkerBridgeDescentPlaceAnchor = true;

    /** Monotonic stuck-window (the open-water step-jitter starvation, A-4 44s stall
     *  2026-07-02): buoyant drift on a straight water path jitters the step pointer back
     *  and forth (wp 375↔387↔374), and the old `step != stuckStep` test treated every
     *  jitter as a fresh node — stuckTicks observed pinned at 0-6 (aimSrc telemetry),
     *  never reaching the nodeAim fallback at 12, so every stuck-gated recovery starved
     *  while the yaw swept 660° and thrust cancelled. When ON: only a step ADVANCE opens
     *  a fresh progress window; a retreat re-bases the distance reference but keeps the
     *  stall clock running. Default ON. */
    public static volatile boolean walkerStuckStepMonotonic = true;

    /** Climbed-past-the-node stall recovery (the steep-mountain churn core, C26-J3
     *  2026-07-02): grinding up a slope carries the bot GROUNDED 2-3 blocks ABOVE its
     *  committed stepUp node — dY exactly 3.00 sits just outside the `> maxJumpUp+2`
     *  fell-off test (mirror of the 2026-06-24 slide-back gap), within/passed both
     *  starve (node below), and the bot pins against the wall for 300+ ticks with the
     *  yaw locked reverse. Airborne FALL edges legitimately have nodes 3+ below, so
     *  this gates on grounded + dry + noStepProgress > 90 before folding into
     *  fellOffPath (foot-search re-routes from the real, higher position). Default ON. */
    public static volatile boolean walkerAboveNodeStallRecover = true;

    /** Sticky planned-break (Task#5 dig aim-drift root, C28-J1 @-258,81,338): once a
     *  planned break starts swinging, own every tick (exclusive selectTool+aim+attack)
     *  until the block breaks. Ticks where the break-edge gate flickers (buoyant bob off
     *  the within stance, projection jitter) fall through to the travel drive, which
     *  releases attack — and ONE released tick resets vanilla mining progress to zero, so
     *  a 25×-slow underwater dig interleaved with travel ticks never completes (200t held
     *  in aggregate, block still solid, walk-keys showed attack=false travel ticks
     *  threaded through the dig). Solid-gone / breakTimeoutTicks / >5-block drift
     *  releases the latch. Default OFF. */
    public static volatile boolean walkerStickyDig = false;

    /** Dig aim priority — the NON-exclusive successor to walkerStickyDig (killed §66):
     *  after the travel tick fully runs (drive, recovery, repath untouched), re-hold
     *  ONLY the crosshair + attack on the committed dig cell, so an interleaved travel
     *  tick can't release attack and zero vanilla mining progress. A human holding
     *  W+LMB against the wall being dug. Latch expires on solid-gone / 300t / beyond
     *  mining reach. Default ON. */
    public static volatile boolean walkerDigAimPriority = true;

    /** Dry wall-pin dig fallback (§71, C49): a route node behind a 1-block wall pins the
     *  bot hCol with the stall clock climbing while safetyRepath returns the same route
     *  and nothing ever digs the wall. Grounded + dry + hCol + stuckTicks>60 → punch the
     *  waypoint-facing block at head/feet height (digAimPriority latch holds it). Default ON. */
    public static volatile boolean walkerWallDigFallback = true;

    /** Ram-pinned node-aim release (§80, ultra#2): two deterministic dry-descent wall-pins
     *  where the DRIVE heading never points at the current node and every displacement
     *  recovery fires uselessly — (A) the aim-deadzone "hold heading" band is WIDER than
     *  the step-advance within-gate, so a bot parked 1.2 blocks beside a stepDown node
     *  holds its stale yaw forever (yawErr -115°, hCol, hSpd 0); (B) walkerTangentAim
     *  projects a ~reversed tangent at a switchback corner (driveYaw -180° vs node
     *  bearing 14°) and the corner corrector (walkerWallCornerNodeAim) is default-dead
     *  (§13 oscillation). When dry + horizontalCollision + stuckTicks past the wall-dig
     *  gate AND the live yaw is >60° off the current-node bearing, re-aim at the node.
     *  Unlike the §25 REVERTED reanchor-to-step-1 (bounce oscillation), this aims at the
     *  CURRENT node under a collision gate: success clears hCol/stuckTicks and normal
     *  aim resumes. Default ON. */
    public static volatile boolean walkerRamNodeAimRelease = true;

    /** Physical stall clock feed for stuck-gated recoveries (§84): a safety-repath loop
     *  swaps the path every few seconds and each swap resets stuckTicks (stepWindowFresh),
     *  so a physically frozen bot never crosses the stuckT>40 gates — wall-dig and
     *  ram-release starve while a jump-ram spins (canopy pin: 1200t frozen with leaves one
     *  instabreak punch away). ON = those recoveries also fire on 60+ ticks without XZ
     *  displacement (path/repath-independent anchor clock; vertical bob doesn't count).
     *  Default ON. */
    public static volatile boolean walkerPhysicalStallClock = true;

    /** Bridge-commit repath hold (§82): a mid-bridge PERIODIC repath swaps the committed
     *  bridgePlace chain for a fresh plan whose first node sits elsewhere, steering the
     *  bot off the end of the placed deck into air ("搭桥中途掉下"). Arena A/B: 19-block
     *  deck finishes inside one repath period and never falls (3/3); diagonal zig-zag and
     *  40-block decks straddle it and fell 100%. ON = hold routine repaths while the
     *  current/next edge is bridgePlace; safety repaths stay live. Default ON. */
    public static volatile boolean walkerBridgeHoldRepath = true;

    /** Floating-dig break repricing (§81, ultra#1 flooded-oak churn): when the from-cell
     *  is water the bot digs while floating — vanilla is 25× slow (eyes-in-water ÷5 ×
     *  not-on-ground ÷5) plus bob-drift progress resets, but the planner priced it 5×.
     *  ON = ×25 for floating digs; standing-in-shallow digs (feet dry) stay ×5. */
    public static volatile boolean pathfinderFloatingBreakTax = true;

    /** Trunk-aversion multiplier on log breakCost (§81): logs are cheap (hardness 2,
     *  bare-hand correct) so dense-forest A* routes THROUGH trees; this prices the
     *  hidden approach/aim/canopy-snag cost so a walk-around wins. 1.0 = byte-identical. */
    public static volatile double pathfinderLogBreakTax = 3.0;

    /** Dig-aversion multiplier on the planner's breakCost (§74): >1 biases A* toward
     *  walking around instead of committing dig-dense mineshaft/cave legs whose hidden
     *  per-block approach/stall costs broke C53/C58/C59 (50-65s worst stalls). Planner
     *  pricing only; executor fallback digs unaffected. 1.0 = byte-identical. */
    public static volatile double pathfinderBreakCostMultiplier = 2.5;

    /** Bank-dig ground-blip immunity (the underground-pool climb-out grind, live 2026-07-02
     *  (-275,49,-38): the committed bank dig requires {@code !onGround}, but the buoyant bob
     *  touches bottom ~4 ticks/second — each blip drops {@code digCommitted}, the tick falls
     *  through to the normal drive with attack=false, and vanilla RESETS the block's break
     *  progress (17 dig + 4 drive ticks per second, 25x underwater mining => the riser NEVER
     *  breaks; observed as "反复挖同一处方块无效"). When ON: a grounded streak of <=5 ticks
     *  keeps the commit alive (a real climb-out grounds for good, ending it after the streak
     *  passes 5). Default ON. */
    public static volatile boolean walkerBankDigGroundBlip = true;

    /** Actuator EXPECTATION alarms (预期-实际实时检测): every tick, compare what the pressed
     *  inputs SHOULD produce against what the world actually did, and log a [expect] WARN the
     *  moment they diverge — turning post-mortem log archaeology into live, causal alarms.
     *  Three observers (zero intrusion into actuator branches; they read the player's real
     *  input state at the top of tick):
     *  JUMP-noRise  — jump pressed while grounded, yet 8 ticks later the peak Y never rose
     *                 +0.9 (an in-place/blocked jump: the §48 mount grind signature).
     *  MOVE-noMove  — forward held on the ground for 10 straight ticks with <0.3 blocks of
     *                 displacement (wall press / trunk snag), hCol attached for cause.
     *  DIG-dropped  — breakHold released while the targeted block is still solid (vanilla
     *                 RESETS break progress on any released tick: the GroundBlip bug class),
     *                 plus DIG-slow when one block stays held 200+ ticks unbroken.
     *  Each alarm is throttled to one line per 40 ticks per class. Default ON. */
    public static volatile boolean walkerExpectAlarm = true;

    /** Bob-immune ascent-ram freeze-breaker trigger: on a steep tall bank (live W→E -861→-632, ~50-70s jank,
     *  reproducible), a +1 {@code diagUp}/{@code stepUp} mount jumps off the diagonal corner, slides back to
     *  the riser foot, and repeats — foot pinned ~0.78 BELOW the node, cur2 orbiting 0.64-0.88 just over the
     *  0.45 reach gate (and at the bank base the drive even flips ~180° backward). The freeze-breaker
     *  {@code stepUpFreeze} that would STOP the pivot + force a grounded straight-at-the-column jump never
     *  fires, because BOTH its triggers are zeroed by the airborne vertical bob: {@code noStepProgressTicks}
     *  (3D closest-approach) takes a new-low at every bob apex (wdy shrinks), and {@code stepRamStuckTicks}
     *  requires {@code onGround()} which the apex defeats. This counter ticks on foot-below-node + laterally
     *  close REGARDLESS of onGround (bob-immune) and ORs into {@code stepUpFreeze} so it engages on time.
     *  <p><b>Default ON</b> — that clean live A/B win landed and flipped it (counter stays 0 while OFF). A clean
     *  fast climb tops out well under the {@code STEPUP_FREEZE_TICKS} bar; water/parkour/far-node are inert. */
    public static volatile boolean walkerAscentRamBobBreak = true;

    /** Early-release the block-less bank-DIG when it is provably FUTILE — an UNREACHABLE OVERHANG
     *  riser a perpetually-buoyant bot can never break or ground on.
     *  <p><b>The bug</b> (live -782/-790 island-pinch, intermittent ~50 s stall): a buoyant bot
     *  floats into a concave notch beside a protruding rock outcrop at the foot of an island bank
     *  (live: foot y62 at (-784,335), riser (-786,65) = <b>+3 above the foot, behind an overhang</b>).
     *  The dig actuator latches that riser and re-aims/swings at it, but the bot NEVER grounds
     *  ({@code onGround=false} for all ~1000 stall ticks) and the bob throws the mining ray off the
     *  +3 face → <b>0 blocks break in 1001 swings</b>. Because {@code waterClimbDigging} sets
     *  {@code breakingEdge=true} (to protect a legit slow stone dig), it SUPPRESSES <i>both</i> recovery
     *  paths — the reactive churn-charge and the anti-stuck back-off burst are gated {@code !breakingEdge}
     *  — so the bot is pinned until the per-riser dig-commit cap ({@code WATER_CLIMB_DIG_COMMIT_CAP=1000})
     *  finally expires ~50 s later and lets the (already-working) back-off burst free it.
     *  <p><b>The fix</b>: when ON, release the latched riser EARLY (latch {@code climbPillarGaveUp},
     *  drop {@code waterClimbDigging} so {@code breakingEdge} falls, penalize the pocket cell) once the
     *  dig has committed to one still-fully-solid riser for {@link Walker#FUTILE_BANK_DIG_TICKS} ticks
     *  WHILE the bot stayed afloat the whole time AND the riser sits {@code >= FUTILE_BANK_DIG_MIN_RISE}
     *  above the foot — the unreachable-overhang signature. The existing reactive charge + anti-stuck
     *  burst then fire ~40 s sooner.
     *  <p>STRICTLY scoped so a LEGIT slow climb-out dig never trips it: the legit toolless climb-out digs
     *  the LOWEST solid cell just above the water line (a +1 reachable notch) and the bot bob-jumps in and
     *  GROUNDS on it — so its riser is +1 (below the {@code >=2} gate) and it touches ground (resets the
     *  never-grounded guard) and its riser BREAKS (resets the tick counter). Only a never-grounded float on
     *  a {@code >=+2} riser that breaks NOTHING for the whole window is released.
     *  <p><b>Default ON</b> — the live A/B this note gated on has landed; flip OFF to A/B against it. Historic note: byte-identical no-op while OFF, which was the pre-flip state, and remains true if you flip it back win
     *  (the parent does the live acceptance). Wired to {@code mc.bot.setting} so a live run can flip it.
     *  GameTest cannot faithfully reproduce the buoyant-dig dynamics (instant-break masks them), so this
     *  MUST be live-A/B'd, not accepted on GT-green. */
    public static volatile boolean walkerFutileBankDigRelease = true;

    /** Block-less bank-dig OVERHANG rejection: when picking a riser to break, require the cell
     *  directly BELOW the chosen riser to be solid — i.e. the riser is a genuine bank-face step
     *  the bob-jump can ground beside, NOT an air-gapped CEILING/overhang.
     *  <p><b>The bug</b> (live -628→-790, 2026-06-27): a buoyant bot floating at a sheer 10-block
     *  cliff base has no climbable +1 step in the forward column; the riser search (which already
     *  finds "lowest solid above the waterline") then latches the column's OVERHANG roof (e.g. a
     *  +4 cell whose floor is air), digs it forever (the floor never appears), yaw-locks into the
     *  cliff and bob-stalls ~10-40 s per spot. A* meanwhile planned a perfectly good pillarUp exit
     *  ~8 blocks along the water — the bot should just swim there, not dig the overhang.
     *  <p><b>The fix</b> restores the invariant the selection comment already states ("a DRY notch
     *  whose floor stays solid") but the code omitted: reject a riser with an air floor. With no
     *  valid +1 riser the dig simply doesn't engage, so the bot swims the committed path to the
     *  real climb-out. A legit staircase-dig tunnels through a SOLID massif (floor always solid),
     *  so it is unaffected.
     *  <p><b>Default ON</b> — validated and flipped; byte-identical no-op if flipped back OFF. Re-A/B on the
     *  -628→-790 water-pocket repro (GT instant-break masks buoyant-dig dynamics), and GT-regressed
     *  for the deep-water-cross staircase-dig before any default flip. */
    public static volatile boolean walkerBankDigSkipOverhang = true;

    /** Buoyant A* search start anchored to the WATER SURFACE instead of the bob-dipping foot.
     *  <p><b>The bug</b> (live -733→-540 deadlock, replay+live evidence 2026-06-27): a bot floating
     *  at a water surface bobs its Y ±0.4 (p.y 61.64↔62.02). The search start is
     *  {@code foot = floor(p.y)}, so a repath fired during a DOWN-bob starts A* one cell UNDER the
     *  surface (y61). A* then prefixes the plan with submerged nodes the buoyant bot can't descend
     *  to; those prefix cells sit BEHIND/below the bot, the current-waypoint never leaves them, and
     *  the climb-out bank-dig follows the waypoint and aims BACKWARD (live: yaw-locked west digging a
     *  stone wall while the goal is east) → the bot bob-stalls and every repath re-derives the same
     *  underwater prefix from the same dipped foot → a permanent churn (replay: 20 repaths, all the
     *  same y61-prefix path).
     *  <p><b>The fix</b>: for a bot floating AT the surface (inWater, !onGround, eye above water),
     *  raise ONLY the search-start cell to the surface (top water cell) so the plan extends FORWARD
     *  from where the body actually floats, never behind/below it. The global {@code foot} used by
     *  actuators/sampling is untouched — only {@code newSearch(foot,…)} sees the lifted cell.
     *  <p><b>Default ON</b> — must be live-A/B'd on the deterministic repro
     *  (tp -733 63 230 + goto -540,320 → bob-stall) before any default flip. */
    public static volatile boolean walkerBuoyantSearchFromSurface = true;

    /** Forward-hemisphere guard for the water climb-out block-less bank-dig riser selection. The
     *  omnidirectional nearest-dry-exit scan (Walker, the {@code sx,sz in -4..4} loop) can pick an
     *  exit BEHIND the bot (opposite the path's current waypoint) and then dig the backward riser —
     *  the live -733/-710 "dig the west wall behind me while the goal is east" deadlock (1000+ digs
     *  at one backward riser, ~48 s frozen, bot reverses tens of blocks). When ON, the exit scan
     *  only considers offsets in the cwp-direction hemisphere (dot(offset, cwp-dir) {@code >= 0}); if
     *  no forward exit exists the cwp-direction fallback still drives a forward dig, so the climb-out
     *  never trenches backward. Independent of (and composable with) {@link #walkerBuoyantSearchFromSurface}
     *  which fixes the search START — a correctly-forward path can still have its exit scan pick a
     *  closer backward exit. Default ON (committed-safe); validate on the deterministic -733 repro. */
    public static volatile boolean walkerBankDigForwardExit = true;

    /** Don't snap an elevated AIR goal DOWN in {@link Walker#snapGoalToStandable} when it is
     *  reachable by PILLARING up (goal cell + head passable, a solid base within a few blocks
     *  below to pillar from, and {@code allowPlace}). The snap was added to settle random goals
     *  that land inside terrain, but it also defeats "pillar up to an elevated goal": the goal
     *  is "unstandable" only because its floor is air, which pillaring creates. Snapping it to
     *  the highest currently-standable cell makes the bot stop 1+ blocks short (live summitArena:
     *  goal y233 snapped to y232, bot arrives at y232, never pillars the last block = "上坡跳不上
     *  高空目标"). When ON, a pillar-reachable elevated goal keeps its real target so A* finds the
     *  pillarUp path; a truly floating void goal (no base below) still snaps. Default ON
     *  (committed-safe); validate on the deterministic summitArena geometry. */
    public static volatile boolean walkerPillarReachGoalNoSnap = true;

    /** Skip the block-less water climb-out bank-dig when the committed path's NEXT node (cwp)
     *  is a WATER cell — the bot should SWIM into it and reach the real climb-out (a stepUp onto
     *  land) further along the path, not dig where it floats. The dig's omnidirectional exit scan
     *  picks the NEAREST dry exit, which on a tall sheer goal-side bank is the perpendicular wall
     *  face (dot≈0, slips past the forward-hemisphere guard {@link #walkerBankDigForwardExit}); the
     *  bot then trenches the goal-side wall, yaw-locks at it, rams it, and bob-stalls forever while
     *  A*'s path swims around to a lower exit (live 2026-06-27 water-bank deadlock @ -646,62,351,
     *  stuck 1181 ticks, cwp=-648 WATER). A genuine climb-out HERE routes cwp to a LAND/stepUp node
     *  (not water) so the dig still fires for it. Default ON (committed-safe); validate via replay
     *  A/B on the archived deadlock before flipping. */
    public static volatile boolean walkerBankDigSkipWhenCwpSwims = true;

    /** Extend the WALK overshoot-advance (crossedWalkNode) to also rescue an overshot
     *  traverseBreak node. A dry traverseBreak (break a block + walk through) is a FLAT
     *  horizontal move just like walk, but the crossedWalkNode nudge is gated move.equals("walk")
     *  only, so when the bot breaks the block and drifts PAST the node centre, cur2 climbs out of
     *  the within-gate (0.45) and neither within nor passed fires → the step freezes and the bot
     *  orbits/creeps until the wedge-repath rescues it (live 2026-06-27 journey -540,300→-700,400
     *  @ -678,68,388: move=traverseBreak, break0 already CLEAR, cur2=2.4 INCREASING, overshoot
     *  1.78b past the node, totStuck ~120-166 ≈ 5-8 s). Fix: let traverseBreak share the same
     *  CONFIRMED-stall overshoot-advance (dot(off,seg)>0 && |Δy|<1.2 && !inWater). Default OFF
     *  (committed-safe; OFF → byte-identical, only "walk" matches as before); validate via live
     *  A/B on the dry mountain crossing before flipping. */
    public static volatile boolean walkerTraverseBreakOvershootResync = false;

    /** Pillar-place FALLBACK for a swimAshore +2 bank that the block-less bank-DIG never engages.
     *  The pillar-takeover (Walker ~L2501) is gated {@code !deepDig}, deferring to the bank-DIG for
     *  buoyant climb-outs. But a swimAshore +2 edge with NO toBreak block never commits a dig
     *  (waterClimbDigging stays false): the swimAshore jump only fires inside the break-loop, so
     *  jump=false and the floating bot bobs against the bank at cur2≈0.64 while neither the pillar
     *  (deepDig-suppressed), the dig (no riser), nor the futile-dig-release (only fires for committed
     *  digs) engages — an 8-45 s churn that occasionally hard-deadlocks (live 2026-06-27 replay-0012
     *  @ -612,420: move=swimAshore node +2, jump=false, hCol, ~4 min frozen in 1/5 runs). When ON,
     *  if the bot HAS a placeable, the dig is NOT engaging (!waterClimbDigging — exactly the
     *  no-toBreak case), and the stall has run PAST the ~4 s dig window (WATER_CLIMB_DIG_STALL), let
     *  the pillar engage DESPITE deepDig so the dirt foothold lifts it onto the +2 bank. Default ON
     *  (byte-identical; the dig still owns every case where it actually swings). Validate via
     *  replay-0012 ×N OFF/ON measuring the -612 churn before flipping. */
    public static volatile boolean walkerSwimAshorePillarDespiteDeepDig = true;

    /** FLOATING +1 water-bank climb-out freeze (live #47 2026-06-28, journey#1 replay-0023 dominant
     *  residual: -646,63 bank ~23.5s churn). A buoyant bot floating at a +1 water bank (node y64) bobs
     *  y62.7(water)↔63.65(air) every 2-3 t, onGround NEVER true, doing stepUp but XZ frozen. ALL three
     *  stepUpFreeze counters miss it: stepRamStuckTicks needs onGround (floating has none), ascentRamBob
     *  needs !isInWater (the bob dips into water and zeroes it every 2-3 t), shallowBankStep needs onGround.
     *  So stepUpFreeze never engages → 23.5 s churn. When ON, a dedicated floatingBankBobTicks counter
     *  accrues on the floating +1 bank (wp.y-foot.y in (0,1.5], !onGround, foot below node, laterally
     *  ramming) IGNORING the in/out-of-water bob, and ORs into stepUpFreeze past a conservative bar
     *  (2×STEPUP_FREEZE_TICKS) so the climb-out repath/pillar engages. Limited to +1 banks (NOT +2, to
     *  avoid the unwinnable-mount over-pin that reverted the bob-immune ascentRam v2). Default ON
     *  (byte-identical). Validate via replay-0023 ×N OFF/ON measuring the -646 jank before flipping. */
    public static volatile boolean walkerFloatingBankBobFreeze = true;

    /** Lateral-bank-follow: when a FLOATING bot rams a water bank (ANY node-Y, including the walk-ram
     *  facet the ascending freeze counters miss) and the ram is sustained past 2×STEPUP_FREEZE_TICKS,
     *  drive a PERPENDICULAR slide ALONG the bank — sign alternating every ~40 ticks — so the body
     *  sweeps to the nearest mountable exit instead of grinding/digging the dead spot. Targets the
     *  NON-DETERMINISTIC dominant residual (replay-proven: same route 0s/0s/33s — buoyant approach
     *  randomly lands on a mountable vs dig-required spot). Forward drive is untouched, so the sweep
     *  mounts the moment it lines up with a climbable lip; self-terminating (climb progress resets the
     *  counter). Default OFF (byte-identical). Validate via the -638,418 reproducible case + journeys. */
    public static volatile boolean walkerFloatingBankFollow = false;

    /** Water climb-out LATERAL gate (task#91, structural). A floating bot engages the bank climb-out
     *  (pillar takeover + block-less bank dig) ONLY when the climb waypoint sits horizontally BESIDE
     *  it ({@link net.magicterra.worlddriver.bot.movement.WalkerConstants#WATER_CLIMB_LATERAL_MAX} cells,
     *  Chebyshev). A higher waypoint that is laterally DISTANT is the routed exit further down an open
     *  corridor, not a bank to climb here: the open-river sheer-bank wedge (riverSheerBank) has the bot
     *  float against a +5 SHEER wall while A* correctly routes the committed exit +5 EAST across open
     *  water to a LOW (+1) bank — but the exit node is +1 higher, so the old {@code cwp.y>foot.y} climb
     *  intent fired and the block-less dig trenched the sheer wall the bot was merely PASSING
     *  (wallPressTicks) instead of swimming the last few cells to the walk-out. Gating the climb on
     *  lateral adjacency lets the swim-drive carry the body along the corridor to the real exit, where
     *  the climb re-arms once adjacent (self-healing). A genuine bank climb-out has its node directly
     *  beside/below the float (Chebyshev 0-1) so it is unaffected.
     *  <p><b>Default ON and deliberately NOT in {@link #applyGameTestBaseline()}'s zero list.</b> This
     *  is a CORRECTNESS invariant (follow the committed path; do not climb a bank that isn't beside
     *  you), not a tunable heuristic — so it must stay active even under the test baseline the water
     *  scenes are pinned to, which is exactly what promotes riverSheerBank from a false-green/wedge to
     *  a genuine climb-out. The 9 sibling water scenes float directly below their banks (adjacent) and
     *  are byte-unchanged. */
    public static volatile boolean walkerWaterClimbLateralGate = true;

    /** Faster anti-churn repath: shorten the net-displacement churn-detection window from 400 ticks
     *  (≈20 s) to 240 (≈12 s) so a path-state churn (planner committed a suboptimal segment the
     *  executor grinds on — dry steep-ascent backtrack, boxed-pinch) arms its escalation/charge sooner,
     *  halving the per-cycle stall. SAFE because the trigger still requires <8 blocks NET XZ in the
     *  window (a healthy walk/swim nets ≫8 blocks even in 12 s) AND the !breakingEdge guard still
     *  protects a legitimate ~750-tick underwater dig from tripping. Default OFF (byte-identical).
     *  Validate via journeys (path-state churns only reproduce in continuous nav, not via tp). */
    public static volatile boolean walkerFasterChurnRepath = false;

    /** Segment anchor-gate exemption for a DEEP-WATER-FLOAT start: accept an otherwise-rejected
     *  continuation whose first node is a FAR node reachable by a straight clear-LOS swim over open water.
     *  <p><b>The bug</b> (live #47, deterministic ~6 s dead-stop): a bot floating in deep open water with a
     *  far open-water goal commits a BEST-EFFORT segment that string-pulls to {@code …[swimUp]·far[walk]}
     *  (an IN-PLACE {@code swimUp} at the foot, then ONE long {@code walk} node tens of blocks ahead). The
     *  eager continuation from that segment's {@code commitEnd} lands while the bot is still back at the
     *  start, so the continuation's {@code path[0]} is that FAR node — tens of blocks from the foot. The
     *  segment anchor-gate (the nearest-prefix-node {@code d2} check in {@link Walker#adoptPath}) finds NO
     *  near forward node (the only intermediate was the in-place {@code swimUp}, in the PREVIOUS segment)
     *  and REJECTS the continuation as {@code mis-anchored} ({@code d2≈2300}). The reject drops the path;
     *  the foot-search returns the SAME {@code swimUp}+far-{@code walk} best-effort; it re-rejects → a
     *  repath storm with {@code hSpd=0}; the bot never commits a forward path and never drives east, and
     *  anti-spin ends best-effort. (Telemetry: {@code reject mis-anchored: -812,62 (d2=2305) vs foot
     *  -860,58}; first segment {@code 0:-860,61 1:-860,62[swimUp] 2:-812,62[walk]}.)
     *  <p><b>The fix</b>: at the anchor-gate, before rejecting on {@code d2 > rejectGate}, accept the
     *  segment when its nearest-prefix-node is reachable by a CLEAR OPEN-WATER bee-line from the foot — the
     *  foot is FLOATING in water, the straight line foot→node is all water/air ({@code losWalkable} AND a
     *  dedicated {@code isOpenWaterLine} no-wall check), and the node is within ±2 Y of the foot (a surface
     *  crossing, never an impossible climb). The buoyant body can swim straight to it, so the carrot
     *  bee-lines toward the far node and the bot makes forward progress (the deep-water-float analog of the
     *  quick-start stub / water bee-line) instead of churning the reject loop.
     *  <p>STRICTLY scoped so it can NOT exempt a truly mis-anchored segment: a fumbled continuation behind
     *  a wall / up a cliff fails the open-water LOS or the ±2 Y band and STILL rejects; a dry (non-floating)
     *  foot is never exempt. Verified by {@code deepWaterFloatBeelineArena} (OFF rejects, ON accepts, a
     *  walled line still rejects, a dry foot still rejects).
     *  <p><b>Default ON</b> — the live A/B this note gated on has landed; flip OFF to A/B against it. Historic note: byte-identical no-op while OFF, which was the pre-flip state, and remains true if you flip it back win
     *  (the parent does the live acceptance). Wired to {@code mc.bot.setting} so a live run can flip it. */
    public static volatile boolean walkerDeepWaterFloatBeeline = true;

    /** Planner gate: forbid a PARKOUR leap whose landing is the SURFACE of a DEEP
     *  floating-water pocket ({@link WorldView#isFloatingWater}: water at the landing
     *  foot AND the cell below it, i.e. ≥2 deep with no floor under the surface).
     *  <p>Every descending/leaping move already refuses a FULLY-submerged landing
     *  ({@code isWater(to) && isWater(to+1)}), but a leap onto the SURFACE of a deep
     *  pocket (head in air, but ≥2 of water below the feet) passes that gate — and a
     *  buoyant body that lands there cannot climb back out: every grounded climb-out
     *  ({@code pillarUp}/{@code stepUp}/{@code diagUp}) gates itself off floating water,
     *  so the only exit is the ~25×-slow toolless underwater bank-dig or a swim to a
     *  gentler shore, costing tens of seconds (live #47 -870: a {@code parkourDescend2d1}
     *  from the dry bank at y63 landed on the y62 surface of a 4-deep pocket; the bot
     *  then bob-stalled ~60-77 s climbing out — and a probe from inside the pocket shows
     *  A* immediately routes BACKWARD to y68, proving the dive is a pure dead-end trap
     *  the committed plan would have BRIDGED over). Forbidding the leap leaves the leap's
     *  cheaper alternatives (continue the bridge-over, or a shallower entry) for A*.
     *  <p>Scoped to PARKOUR leaps only (never necessary — you swim or bridge, you don't
     *  LEAP into deep water), so plain {@code stepDown}/{@code fall}/{@code diagDown}
     *  water entries (which a real river crossing needs) are untouched, and it is a hard
     *  per-move FORBID, not a cost-tax (so it can't inflate the A* node budget the way a
     *  floating climb-out surcharge did — that was reverted). 1-deep shallow water (solid
     *  floor below → {@code isFloatingWater} false) and leaping OVER water onto a dry far
     *  bank are unaffected.
     *  <p>Default ON: live A/B at the #47 -870 lake west-pocket (tp the bot to the dry
     *  bank, goto across the deep SW pocket, repeated) — OFF leaps a {@code parkourDescend2d1}
     *  onto the -866,62 deep-pocket surface and bobs (max totStuck 17, 56-94 inW ticks);
     *  ON routes a {@code stepDown} chain instead (max totStuck 7, 38 inW ticks, fewer total
     *  ticks, ARRIVES every run). GameTest 53/53 required + every water arena (deepWaterCross/
     *  buoyantWall/riverSheerBank/waterLowBank/tallBankDig/waterClimbOutRoute/waterFarAimBankCorner/
     *  vineOverWater/parkourAscend) still ARRIVED with it ON, so deep-water CROSSINGS (which swim,
     *  not parkour-stepping-stone) are unaffected. Flip OFF via {@code mc.bot.setting} if a future
     *  geometry needs a parkour leap into deep water as its only exit. */
    public static volatile boolean pathfinderForbidParkourIntoDeepWater = true;

    /** Sibling of {@link #pathfinderForbidParkourIntoDeepWater}, the TAKEOFF complement: forbid any parkour
     *  LAUNCH from a DEEP floating-water source ({@link WorldView#isFloatingWater}: water at foot AND below).
     *  A floating bot has no floor to push off, so it physically can't sprint-jump a gap — the grounded
     *  climb-out siblings (StepUp/StepUp2/DiagonalAscend/PillarUp/StairUpBreak) ALL already gate
     *  {@code isFloatingWater(from)} for exactly this reason, but the whole Parkour family
     *  (Ascend/2/3/4/2d/3d/Descend/Place) was the gap A* exploited: when the grounded climb-outs are forbidden
     *  at a water edge, A* picks an unguarded parkour leap FROM the floating cell, which the executor can only
     *  bob + fall-back against.
     *  <p>Live #47 J2 (-799,429 → -680,280, 2026-06-26): A* committed a {@code parkourAscend2} takeoff from a
     *  floating water cell at -682,70,308; the buoyant bot couldn't launch it (cur2=3.1, ~2.2-block gap), bobbed
     *  185 ticks (~9s) on that one node with repeated {@code fall2/3/4} re-entries, totStuck 409, ~90s region of
     *  jank — the DOMINANT residual on tractable water terrain (the ascent-ram domain was non-dominant here).
     *  <p>A hard per-move FORBID (not a tax), mirroring the landing guard: the leap is physically unexecutable,
     *  so removing it just routes A* through the swim-to-edge + {@code stepUp}/{@code diagUp} climb-out instead.
     *  1-deep shallow water (solid floor → {@code isFloatingWater} false) is unaffected. Default ON; the gate below predates the flip and describes the
     *  live A/B (the landing-side sibling defaults ON once proven; flip after A/B confirms 409→low + no regression). */
    public static volatile boolean pathfinderForbidParkourFromFloatingWater = true;

    /** Third member of the forbid-parkour-water family (after {@link #pathfinderForbidParkourFromFloatingWater}
     *  takeoff + {@link #pathfinderForbidParkourIntoDeepWater} landing): the GAP DROP-ZONE guard. A rising/flat
     *  parkour leap clears a gap whose floor sits below the launch; if the bot UNDERSHOOTS (the common buoyant /
     *  low-momentum failure) it drops ~1 below launch INTO that gap, and when the drop-zone is DEEP water (≥2:
     *  water at launch-1 AND below) the buoyant body can't climb back out — it bob-stalls against the far wall.
     *  <p>Live #47 J2 -665,64,313 (2026-06-26): A* committed a {@code parkourAscend2} from a y63 launch over an
     *  AIR gap with water below (so the existing launch-level {@code canStandAt}=false "real gap" test and the
     *  isFloatingWater takeoff guard both pass — the takeoff is dry land, the LANDING is a dry y64 node, only the
     *  GAP BOTTOM is water). The bot undershoots to y62 water, hCol on the far wall, hSpd~0.01, onG=false 94%,
     *  ~74t / 3.7s before a repath bounces it around. This is a route-variance repath-bounce chokepoint the
     *  EXECUTOR layer structurally CAN'T A/B-recover (Task-19: jitter-recovery reroutes BEFORE the gap so the
     *  in-water recovery branch never even fires; rerouting is itself unreliable, 1/5 → totStuck 991). The clean
     *  fix is here, at the source: a buoyant bot should SWIM a water crossing, not sprint-jump it, so forbid the
     *  leap whose undershoot strands in deep water and let A* route around / swim. Currently wired into
     *  {@link net.magicterra.worlddriver.bot.pathfinder.moves.ParkourAscend} (the live -665 case); the flat parkour
     *  siblings can adopt the same per-gap-cell check if a flat-gap water-drop stall surfaces. Default OFF
     *  pending the live planner A/B (confirm A* drops the -665 leap + routes around with the flag ON). */
    public static volatile boolean pathfinderForbidParkourOverWaterGap = false;

    /** Approach-runway gate for {@link net.magicterra.worlddriver.bot.pathfinder.moves.ParkourAscend} (the +1-up
     *  cardinal parkour leap): a rising sprint-jump needs horizontal MOMENTUM at launch (~5.6 b/s), but a bot
     *  climbing OUT of a bank reaches the crest via a {@code stepUp}/{@code diagUp} that decelerates it to ~0
     *  b/s, then fires the leap from a standstill → it lands short and falls back down the staircase (live #47
     *  J2 dry crest -682,69,310: no runway behind the lip, 49/82/14 fall-back ticks, totStuck 579 — the
     *  DOMINANT climb-out bob after the floating-water and ascent-ram fixes). When ON, {@code ParkourAscend.valid}
     *  additionally requires the cell directly BEHIND the launch (opposite the leap, same Y) to be
     *  {@code canStandAt} — a flat run-up the bot can accelerate along. A thin-lip crest (staircase drops away
     *  behind → behind cell not standable) is forbidden, so A* substitutes a {@code stepUp} staircase the
     *  executor CAN make from rest; a flat-topped crest (standable behind → real run-up) is unaffected. Per the
     *  diagnosis this is the single highest-leverage lever on the bank-crest ascending-mount class — but it can
     *  SHIFT thrash onto the substitute chain at a true +2 gap with no stepUp alternative, and totStuck is
     *  A*-route-bimodal (unprovable by clean A/B), so validate by committed-plan + video (no-runway
     *  parkourAscend2 gone, smooth stepUp climb-out), NOT totStuck.
     *
     *  <p><b>Default ON (task#86, 2026-07-19).</b> The gap #53 self-shaft dig-up backslide
     *  ({@code wd.selfShaftDigUp}) is the same class of bug on the ASCENT side: a bare-hand
     *  {@code Goal.YLevel} climb pillars a 1-wide free-standing column up beside the slab, and
     *  near the top A* re-plans a {@code parkourAscend2} leap from the pillar TOP onto the slab
     *  (cheaper than 2 more pillars) — but a stationary 1-wide pillar top has no run-up, so the
     *  executor launches into the void and free-falls ~20 blocks straight down its own column
     *  (strideFloorGuard cannot arrest an airborne straight-down fall — there is no face to place
     *  a floor against). The launch cell's below-neighbour is the pillar (so the coarse
     *  {@link net.magicterra.worlddriver.bot.pathfinder.Move#hasRunway} passes); only THIS approach-runway
     *  gate — the cell BEHIND the launch must be {@code canStandAt} — rejects the leap, so A*
     *  substitutes the straight-up pillar and tops out clean. Dogfood A/B (neoforge, byte-identical
     *  ×3): OFF ⇒ {@code worstBackslide=20.252203415101263}; ON ⇒ {@code 1.2522034151012633}
     *  (the normal pillar-jump-arc settle), {@code reached=true}, NO required scene regressed. */
    public static volatile boolean pathfinderParkourAscendNeedRunway = true;

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
     *  {@code WorldDriver} logger. Off by default; toggle via
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
    /** Master gate for path-archive recording (trajectory export/replay). When true the
     *  per-tick WalkerSample is captured even if pathDebug is off. Default off. */
    public static volatile boolean pathArchive = false;

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
     *  {@link net.magicterra.worlddriver.bot.elytra.ElytraPhysics} simulator
     *  tick-by-tick against the live client (predicted vs observed
     *  {@code deltaMovement}) and logs per-tick error plus a summary on stop.
     *  Used to prove the simulator is tick-exact before the reactive controller
     *  is built on it. Off by default; toggle via
     *  {@code mc.bot.setting{elytraDebug:true}}. */
    public static volatile boolean elytraDebug = false;

    /** Keep the game ticking while the BOT is driving and the window loses focus.
     *
     *  <p>Vanilla singleplayer pauses on lost focus, which is right for a human who alt-tabbed
     *  and wrong for a bot mid-task: the world freezes partway through a goto/mine, and the
     *  {@code PauseScreen} it opens sits underneath every subsequent screen assertion — a
     *  screen-close check reads "screen should be null" and finds the pause menu instead.
     *
     *  <p>Scoped exactly like the {@link MouseYield} handshake: the override is applied only
     *  while a process actually owns the tick, and the human's own
     *  {@code options.pauseOnLostFocus} value is handed straight back the moment the bot stops.
     *  A pause menu the human opened deliberately (Esc) is never touched — only the AUTOMATIC
     *  focus-loss pause is suppressed. See {@link FocusPolicy}. */
    public static volatile boolean keepTickingUnfocused = true;

    // ===================== persistence (survives a restart) =====================
    // The "重启游戏需要重新配置" pain: every relaunch reset these volatiles to their
    // defaults, so survival toggles (autoSwim, autoRetreat, …) had to be re-applied
    // by hand each time — and a default-off autoSwim drowned the bot on reload before
    // it could be set. Now a successful mc.bot.setting apply writes every scalar (+ the
    // hazard-block Set) to a properties file via reflection, and the file is reloaded
    // at startup. Reflection means new tunables persist automatically with no per-field
    // code. Opt-in via -Dworlddriver.persistConfig=true (set by the dev runClient/runServer
    // launch, NOT the headless validation/GameTest tasks) so the suites stay
    // deterministic on pure defaults. avoidZones (a transient per-goto double[][]) is
    // intentionally NOT persisted.

    private static Path persistPath() {
        return Path.of("config", "worlddriver_bot.properties");
    }

    /** Persistence is opt-in so headless validation / GameTest never read or write
     *  the file (their setting tests must run on pristine defaults). */
    private static boolean persistEnabled() {
        return Boolean.getBoolean("worlddriver.persistConfig")
                && !Boolean.getBoolean("worlddriver.runValidation");
    }

    /** Suffix of the SHADOW-DEFAULT companion line written next to every persisted
     *  key: {@code <key>.default=<compiled default when the file was saved>}. A '.'
     *  can never collide with a field name (Java identifiers have no dots), so a
     *  {@code .default} line is never mistaken for an unknown field — and legacy
     *  code (which only ever looks up keys by field name) simply ignores it, so a
     *  new-format file stays readable by an older build. */
    private static final String SHADOW_SUFFIX = ".default";

    /** Serialize a field's current value to the persisted string form (the Set is
     *  comma-joined; scalars via {@code String.valueOf}). Returns {@code null} for a
     *  null value so save can skip it. The ONE encoder shared by {@link #save()} and
     *  {@link #COMPILED_DEFAULTS} capture, so a value and its shadow default are
     *  byte-for-byte comparable. */
    private static String serialize(Field f) throws IllegalAccessException {
        Object v = f.get(null);
        if (v == null) return null;
        if (v instanceof Set<?> set) {
            // ADVISORY (task#93 review): Set.of(...) iteration order is SALTED per JVM run,
            // so a NON-EMPTY compiled Set default would serialize differently each boot and
            // false-flag the value-vs-current-default comparisons (stale-snapshot check +
            // legacy drift WARN) into churn. All persistable Set defaults are empty today
            // (SALT-immune, "" either way). If a Set default ever becomes non-empty, sort
            // the elements here (or compare as parsed sets) before relying on text equality.
            StringBuilder sb = new StringBuilder();
            for (Object o : set) { if (sb.length() > 0) sb.append(','); sb.append(o); }
            return sb.toString();
        }
        return String.valueOf(v);
    }

    /** The compiled-in default of every persistable field, captured at class
     *  initialization — which completes BEFORE {@link #load()} (a method call) can
     *  apply any persisted value, so these are the true defaults baked into THIS
     *  build. The shadow-default scheme leans on this map twice: {@link #save()}
     *  writes it as the {@code <key>.default} line, and {@link #load()} re-saves a
     *  file whose stored snapshot no longer matches the current compiled default.
     *  Assigned in a static block at the very END of the class so every field it
     *  reads (all the volatiles above, and {@link #NON_PERSISTED} which
     *  {@link #persistable} consults) is already class-initialized. */
    private static final Map<String, String> COMPILED_DEFAULTS;

    private static Map<String, String> captureCompiledDefaults() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Field f : persistableFields()) {
            try { String s = serialize(f); if (s != null) m.put(f.getName(), s); }
            catch (Exception ignored) { /* own field — cannot happen */ }
        }
        return m;
    }

    /** Write every persistable field to disk in SHADOW-DEFAULT format. Called after
     *  a successful {@code mc.bot.setting} apply (and by {@link #load()} to upgrade a
     *  stale/legacy file). For each field two lines are emitted:
     *  <pre>  &lt;key&gt;=&lt;current value&gt;
     *  &lt;key&gt;.default=&lt;compiled default of THIS build&gt;</pre>
     *  On the next {@link #load()} a key whose value equals its shadow default is a
     *  mere snapshot of the-then default and yields to the current compiled default;
     *  a key whose value differs was set by the user and is preserved. Setting a key
     *  explicitly back to its default therefore counts as following the default (its
     *  value will equal the freshly-written shadow), which is the intended semantics:
     *  "I want the default" and "I never touched it" persist identically.
     *  Best-effort: a failure is logged, not thrown — persistence never blocks. */
    public static synchronized void save() {
        if (!persistEnabled()) return;
        try {
            Properties props = new Properties();
            for (Field f : persistableFields()) {
                String v = serialize(f);
                if (v == null) continue;
                props.setProperty(f.getName(), v);
                String def = COMPILED_DEFAULTS.get(f.getName());
                if (def != null) props.setProperty(f.getName() + SHADOW_SUFFIX, def);
            }
            Path path = persistPath();
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            try (var w = Files.newBufferedWriter(path)) {
                props.store(w, "worlddriver bot settings — auto-saved by mc.bot.setting"
                        + " (<key>.default = compiled default at save time; a key equal to its"
                        + " default is a snapshot and follows the current build's default)");
            }
        } catch (Exception e) {
            LOG.warn("[config] save failed: {}", e.toString());
        }
    }

    /** Reload persisted settings at startup, before any tick reads them, applying
     *  SHADOW-DEFAULT semantics per key. Missing file → defaults stand; a malformed
     *  individual key is skipped (a partial/old file still applies what it can).
     *  <ul>
     *    <li><b>shadow present, value == shadow</b> → SKIP: the persisted value was
     *        just a snapshot of the default when the file was saved, so the current
     *        compiled default wins (this is the fix for the config-persistence trap —
     *        a stale snapshot no longer crushes a later default flip).</li>
     *    <li><b>shadow present, value != shadow</b> → APPLY: the user changed it;
     *        preserve their value.</li>
     *    <li><b>no shadow (legacy file)</b> → APPLY conservatively, then emit ONE
     *        WARN listing the keys that differ from the current compiled default, and
     *        upgrade-re-save the file in shadow format.</li>
     *  </ul>
     *  {@code .default} lines are looked up deliberately as each key's companion; the
     *  loop iterates the field set (never the raw property keys), so a shadow line is
     *  never treated as an unknown field. A stale snapshot (SKIP where the stored
     *  value no longer equals the current compiled default) also triggers an upgrade
     *  re-save so the file reflects the winning default. Any re-save is best-effort —
     *  an IO failure is logged, never fatal to startup. */
    public static synchronized void load() {
        if (!persistEnabled()) return;
        try {
            Path path = persistPath();
            if (!Files.exists(path)) return;
            Properties props = new Properties();
            try (var r = Files.newBufferedReader(path)) { props.load(r); }
            int applied = 0, skipped = 0;
            boolean needsUpgrade = false;          // any legacy key, or any stale snapshot
            List<String> legacyDrift = new ArrayList<>();
            for (Field f : persistableFields()) {
                String key = f.getName();
                String s = props.getProperty(key);
                if (s == null) continue;
                String shadow = props.getProperty(key + SHADOW_SUFFIX);
                String curDefault = COMPILED_DEFAULTS.get(key);
                if (shadow == null) {
                    // Legacy line (no shadow): keep the user's value conservatively.
                    try {
                        assign(f, s); applied++;
                        if (curDefault != null && !s.trim().equals(curDefault.trim())) legacyDrift.add(key);
                    } catch (Exception ex) { LOG.warn("[config] skip {}: {}", key, ex.toString()); }
                    needsUpgrade = true;
                } else if (s.trim().equals(shadow.trim())) {
                    // Snapshot of the-then default → the current compiled default wins.
                    skipped++;
                    if (curDefault != null && !s.trim().equals(curDefault.trim())) needsUpgrade = true;
                } else {
                    // User set it away from the saved default → preserve.
                    try { assign(f, s); applied++; }
                    catch (Exception ex) { LOG.warn("[config] skip {}: {}", key, ex.toString()); }
                }
            }
            LOG.info("[config] loaded {} persisted bot setting(s) ({} snapshot key(s) followed current default) from {}",
                    applied, skipped, path.toAbsolutePath());
            if (!legacyDrift.isEmpty()) {
                // NOTE the permanence: the upgrade re-save stamps each legacy drift key with
                // shadow = CURRENT compiled default while keeping its stale value, so from then
                // on value != shadow and the key is treated as user-set FOREVER. Only files
                // written by shadow-aware code get true snapshot-follows-default semantics;
                // this WARN list is the operator's one chance to spot and hand-fix stale keys.
                LOG.warn("[config] legacy config file (no shadow defaults) — applied as-is;"
                        + " {} key(s) differ from this build's compiled default and were preserved"
                        + " (they will remain user-set after the upgrade): {}."
                        + " Upgrading file to shadow-default format.", legacyDrift.size(), legacyDrift);
            }
            if (needsUpgrade) save();   // best-effort upgrade re-save (IO failure logged, not fatal)
        } catch (Exception e) {
            LOG.warn("[config] load failed: {}", e.toString());
        }
    }

    /** Reset every persistable field to THIS build's compiled default — the live
     *  truth stack. StageWright scenes whose contract is live behaviour (not the §78
     *  legacy arena baseline) call this right after {@link #pinnedBaseline()}: the
     *  pin still restores the pre-scene state on close; this only changes what the
     *  scene body runs. Scenes must re-assert any flag they need OFF (allowBreak /
     *  allowPlace default ON live). */
    public static synchronized void applyCompiledDefaults() {
        for (Field f : persistableFields()) {
            String s = COMPILED_DEFAULTS.get(f.getName());
            if (s == null) continue;
            try { assign(f, s); }
            catch (Exception ex) { LOG.warn("[config] default reset skip {}: {}", f.getName(), ex.toString()); }
        }
    }

    /** Fields excluded from persistence even though their type is persistable:
     *  pure RUNTIME state that must NOT survive a restart. {@code fleeActive} is
     *  a per-frame flee-context flag (set true by RunAwayProcess.tick, reset each
     *  clientTick) — if saved it would reload {@code true} and wrongly boost every
     *  goto's terrain cost. Keep this in sync with any other transient scalar. */
    private static final Set<String> NON_PERSISTED = Set.of("fleeActive", "walkerDigActive", "pathfinderBoxedEscalate");

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

    /** The {@link #persistable} fields, in declaration order — the ONE
     *  enumeration save/load/snapshotAll/restoreAll all iterate, so the
     *  persisted set and the snapshot set can't drift apart. */
    private static List<Field> persistableFields() {
        List<Field> out = new ArrayList<>();
        for (Field f : BotConfig.class.getDeclaredFields()) {
            if (persistable(f)) out.add(f);
        }
        return out;
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

    /** Snapshot every mutable config key (the {@link #persistable} set — all
     *  walker/pathfinder flags included). Rig guard for tests that flip config
     *  beyond a handful of named keys — especially anything calling
     *  {@link #applyGameTestBaseline} mid-suite: pair with {@link #restoreAll}
     *  in a finally so a {@code /test} run on an integrated server can't leak
     *  the legacy-OFF baseline into the live bot. */
    public static synchronized Map<String, Object> snapshotAll() {
        Map<String, Object> snap = new LinkedHashMap<>();
        try {
            for (Field f : persistableFields()) {
                snap.put(f.getName(), f.get(null));
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);   // own accessible fields — cannot happen
        }
        return snap;
    }

    /** Restore a {@link #snapshotAll} snapshot verbatim. */
    public static synchronized void restoreAll(Map<String, Object> snap) {
        try {
            for (Field f : persistableFields()) {
                if (snap.containsKey(f.getName())) {
                    f.set(null, snap.get(f.getName()));
                }
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Closeable snapshot+restore pair — {@code close()} is {@link #restoreAll}. */
    public interface ConfigPin extends AutoCloseable {
        @Override void close();
    }

    /** The gametest rig template in one unlosable piece: snapshot EVERY mutable
     *  config key, re-pin {@link #applyGameTestBaseline}, and hand back the
     *  restore as an {@link AutoCloseable} —
     *  <pre>try (var pin = BotConfig.pinnedBaseline()) { … arena body … }</pre>
     *  Arenas whose metrics are config-sensitive must use this instead of
     *  hand-rolling the three steps: restoring only a few named keys after
     *  flipping the whole baseline is exactly the leak that made
     *  descentYawArena "flaky" for sixty days (a {@code /test} run on an
     *  integrated server left the live bot on the legacy-OFF baseline). */
    public static ConfigPin pinnedBaseline() {
        Map<String, Object> snap = snapshotAll();
        applyGameTestBaseline();
        return () -> restoreAll(snap);
    }

    /** GameTest legacy baseline (§78): the arena suite's assertions were authored
     *  against the historical default-OFF flag set; when the #47-validated combo was
     *  flipped to default-ON for live play, 14 required arenas broke because flags the
     *  tests never touch (allowBreak/allowPlace/DrowningEscape/...) changed the bot's
     *  behavior mid-arena. The stagewright dogfood server calls this once at server
     *  start under {@code -Dstagewright.autorun} (WorldDriverNeoForge / WorldDriverFabric,
     *  gated on {@code TESTKIT_AUTORUN}) to pin the suite back to the baseline the
     *  scenes were written for; scenes that WANT a flag still set it explicitly. Live
     *  clients (integrated server, autorun unset) never call this. (The GameTestServer
     *  delivery this note once described was retired in P4-final; the baseline pin
     *  survives, now driven by the testkit-autorun path.) */
    public static void applyGameTestBaseline() {
        allowBreak = false;
        allowPlace = false;
        allowWaterBucketFall = false;
        walkerStepUpBackoffRetry = false;
        walkerCarrotBodyLos = false;
        walkerBankDigGroundBlip = false;
        walkerExpectAlarm = false;
        walkerStuckStepMonotonic = false;
        walkerPillarSurfacePlace = false;
        walkerAboveNodeStallRecover = false;
        walkerDigAimPriority = false;
        walkerWallDigFallback = false;
        pathfinderBreakCostMultiplier = 1.0;
        walkerDryReanchor = false;
        walkerBuoyantSearchFromSurface = false;
        walkerBankDigSkipWhenCwpSwims = false;
        walkerBankDigSkipOverhang = false;
        walkerVineDescentDrop = false;
        walkerAscentRamBobBreak = false;
        walkerPillarReachGoalNoSnap = false;
        pathfinderForbidParkourFromFloatingWater = false;
        walkerDeepWaterFloatBeeline = false;
        walkerWaterWalkReach = false;
        walkerWaterStepDownFloat = false;
        walkerWallCornerFastChurn = false;
        walkerSwimAshorePillarDespiteDeepDig = false;
        walkerFutileBankDigRelease = false;
        walkerBankDigForwardExit = false;
        walkerFloatingBankBobFreeze = false;
        walkerDrowningEscape = false;
        walkerClimbGaveUpSticky = false;
        // §87 second flip wave (C100+C101 double-green endorsement)
        walkerRouteHysteresis = false;
        walkerDigCommitHoldRepath = false;
        walkerRamNodeAimRelease = false;
        walkerPhysicalStallClock = false;
        walkerBridgeHoldRepath = false;
        pathfinderFloatingBreakTax = false;
        pathfinderLogBreakTax = 1.0;
    }

    // Capture the compiled-in defaults LAST — after every persistable volatile and
    // NON_PERSISTED are initialized, and (being class init) before load() runs.
    static { COMPILED_DEFAULTS = captureCompiledDefaults(); }
}
