package net.magicterra.agent.bot.movement;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.debug.BotLevelHolder;
import net.magicterra.agent.bot.movement.PathSmoothing.SmoothResult;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.PathFinder;
import net.magicterra.agent.bot.pathfinder.PathTrace;
import net.magicterra.agent.bot.pathfinder.PathTraceHolder;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.bot.world.SurvivalMath;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

import static net.magicterra.agent.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.agent.bot.movement.PathSmoothing.*;
import static net.magicterra.agent.bot.util.BotInteract.*;
import static net.magicterra.agent.bot.util.BotUtil.*;
import java.util.Locale;
import static net.magicterra.agent.AgentDriverCommon.LOG;

public final class Walker {
    private static final int CARROT_MAX_NODES = 10;
    private static final double CARROT_DIST = 4.0;
    /** Horizontal aim vector (blocks²) below which the heading is HELD instead of
     *  recomputed from atan2. During a vertical maneuver (stepUp/stepDown/pillar/
     *  fall) the bot sits almost directly over its target column, so adx/adz hover
     *  near zero and atan2 on that sub-block noise snaps the yaw between +90 and -90
     *  every tick — a 180° forward/backward thrash that also stalls forward thrust.
     *  A half-block (0.5) dead-zone keeps the last good heading until the bot is far
     *  enough from the column for the bearing to be meaningful again. Far larger than
     *  the old 1e-4 epsilon (which only caught standing-exactly-on-the-point). */
    private static final double YAW_DEADZONE_SQ = 0.25;
    /** Hard per-tick cap (degrees) on how far the commanded body yaw may turn during
     *  normal ground walking — independent of the cosmetic {@code smoothLook}. A single
     *  degenerate aim vector (reCentre pointing back at the previous node, atan2 on a
     *  near-zero vector, a fall's transient carrot) can otherwise snap the heading 180°
     *  in one tick — the "朝向反复跳变" the pathfinding charts surfaced. Capping the slew
     *  turns any such transient into a small wobble the next (correct) tick undoes, so
     *  the body holds its forward line; a stuck/reCentre limit-cycle can no longer spin
     *  it, so forward progress resumes and the bot escapes the stall. 30°/tick = 600°/s,
     *  far faster than any real turn needs, so legitimate corners are unaffected.
     *  Launches (parkour / MLG fall) bypass this and snap — you can't steer mid-air. */
    private static final float WALKER_MAX_YAW_SLEW_DEG = 30f;
    /** EMA factor for low-passing the TARGET heading. A near-45° goal makes A* emit a
     *  grid STAIRCASE whose immediate-waypoint bearing alternates ±~30° around the true
     *  diagonal every step; chasing that raw target (even clamped) leaves a bounded
     *  sawtooth. Averaging the target with this factor collapses the alternation to a
     *  steady bearing (a 20°/50° square wave settles to ~35° ±5° at 0.5) while still
     *  converging on a genuine turn in a few ticks. Launches bypass it (must snap). */
    private static final float YAW_SMOOTH_ALPHA = 0.5f;
    /** Heading tolerance for committing to a +1 step climb. A step is climbed by
     *  walking INTO the riser then jumping ONTO it, so the body must already FACE
     *  the step — if it arrived off the climb column or after a sharp path turn the
     *  bearing can be 90-150° off, and since smoothLook only turns
     *  {@link #WALKER_MAX_YAW_SLEW_DEG}°/tick the forward key would RAM the riser
     *  face (horizontalCollision, hSpd≈0) and the jump fire the wrong way for the
     *  several ticks it pivots — the bot bob-jams in place (measured: 36% of a
     *  jungle-hill climb was collision-stalled, sprint effective only 1% of ticks).
     *  While the step is still mis-aimed beyond this tolerance we PIVOT in place
     *  (cut forward + jump) so the body turns cleanly to face the step, then climbs. */
    private static final float STEPUP_AIM_TOLERANCE_DEG = 40f;
    public enum Step { WALKING, ARRIVED, FAILED }
    private static final double REACH_DIST_SQ = 0.45;
    private static final int STUCK_TICKS = 60;
    /** Ticks a buoyant body may stay pinned ABOVE an in-water below-node before the
     *  step-advance gate surface-crosses past it. A* routes a deep-water crossing
     *  along the riverbed (nodes 1-2 below the floating foot); the executor can't
     *  sink onto them — a swimDown dive's buoyancy refuses the sink and a non-dive
     *  below-node has its float-over disabled the instant the eyes bob under the
     *  waterline (isUnderWater flickers true) — so without this the bot pins at the
     *  node's XZ forever (live 2026-06-15 deep-water arena: node 1.4 below, 2600+
     *  ticks at step 1). Short (≈1.2 s) so a legitimate in-progress dive — which
     *  keeps closing the Y gap and advancing — never trips it. */
    private static final int WATER_DESCEND_GIVEUP = 25;
    /** Horizontal cur2 (blocks²) beyond which the foot has clearly gone PAST a node
     *  rather than approaching its climb base. The pure-pursuit re-sync's two
     *  climb-base guards — the strict '<' that refuses to skip a pillarUp/swimUp node
     *  stacked directly above the current one (ndx²+ndz² ties cur2), and the in-water
     *  "don't skip a buoyant +1 climb" gate — exist to stop a NEAR bot jumping a
     *  stacked climb node. When the foot has drifted this far beyond the node (force-
     *  displacement, or a slow far-goal repath that lands a stale path tail behind the
     *  bot), those guards instead FROZE `step` for 1000+ ticks while anti-stuck crabbed
     *  the bot on — minutes of camera thrash (live 2026-06-15 wide-water crossing:
     *  drifted 5.5 blk past an in-water walk node whose next node was a swimUp directly
     *  above → tie rejected the skip). Past this margin the guards relax so the re-sync
     *  fast-forwards to the node nearest the drifted foot. 4.0 = 2 blocks: comfortably
     *  past the ≈1.7-block pillarUp-base approach the strict '<' must still protect. */
    private static final double OVERSHOOT_RESYNC_SQ = 4.0;
    /** Jitter-immune wedge timer: max ticks the bot may dwell on the SAME path step
     *  before forcing a re-path (and blacklisting that node). Unlike {@link #stuckTicks}
     *  (progress-based — a bob/creep that finds a fractionally-closer approach each tick
     *  keeps resetting it to ≈0), this counts RAW ticks-on-step, so it catches a node the
     *  Walker physically cannot complete: e.g. a fallN whose drop is blocked by ground
     *  (the bot sits at the node's XZ with |Δy| stuck above the 1.2 reach gate, micro-
     *  jittering at hSpd≈0.03 so stuckTicks≈0). Such a node otherwise deadlocks FOREVER —
     *  a best-effort path takes no periodic repath, and stuckTicks≈0 fires no safety
     *  repath. Generous (5 s) so genuinely slow legit moves (water creep, pillar climb)
     *  finish well within it; bridge edges are excluded (they hard-zero progress timers). */
    private static final int WEDGE_TICKS = 100;
    /** A dry stepUp / diagUp that has dwelt this many ticks WITHOUT closing on its node
     *  while grounded and laterally close to the step column is ramming the riser (the
     *  cur2≈0.64 freeze: pivotForStepUp keeps cutting forward on the noisy close-node
     *  bearing and the jump never clears). After this many stalled ticks, STOP pivoting
     *  and force a grounded jump straight at the column. ~1.2 s: a normal stepUp closes
     *  in <12 ticks so it never trips, yet this breaks a freeze far sooner than the ~6 s
     *  anti-stuck burst (which yanks the bot BACKWARD off the very step it needs). */
    private static final int STEPUP_FREEZE_TICKS = 24;
    /** Ticks an in-place pillar-up recovery is latched once armed — long enough for a
     *  jump's airborne arc to crest and place a support (vanilla peak ~tick 6-8), short
     *  enough that it re-evaluates promptly. Re-armed each grounded tick while the bot is
     *  still below the next node beyond jump reach (see overJump). */
    private static final int PILLAR_RECOVER_TICKS = 14;
    /** Anti-spin: consecutive in-water repaths with no goal-progress before the camera
     *  heading is FROZEN. A failed water climb-out makes every repath return a
     *  swim-back/circle best-effort; following each one U-turns the bot and the
     *  repeated U-turns wind the camera (the water "转圈"). Past this count the heading
     *  is held steady (see the heading block) — MC movement follows body yaw, so a
     *  frozen heading also steadies the bot pressing toward the climb-out instead of
     *  whipping around. Small so the spin is killed within ~1-2s of churn. */
    private static final int CHURN_REPATH_CAP = 3;
    /** Anti-spin: consecutive in-water repaths with no goal-progress before the goto
     *  gives up best-effort (ARRIVED) rather than pressing a wall forever. Well above
     *  {@link #CHURN_REPATH_CAP} so the freeze gets a fair chance to let the bot grind
     *  through a hard climb-out before we conclude it's truly walled. */
    private static final int CHURN_GIVEUP_CAP = 12;
    /** Fresh anti-spin baselines granted after a water stall before truly giving up —
     *  a legitimate go-around (e.g. rounding a lava arm via the river) raises the
     *  goal-distance for a dozen repaths and must not end the journey; only a churn
     *  that stalls through ALL fresh baselines is the unwinnable spin. */
    private static final int CHURN_MAX_RESETS = 3;
    /** DRY-LAND boxed-pocket churn detector — a fixed time window over which NET XZ
     *  displacement is measured. A per-repath "no goal-progress" counter is defeated
     *  by the pocket's limit-cycle: the bot pillars up a wall to an XZ-closer column
     *  (which RESETS a goal-distance counter), then falls back, looping 1416↔1454
     *  forever with net-zero ground travel (live round73). Net displacement over a
     *  window is immune to that oscillation. CHURN_WINDOW≈20 s, CHURN_MIN_MOVE_SQ =
     *  (8 blocks)²: a real journey clears 8 blocks/20 s even on hard terrain; a pocket
     *  churn does not. */
    private static final int CHURN_WINDOW = 400;
    private static final int CHURN_MIN_MOVE_SQ = 64;
    /** Bounded fresh re-searches at a loaded-chunk frontier before giving up (the
     *  bot is stationary while waiting, so a couple of tries is plenty — see
     *  {@link #frontierHoldOrArrive}). */
    private static final int FRONTIER_WAIT_CAP = 3;
    /** Ticks between quick-start stub attempts after one finds nothing useful —
     *  a boxed-in micro-search keeps finding nothing until the terrain context
     *  changes, so retrying every tick would just burn frames. */
    private static final int QUICK_RETRY_TICKS = 10;
    /** Hard wall-clock cap for one synchronous quick-start stub search; the node
     *  cap ({@link BotConfig#pathfinderQuickNodes}) normally lands well under it. */
    private static final long QUICK_MAX_MS = 80;
    /** Open-water bee-line stub length: how many surface cells to march toward the
     *  goal when the sliced A* can't keep up over deep water. Long enough that the
     *  bot (sprint-swim ≈5.6 b/s) has multiple seconds of runway before consuming it,
     *  so it never outruns its own pathfinding and burst-crabs. */
    private static final int BEELINE_MAX_STEPS = 24;
    /** Don't bother adopting a bee-line shorter than this (a 1-3 cell run is just the
     *  bot already at a bank — let the normal search/climb-out own it). */
    private static final int BEELINE_MIN_STEPS = 4;
    /** Foot-to-current-node dist² (blocks²) past which a WATER-wedged bot is treated as
     *  having sprint-swum PAST its committed segment (vs pinned AT a node it can't
     *  reach) — the trigger to drop the stale segment and adopt an open-water bee-line.
     *  16 = 4 blocks: well past the 0.45 reach gate, comfortably short of the 30-block
     *  overshoot the burst-crab leaves. */
    private static final double BEELINE_OVERSHOOT_SQ = 16.0;
    /** Max ticks to hold a "no path" verdict while self-inflicted stuck-penalties
     *  decay (15 s window) before genuinely failing — three full decay windows. */
    private static final int NO_PATH_WAIT_CAP = 900;
    /** Max blocks BELOW a still-valid route from which the fall-below recovery
     *  pillars back up instead of re-searching (a deeper fall is a real
     *  divergence — the route above is likely stale). */
    private static final int PILLAR_RECOVER_MAX_DY = 8;
    /** Min reduction in distance² (blocks²) to the current node that counts as real
     *  forward progress for the {@link #stuckTicks} no-progress timer. Set above the
     *  sub-0.1 b/tick position jitter of a treading / water-creeping bot but below a
     *  normal sprint step, so slow-but-steady advance (esp. ~0.08 b/tick in water)
     *  keeps resetting the timer and never trips the reCentre / wiggle recovery. */
    private static final double STUCK_PROGRESS_EPS = 0.02;
    /** Per-step ticks of bob-stalling before the water climb-out actuator places
     *  a foothold to ground a floating bot against a too-high bank. Keyed off the
     *  per-step no-progress timer ({@code totalTicks}, which a bob can't reset —
     *  unlike {@code stuckTicks}, which the oscillating foot-Y clears each cycle).
     *  Well under {@code walkerTotalTickBudget}, comfortably past a normal flush /
     *  staircase climb-out (grounds in <10 ticks, never stalls). */
    private static final int WATER_CLIMB_STALL = 30;
    /** Stall threshold for the LAST-RESORT block-less bank DIG (vs the with-block
     *  pillar takeover at {@link #WATER_CLIMB_STALL}). Much higher so the dig is a
     *  genuine deadlock-breaker, not a first response: a buoyant climb-out that the
     *  bob or an anti-stuck burst resolves within a few seconds must NOT trip it, or
     *  in a long water canyon (every far bank a climb-out) the no-block bot turns
     *  into a compulsive digger — live journey fired it 1520× across ~6 min, gouging
     *  terrain + thrashing the climb/dig aim into camera judder, while burst-crab
     *  alone would have crossed many of those banks. Only a bank still un-mounted
     *  after ~4 s (bob + burst both failed) is a real wedge worth digging. */
    private static final int WATER_CLIMB_DIG_STALL = 80;
    /** Chebyshev radius searched around an UNSTANDABLE Goal.Block target for the nearest
     *  standable cell to snap to (see {@link #snapGoalToStandable}). 6 covers a goal a few
     *  blocks inside a hill / under a thin ceiling while staying cheap (one (2R+1)^3 scan
     *  per goal). Too small misses deeper burials; too large risks snapping past a wall to
     *  an unrelated pocket — A* still has to reach it, so an unreachable snap just fails as
     *  before, no worse than no snap. */
    private static final int GOAL_SNAP_RADIUS = 6;
    /** Fast dig-engage threshold when the bot is FLOATING over deep water (water directly
     *  below the foot). There a buoyant bot physically cannot swim-jump a +1 bank — the
     *  dig is the ONLY exit — so there is no point bob-stalling the full {@link
     *  #WATER_CLIMB_DIG_STALL} (~4 s) first; engage in ~1 s. Still requires a solid riser
     *  being rammed in a climb-out context, so open-water cruise (no adjacent solid bank)
     *  never trips it. Shallow water keeps the slow threshold (a swim-jump may still mount
     *  a low bank, so give it the benefit first). Cuts the dominant per-bank stall: the
     *  deep-water climb-out arena dropped its 80-tick wait, ashoreTick 98→~40. */
    private static final int WATER_CLIMB_DIG_DEEP_STALL = 20;
    /** Ticks the pillar takeover may bob WITHOUT a successful place before it's judged
     *  futile here (a buoyant bot can't lift its feet above a surface fill cell) and the
     *  bank-DIG takes over. ~50 ticks past the WATER_CLIMB_STALL engage ≈ the same ~4 s
     *  last-resort window as WATER_CLIMB_DIG_STALL, so place-banks and dig-banks converge
     *  on the dig at the same patience. */
    private static final int PILLAR_FUTILE_TICKS = 50;
    /** Grace ticks the "in a water climb-out" state stays LATCHED after the last
     *  water contact. A bob-cycling climb-out breaches the surface every cycle (head
     *  clears water, feet top a just-placed foothold), so the per-tick water test
     *  FLICKERS false at each bob peak; without this grace the stall counter reset on
     *  every flicker and the pillar takeover engaged only after 1-2 MINUTES of bobbing
     *  (live round71). 12 ticks (0.6 s) spans a bob peak without masking a genuine
     *  walk-away onto dry land. */
    private static final int WATER_TOUCH_STICKY = 12;
    /** Grace ticks the "needs to climb out" intent stays LATCHED after the last
     *  tick a higher node sat ahead — spans the bob-peak {@code wantClimb} flicker
     *  (at a bob peak the foot BLOCK rises to the stepUp target Y, so
     *  {@code cwp.y > foot.y} briefly goes false) and the brief {@code edge==null}
     *  gap mid-repath. Both otherwise reset the in-context stall count and kept the
     *  pillar takeover from ever arming (live round76: 461 stairUpBreak/245 stepUp
     *  thrash, 0 takeovers, bob-stalled 5+ min). A HEIGHT-based "made progress" reset
     *  can't substitute: the buoyant deep-water bob (~1.5 blocks) exceeds any sane
     *  net-rise threshold, so the bob itself trips it (round76b: a 1-block net window
     *  still armed 0 takeovers). Pure in-context tick-count is the robust signal —
     *  a real climb-out LEAVES the context within ~10 ticks (grounds on the bank →
     *  no higher node ahead → wantClimb false), so only a true bob-stall accumulates. */
    private static final int WANT_CLIMB_STICKY = 12;
    /** Ticks after the jump press before placing the pillar block beneath —
     *  by then the player has cleared the old feet cell (matches TowerProcess). */
    private static final int PILLAR_PLACE_DELAY = 3;
    // Water-bucket (MLG) fall handling lives in the always-on {@link
    // ClutchController} (BotApiImpl#CLUTCH), so an unplanned fall self-rescues
    // whether or not a Walker is driving. The Walker only ARMS a planned fall
    // (CLUTCH.armPlanned) as it steps off a fallBucket lip and biases the
    // step-off keys (walk-speed, no sprint) so the body drops near-vertically.
    // Other tuning lives in BotConfig (mutable via mc.bot.setting).

    /** Snapshot of the most recent {@code findPath()} result across ALL
     *  Walkers (volatile so cross-thread reads in {@code mc.bot.status} are
     *  consistent). Surfaced under {@code status.lastPath} for debugging
     *  pathing failures — Baritone exposes the same via {@code path}/{@code stats}. */
    public record PathStats(int expanded, long ms, boolean goalReached, double finalCost, int pathLen) {}
    public static volatile PathStats lastStats;

    private Goal goal;
    private boolean goalSnapChecked;   // one-shot per goal: snap an unstandable Goal.Block target to the nearest standable cell (see snapGoalToStandable) — needs a live WorldView so it runs on the first tick, not at setGoal
    private List<BlockPos> path;
    private List<Move.Edge> edges;   // aligned with path; edges.get(i) enters path.get(i)
    private int step;
    private int ticksSinceRepath;
    private int stuckTicks;
    private int totalTicks;
    private int actionTicks;          // ticks spent on the current break/place edge
    private int pillarStep = -1;      // path index of the pillar edge in progress
    private int pillarSinceJump = -1; // ticks since the pillar jump press (-1 = grounded)
    private int waterClimbStall;      // armed flag (>WATER_CLIMB_STALL) once net-displacement window shows a bob-stall climbing out of water
    private int waterTouchRecent;     // sticky countdown: >0 while in a water climb-out, kept latched through bob-peak surface breaches (see WATER_TOUCH_STICKY)
    private int wantClimbRecent;      // sticky countdown: >0 while a higher node sits ahead, latched through bob-peak/repath flicker (see WANT_CLIMB_STICKY)
    private boolean waterClimbPillaring;   // latched: pillaring up the bot's column to bank stand level
    private boolean waterClimbDigging;     // set the tick the block-less bank-dig actuator swings; OR'd into breakingEdge next tick so the anti-stuck burst can't yank the bot off the riser mid-dig (it has no planned toBreak edge of its own)
    private BlockPos lastDigRiser;         // the riser the dig last aimed at; re-snap the look ONLY when it changes (not every tick) so the camera holds steady instead of juddering off the bobbing eye — the bob keeps the crosshair on the 1-tall block between re-aims
    private BlockPos waterClimbDigRiser;   // LATCHED bank-dig riser cell — held while still solid so a buoyant bob (foot.y flickering ±1) or lateral drift (foot.z wandering) can't re-target a LOWER block of the same column or a neighbouring column mid-dig; cleared once the block breaks so the next +1 step is chosen fresh (drift-arena over-dig fix)
    private boolean climbPillarGaveUp;     // latched once the pillar takeover proves futile (drifted off its locked column, or bob peak never clears the surface fill cell) → block pillar re-engage + let the bank-DIG take over even with a place block in hand; cleared when the climb context ends
    private int pillarNoPlaceTicks;        // ticks the pillar takeover has been engaged without a successful place / height gain — buoyant bob can't lift feet above a surface fill cell, so beyond PILLAR_FUTILE_TICKS the place is hopeless and we fall to the dig
    private int waterClimbTargetY;         // safety ceiling Y for the pillar (engage foot + a few); bail if exceeded
    private int waterClimbColX, waterClimbColZ; // LOCKED column the takeover pillars in (don't chase repathing nodes)
    private float waterClimbYaw;           // LOCKED heading toward the bank at engage (no horizontal chase → no wander)
    private int diveLatch;            // ticks left forcing a dive-under-cap (set on a blocked submerged descent; holds the dive through the sink so it doesn't flip-flop)
    private int diveHold;             // ticks left holding an ACTIVE descent (diving) across repaths — a mid-sink repath re-plans from the buoyancy point with a dy=1 first hop, which alone never re-arms diving, so the bot pops back up (round45 water-well live)
    private int pillarRecoverLatch;   // ticks left driving an in-place pillar-up recovery (bot fell below the climb path beyond jump reach) — latched across the jump's airborne phase so a place can land
    private BlockPos pillarRecoverCell; // the (grounded) feet cell the recovery is filling this rung
    private boolean descending;       // ending creative flight; wait to land before pathing
    private PathFinder.Search activeSearch;  // in-flight time-sliced A* (null = none)
    private double bestDistToGoal = Double.POSITIVE_INFINITY;
    private double bestGoalDist = Double.POSITIVE_INFINITY;  // anti-spin: best goal-estimate across repaths (5-block margin ignores micro-lunges)
    private int repathsNoProgress;                           // anti-spin: consecutive in-water repaths that didn't improve bestGoalDist
    private int churnResets;                                 // anti-spin: fresh baselines granted after a stall (tolerates water go-arounds; real progress clears it)
    private BlockPos churnBase;                              // land boxed-pocket: foot at the start of the current net-displacement window
    private int churnWindowTicks;                            // land boxed-pocket: ticks elapsed in the current window
    private int churnEscapes;                                // land boxed-pocket: consecutive windows that detected churn (escalates the charge radius)
    private double bestStepDist = Double.POSITIVE_INFINITY; // closest approach² to the current node (drives the progress-based stuckTicks)
    private int stuckStep = -1;                             // path index bestStepDist tracks; a step change starts a fresh progress window
    private int noStepProgressTicks;                        // jitter-immune ticks on the SAME step (resets only when step advances/path changes) → wedge detector
    private int underwaterTicks;                            // consecutive eyes-under ticks → debounces the swim-up jump (surface bob ≠ sinking)
    private int noProgressStep = -1;                        // path index noStepProgressTicks tracks (independent of bridge/progress resets)
    private double noProgressBestD2 = Double.POSITIVE_INFINITY; // closest-ever approach² to the tracked step; monotonic, so a bob can't reset the wedge timer but a slow water cruise along a long string-pulled edge does
    private boolean searchSuppressedPlace;                  // the in-flight search dropped placing moves (block-budget reroute) → adopt its result without re-checking
    private float smoothTargetYaw = Float.NaN;              // EMA-low-passed target heading (NaN = uninitialised; resync on launch/new goal)
    private boolean pathBestEffort;                         // current path is a best-effort partial (goal NOT reached) → commit to it before re-searching
    private BlockPos commitEnd;                             // last node of the current best-effort segment (null for a full path) → where continuation searches launch from
    private boolean searchFromEnd;                          // activeSearch is a continuation launched from commitEnd (deferred splice) vs a foot-search (splice immediately)
    private int frontierWaitTicks;                          // bounded retries re-searching at a loaded-chunk frontier before giving up (pathfinderFrontierCommit)
    private PathFinder.Result pendingSegment;              // a finished continuation segment awaiting splice at the current segment's end
    private int quickCooldown;                              // ticks before the next quick-start stub attempt (a useless stub backs off)
    private int noPathWaitTicks;                            // ticks spent holding a "no path" verdict while self-inflicted stuck-penalties decay
    private BlockPos lastWedgeFoot;                         // anti-stuck: where the last wedge/stuck repath fired
    private int wedgeRepathsHere;                           // anti-stuck: consecutive wedge repaths from (about) the same foot
    private int unstuckCountCooldown;                       // anti-stuck: min ticks between counted repath events (debounce)
    private int unstuckTicks;                               // anti-stuck: ticks left driving the forced displacement
    private float unstuckYaw;                               // anti-stuck: fixed heading for the displacement burst
    private int dbgPrevStep = -1;     // walkerDebug: detect step changes for per-step timing
    private int dbgTicksOnStep = 0;   // walkerDebug: ticks spent on the current step
    private boolean replayMode;   // executing a fixed archived plan: no repath/quick-start/splice/anti-stuck repath
    public String lastError;

    public void setGoal(Goal g) {
        this.goal = g;
        this.goalSnapChecked = false;
        this.path = null;
        this.edges = null;
        this.step = 0;
        this.ticksSinceRepath = 0;
        this.stuckTicks = 0;
        this.totalTicks = 0;
        this.actionTicks = 0;
        this.pillarStep = -1;
        this.pillarSinceJump = -1;
        this.waterClimbStall = 0;
        this.waterTouchRecent = 0;
        this.waterClimbPillaring = false;
        this.waterClimbDigging = false;
        this.climbPillarGaveUp = false;
        this.pillarNoPlaceTicks = 0;
        this.lastDigRiser = null;
        this.waterClimbDigRiser = null;
        this.diveLatch = 0;
        this.diveHold = 0;
        this.pillarRecoverLatch = 0;
        this.wantClimbRecent = 0;
        this.descending = false;
        this.activeSearch = null;
        this.bestDistToGoal = Double.POSITIVE_INFINITY;
        this.bestGoalDist = Double.POSITIVE_INFINITY;
        this.repathsNoProgress = 0;
        this.churnBase = null;
        this.churnWindowTicks = 0;
        this.churnEscapes = 0;
        this.bestStepDist = Double.POSITIVE_INFINITY;
        this.stuckStep = -1;
        this.noStepProgressTicks = 0;
        this.noProgressStep = -1;
        this.smoothTargetYaw = Float.NaN;
        this.commitEnd = null;
        this.searchFromEnd = false;
        this.pendingSegment = null;
        this.quickCooldown = 0;
        this.noPathWaitTicks = 0;
        this.lastWedgeFoot = null;
        this.wedgeRepathsHere = 0;
        this.unstuckCountCooldown = 0;
        this.unstuckTicks = 0;
        this.replayMode = false;
        this.lastError = null;
    }

    /**
     * If the goal is an exact-cell {@link Goal.Block} whose target is NOT standable per
     * {@code world.canStandAt}, replace it with a Goal.Block on the nearest standable cell
     * within {@link #GOAL_SNAP_RADIUS}. This is the fix for a random/long-distance goto
     * landing its target inside terrain or a sub-stand pocket: A* can never accept the
     * unstandable cell as the goal, so it burns its whole node budget every repath (~5 s
     * freeze) while the bot oscillates at the cell and drifts into nearby water. Snapping
     * to a cell the pathfinder itself considers standable lets the search terminate and the
     * bot settle at the closest reachable spot. Uses the SAME predicate the planner uses, so
     * planning and arrival ({@code goal.reached}) stay consistent. No-op for standable
     * targets, non-Block goals (Near/XZ already tolerate it), or when nothing standable is
     * near (then the goal is unchanged — fails as before, never worse).
     */
    private void snapGoalToStandable(WorldView world, BlockPos foot) {
        if (!(goal instanceof Goal.Block b)) return;
        BlockPos t = b.target();
        if (world.canStandAt(t)) return;                 // already fine — leave it
        BlockPos best = null;
        long bestToTarget = Long.MAX_VALUE, bestToFoot = Long.MAX_VALUE;
        for (int dy = -GOAL_SNAP_RADIUS; dy <= GOAL_SNAP_RADIUS; dy++)
            for (int dx = -GOAL_SNAP_RADIUS; dx <= GOAL_SNAP_RADIUS; dx++)
                for (int dz = -GOAL_SNAP_RADIUS; dz <= GOAL_SNAP_RADIUS; dz++) {
                    BlockPos c = t.offset(dx, dy, dz);
                    if (!world.canStandAt(c)) continue;
                    long dT = (long) c.distSqr(t);
                    if (dT > bestToTarget) continue;
                    long dF = (long) c.distSqr(foot);
                    // Nearest to the target wins; ties broken toward the bot so the snap
                    // doesn't pick a standable cell on the far side of the obstruction.
                    if (dT < bestToTarget || dF < bestToFoot) {
                        bestToTarget = dT; bestToFoot = dF; best = c;
                    }
                }
        if (best != null && !best.equals(t)) {
            if (BotConfig.walkerDebug)
                LOG.info("[walker] goal snap: unstandable target {} → nearest standable {} (d={})",
                        t, best, String.format(Locale.ROOT, "%.1f", Math.sqrt(bestToTarget)));
            this.goal = new Goal.Block(best);
        }
    }

    /** Drop the cached path (keep the goal) so the next {@link #tick} recomputes
     *  from the current position. Used when a movement process is resumed after
     *  being preempted by a higher-priority chain — during the suspension the bot
     *  may have been knocked back or the terrain changed, so reusing the stale
     *  path would walk into a wall. See ProcessScheduler / UserTaskChain.onResume. */
    public void forceRepath() {
        this.path = null;
        this.edges = null;
        this.step = 0;
        this.ticksSinceRepath = 0;
        this.stuckTicks = 0;
        this.actionTicks = 0;
        this.pillarStep = -1;
        this.pillarSinceJump = -1;
        this.activeSearch = null;
        this.bestStepDist = Double.POSITIVE_INFINITY;
        this.stuckStep = -1;
        this.noStepProgressTicks = 0;
        this.noProgressStep = -1;
        this.smoothTargetYaw = Float.NaN;
        this.commitEnd = null;
        this.searchFromEnd = false;
        this.pendingSegment = null;
        this.quickCooldown = 0;
        // Clear the water-climb-out / descent state too (setGoal resets these): a
        // resume mid water-climb otherwise carries a stale window base-Y / armed
        // stall, so the next tick either fires a spurious foothold-place takeover
        // (stall already past threshold) or suppresses a legitimate stall.
        this.waterClimbStall = 0;
        this.waterTouchRecent = 0;
        this.waterClimbPillaring = false;
        this.waterClimbDigging = false;
        this.climbPillarGaveUp = false;
        this.pillarNoPlaceTicks = 0;
        this.lastDigRiser = null;
        this.waterClimbDigRiser = null;
        this.diveLatch = 0;
        this.diveHold = 0;
        this.pillarRecoverLatch = 0;
        this.wantClimbRecent = 0;
        this.descending = false;
    }

    /** Replay a fixed archived plan. Caller has already teleported the bot to the plan
     *  start and restored the block envelope. Disables A* entirely — the plan executes
     *  with real physics; a wedge is recorded, not re-planned. */
    public void beginReplay(WorldView world, List<BlockPos> plan, List<Move.Edge> planEdges, Goal endGoal, BlockPos startFoot) {
        setGoal(endGoal);
        this.replayMode = true;
        adoptPath(new PathFinder.Result(plan, planEdges, true, 0, 0L, 0.0), world, startFoot);
    }

    public int pathLen() { return path == null ? 0 : path.size(); }
    public int pathStep() { return step; }

    // Jump / sneak actuators — drive the player's OWN AgentInput (Input.jumping /
    // Input.shiftKeyDown) instead of the SHARED global keybinds mc.options.keyJump /
    // keyShift, so the Walker never fights a human's space/shift. Per-tick (see
    // AgentInput): tick() sets a default below, branches override. p.input is always
    // an AgentInput here (installed at the top of tick()); the guard keeps it safe if
    // a respawn swapped a fresh KeyboardInput in between.
    private static void agentJump(Avatar a, boolean v) { a.commandJump(v); }
    private static void agentSneak(Avatar a, boolean v) { a.commandSneak(v); }
    /** Raw forward (keyUp equivalent) for the special branches that drive the impulse
     *  themselves (the main walk path uses commandMove). v=false also zeroes strafe. */
    private static void agentForward(Avatar a, boolean v) { a.commandForward(v ? 1f : 0f); }

    /** Client bridge: existing callers pass {@link Minecraft}; wrap it in a
     *  {@link ClientPlayerAvatar} (1:1 passthrough). The decoupled core is
     *  {@link #tick(Avatar, WorldView)}, which the server path calls directly. */
    public Step tick(Minecraft mc, WorldView world) {
        return tick(new ClientPlayerAvatar(mc), world);
    }

    public Step tick(Avatar a, WorldView world) {
        Player p = a.player();
        if (p == null) { lastError = "player vanished"; return terminal(Step.FAILED, PathTrace.Outcome.ERROR, lastError); }
        // AgentInput install (client) is handled inside the Avatar implementation.

        // Per-tick baseline for the jump/sneak channel: default to "not jumping / not
        // sneaking" so any path that returns without setting them can't leak a stale
        // value — branches below override as needed. (jump only matters on the ground,
        // so a default-false on an airborne tick is a no-op; see AgentInput.)
        agentJump(a, false);
        agentSneak(a, false);

        // Ground pathfinder: end creative flight so the player descends and
        // the walk/jump actuator (which relies on gravity + onGround) works.
        // While flying the player floats above the ground path, overshoots
        // waypoints frictionlessly, and spins in place re-aiming — then
        // times out hovering. Re-applied each tick so re-toggling flight
        // mid-path can't strand the bot.
        if (p.getAbilities().flying) {
            p.getAbilities().flying = false;
            p.onUpdateAbilities();
            if (!descending && BotConfig.walkerDebug)
                LOG.info(
                        "[walker] creative flight detected → disabling, will descend (y={})", p.getY());
            descending = true;
        }
        // Let the post-flight free-fall settle before we path — A* from a
        // mid-air foot finds no neighbours and reports "no path". Scoped to
        // this one-shot descent so the legitimately-airborne ticks of
        // jump / parkour / fall moves are untouched.
        if (descending) {
            if (!p.onGround() && !world.isWater(new BlockPos(
                    (int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ())))) {
                agentForward(a, false);
                agentJump(a, false);
                p.setSprinting(false);
                if (BotConfig.walkerDebug)
                    LOG.info(
                            "[walker] descending… y={} onGround={}", p.getY(), p.onGround());
                return Step.WALKING;
            }
            descending = false;
            if (BotConfig.walkerDebug)
                LOG.info(
                        "[walker] landed at y={} → resume ground pathing", p.getY());
        }

        // Water-bucket (MLG) fall execution is owned by the always-on
        // CLUTCH (see BotApiImpl#clientTick): once armed — planned, via
        // CLUTCH.armPlanned below as we step off a fallBucket lip, OR
        // reactively, on any unplanned damaging fall — the clutch runs at the
        // top of clientTick, takes the keys for the whole descent, and returns
        // early so this Walker is suspended until the bot has landed and
        // scooped. So there's nothing to do here; the walk dispatch below only
        // ARMS the planned case and biases the step-off keys.

        BlockPos foot = new BlockPos((int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ()));
        sampleTick(p);
        // One-shot goal snap (needs a live WorldView, so here not in setGoal): a random
        // long-distance goto whose exact target block is UNSTANDABLE — buried in terrain,
        // a sub-stand 1-tall pocket, or under a ceiling — makes A* never accept any node
        // as the goal (goalReached stays false), so it exhausts its whole node budget every
        // repath (~5 s freeze) while the bot oscillates at the unreachable cell and drifts
        // into nearby water (live 2026-06-15: goal (2350,64,1820) was solid stone). Snap the
        // target to the nearest cell that passes the SAME world.canStandAt the pathfinder
        // uses, so planning AND arrival agree and the bot settles at the closest standable
        // spot. Only affects exact-cell Goal.Block whose target is genuinely unstandable;
        // standable targets (every mine/farm/combat stand cell) are left untouched.
        if (!goalSnapChecked) { goalSnapChecked = true; snapGoalToStandable(world, foot); }
        if (goal.reached(foot)) {
            // While mid-pillar-jump the floored feet-Y can tick into the goal
            // cell at the apex before we've placed the block to stand on —
            // declaring ARRIVED there makes the bot abort the last placement
            // and fall back a block. Wait until grounded so it ends up
            // actually standing in the goal cell. Scoped to pillar edges so
            // swim/parkour arrivals (legitimately airborne) are unaffected.
            // A parkour-place leap can put the floored feet-Y into the goal
            // cell mid-arc — like the pillar case — before we've landed on the
            // placed block and braked the overshoot. Hold ARRIVED until grounded
            // so the settle phase can stop the bot ON the block, not past it.
            // A parkour-DESCEND leap likewise puts the floored feet-Y into the
            // lower goal cell mid-arc while still airborne and carrying forward
            // momentum; declaring ARRIVED there skips the landing brake and the
            // bot overshoots the 1-wide block into the void. Hold until grounded.
            Move.Edge ce = edgeAt(step);
            boolean midAirEdge = ce != null && ce.move != null && !p.onGround()
                    && ("pillarUp".equals(ce.move) || ce.move.startsWith("parkourPlace")
                        || ce.move.startsWith("parkourDescend"));
            if (!midAirEdge) return terminal(Step.ARRIVED, PathTrace.Outcome.SUCCESS, null);
        }

        // Hard tick budget: prevents infinite walking when A* returns a partial path
        // for an unreachable goal (best-effort fallback path > 1 node satisfies ok()).
        // Reset on real progress (closer to goal) so legitimate long walks aren't killed.
        double d = goal.estimate(foot);
        if (d < bestDistToGoal - 0.5) {
            bestDistToGoal = d;
            totalTicks = 0;
        } else if (++totalTicks > BotConfig.walkerTotalTickBudget) {
            lastError = "no progress for " + BotConfig.walkerTotalTickBudget + " ticks (best dist=" + Math.round(bestDistToGoal) + ")";
            return terminal(Step.FAILED, PathTrace.Outcome.STUCK, lastError);
        }

        // Re-searching toward a FAR goal mid-segment returns a DIVERGENT best-effort
        // path that can point backward, so the bot zig-zags/backtracks. Baritone-style
        // segment commitment avoids that by committing to each best-effort segment and
        // only splicing the next one (computed from the segment's END) once the bot is
        // standing there. Two kinds of re-search, kept distinct so this stays
        // backtrack-free:
        //   • SAFETY / FULL — re-search from the CURRENT foot and splice the
        //     result immediately. Fires when we have no path, we're stuck, we've
        //     been knocked off the path, or (for a path that actually reaches the
        //     goal) on the periodic refresh interval.
        //   • CONTINUATION — for a committed best-effort partial: the moment a
        //     segment is adopted, pre-compute the NEXT segment in the background
        //     starting from THIS segment's END (commitEnd), so the search overlaps
        //     the walk and the result is ready to splice the instant we arrive.
        //     Adoption is DEFERRED to the segment end (see below) so the new path
        //     always starts where the bot will be — no backward yaw flip.
        boolean offPath = path != null && step < path.size() && path.get(step).distSqr(foot) > 9;
        // Jitter-immune wedge: stuck on a node the Walker can't complete (e.g. a
        // ground-blocked fallN). An edge that's legitimately BREAKING blocks gets a
        // far longer leash: bare-handed stone takes ~150 ticks/block — well past
        // WEDGE_TICKS — so the plain threshold interrupted every mid-dig, penalized
        // the dig node (the only exit), and after a few cycles the accumulating
        // stuck-penalties walled off a small cave pocket entirely → 60k-node
        // pathLen=0 → goto FAILED (live 2026-06-09, mountain cave). The break
        // actuator has its own breakTimeoutTicks watchdog, so a dig that's truly
        // stuck (no line of sight, unreachable cell) still escapes after the sum.
        Move.Edge wedgeEdge = (path != null && step < path.size()) ? edgeAt(step) : null;
        // "Actively mining" = the break-edge leash/exemption applies ONLY while the
        // pick is actually swinging (breakHold set by last tick's actuator). A
        // breaking edge whose APPROACH is wedged — hCol-pinned short of the dig
        // stance, attack never starts (round37: stairUpBreak under a lake, bot
        // buoyed 0.8 blocks off the within-gate for minutes) — must fall through
        // to the normal wedge timer + anti-stuck, or the exemption turns a
        // mis-positioned dig into a silent permanent deadlock.
        // The block-less bank-dig (deep-water +1 climb-out, no place block) hand-mines
        // a SYNTHESIZED riser that no path edge planned — its edge's toBreak is empty,
        // so the test above can't see it. Treat a dig swung last tick exactly like a
        // breaking edge: extend the wedge leash and exempt it from the anti-stuck burst,
        // or the burst yanks the buoyant bot off the riser mid-dig and it never tops out
        // (the live continuous-context churn the clean single-bank arena can't reproduce).
        boolean breakingEdge = (wedgeEdge != null && !wedgeEdge.toBreak.isEmpty() && a.breakHeld())
                || waterClimbDigging;
        waterClimbDigging = false;   // re-armed below only if the block-less dig actuator runs this tick
        int wedgeLimit = breakingEdge ? WEDGE_TICKS + BotConfig.breakTimeoutTicks : WEDGE_TICKS;
        boolean wedged = noStepProgressTicks > wedgeLimit;
        // Fell off the committed climb path VERTICALLY (the current node is more than a jump
        // above/below the feet — a fumbled pillar/parkour dropped the bot below the route, or
        // it slid down). A CONTINUATION search (launched from commitEnd, not the foot) can
        // only return a path from where the bot ISN'T, so letting it finish just wastes ~6s of
        // bobbing toward a stale far carrot (the #3 far-climb-target stall). Preempt it so the
        // foot-search below restarts from the actual position NOW. Only cancels a continuation;
        // an in-flight foot-search is already from the right place and is left to finish.
        boolean fellOffPath = path != null && step < path.size()
                && Math.abs(path.get(step).getY() - foot.getY()) > world.maxJumpUpBlocks() + 2;
        if (fellOffPath && activeSearch != null && searchFromEnd) {
            activeSearch = null;
            searchFromEnd = false;
        }
        // Fell BELOW a still-valid route with blocks in hand → PILLAR BACK UP to
        // it instead of re-searching. A fresh foot-search from down here (a cave
        // corridor under a mountain high route) only finds the free goal-ward
        // funnel back into the dead pocket — the deterministic relapse loop
        // (live 2026-06-10: escape→high route→fall y104→96→re-search→cave→pocket,
        // ~80 s/lap, every lap byte-identical). Climbing the few blocks back
        // re-joins the committed route; the pillar-recover actuator below drives
        // it (latch re-armed each grounded tick while still low, overJump takes
        // over inside jump reach). Re-search only for a real divergence.
        boolean fellBelowRoute = fellOffPath
                && path.get(step).getY() > foot.getY()
                && path.get(step).getY() - foot.getY() <= PILLAR_RECOVER_MAX_DY
                && world.canPlace() && a.holdPlaceable();
        if (fellBelowRoute && p.onGround()) {
            pillarRecoverLatch = PILLAR_RECOVER_TICKS;
            pillarRecoverCell = foot;
        }
        // fellOffPath (when NOT recoverable) forces a fresh foot-search: with only
        // the continuation cancelled, the stale path's far carrot kept driving the
        // bot against the wall until the wedge timer fired — up to 15 s with the
        // breaking-edge leash. offPath is folded in: a 4-block fall puts the 3D
        // distSqr over its gate too, and that re-search would steal the recovery.
        if (unstuckCountCooldown > 0) unstuckCountCooldown--;
        // BOXED-POCKET churn escape (time-windowed net displacement). Catches a pocket
        // where the bot pillars a goal-ward dead-end wall and limit-cycles (round73:
        // 1416↔1454, pillaring to XZ-closer columns that RESET a goal-distance counter,
        // then falling back — net-zero ground travel for minutes; the existing wedge
        // BURST backs it off but it returns because the best-effort re-routes straight
        // back in). Measured over a fixed window so the oscillation can't mask it. On a
        // churned window, CHARGE the pocket with the executor's accumulating blacklist
        // (escalating radius) so the next search prices the dead-end out and routes
        // OUT / backtracks — the charge is the missing ingredient the plain back-off
        // burst lacks. Gated to best-effort (a goal-reaching path is real progress).
        // ALSO fires IN WATER (2026-06-15): a boxed submerged chamber under a rock
        // overhang (canyon pocket (2358,1863): water y58-62 under a y64+ shelf, walls
        // N/E/W) churns identically — carrots flip-flop into the E/W chamber walls,
        // yaw spins, totStuck 1800+ for minutes — but the open-water anti-spin only
        // damps the spin, it never PRICES THE POCKET OUT, so every best-effort repath
        // routes straight back in (the SW goal pulls through the chamber; run-1 only
        // arrived because it happened to route AROUND via the NE bank). The old
        // !isInWater gate excluded exactly this case. The penalty decays (~90 s) and a
        // healthy crossing nets ≫8 blocks / 20 s, so legit swims never trip it.
        if (churnBase == null) { churnBase = foot; churnWindowTicks = 0; }
        else if (++churnWindowTicks >= CHURN_WINDOW) {
            int cdx = foot.getX() - churnBase.getX(), cdz = foot.getZ() - churnBase.getZ();
            if ((cdx * cdx + cdz * cdz) < CHURN_MIN_MOVE_SQ && pathBestEffort) {
                churnEscapes++;
                // Widen the priced-out zone each repeat. In WATER a boxed pocket is far
                // costlier to sit in — a buoyant bot can't even hold position, it bob-
                // churns and burns minutes (live z1864: the slow r=2→3→4 land ramp took
                // ~3 min to finally route A* out). Detection already requires a CONFIRMED
                // churn (net <8 blocks / 20 s, which a healthy swim never trips), so it's
                // safe to jump straight to a wide blacklist there: grow by 2 per window
                // (cap 5) so one or two 20 s windows price the pocket out.
                int r = p.isInWater()
                        ? Math.min(2 + 2 * churnEscapes, 5)
                        : Math.min(1 + churnEscapes, 4);     // widen the priced-out zone each repeat
                for (int dx = -r; dx <= r; dx++)
                    for (int dz = -r; dz <= r; dz++) {
                        BlockPos c = foot.offset(dx, 0, dz);
                        world.penalizeStuckNode(c);
                        world.penalizeStuckNode(c.above());
                    }
                BlockPos wp = (path != null && step < path.size()) ? path.get(step) : null;
                if (wp != null) {
                    double bdx = p.getX() - (wp.getX() + 0.5), bdz = p.getZ() - (wp.getZ() + 0.5);
                    if (bdx * bdx + bdz * bdz > 0.01)
                        unstuckYaw = (float) Math.toDegrees(Math.atan2(-bdx, bdz));   // away from the goal-ward wp
                    unstuckTicks = 16;
                }
                if (BotConfig.walkerDebug)
                    LOG.info("[walker] anti-churn({}): net XZ move <{} blocks in {} ticks at {} (escapes={}) → charge r={} pocket + back off",
                            p.isInWater() ? "water" : "land",
                            (int) Math.sqrt(CHURN_MIN_MOVE_SQ), CHURN_WINDOW, foot, churnEscapes, r);
            } else {
                churnEscapes = 0;
            }
            churnBase = foot;
            churnWindowTicks = 0;
        }
        // OPEN-WATER bee-line preempt: a wide deep-water crossing wedges MID-segment.
        // The bot sprint-swims PAST its short committed segment faster than the
        // expensive sliced search (the whole 3-D water volume expands) can re-commit,
        // so `step` lags 30+ blocks behind, the wedge timer fires, and anti-stuck
        // bursts crab it across in jerky 13-block shoves (live 2026-06-15: ~9 bursts /
        // crossing, max step-stuck 100+). When WEDGED over water with the foot well
        // past its current node and a search already in flight, drop the stale segment
        // so the no-path branch below adopts a fresh straight SURFACE bee-line toward
        // the goal — smooth runway that the big search supersedes on landing — and the
        // path==null burst guard skips the shove. Near a bank the bee-line march is
        // short (< MIN, not adopted) so this can't strand a real climb-out.
        if (!replayMode && wedged && path != null && step < path.size() && !breakingEdge
                && activeSearch != null && world.isWater(foot)
                && foot.distSqr(path.get(step)) > BEELINE_OVERSHOOT_SQ) {
            path = null;
            edges = null;
            step = 0;
        }
        boolean safetyRepath = (path == null) || (stuckTicks > STUCK_TICKS) || wedged
                || ((offPath || fellOffPath) && !fellBelowRoute);
        boolean fullPeriodic = !pathBestEffort && path != null
                && ticksSinceRepath > BotConfig.walkerRepathEveryTicks;
        // ANTI-STUCK (forced displacement, Baritone-UnstuckChain-style): a
        // re-search from the SAME foot is deterministic — when the blocker is
        // a gap the executor can't thread but the planner prices passable
        // (a sole route, so the soft penalty never reroutes it), every repath
        // returns the same segment and the bot stands forever (live
        // 2026-06-10: 155 s frozen at a flooded bank corner against a diagDown
        // it couldn't enter). Counted on EVERY safety-repath tick (cooldown-
        // debounced), NOT inside the search-kickoff branch: at a hard pocket
        // the big escape/continuation searches monopolise activeSearch for
        // 9+ s each, so kickoff ticks are minutes apart and a counter gated
        // there never reaches three (live: 110 s wedged in a waterfall-pool
        // corner, counter stuck at 1-2). Three counted events from (about)
        // the same spot → physically MOVE so the next search starts elsewhere.
        // breakingEdge exempt: hand-mining a stairUpBreak holds the bot in
        // place 5-10 s per block — stuckTicks sails past its gate and the
        // burst YANKS the half-mined block's miner away (live: 17 bursts up
        // one staircase, each restarting the dig). The break watchdog
        // (breakTimeoutTicks) already covers a dig that's truly stuck.
        // Waiting on an in-flight search is PLANNER latency, not an execution wedge:
        // path==null alone makes safetyRepath true every hold tick, so over water —
        // where big searches run 1-1.7 s back-to-back and the current drifts the bot
        // slowly (foot stays within the 4-blk cell) — the event counter filled in
        // exactly 3 cooldowns (6 s) and burst the bot away from the search origin,
        // chaining rejects (round29 swamp: bursts every 6 s while cruising at 1.5 b/s).
        if (safetyRepath && !breakingEdge && !(path == null && activeSearch != null)) {
            // lastWedgeFoot ANCHORS the spot where this wedge began — it must NOT be
            // re-stamped to `foot` every tick. A buoyant bot CRUISING across open
            // water bobs ±0.04 vertically and so keeps failing the tight node-reach
            // gate; noStepProgressTicks climbs, `wedged` (hence safetyRepath) goes
            // true, and the bot enters this block every tick WHILE still swimming
            // ~1.2 b/s toward the goal. If the anchor followed the foot tick-by-tick
            // the "<=4 of the anchor" test would compare against LAST tick's foot
            // (0.06 b away) → always true → the event counter filled every cooldown
            // and burst the cruising bot every ~6 s (live 2026-06-15: forward bursts
            // at x=2526,2534,2542,2596,2604 mid-ocean while net-progressing). Anchor
            // ONLY when (re)starting the count; a bot that travels >2 blocks off the
            // anchor lands in the else branch, resets, and re-anchors — so only a bot
            // that genuinely stays within 2 blocks for 3 cooldowns (6 s) ever bursts.
            if (lastWedgeFoot != null && foot.distSqr(lastWedgeFoot) <= 4) {
                // Count EVENTS, not ticks (40-tick cooldown): a trivial search
                // completing same-tick makes every tick a safety repath while
                // the bot is still accelerating from standstill — three such
                // ticks (150 ms, 0.2 blocks of motion) are NOT three stuck
                // loops. A genuinely wedged bot stays put well past 6 s.
                if (unstuckCountCooldown <= 0) {
                    unstuckCountCooldown = 40;
                    if (++wedgeRepathsHere >= 3) {
                        unstuckTicks = 14;
                        // Burst AWAY from the waypoint, not yaw+150°: in a concave
                        // pocket (three walls + water) a fixed rotation just grinds
                        // the next wall — live: yaw wound 11 full turns at a mud-
                        // cliff notch, every burst re-entering the same seam.
                        // Backing straight off the wp is the one heading that's
                        // guaranteed open (the bot came from there).
                        BlockPos bwp = (path != null && step < path.size()) ? path.get(step) : null;
                        double bdx = bwp != null ? p.getX() - (bwp.getX() + 0.5) : 0;
                        double bdz = bwp != null ? p.getZ() - (bwp.getZ() + 0.5) : 0;
                        unstuckYaw = (bdx * bdx + bdz * bdz) > 0.01
                                ? (float) Math.toDegrees(Math.atan2(-bdx, bdz))
                                : p.getYRot() + 150f;
                        wedgeRepathsHere = 0;
                        lastWedgeFoot = null;   // displaced → drop the anchor; next wedge re-anchors fresh
                        if (BotConfig.walkerDebug)
                            LOG.info("[walker] anti-stuck: repeated safety repaths at {} → forced displacement burst", foot);
                    }
                }
            } else {
                // First wedge here, or the bot has TRAVELLED >2 blocks off the old
                // anchor (genuine progress) — (re)anchor at the current foot and
                // restart the count. This is the ONLY place lastWedgeFoot is set.
                wedgeRepathsHere = 0;
                lastWedgeFoot = foot;
            }
        }
        if (!replayMode && (safetyRepath || fullPeriodic) && activeSearch == null) {
            // Stuck too long on a move the Walker can't execute (a steep stepUp it
            // slides off, a pillar it can't ground)? Blacklist that node so this
            // re-search routes AROUND the wedge instead of re-planning into it —
            // otherwise A* keeps returning the same unclimbable spot and the bot
            // bobs there until an unrelated repath happens to diverge (a 600+-tick
            // stall observed on a steep mountain). Soft + decaying, so a sole route
            // is still taken eventually.
            if ((stuckTicks > STUCK_TICKS || wedged || fellOffPath) && path != null && step < path.size()) {
                // fellOffPath included: the step node the bot FELL AWAY from is a
                // demonstrably fragile traverse (mountain high route) — charge it
                // so the immediate re-search doesn't commit the same brittle line.
                world.penalizeStuckNode(path.get(step));
                // Also penalize the cells at the bot's NOSE. After string-pulling
                // the current step node can sit many blocks past the actual
                // obstruction (live 2026-06-09: afloat in a 1-wide flooded crevice,
                // carrot 10 blocks south, zero displacement for ~50 s) — punishing
                // only that far carrot leaves the choke point itself cheap, so
                // every re-search threads the same impassable gap from the same
                // foot and returns the same segment. Charging the blocks directly
                // ahead makes the next search route AROUND the choke (other bank /
                // over the top) instead of back into it.
                BlockPos nose = foot.relative(p.getDirection());
                // In a deep-water bowl pocket a single-cell nose charge barely
                // shifts A*'s cost — an adjacent equally-cheap water cell funnels
                // the next search straight back in, so the pocket only prices out
                // after a dozen slow over-water repaths (~27 s observed live,
                // round76 NE leg at 2307,62,2579). The land-churn escape already
                // ESCALATES its priced-out radius each repeat; the safety-repath
                // nose charge did not, the asymmetry IS the deep-water latency.
                // When the SAME foot wedges repeatedly IN WATER, widen the charge
                // with the existing same-foot repath counter so the bowl is priced
                // out in a few cycles, not a dozen. Land/first-wedge keep radius 0
                // (loop runs once at the nose) — behaviour there is unchanged.
                // Soft+decaying → a sole route is still taken; gated to repeated
                // water wedges so it can't misfire on legitimate slow progress
                // (the foot must stay put across repaths to grow the counter).
                int chargeR = world.isWater(foot) ? Math.min(wedgeRepathsHere, 2) : 0;
                for (int dx = -chargeR; dx <= chargeR; dx++)
                    for (int dz = -chargeR; dz <= chargeR; dz++) {
                        BlockPos c = nose.offset(dx, 0, dz);
                        world.penalizeStuckNode(c);
                        world.penalizeStuckNode(c.above());
                    }
            }
            activeSearch = new PathFinder(world).newSearch(foot, goal);
            searchFromEnd = false;
            searchSuppressedPlace = false;    // normal search: placing allowed; budget re-checked on result
            pendingSegment = null;            // a foot-search supersedes any stashed continuation
            ticksSinceRepath = 0;
        } else if (!replayMode && pathBestEffort && commitEnd != null
                && activeSearch == null && pendingSegment == null) {
            // Eagerly precompute the next best-effort segment from the committed end.
            activeSearch = new PathFinder(world).newSearch(commitEnd, goal);
            searchFromEnd = true;
            searchSuppressedPlace = false;
            ticksSinceRepath = 0;
        }
        // ANTI-STUCK displacement burst: drive a fixed turned heading + jump for a
        // few ticks so the body physically leaves the wedge cell. Path/edges stay
        // as-is; once the burst ends the normal logic sees a NEW foot (offPath or
        // the in-flight re-search lands) and plans from genuinely new ground.
        if (unstuckTicks > 0) {
            unstuckTicks--;
            // Drive the displacement in the CAMERA frame instead of slamming yaw:
            // p.setYRot here wound the camera 8+ full turns in a water-cave wedge
            // cluster (bursts every ~6 s, each with a different escape bearing,
            // every one yanking the view — raw yaw hit -3109°). commandMove pushes
            // the body along the escape bearing with ZERO camera motion: AgentInput
            // pre-rotates the impulse by Δ = bearing − cameraYaw and vanilla
            // travel() rotates it back, so the net push is along unstuckYaw exactly
            // as before — the same decoupling the main walk branch already uses.
            double bd = Math.toRadians(angleDiff(p.getYRot(), unstuckYaw));
            a.commandMove((float) -Math.sin(bd), (float) Math.cos(bd));
            agentJump(a, true);
            p.setSprinting(false);
            return Step.WALKING;
        }
        // Advance any in-flight search by one tick-slice so a big search never
        // blocks the render thread in a single tick (fixes the stutter).
        boolean searchDone = false;
        if (activeSearch != null) {
            long sb = System.nanoTime();
            // IDLE-SLICE: if there's no walkable path this tick (segment consumed or
            // none yet), the bot is FROZEN until the search lands — so frame-smoothness
            // is moot and a thin 6 ms slice just stretches a 3 s search to ~27 s of
            // standing still. Spend a much bigger slice to finish it ~5× sooner; while
            // walking, keep the thin slice so frames stay smooth.
            boolean idleNoPath = path == null || step >= path.size();
            long slice = idleNoPath
                    ? Math.max(BotConfig.pathfinderSliceMs, BotConfig.pathfinderIdleSliceMs)
                    : BotConfig.pathfinderSliceMs;
            searchDone = activeSearch.advance(slice);
            if (BotConfig.walkerDebug && !searchDone)
                LOG.info(
                        "[walker] search slice {}ms expanded={} (still running)",
                        (System.nanoTime() - sb) / 1_000_000, activeSearch.expanded());
        }
        if (searchDone) {
            PathFinder.Result res = activeSearch.result();
            activeSearch = null;
            boolean wasFromEnd = searchFromEnd;
            searchFromEnd = false;
            lastStats = new PathStats(res.expanded(), res.ms(), res.goalReached(),
                    res.finalCost(), res.path().size());
            PathTraceHolder.SINK.onSearchResult(res.path(), res.edges(), res.goalReached(),
                    res.expanded(), res.ms(), res.finalCost());
            if (BotConfig.walkerDebug)
                LOG.info(
                        "[walker] repath from {} → goalReached={} pathLen={} expanded={} ms={}",
                        wasFromEnd ? commitEnd : foot, res.goalReached(), res.path().size(), res.expanded(), res.ms());
            if (wasFromEnd && path != null && step < path.size()) {
                // Continuation finished while we're still walking the current
                // segment — stash it and splice only once we reach the segment end
                // (see the step>=size handler), so its start coincides with the bot.
                // Stash even an empty (no-path) result: the segment-end handler
                // reads that as "no further progress" and ends cleanly, rather than
                // letting the kickoff re-launch the same boxed-in search forever.
                pendingSegment = res;
            } else if (wasFromEnd && !res.hasPath()) {
                // Already at/over the segment end and no onward route. With frontier
                // planning this may be a STALE continuation (computed before we
                // arrived & loaded the chunks beyond) — re-search fresh before giving
                // up; otherwise we've gone as far as the best effort allows.
                return frontierHoldOrArrive(a, world, p);
            } else if (res.hasPath()) {
                // ANTI-SPIN (water repath-churn): a failed water climb-out (bot can't
                // mount the bank) makes every repath return a best-effort that swims
                // back / circles without the bot's ACTUAL position getting any closer
                // to the goal; following each one U-turns the bot and the repeated
                // U-turns wind the camera (the water "转圈"). This is a TEMPORAL signal
                // (no net progress across repaths), not a single-path property — the
                // churn segment can even END on dry land (the unreachable climb-out
                // target). Track the bot's best goal-distance: when several consecutive
                // repaths IN WATER fail to improve it, end best-effort instead of
                // churning. A real journey keeps improving (counter stays 0); land is
                // exempt (go-arounds may step away from the goal there).
                // (d = goal.estimate(foot), computed above for the hard tick budget)
                // Count consecutive in-water repaths that don't get the bot closer to
                // the goal (the 5-unit margin ignores micro-lunges). This drives the
                // anti-spin camera FREEZE in the heading block (repathsNoProgress >
                // CHURN_REPATH_CAP) so the churn stops winding the camera while the bot
                // keeps pressing toward the climb-out; only after a long stall with no
                // progress at all do we give up best-effort instead of pressing forever.
                if (d < bestGoalDist - 5.0) { bestGoalDist = d; repathsNoProgress = 0; churnResets = 0; }
                else repathsNoProgress++;
                // Only a body actually AFLOAT counts as a water repath: the old
                // `|| isWater(foot.below())` arm also matched a bot standing on dry
                // ground beside a waterfall, so a canyon pacing loop was misread as
                // water churn and the goto ended in a FAKE ARRIVED at (420,-349)
                // (live 2026-06-09, "anti-spin: 13/15 water repaths" on dry land).
                boolean overWaterRepath = p.isInWater() && !p.onGround();
                if (overWaterRepath && !res.goalReached() && repathsNoProgress > CHURN_GIVEUP_CAP) {
                    // A LEGITIMATE go-around also reads as "no progress" here: rounding a
                    // lava arm via the river pushed d above the pre-detour best for 13+
                    // repaths and the give-up fired mid-journey with 440 blocks to go
                    // (live 2026-06-09 — the land exemption in the note above was right,
                    // it just didn't cover WATER detours). So don't give up on the first
                    // stall: RE-BASELINE at the current distance and watch again — a real
                    // detour soon improves on the new baseline (and any true progress
                    // resets churnResets); only a churn that stalls through several fresh
                    // baselines is the genuine unwinnable spin.
                    if (churnResets < CHURN_MAX_RESETS) {
                        churnResets++;
                        bestGoalDist = d;
                        repathsNoProgress = 0;
                        if (BotConfig.walkerDebug)
                            LOG.info("[walker] anti-spin: stalled in water (d={}) → re-baseline {}/{}",
                                    String.format(Locale.ROOT, "%.0f", d), churnResets, CHURN_MAX_RESETS);
                    } else {
                        if (BotConfig.walkerDebug)
                            LOG.info("[walker] anti-spin: {} water repaths w/o progress after {} re-baselines (d={}) → end best-effort",
                                    repathsNoProgress, churnResets, String.format(Locale.ROOT, "%.0f", d));
                        agentForward(a, false);
                        agentJump(a, false);
                        p.setSprinting(false);
                        return terminal(Step.ARRIVED, PathTrace.Outcome.SUCCESS, null);
                    }
                }
                // BLOCK-BUDGET ("搭桥前算够不够，否则就挖"): if this path would place
                // more blocks (bridge/pillar/parkour-place) than the bot carries, it
                // would bridge partway, burn its blocks and strand. Re-search with
                // placing OFF so A* digs through / routes around (break moves need no
                // blocks). Guard with searchSuppressedPlace so the place-off result is
                // adopted as-is (no second reroute / loop).
                if (!replayMode && !searchSuppressedPlace) {
                    int placesNeeded = countPlaceEdges(res.edges());
                    if (placesNeeded > world.placeableBlockCount()) {
                        if (BotConfig.walkerDebug)
                            LOG.info("[walker] path needs {} placed blocks, have {} → re-search place-off (dig/around)",
                                    placesNeeded, world.placeableBlockCount());
                        activeSearch = new PathFinder(world).newSearch(foot, goal, true);
                        searchFromEnd = false;
                        searchSuppressedPlace = true;
                        pendingSegment = null;
                        return Step.WALKING;
                    }
                }
                adoptPath(res, world, foot);
            } else if (path == null) {
                // No route and nothing to fall back on. But if we're airborne
                // — plummeting from an unplanned fall (knockback, the ground
                // broken out from under us, a tp) — a mid-air foot ALWAYS has
                // no walkable neighbours, so failing here ends the process and
                // stops ticking, and the reactive water-clutch + MLG latch at
                // the top of tick() never get to run. Hold instead so they keep
                // ticking; once we land, the next repath finds a route or fails
                // for real. (Bounded by the total-tick budget above, so a void
                // fall with no clutch can't hang the goto forever.)
                if (!p.onGround()) {
                    agentForward(a, false);
                    p.setSprinting(false);
                    return Step.WALKING;
                }
                // SELF-INFLICTED no-path: repeated wedge penalties can wall the
                // bot into a small pocket (every exit charged 600-3600 within a
                // 2.5-block bump) so the search legitimately finds nothing — but
                // the blindness is OURS, not the terrain's (live 2026-06-09 cave
                // pocket: debug.plan with a clean view reached the goal in 12
                // segments from the same foot). Penalties decay in 15 s; hold and
                // let the regular path==null kickoff retry instead of failing the
                // whole goto. Bounded so a genuinely walled-in bot still fails.
                if (world.hasStuckPenalties() && noPathWaitTicks < NO_PATH_WAIT_CAP) {
                    noPathWaitTicks++;
                    if (BotConfig.walkerDebug && noPathWaitTicks % 100 == 1)
                        LOG.info("[walker] no path but stuck-penalties still live → waiting out decay ({}/{})",
                                noPathWaitTicks, NO_PATH_WAIT_CAP);
                    agentForward(a, false);
                    agentJump(a, false);
                    p.setSprinting(false);
                    return Step.WALKING;
                }
                lastError = "no path (expanded=" + res.expanded() + ")";
                return terminal(Step.FAILED, PathTrace.Outcome.NO_PATH, lastError);
            }
            // else: search failed but we still have the previous path — keep it.
        }
        // First path still computing (no path to follow yet). PROGRESSIVE
        // QUICK-START: instead of holding frozen for the whole background
        // search (the visible inter-segment stall), grab a tiny synchronous
        // stub segment toward the goal and start walking it this very tick;
        // the big result supersedes it on landing. Only hold if no useful
        // stub exists (boxed in — moving blind would jitter).
        if (path == null) {
            if (replayMode || activeSearch == null
                    || (!tryQuickStart(world, foot, goal) && !tryWaterBeeline(world, foot, goal))) {
                // replayMode: a fixed plan was adopted at beginReplay, so path is
                // never null here in practice; if it somehow is, just hold (no
                // quick-start/beeline) — replay never re-plans.
                agentForward(a, false);
                agentJump(a, false);
                p.setSprinting(false);
                return Step.WALKING;
            }
            // stub adopted → fall through and walk it
        }
        ticksSinceRepath++;
        // "Stuck" = no PROGRESS toward the current node — NOT a foot block that has
        // not changed. A bot creeping forward (water ≈ 0.08 b/tick, a place-bridge
        // sneak ≈ 1 block/15 ticks) stays in the same foot block for many ticks while
        // genuinely advancing; the old block-change test mis-flagged that as stuck and
        // fired the reCentre recovery (which aims yaw at the PREVIOUS node) → a
        // forward/backward yaw limit-cycle (0↔180) at zero net speed. So measure
        // 3D distance² to the current step node and only count ticks that fail to
        // improve on the closest approach so far. The vertical (dy) term matters: a
        // stepUp/stairUpBreak climbs in place — horizontal distance is frozen while
        // the bot rises toward the node — so a horizontal-only metric would falsely
        // count the whole climb as stuck. Bridge edges still hard-zero the counter
        // (a sneak crawl barely moves the carrot, so even node-distance can stall
        // mid-place).
        Move.Edge curBridgeE = edgeAt(step), nxtBridgeE = edgeAt(step + 1);
        boolean onBridge = (curBridgeE != null && "bridgePlace".equals(curBridgeE.move))
                || (nxtBridgeE != null && "bridgePlace".equals(nxtBridgeE.move));
        // Jitter-immune WEDGE timer: counts raw ticks the bot stays on the SAME path
        // step, reset only when the step actually advances (or the path/segment ends).
        // Deliberately INDEPENDENT of the bridge/progress resets below — a bridgePlace
        // that can't place, or a fallN that can't drop, dwells on one step indefinitely
        // and IS a wedge; the progress-based stuckTicks (which a bob/creep keeps zeroing,
        // and which onBridge hard-zeroes) misses both, so without this the bot deadlocks.
        if (path == null || step >= path.size() || step != noProgressStep) {
            noProgressStep = step;
            noStepProgressTicks = 0;
            noProgressBestD2 = Double.POSITIVE_INFINITY;
        } else {
            // Genuinely closing in on the current node is progress, not a wedge: a
            // water cruise crosses 20+-block string-pulled edges at ~1.5 b/s (260
            // ticks on ONE step — far over WEDGE_TICKS) and used to trip safety
            // repaths + anti-stuck bursts mid-lake. Track the closest-EVER approach
            // (monotonic, so a bob against a wall can't keep resetting) and only
            // count wedge ticks while that stops improving — a fall2 that can't
            // drop or a blocked bridgePlace never closes in and still trips.
            BlockPos wn = path.get(step);
            double wdx = (wn.getX() + 0.5) - p.getX();
            double wdy = wn.getY() - p.getY();
            double wdz = (wn.getZ() + 0.5) - p.getZ();
            double wd2 = wdx * wdx + wdy * wdy + wdz * wdz;
            if (wd2 < noProgressBestD2 - 0.05) {   // any real new-low counts; 0.05 is float-noise margin (1.0 starved a 1.5 b/s approach inside ~7 blocks: 2·d·v < 1)
                noProgressBestD2 = wd2;
                noStepProgressTicks = 0;
            } else {
                noStepProgressTicks++;
            }
        }
        if (onBridge) {
            stuckTicks = 0;
            bestStepDist = Double.POSITIVE_INFINITY;
            stuckStep = step;
        } else if (path != null && step >= 0 && step < path.size()) {
            BlockPos sn = path.get(step);
            double sdx = (sn.getX() + 0.5) - p.getX();
            double sdy = sn.getY() - p.getY();
            double sdz = (sn.getZ() + 0.5) - p.getZ();
            double sd2 = sdx * sdx + sdy * sdy + sdz * sdz;
            if (step != stuckStep) {                       // new node → fresh progress window
                stuckStep = step;
                bestStepDist = sd2;
                stuckTicks = 0;
            } else if (sd2 < bestStepDist - STUCK_PROGRESS_EPS) {
                bestStepDist = sd2;                        // closer than ever to this node → real progress
                stuckTicks = 0;
            } else {
                stuckTicks++;                              // no closer this tick → maybe stalled
            }
        } else {
            stuckTicks = 0;
        }

        while (step < path.size()) {
            Move.Edge se = edgeAt(step);
            // Buoyant pillar (bunker / flooded-pit self-exit): the bot rises through
            // a water-filled shaft. Detect it from the WORLD — the cell being risen
            // FROM is water — not p.isInWater(), which flickers false at the bob peak
            // when the head clears the surface (that flicker stalled the climb).
            // A FLOODED shaft (destination cell is itself water) lets the bot float
            // straight up; a buoyant pillar more broadly is any pillarUp begun from
            // water (cell below is water / bot in water) — including the dry-air
            // chimney where the bot must place a support to climb out.
            boolean shaftFlooded = se != null && "pillarUp".equals(se.move) && world.isWater(path.get(step));
            boolean waterPillar = se != null && "pillarUp".equals(se.move)
                    && (shaftFlooded || p.isInWater() || world.isWater(path.get(step).offset(0, -1, 0)));
            // Don't advance past a cell whose break/place actions are still
            // pending — otherwise a DownBreak (foot vertically aligned, < 1.2
            // away) would be skipped before we ever mine the floor.
            if (waterPillar) {
                // The buoyant climb never places the support via the dry path, so the
                // unfilled place cell is not genuinely pending; only a still-solid
                // ceiling is. (In the dry-air case the in-water actuator does place a
                // support, which makes the bot ground and falls through to the gate.)
                boolean ceilingPending = false;
                for (BlockPos b : se.toBreak) if (world.isSolid(b)) { ceilingPending = true; break; }
                if (ceilingPending) break;
            } else if (hasPendingEdge(world, se)) break;
            // A pillar step isn't "reached" until risen to height. A FLOODED-shaft
            // float has no landing → risen-height alone promotes it; every other
            // pillar (dry, or the water-exit place) must be grounded first so we
            // don't advance mid-jump / before the support lands.
            if (se != null && "pillarUp".equals(se.move)
                    && !((p.onGround() || shaftFlooded) && p.getY() >= path.get(step).getY() - 0.1)) break;
            // Don't advance past a parkour-place edge while airborne — keep the
            // settle phase owning the descent so it brakes the leap on landing.
            if (se != null && se.move != null && se.move.startsWith("parkourPlace")
                    && !p.onGround()) break;
            // Same for a parkour-descend leap: don't advance off it until we've
            // actually landed, so the descend landing-brake keeps owning the arc
            // (otherwise the next edge fires mid-air and the bot sails past).
            // EXEMPT in water: a buoyant body never grounds on a water-side landing
            // (A* exits a shore DOWN into water with a parkourDescend, or a leap
            // falls short into a water gap), so the !onGround hold would pin the
            // step on the parkour node forever while the bot bobs at the surface —
            // the parkour jump just bobs it, pure-pursuit can't advance past the
            // node it drifted beyond, and it deadlocks until a safety repath (live
            // 2026-06-15: shoreline parkourDescend2d1 over water, bot bobbed y62.5
            // <-> 63 ~19 s, stuck 385). When in water let the normal water step-
            // advance (within / floatOverSubmerged / pure-pursuit) carry it past
            // the node so flatWaterWalk swims it on to the next node.
            if (se != null && se.move != null && se.move.startsWith("parkourDescend")
                    && !p.onGround() && !p.isInWater()) break;
            BlockPos w = path.get(step);
            double dx = (w.getX() + 0.5) - p.getX();
            double dz = (w.getZ() + 0.5) - p.getZ();
            double cur2 = dx * dx + dz * dz;
            // A climb node counts as REACHED only once the feet are up at it. The
            // |Δy|<1.2 gate marks a +1 climb node "reached" from a full block below —
            // fine on dry land (mid-step), but in WATER the bot bobs at the surface 1
            // block under the node, so `within` fired early, advanced `step` to the
            // NEXT node, and left an impossible +2 climb (trace: stuck at y62 targeting
            // node y64). For an upward node while in water, don't advance until the
            // feet have actually risen to it.
            double dyNode = w.getY() - p.getY();
            // In WATER the buoyant body rides the surface; a path node BELOW it
            // (A* routed a wide crossing along the riverbed) can never be reached
            // by Y — the bot floats over it forever (trace: rode y61.78 above a y60
            // diagDown node, |dY|=1.78 > 1.2, never advanced → a ~50-block river was
            // uncrossable). Treat horizontal alignment ALONE as "reached" for such
            // a submerged below-node so the crossing advances node-by-node at the
            // surface. Excluded: a deliberate swimDown dive edge (we want that
            // descent); a climb-up node (dyNode>0) stays gated by the clause below
            // so a buoyant bob can't skip an intermediate +1 climb node.
            boolean diveEdge = se != null && se.move != null && se.move.startsWith("swimDown");
            // A buoyant body rides 1-2 blocks ABOVE the in-water below-nodes of a
            // riverbed crossing and can never close the Y gap — the flatWaterWalk
            // actuator already sprint-swims it flat at the surface, so horizontal
            // alignment ALONE must advance the step. The old gate also required
            // !p.isUnderWater(), which DISABLED the whole crossing the instant the
            // eyes bobbed under the waterline (isUnderWater flickers true on a
            // surface swimmer) — the bot then pinned at the node's XZ forever (live
            // 2026-06-15 deep-water arena: open-water cross, node 1.4 below,
            // undW=true bobbing, 2600+ ticks at step 1). Bounded to ≤2.5 below so a
            // genuine deep descent isn't skipped (A* places crossing nodes ~1 below
            // the floating foot, not at the riverbed). A deliberate swimDown dive is
            // still allowed to descend — UNLESS buoyancy has refused the sink past
            // WATER_DESCEND_GIVEUP (the same surface-pin failure for a dive edge),
            // in which case surface-cross past it too rather than deadlock.
            boolean floatOverSubmerged = p.isInWater() && dyNode < -0.5 && dyNode > -2.5
                    && (!diveEdge || noStepProgressTicks > WATER_DESCEND_GIVEUP);
            boolean within = cur2 < REACH_DIST_SQ
                    && (Math.abs(dyNode) < 1.2 || floatOverSubmerged)
                    && !(p.isInWater() && dyNode > 0.5);
            // Pure-pursuit re-sync: also advance past a node we've already gone
            // by — the next node being closer than this one means the player is
            // beyond it. Without this, sprinting toward a far carrot (or a
            // sliced repath that starts from a now-stale foot) leaves `step`
            // pointing at a node BEHIND the player, so the aim flips ~180°.
            boolean passed = false;
            if (!within && step + 1 < path.size()) {
                BlockPos nx = path.get(step + 1);
                double ndx = (nx.getX() + 0.5) - p.getX();
                double ndz = (nx.getZ() + 0.5) - p.getZ();
                // Skip the current node only if the player is genuinely beyond it
                // (next node STRICTLY horizontally closer) AND the next node is
                // itself vertically reachable from where the player actually IS.
                // Without the vertical clause, standing at the bottom of a 2-deep
                // pit the re-sync skips the intermediate +1 climb node and locks
                // onto a node +2 above — an impossible single jump → bot wedged
                // (bunker pit, totStuck>1000). The STRICT '<' matters when the next
                // node is stacked directly above the current one (a pillarUp: same
                // x,z → ndx²+ndz² EQUALS cur2): with '<=' the tie reads as "passed"
                // and the bot skips the walk-to-the-pillar-base node, then tries to
                // pillar in place wherever it happens to be standing (observed:
                // mining an offset trunk, bot jumped at x=16.7 chasing a pillar at
                // x=15, never placing). A real overshoot makes next STRICTLY closer,
                // so '<' still resyncs those. The 1.2 gate matches a jump's climb.
                // Same in-water climb gate as `within` above: the buoyant body bobs
                // y±0.9, so at the bob's crest |nx.y−p.y| can dip under 1.2 for a
                // node it never actually climbed to — passing skips the climb base
                // and leaves an impossible +2 target (stuck at y62 vs node y64).
                // OVERSHOOT relaxation: once the foot is clearly PAST node w
                // horizontally (cur2 > OVERSHOOT_RESYNC_SQ) the bot is no longer
                // approaching a climb base, so the two near-base guards must not
                // freeze the pointer. (a) The strict '<' tie-break — which protects
                // a pillarUp/swimUp node stacked directly above w (ndx²+ndz² == cur2)
                // — relaxes to '<=' so a stacked climb node can't pin a node the bot
                // has overshot. (b) The in-water "don't skip a buoyant +1 climb" gate
                // is dropped. The |Δy|<1.2 reachability gate on nx STAYS in both
                // cases, so the re-sync still can't lock onto an impossible +2 climb.
                boolean overshot = cur2 > OVERSHOOT_RESYNC_SQ;
                double nd2 = ndx * ndx + ndz * ndz;
                // Fell BELOW a descend node the bot has gone PAST: a steep crest /
                // shoulder where the bot crosses with forward momentum and free-falls
                // 2-3 blocks past the fall node, grounding on the terrace below it
                // (live 2026-06-15 reverse (2426,99,2153): foot y96, node y99, 1.8 b
                // past it in z, pinned against the far face → stuck 1341 / ~67 s). The
                // |w.y - p.y| < 1.5 guard — there to refuse "passing" a node we haven't
                // risen TO — also refuses this dropped-past node, freezing `step` on a
                // node 3 ABOVE the foot with the aim pointing back-UP at it (none of
                // within / fellOffPath catch it: |Δy|=3 misses within, and 3 ≤ maxJump+2
                // misses the re-search). Relax it when w is clearly ABOVE the foot AND
                // the route keeps DESCENDING through it (nx no higher than w): then w is
                // behind+above, a dropped-past descend node, not a climb target — and the
                // nx reachability gate below (|nx.y - p.y| < 1.2) still bars locking onto
                // an impossible climb.
                // Dry land only: a buoyant body in water legitimately rides above/below
                // its nodes (floatOverSubmerged / dive own that), so "foot below the node"
                // is normal there and must NOT skip a climb/parkour node — the live wedge
                // is a grounded terrace landing (inW=false, onG=true throughout).
                boolean droppedPastDescend = !p.isInWater()
                        && w.getY() - p.getY() >= 1.5
                        && nx.getY() <= w.getY();
                passed = (overshot ? nd2 <= cur2 : nd2 < cur2)
                        && (Math.abs(w.getY() - p.getY()) < 1.5 || droppedPastDescend)
                        && Math.abs(nx.getY() - p.getY()) < 1.2
                        && !(!overshot && p.isInWater() && nx.getY() - p.getY() > 0.5);
            }
            // TAIL overshoot: the foot blew past the FINAL node of a best-effort
            // (sliced / progressive quick-start-stub) segment. There is no next
            // node to re-sync onto, so the block above never runs and neither
            // `within` nor `passed` can fire — `step` froze on the tail while the
            // bot crabbed 100 blocks past it on anti-stuck bursts, waiting out the
            // slow superseding search (live 2026-06-15 wide-water crossing: step
            // 6/7, p 100 blk past a 6-node stub's last node, stuck 745, minutes of
            // burst-crab). A best-effort tail is a splice point, never the goal, so
            // a gross HORIZONTAL overshoot (cur2 gate, so a straight pillar-up to
            // the tail — same XZ, small cur2 — is excluded) means the segment is
            // spent: advance so the segment-end block below adopts the continuation
            // / repaths from HERE instead of pinning on the stale tail.
            // VERTICAL tail overshoot: the bot blew past the tail DOWNWARD (a fall
            // node on a cliff lip — it free-fell well below the tail and is grounding
            // at the bottom, horizontally still on the tail's XZ so the cur2 gate above
            // never trips). Same dead-zone as droppedPastDescend but with no next node
            // to re-sync onto (live 2026-06-15 reverse fall2 (2436,92,2180): foot fell
            // to y82, 10 below, |dy|=9.9, cur2 0.6 → stuck 128 / ~6.4 s waiting on the
            // fellOffPath re-search). Consume the spent descend tail so the segment-end
            // block repaths from HERE at once. Gated to a DESCEND incoming edge so a
            // pillarUp/climb tail (bot legitimately below it) is never aborted.
            boolean descendTail = se != null && se.move != null
                    && (se.move.startsWith("fall") || se.move.startsWith("diagDown")
                        || se.move.equals("stepDown"));
            boolean tailDroppedPast = !p.isInWater() && descendTail
                    && p.getY() < w.getY() - 2.0;
            boolean tailConsumed = !within && step + 1 == path.size() && pathBestEffort
                    && (cur2 > OVERSHOOT_RESYNC_SQ || tailDroppedPast);
            if (within || passed || tailConsumed) step++;
            else break;
        }
        if (step >= path.size()) {
            if (!pathBestEffort || goal.reached(foot)) {
                // A path that actually reaches the goal (or we ended up standing in
                // the goal cell): the journey is done.
                return terminal(Step.ARRIVED, PathTrace.Outcome.SUCCESS, null);
            }
            // Best-effort segment consumed but the goal is still ahead. DON'T give
            // up — this is the long-distance splice point. Adopt the continuation
            // if its background search has landed; otherwise hold here (keys
            // released) until it does. The total-tick budget above still bounds a
            // goal we can never make real progress toward, so this can't hang.
            if (pendingSegment != null) {
                PathFinder.Result next = pendingSegment;
                pendingSegment = null;
                if (next.hasPath() && next.path().size() > 1) {
                    if (!adoptPath(next, world, foot)) {
                        // Mis-anchored continuation (the bot never made it to the
                        // commitEnd it was computed from) — drop it and force a
                        // fresh foot-search next tick instead of hanging on an
                        // unreachable step 1.
                        path = null;
                        edges = null;
                        step = 0;
                        commitEnd = null;
                        agentForward(a, false);
                        agentJump(a, false);
                        p.setSprinting(false);
                        return Step.WALKING;
                    }
                    // adopted: step→1 on the new segment; fall through to walk it
                } else {
                    // Stale eager continuation found nothing — at a chunk frontier the
                    // newly-loaded terrain may now reveal the next segment, so re-search
                    // fresh before giving up (bounded).
                    return frontierHoldOrArrive(a, world, p);
                }
            } else {
                if (!replayMode && activeSearch == null && commitEnd != null) {
                    activeSearch = new PathFinder(world).newSearch(commitEnd, goal);
                    searchFromEnd = true;
                }
                // PROGRESSIVE QUICK-START at the splice gap: the continuation
                // search hasn't landed yet (eager precompute missed this one) —
                // walk a synchronous stub toward the goal instead of holding
                // at the segment end until it does.
                if (replayMode || activeSearch == null
                        || (!tryQuickStart(world, foot, goal) && !tryWaterBeeline(world, foot, goal))) {
                    agentForward(a, false);
                    agentJump(a, false);
                    p.setSprinting(false);
                    return Step.WALKING;
                }
                // stub adopted (path replaced, step=1) → fall through and walk it
            }
        }

        Move.Edge edge = edgeAt(step);

        // === Comprehensive per-tick Walker trace (walkerDebug) ===
        // The single source of truth for "why is the bot stuck": for the current
        // step it prints the exact advance-decision inputs (horizontal dist² vs the
        // REACH_DIST_SQ gate, the |Δy| vs the 1.2 gate that together decide `within`),
        // how many ticks we've been stuck on THIS step, the move type + its
        // break/place needs, and the live body state. Read this trace top-to-bottom
        // to see precisely which condition fails tick after tick — no guessing.
        if (BotConfig.walkerDebug) {
            if (step != dbgPrevStep) { dbgPrevStep = step; dbgTicksOnStep = 0; }
            dbgTicksOnStep++;
            BlockPos nd = path.get(step);
            double ddx = (nd.getX() + 0.5) - p.getX();
            double ddz = (nd.getZ() + 0.5) - p.getZ();
            double cur2 = ddx * ddx + ddz * ddz;
            double dY = nd.getY() - p.getY();
            boolean within = cur2 < REACH_DIST_SQ && Math.abs(dY) < 1.2;
            BlockPos br0 = (edge != null && !edge.toBreak.isEmpty()) ? edge.toBreak.get(0) : null;
            LOG.info("[walker] t={} step={}/{} move={} node={},{},{} p=({},{},{}) pitch={} cur2={} (gate {}) |dY|={} (gate 1.2) within={} onG={} inW={} undW={} stuck={} totStuck={} pend={} break0={}{}",
                    dbgTicksOnStep, step, path.size(), edge != null ? edge.move : "-",
                    nd.getX(), nd.getY(), nd.getZ(),
                    String.format(Locale.ROOT, "%.2f", p.getX()), String.format(Locale.ROOT, "%.2f", p.getY()), String.format(Locale.ROOT, "%.2f", p.getZ()),
                    String.format(Locale.ROOT, "%.0f", p.getXRot()),
                    String.format(Locale.ROOT, "%.3f", cur2), REACH_DIST_SQ,
                    String.format(Locale.ROOT, "%.2f", Math.abs(dY)), within,
                    p.onGround(), p.isInWater(), p.isUnderWater(),
                    stuckTicks, totalTicks, edge != null && hasPendingEdge(world, edge),
                    br0, br0 != null ? (world.isSolid(br0) ? " (solid)" : " (clear)") : "");
        }

        // === Water climb-out foothold (place to get grounded) ===
        // Runs BEFORE the break/place actuators so it catches a bob-stall no matter
        // how A* labelled the climb (plain StepUp, StairUpBreak into the bank, …).
        // A floating bot can't gain height onto a bank whose top sits ABOVE the
        // water surface: swim-up tops out AT the surface (~0.6 short of the step-up
        // grab) and a break/pillar can't actuate from deep water either — so it
        // bob-cycles forever (live trace: y6.2 peak → sinks to y4.7, no NET rise).
        // Fix: once stalled with no vertical progress, at a bob peak (feet clear of
        // the water, surface right beneath) place ONE throwaway block to fill that
        // top water cell → a flush foothold the bot rests on, GROUNDED; from solid
        // ground the ordinary climb (step-up / break-carve / pillar) finishes the
        // +1/+2 (verified live). The counter only advances while height is NOT
        // rising, so a working pillar / swim-up that DOES gain height never trips
        // it. Default-ON escape permission + a placeable in hand required; reads
        // only here, so the pathfinder is byte-for-byte unchanged.
        {
            BlockPos cwp = path.get(step);
            // Detecting a stalled climb-out must survive confounders that reset the old
            // accounting before it armed: (1) the bob peak breaches the surface so
            // `touchingWater` flickers false; (2) at that same peak the foot BLOCK rises
            // to the stepUp target Y, so `cwp.y > foot.y` (the climb intent) flickers
            // false; (3) A* repaths every ~13 ticks while it can't execute the climb,
            // nulling `edge`; (4) the peak-vs-high-water-mark "real rise" reset — and any
            // height-based substitute — is itself tripped by the ~1.5-block buoyant bob.
            // Fix: keep water-contact AND climb-intent STICKY across (1)-(3), then count
            // pure ticks-in-context with NO height reset (4). A genuine climb-out leaves
            // the context within ~10 ticks (grounds on the bank → no higher node ahead →
            // wantClimb false), so it never reaches WATER_CLIMB_STALL; only a real
            // bob-stall sits in-context long enough to arm. (live round76b: net-window
            // armed 0 takeovers; pure tick-count arms reliably.)
            boolean wantClimbNow = edge != null && cwp.getY() > foot.getY();
            boolean touchingWater = p.isInWater() || world.isWater(foot) || world.isWater(foot.below());
            if (touchingWater) waterTouchRecent = WATER_TOUCH_STICKY;
            else if (waterTouchRecent > 0) waterTouchRecent--;
            boolean nearWater = touchingWater || waterTouchRecent > 0;
            if (wantClimbNow) wantClimbRecent = WANT_CLIMB_STICKY;
            else if (wantClimbRecent > 0) wantClimbRecent--;
            boolean wantClimb = wantClimbNow || wantClimbRecent > 0;
            boolean waterClimbing = wantClimb && nearWater && !p.onGround();
            // Floating over DEEP water (water directly below the foot) with the dig
            // available: a buoyant bot can't swim-jump a +1 bank AND can't clear a surface
            // fill cell to pillar, so the pillar is ALWAYS futile here — pure wasted bob.
            // Skip it and engage the fast dig directly (the live journey's banks are all
            // this case, and it was eating ~50 ticks of futile pillaring per bank before
            // climbPillarGaveUp fell through to the dig). When break is OFF (place-only
            // arena) this is false → the pillar is kept as the only exit.
            boolean deepDig = world.isWater(foot.below())
                    && BotConfig.allowBreak && BotConfig.allowSwimEscapeBreak;
            if (!wantClimb || !nearWater) {
                // Left the climb context (grounded on the bank, or A* now routes
                // down/along) → clear the per-attempt accounting AND the "pillar
                // gave up" latch, so the NEXT genuine climb-out starts fresh.
                waterClimbStall = 0;
                climbPillarGaveUp = false;
                pillarNoPlaceTicks = 0;
                lastDigRiser = null;
                waterClimbDigRiser = null;
            } else waterClimbStall++;
            // Trigger once bob-stalled below a bank we can't mount, with a placeable in
            // hand — then LATCH a pillar-up that runs to completion. Suppressed once the
            // pillar has proven futile here (climbPillarGaveUp): a buoyant bob can't lift
            // its feet above a surface fill cell, so re-engaging just bobs again — the
            // bank-DIG below takes over instead.
            if (waterClimbing && waterClimbStall > WATER_CLIMB_STALL && !climbPillarGaveUp && !deepDig
                    && BotConfig.allowSwimEscapePlace && a.holdPlaceable()) {
                if (!waterClimbPillaring && BotConfig.walkerDebug)
                    LOG.info("[walker] water climb-out: pillar takeover engaged (bob-stalled) toward bank node {},{},{}",
                            cwp.getX(), cwp.getY(), cwp.getZ());
                waterClimbPillaring = true;
                // LOCK the column + heading at engage. The takeover pillars the bot's
                // own column STRAIGHT UP, pinned to this one bank, until it tops out of
                // the water onto dry ground. Following the live (repathing) node instead
                // made the bot wander between columns — chasing dive-to-floor and
                // other-column pillar nodes A* kept replanning — and lose the
                // wall-supported foothold (live round76c: engaged toward y145 pool
                // floor + x2438→2441 drift, never climbed out).
                waterClimbColX = foot.getX();
                waterClimbColZ = foot.getZ();
                double ex = (cwp.getX() + 0.5) - p.getX();
                double ez = (cwp.getZ() + 0.5) - p.getZ();
                waterClimbYaw = (ex * ex + ez * ez > 1e-4)
                        ? (float) Math.toDegrees(Math.atan2(-ex, ez)) : p.getYRot();
                // Safety ceiling: a sane bank is +1..+3; never pillar more than +5 above
                // the engage foot, then bail to the fallback actuators.
                waterClimbTargetY = foot.getY() + 5;
            }
            // PILLAR-UP climb-out: place support blocks in the bot's OWN column up to the
            // bank stand level, so the final move onto the bank is a flush WALK — not a
            // fragile in-place +1 jump. A single surface foothold only lifts +1; a +2
            // bank then left an un-runnable +1 step (no running room, water behind) the
            // bot pogo-bobbed forever (live round69: jumped to bank height but z frozen,
            // never translated across). Reading-only — the pathfinder is unchanged.
            if (waterClimbPillaring) {
                boolean haveBlock = BotConfig.allowSwimEscapePlace && a.holdPlaceable();
                // Done when we've topped out onto DRY solid ground (grounded, clear of
                // water). The old `foot.y >= targetY` test fired the instant targetY was
                // a path node BELOW us (A* dives to the pool floor), declaring success at
                // the water surface before any real climb — round76c. A terrain test is
                // robust to whatever A* planned and to the exact bank height.
                boolean dryGrounded = p.onGround() && !p.isInWater()
                        && !world.isWater(foot) && !world.isWater(foot.below());
                boolean tooHigh = foot.getY() > waterClimbTargetY;
                // Self-correction: the latch pins the bot to ONE locked column +
                // heading, which goes stale two ways in a live crossing — (a) the bot
                // DRIFTS off the column (swimming along a continuous bank), so the place
                // targets an unreachable far cell; (b) A* repaths the climb away (now
                // routes down/along → wantClimb gone), pinning the bot to a wall it
                // should swim past. And (c) a buoyant bob simply can't lift its feet
                // above a surface fill cell, so the place never fires. Any of these →
                // release the latch; for (a)/(c)/over-ceiling, remember it
                // (climbPillarGaveUp) so the bank-DIG takes this bank instead of the
                // pillar re-engaging into the same hopeless bob. (live 2026-06-15:
                // latched col z1954, bot drifted to z1940 while the path went diagDown —
                // 90 s deadlock bobbing at an unreachable column.)
                boolean drifted = Math.abs(foot.getX() - waterClimbColX) > 2
                        || Math.abs(foot.getZ() - waterClimbColZ) > 2;
                boolean staleClimb = !wantClimb;
                boolean placeFutile = pillarNoPlaceTicks > PILLAR_FUTILE_TICKS;
                if (dryGrounded || tooHigh || !haveBlock || drifted || staleClimb || placeFutile) {
                    waterClimbPillaring = false;
                    pillarNoPlaceTicks = 0;
                    if (dryGrounded) {
                        // Out of the water on solid ground → re-plan; a flush walk now.
                        path = null;
                        stuckTicks = 0;
                        totalTicks = 0;
                        waterClimbStall = 0;
                        if (BotConfig.walkerDebug)
                            LOG.info("[walker] water climb-out: topped out dry at y={} → flush walk, repath", foot.getY());
                        return Step.WALKING;
                    }
                    if (drifted || placeFutile || tooHigh) climbPillarGaveUp = true;
                    if (BotConfig.walkerDebug)
                        LOG.info("[walker] water climb-out: bail ({}) → fallback",
                                !haveBlock ? "no block" : tooHigh ? "over ceiling"
                                : drifted ? "drifted off column" : placeFutile ? "place futile"
                                : "no longer climbing");
                    // fall through to the normal actuators / bank-dig
                } else {
                    // Pin to the LOCKED bank heading + column; look down to aim the place.
                    p.setYRot(waterClimbYaw); p.yHeadRot = waterClimbYaw; p.yBodyRot = waterClimbYaw;
                    p.setXRot(40f);
                    agentForward(a, true);
                    p.setSprinting(false);
                    agentJump(a, true);
                    // Fill the top water cell of the LOCKED column (floating) or the feet
                    // cell (grounded on the fresh rung) — not the live foot column, which
                    // drifts off the wall-supported pillar.
                    BlockPos colFoot = new BlockPos(waterClimbColX, foot.getY(), waterClimbColZ);
                    BlockPos fillCell = world.isWater(colFoot) ? colFoot : foot;
                    while (world.isWater(fillCell.above())) fillCell = fillCell.above();
                    boolean fcSolid = world.isSolid(fillCell);
                    boolean fcSupport = Move.hasPlaceSupport(world, fillCell);
                    boolean fcCleared = p.getY() >= fillCell.getY() + 0.9;
                    if (BotConfig.walkerDebug)
                        LOG.info("[walker] climbout-place col={},{} fill={} fcSolid={} support={} cleared={}(p.y={} need={}) dryG={} foot.y={} ceil={}",
                                waterClimbColX, waterClimbColZ, fillCell.getY(), fcSolid, fcSupport, fcCleared,
                                String.format("%.2f", p.getY()), fillCell.getY() + 0.9,
                                dryGrounded, foot.getY(), waterClimbTargetY);
                    if (!fcSolid && fcSupport && fcCleared) {     // feet cleared the cell
                        a.place(world, fillCell);
                        pillarNoPlaceTicks = 0;                    // made a place → progressing
                    } else {
                        pillarNoPlaceTicks++;                      // bobbing, can't clear the cell
                    }
                    return Step.WALKING;
                }
            }
            // Block-less climb-out fallback: a buoyant bot bob-stalled below a +1
            // bank with NO usable (non-falling) support block can't pillar — but it
            // can DIG. Break the single bank riser toward the climb node at foot
            // level so the +1 mount becomes a flat swim into the notch: the bot
            // enters the freed cell, grounds on whatever's below, and the next step
            // is a normal grounded climb (or a clean repath from the lower cell).
            // Gated identically to the pillar takeover (waterClimbing + bob-stalled) so
            // a grounded land step never triggers it. Fires when there's no place block
            // OR the pillar gave up here (climbPillarGaveUp) — a buoyant bob can't lift
            // its feet above a surface fill cell, so for a bank whose top is above the
            // water the DIG is the reliable primitive whether or not blocks are in hand
            // (the live z1940 deadlock had sand/gravel/cobble yet the place never cleared
            // → 90 s bob; the dig breaks the riser and the bot swims into the notch).
            // (live 2026-06-15: deep-water +1 dirt bank, sand/gravel-only inventory →
            // holdPlaceable false → 12.6s bob-stall, move=diagUp with empty toBreak so
            // neither swimAshore nor the floating-pocket break engaged; bob peak y63.56
            // sat 0.44 below the y64 ledge, hCol ramming the riser every tick.)
            int digStall = deepDig ? WATER_CLIMB_DIG_DEEP_STALL : WATER_CLIMB_DIG_STALL;
            if (!waterClimbPillaring && waterClimbing && waterClimbStall > digStall
                    && BotConfig.allowBreak && BotConfig.allowSwimEscapeBreak
                    && (!a.holdPlaceable() || climbPillarGaveUp || deepDig)) {
                // Keep digging the LATCHED riser while it's still solid — a buoyant bob
                // (foot.y flickering ±1) or lateral drift (foot.z wandering) must NOT
                // re-target a lower block of the same column or a neighbouring column
                // mid-dig. Choose a fresh riser only once the latched one breaks.
                // The fix the drift arena exposed: the old code dug `foot.y` directly, so
                // a low bob dug the foot-level block AND a high bob dug the step block of
                // the SAME column → the bank surface tunnelled DOWN to the water line and
                // the next column stayed a fresh +2 wall (infinite pogo, ashoreTick 162).
                BlockPos riser = waterClimbDigRiser;
                if (riser == null || !world.isSolid(riser)) {
                    riser = null;
                    int dx = Integer.signum(cwp.getX() - foot.getX());
                    int dz = Integer.signum(cwp.getZ() - foot.getZ());
                    BlockPos[] cands = {
                            (dx != 0 || dz != 0) ? new BlockPos(foot.getX() + dx, foot.getY(), foot.getZ() + dz) : null,
                            dx != 0 ? new BlockPos(foot.getX() + dx, foot.getY(), foot.getZ()) : null,
                            dz != 0 ? new BlockPos(foot.getX(), foot.getY(), foot.getZ() + dz) : null};
                    for (BlockPos cand : cands) {
                        if (cand == null || !world.isSolid(cand)) continue;
                        // Dig the TOP solid block of this forward column (the step block),
                        // not the foot-level block: removing the top lowers the bank
                        // surface by exactly one → a clean +1 step the bot then mounts.
                        // Walking up to the top makes the choice bob-INVARIANT (always the
                        // wall crest, whatever the live foot bob). Only engage when it's
                        // MORE than a +1 step (top.y > foot.y); a +1 step is already
                        // mountable, and digging it would tunnel the bank below the water.
                        BlockPos top = cand;
                        while (world.isSolid(top.above())) top = top.above();
                        if (top.getY() > foot.getY()) { riser = top; break; }
                    }
                    waterClimbDigRiser = riser;
                }
                if (riser != null) {
                    if (BotConfig.walkerDebug)
                        LOG.info("[walker] water climb-out: block-less bank dig (bob-stalled, no place block) riser={},{},{}",
                                riser.getX(), riser.getY(), riser.getZ());
                    a.selectTool(riser);
                    // Re-snap the look onto the riser ONLY when it changes, not every
                    // tick: aimAtBlock SNAPS yaw+pitch from the LIVE (bobbing) eye, so a
                    // per-tick call judders the camera ~25°/cycle — the "镜头剧烈抖动" the
                    // video flags during digs. The first snap aims dead-on; the ±0.5 bob
                    // then keeps the crosshair on the 1-tall riser face while the camera
                    // holds steady, and we only re-aim when the dig moves to a new riser.
                    if (!riser.equals(lastDigRiser)) {
                        a.aimAtBlock(riser);
                        lastDigRiser = riser;
                    }
                    a.breakHold(true);
                    // Mark the dig active: next tick's breakingEdge holds the leash and
                    // exempts the burst so the dig can finish (see the breakingEdge note).
                    // Clear any burst count accrued during the pre-dig bob-stall so the
                    // very first dig tick can't fire a stale burst before the exemption.
                    waterClimbDigging = true;
                    wedgeRepathsHere = 0;
                    // Press into the bank to anchor the aim ONLY at the surface
                    // (forward while submerged drops the bot into the prone-swim pose
                    // and it sinks); autoSwim keeps it floating at the surface so it
                    // can't drown while the dig finishes.
                    if (!p.isUnderWater()) agentForward(a, true);
                    return Step.WALKING;
                }
            }
        }

        // Pillar-up actuator: clear the ceiling if one blocks the rise, then
        // jump and place the support block beneath at the apex. Distinct from
        // the generic place actuator because it owns the airborne timing
        // (you can't place a block in the cell you're standing in).
        if (edge != null && "pillarUp".equals(edge.move) && hasPendingEdge(world, edge)) {
            agentForward(a, false);
            p.setSprinting(false);
            totalTicks = 0;
            stuckTicks = 0;   // pillaring stays on one cell while placing — not "stuck"
            if (step != pillarStep) { pillarStep = step; pillarSinceJump = -1; }
            if (++actionTicks > BotConfig.breakTimeoutTicks) {
                a.breakHold(false);
                agentJump(a, false);
                lastError = "pillar stalled at " + path.get(step);
                path = null;
                return Step.WALKING;
            }
            for (BlockPos b : edge.toBreak) {
                if (world.isSolid(b)) {
                    agentJump(a, false);
                    a.selectTool(b);
                    a.aimAtBlock(b);
                    a.breakHold(true);
                    return Step.WALKING;
                }
            }
            a.breakHold(false);
            // Buoyant pillar — the bot is rising out of water. Two sub-cases:
            //   (a) FLOODED shaft (the destination cell is itself water): just hold
            //       jump and FLOAT up through it; water follows up so the next
            //       ceiling break repeats until the bot surfaces. No place — a block
            //       would only dam the float.
            //   (b) DRY air above (a partly-mined bunker / 1-deep pocket with an open
            //       chimney): the swim-bob alone never gains permanent height, so on
            //       the crest — when the feet clear the place cell — PLACE a support
            //       there to stand on, lifting the bot one block; after that first
            //       lift it is grounded and the normal dry pillar climbs the rest.
            // Detect water from the world (cell below is water), not p.isInWater(),
            // which flickers false at the bob peak and would drop the jump.
            boolean shaftFlooded = world.isWater(path.get(step));
            if (shaftFlooded || p.isInWater() || world.isWater(path.get(step).offset(0, -1, 0))) {
                agentJump(a, true);
                if (!shaftFlooded && a.holdPlaceable()) {
                    BlockPos wp = edge.toPlace.get(0);
                    p.setXRot(89.5f);                       // look down to aim the support
                    if (p.getY() >= wp.getY() + 0.9) {      // bobbed clear of the place cell
                        a.placeOn(wp.offset(0, -1, 0), Direction.UP);
                    }
                }
                return Step.WALKING;
            }
            if (!a.holdPlaceable()) {
                lastError = "pillar: no placeable block in hotbar";
                path = null;
                return Step.WALKING;
            }
            BlockPos place = edge.toPlace.get(0);                    // cell we fill (old feet cell)
            BlockPos support = place.offset(0, -1, 0);               // click its top face (block we stood on)
            p.setXRot(89.5f);                                        // look straight down (snap)
            if (p.onGround()) {
                agentJump(a, true);
                pillarSinceJump = 0;
            } else {
                agentJump(a, false);
                if (pillarSinceJump >= 0) pillarSinceJump++;
                // Place only once the feet have actually risen clear of the cell
                // being filled. The target IS the old feet cell, so vanilla's
                // entity-collision check (Level#isUnobstructed) silently rejects
                // the block while the player AABB still overlaps it — i.e. until
                // the feet (getY) reach that cell's top face, place.y + 1. A
                // vanilla jump (peak ~+1.25) only crosses +1.0 around tick 4, so
                // the old fixed 3-tick delay fired at ~+0.99 and the place
                // no-op'd against the player's own body. Gate on real height.
                if (pillarSinceJump >= PILLAR_PLACE_DELAY && p.getY() >= place.getY() + 1.0) {
                    a.placeOn(support, Direction.UP);
                }
            }
            return Step.WALKING;
        }

        // Parkour-place actuator (Baritone allowParkourPlace): a two-phase leap
        // owned here so the generic place actuator below (which freezes all
        // motion to place) can't kill the arc's momentum.
        //   LEAP  (floor still air): forward + sprint + jump off the lip with
        //         run-up PRESERVED, then place the landing block mid-air the
        //         instant a support is in reach. The hit is synthetic
        //         (clientUseItemOn), so aiming down isn't needed — the
        //         crosshair stays on the destination for the whole arc.
        //   SETTLE(floor placed, still airborne): keep driving forward to clear
        //         the gap, but DROP sprint and hold sneak — sneak's ledge guard
        //         stops the bot on the fresh 1-wide block on touchdown instead
        //         of letting sprint momentum carry it off the far edge into a
        //         gap beyond (matters when the place-support is below/beside,
        //         not a walkable same-level wall).
        // Once placed AND grounded the guard falls through to the normal walk /
        // arrival check.
        if (edge != null && edge.move != null && edge.move.startsWith("parkourPlace")
                && (hasPendingEdge(world, edge) || !p.onGround())) {
            BlockPos dest = path.get(step);
            BlockPos floor = edge.toPlace.get(0);
            boolean placed = !hasPendingEdge(world, edge);   // landing block is down
            boolean grounded = p.onGround();
            totalTicks = 0;
            stuckTicks = 0;       // owns this cell while leaping — not "stuck"
            if (++actionTicks > BotConfig.breakTimeoutTicks) {
                lastError = "parkour-place stalled at " + dest;
                path = null;
                return Step.WALKING;
            }
            // Snap heading at the destination — can't course-correct mid-air.
            double adx = (dest.getX() + 0.5) - p.getX();
            double adz = (dest.getZ() + 0.5) - p.getZ();
            if (Math.abs(adx) > 1e-4 || Math.abs(adz) > 1e-4) {
                float yaw = (float) Math.toDegrees(Math.atan2(-adx, adz));
                p.setYRot(yaw);
                p.yHeadRot = yaw;
                p.yBodyRot = yaw;
                LookController.requestSnap();   // a parkour leap's heading is functional — exempt from the global slew
            }
            p.setXRot(0f);
            agentForward(a, true);
            agentJump(a, !placed && grounded);   // jump off the lip once
            boolean sprint = !placed;                          // brake after the block is down
            p.setSprinting(sprint);
            agentSneak(a, placed);               // sneak-brake / ledge-guard on landing
            p.setShiftKeyDown(placed);
            if (!placed && !grounded && a.holdPlaceable()) {
                Vec3 eye = p.getEyePosition();
                double fdx = (floor.getX() + 0.5) - eye.x, fdy = (floor.getY() + 0.5) - eye.y, fdz = (floor.getZ() + 0.5) - eye.z;
                boolean inReach = fdx * fdx + fdy * fdy + fdz * fdz < 16;   // ~4 blocks of the eye
                if (inReach) {
                    a.place(world,floor);
                    if (BotConfig.walkerDebug)
                        LOG.info(
                                "[walker] parkour-place floor={},{},{} y={} dy={} solid={}",
                                floor.getX(), floor.getY(), floor.getZ(),
                                String.format(Locale.ROOT, "%.2f", p.getY()),
                                String.format(Locale.ROOT, "%.2f", p.getDeltaMovement().y),
                                world.isSolid(floor));
                }
            }
            return Step.WALKING;
        }

        // Break/place actuator: mine or place the blocks this edge needs
        // before walking into the cell. Functional aim SNAPS (same-tick),
        // independent of smoothLook. Returns each tick until the edge is
        // clear, then falls through to the normal walk below.
        if (edge != null && pillarRecoverLatch <= 0 && hasPendingEdge(world, edge)) {
            // pillarRecoverLatch gate: while a fall-below-route recovery is active,
            // this actuator would otherwise grab the edge's HIGH toBreak cell
            // (unreachable from down here) and hold a futile dig until its own
            // timeout — starving the recovery block below that actually climbs.
            agentForward(a, false);
            agentJump(a, false);
            p.setSprinting(false);
            totalTicks = 0;                       // breaking/placing IS progress
            stuckTicks = 0;                       // foot stays put while placing — don't trip the wiggle-jump (it'd leap off a 1-wide bridge)
            if (++actionTicks > BotConfig.breakTimeoutTicks) {
                // Lag or an unexpected obstruction — drop the path and let
                // the next tick repath from the current position.
                a.breakHold(false);
                lastError = "break/place stalled at " + path.get(step);
                path = null;
                return Step.WALKING;
            }
            // Water-escape break (swimAshore / swimTraverseBreak): while breaking the
            // bank, a FLOATING bot drifts off its foot cell and the edge invalidates
            // before the block breaks — it bobs and never climbs out. We anchor by
            // pressing INTO the bank, but ONLY when the head is at the surface
            // (!isUnderWater). The earlier unconditional keyUp drowned the bot:
            // forward input while SUBMERGED drops it into the prone swim pose and it
            // sinks. Gating on surface means forward can't trigger swim-pose, so it
            // presses the body against the bank + jumps to mount, holding position
            // long enough to finish the dig. (#9 autoSwim still surfaces it each tick.)
            boolean swimEscapeBreak = edge.move != null
                    && (edge.move.startsWith("swimAshore") || edge.move.startsWith("swimTraverseBreak"));
            // Generic floating-pocket climb-break: the planner can route a *dry*
            // move (stairUpBreak / stepUp) through a flooded canyon pocket. Executed
            // while the bot floats (isInWater && !onGround) with the break cell at or
            // above the feet, the buoyant bot drifts off its foot cell and the edge
            // invalidates before the block breaks → it bobs forever hand-mining the
            // bank without escaping (live canyon water-pocket dig-loop at
            // (2358,62,1863), break0=(2358,63,1862); the break-exempt stuck counter
            // means neither the freeze-breaker nor the anti-stuck burst engages).
            // Anchor it like swimAshore: press INTO the aimed bank at the surface
            // (gated !isUnderWater so forward never triggers the prone-swim drown)
            // and jump to mount the freed cell. swimEscape moves keep their own
            // handling (excluded), descending/diving breaks (cell below the feet)
            // are excluded so this never fights a swimDown.
            boolean floatingPocket = !swimEscapeBreak && p.isInWater()
                    && !p.onGround() && !p.isUnderWater();
            for (BlockPos b : edge.toBreak) {
                if (world.isSolid(b)) {
                    a.selectTool(b);
                    a.aimAtBlock(b);
                    a.breakHold(true);
                    boolean climbBreak = floatingPocket && b.getY() >= foot.getY();
                    if ((swimEscapeBreak && p.isInWater() && !p.isUnderWater()) || climbBreak) {
                        agentForward(a, true);     // press into the aimed bank (surface only)
                        if (climbBreak || edge.move.startsWith("swimAshore"))
                            agentJump(a, true);   // rise to mount the +1 / out of the pocket
                    }
                    return Step.WALKING;
                }
            }
            a.breakHold(false);
            for (BlockPos b : edge.toPlace) {
                if (!world.isSolid(b)) {
                    if (BotConfig.walkerDebug)
                        LOG.info(
                                "[walker] place-act foot={},{},{} y={} step={} placing={},{},{} onGround={} edge={}",
                                foot.getX(), foot.getY(), foot.getZ(), String.format(Locale.ROOT, "%.2f", p.getY()),
                                step, b.getX(), b.getY(), b.getZ(), p.onGround(), edge.move);
                    a.aimAtBlock(b);
                    a.place(world,b);
                    return Step.WALKING;
                }
            }
            return Step.WALKING;                  // settle a tick before walking on
        }
        a.breakHold(false);
        actionTicks = 0;

        // Pillar placed but the player is still rising onto it — hold (no
        // horizontal walk) until grounded at the new level, so we don't walk
        // off the fresh block mid-jump.
        if (edge != null && "pillarUp".equals(edge.move)
                && !(p.onGround() && p.getY() >= path.get(step).getY() - 0.1)) {
            agentForward(a, false);
            agentJump(a, false);
            p.setSprinting(false);
            return Step.WALKING;
        }

        BlockPos wp = path.get(step);
        Move.Edge nextEdge = edgeAt(step + 1);
        boolean parkourEdge = edge != null && edge.move != null && edge.move.startsWith("parkour");
        // Bridging-over-a-gap detection (place-bridge on the current OR next edge),
        // hoisted up here so the travel-aim pitch (below) can keep the camera trained on
        // the bridge frontier instead of being yanked back to the horizon between place
        // ticks. The sneak/sprint gating that also reads this lives further down.
        boolean bridging = (edge != null && "bridgePlace".equals(edge.move))
                || (nextEdge != null && "bridgePlace".equals(nextEdge.move));
        // ANY block-placing edge (pillarUp / parkourPlace / stair scaffolds), current or
        // next: the place-tick aimAtBlockSnap pitches the camera down exactly like a
        // bridge place does, so the pitch-hold below must cover these too — a steep
        // dirt-ladder ascent showed the same down-snap ↔ horizon-pull sawtooth the
        // bridge fix (#30) solved, just via pillarUp edges instead of bridgePlace.
        boolean placingEdge = (edge != null && !edge.toPlace.isEmpty())
                || (nextEdge != null && !nextEdge.toPlace.isEmpty());
        // VINE traversal (user insight 2026-06-06): vines are CLIMBABLE, so when the
        // walker fights a stale waypoint while the body is clinging to a vine it wedges
        // for seconds (the jungle cling-freeze: suspended off-ground, hCol, hSpd≈0, while
        // the waypoint sat 3 blocks BELOW). Don't fight it — a vine is a fixed climb
        // sub-path. Once the body is suspended ON a vine (!onGround), take over: face the
        // path-ahead bearing and, if the path ahead is up/level ("direction correct"),
        // CLIMB the vine (forward+jump); the offPath(>3) / stuck repath then re-routes
        // from the new elevation ("修正路径"). If the path ahead is BELOW (we over-climbed),
        // release the climb keys so the body slides back down the vine to be re-planned.
        // Gated on !onGround so it never hijacks normal ground-walking through a vine-
        // draped cell; ladders are excluded by isClimbable's caller using vines in jungle.
        boolean onVine = !parkourEdge && !p.isInWater() && !p.onGround() && world.isClimbable(foot);
        if (onVine) {
            BlockPos ahead = path.get(Math.min(step + 2, path.size() - 1));
            double vdx = (ahead.getX() + 0.5) - p.getX();
            double vdz = (ahead.getZ() + 0.5) - p.getZ();
            float vYaw = (vdx * vdx + vdz * vdz < YAW_DEADZONE_SQ)
                    ? p.getYRot()
                    : (float) Math.toDegrees(Math.atan2(-vdx, vdz));
            if (Math.abs(angleDiff(p.getYRot(), vYaw)) > BotConfig.walkerYawHysteresisDeg) {
                float want = smoothAngle(p.getYRot(), vYaw);
                float dyaw = angleDiff(p.getYRot(), want);
                if (Math.abs(dyaw) > WALKER_MAX_YAW_SLEW_DEG) dyaw = Math.copySign(WALKER_MAX_YAW_SLEW_DEG, dyaw);
                float ny = p.getYRot() + dyaw;
                p.setYRot(ny); p.yHeadRot = ny; p.yBodyRot = ny;
            }
            p.setXRot(smoothAngle(p.getXRot(), 0f));
            boolean climbUp = ahead.getY() >= foot.getY();   // path ahead up/level → climb; below → over-climbed, descend
            agentForward(a, climbUp);               // forward INTO the vine = climb up (vanilla vine ascent)
            agentJump(a, climbUp);             // jump also drives vine ascent; off → slide back down
            agentSneak(a, false);              // sneak would HALT the vine climb
            p.setShiftKeyDown(false);
            p.setSprinting(false);
            if (BotConfig.walkerDebug)
                LOG.info("[walker] vine-climb foot={},{},{} ahead={},{},{} climbUp={} yaw={}",
                        foot.getX(), foot.getY(), foot.getZ(),
                        ahead.getX(), ahead.getY(), ahead.getZ(), climbUp,
                        String.format(Locale.ROOT, "%.0f", p.getYRot()));
            return Step.WALKING;
        }
        // Stepping off into an MLG fall: walk off at WALK speed (no sprint) so
        // the body drops near-vertically and lands in the water we place under
        // it — a sprint launch carries horizontal momentum that drifts the bot
        // off the placed source and it clips the edge (partial fall damage).
        // Do NOT sneak though: sneak is ledge-protection and would stop the bot
        // walking off the edge at all. Triggered when the edge we're walking
        // onto OR the next one is an MLG fall.
        boolean steppingOffFall = (edge != null && edge.move != null && edge.move.startsWith("fallBucket"))
                || (nextEdge != null && nextEdge.move != null && nextEdge.move.startsWith("fallBucket"));
        // A fall into EXISTING water (Move.FallIntoWater, "fallWater*") wants
        // the same near-vertical step-off — no sprint — so the longer airtime
        // of a tall drop doesn't carry the bot horizontally past the 1-wide
        // water column and onto dry land beyond. But there's no source to
        // place or scoop here, so it must NOT arm the mlg latch (that latch
        // drives water placement and would strand an empty hand mid-fall).
        boolean steppingOffWaterFall = (edge != null && edge.move != null && edge.move.startsWith("fallWater"))
                || (nextEdge != null && nextEdge.move != null && nextEdge.move.startsWith("fallWater"));
        // Commit to the MLG fall while still on the launch lip: the latch at
        // the top of tick() then owns the entire descent (placing/scooping the
        // water) independent of how A* relabels the edge as we near the ground.
        if (steppingOffFall) {
            // Commit to the MLG fall while still on the launch lip, recording
            // the planned landing column so the airborne clutch can damp drift
            // back toward it: a walk-off's residual horizontal momentum (zero
            // air friction) otherwise carries the body past a 1-wide landing
            // before it descends, and the water gets placed on whatever happens
            // to be below the drifted-into column instead.
            BlockPos land = (edge != null && edge.move != null && edge.move.startsWith("fallBucket"))
                    ? wp : path.get(step + 1);
            if (land != null) CLUTCH.armPlanned(land.getX(), land.getZ());
        }

        // Aim at a line-of-sight carrot further along the (now string-pulled)
        // path so the heading stays steady — no left-right wobble. For a
        // vertical move or a real parkour leap, face the actual waypoint so
        // the jump goes the right way.
        // A buoyant bot BOBS ±0.5 around its swim level, so foot.getY()=floor(p.getY())
        // flickers between the node's Y and Y-1 even on a dead-flat swim. With the raw
        // `wp.getY() != foot.getY()` test that toggled aimAtWaypoint every bob tick,
        // snapping the aim between the stable look-ahead carrot and the CLOSE waypoint
        // and swinging the yaw left-right (live 2026-06-15 deep-water crossing: camera
        // "高频左右摆", pathChart maxYawErr 172°). In water, judge the vertical maneuver
        // by the CONTINUOUS Y gap to the waypoint with a bob-proof threshold (a flat or
        // gently-graded swim stays on the carrot; only a genuine dive / bank-climb ≥~1.5
        // aims at the block). On land the integer test is exact and unchanged.
        double wpAimDy = (wp.getY() + 0.5) - p.getY();
        boolean aimAtWaypoint = (p.isInWater() ? Math.abs(wpAimDy) > 1.5 : wp.getY() != foot.getY())
                || parkourEdge;
        // Re-centre recovery on a stuck flat walk: a 1-wide channel needs the
        // body centred on the lane axis or the off-centre hitbox snags a corner
        // and wedges (the look-ahead carrot aims diagonally, so it never centres
        // and the bot grinds the boundary). When genuinely stuck, steer to the
        // centre of the last confirmed on-spine node (the cell we came from):
        // that pulls the body straight onto the lane axis, after which the carrot
        // — aimed at the next node, same axis — is a clean straight push. Only
        // fires when stuck (normal open walking never is), so it can't reverse a
        // healthy run.
        // Cross-axis re-centre on a stuck flat walk: a 1-wide channel flush against
        // a wall needs the body held on the lane axis or the off-centre hitbox
        // grazes the wall and can't slide forward. When stuck, steer PURELY along
        // the cross axis of the immediate cardinal move (correct X for a N/S lane,
        // Z for an E/W lane) — never along the lane itself, so it can't cancel the
        // forward carrot by pulling backward (the bug that pinned the bot mid-
        // channel). Once centred (≤0.1) it releases and the carrot's straight push
        // resumes; the two alternate but only ever add forward + lateral, so the
        // bot threads the channel instead of grinding the wall.
        // Off-spine recovery: if we've drifted to a cell from which the immediate
        // waypoint is diagonal (slid back across a corner), the carrot would aim
        // diagonally and re-snag the wall — instead steer back to the previous
        // on-spine node's centre to regain the lane. Lateral lane-CENTRING on the
        // spine is handled by the strafe below, so this only handles the gross
        // drift-off case (foot no longer in the spine cell).
        boolean reCentre = false;
        double recX = 0, recZ = 0;
        if (!aimAtWaypoint && stuckTicks > 5 && step > 0) {
            BlockPos sp = path.get(step - 1);
            boolean onSpine = foot.getX() == sp.getX() && foot.getZ() == sp.getZ();
            if (!onSpine && losWalkable(world, foot, sp)) {
                double rcx = (sp.getX() + 0.5) - p.getX();
                double rcz = (sp.getZ() + 0.5) - p.getZ();
                // Only re-centre toward the previous node when it is NOT behind us:
                // once the bot has progressed past sp, aiming at its centre points
                // backward and steers the body the wrong way (a stuck spot then drags
                // the heading ~180° around — the residual backward episode the slew
                // clamp turned from a flip into a slow reversal). Gate on the dot
                // product with the forward (toward-waypoint) direction so reCentre only
                // fires for genuine lateral drift, never to reverse. Backward case
                // falls through to the carrot, which keeps pushing forward.
                double fwx = (wp.getX() + 0.5) - p.getX();
                double fwz = (wp.getZ() + 0.5) - p.getZ();
                if (rcx * fwx + rcz * fwz >= 0) {
                    recX = rcx;
                    recZ = rcz;
                    reCentre = true;
                }
            }
        }
        double adx, adz;
        if (aimAtWaypoint) {
            adx = (wp.getX() + 0.5) - p.getX();
            adz = (wp.getZ() + 0.5) - p.getZ();
        } else if (reCentre) {
            adx = recX; adz = recZ;
        } else {
            double[] c = carrotPoint(world, foot, p.getX(), p.getZ());
            adx = c[0] - p.getX();
            adz = c[1] - p.getZ();
        }
        // Hold heading when the horizontal aim vector is tiny (within the dead-zone)
        // so atan2 on sub-block noise can't snap the yaw ±90/±180 each tick — see
        // YAW_DEADZONE_SQ. Above the dead-zone the bearing is well-defined.
        float targetYaw = (adx * adx + adz * adz < YAW_DEADZONE_SQ)
                ? p.getYRot()   // essentially on the aim column — hold heading, don't thrash atan2
                : (float) Math.toDegrees(Math.atan2(-adx, adz));
        // A launch into a leap (parkour or MLG fall) must SNAP the heading even when
        // smoothLook is on — you can't course-correct mid-air, so a lagged launch sends
        // the bot off at an angle (drifts off a narrow landing). Launches bypass both the
        // EMA and the slew cap; everything else is smoothed + capped.
        boolean launch = parkourEdge || steppingOffFall || steppingOffWaterFall;
        // Low-pass the TARGET heading (EMA on the shortest angle, kept in [-180,180]) so a
        // grid staircase on a diagonal — whose immediate-waypoint bearing alternates ±~30°
        // around the true diagonal each step — averages to a steady bearing instead of a
        // bounded sawtooth (see YAW_SMOOTH_ALPHA). Resync on launch / first use.
        if (Float.isNaN(smoothTargetYaw) || launch) {
            smoothTargetYaw = targetYaw;
        } else {
            smoothTargetYaw = angleDiff(0f, smoothTargetYaw + YAW_SMOOTH_ALPHA * angleDiff(smoothTargetYaw, targetYaw));
        }
        float aimYaw = launch ? targetYaw : smoothTargetYaw;
        // ANTI-SPIN camera freeze: while churning in water (consecutive repaths with no
        // goal-progress — a failed climb-out whose best-effort segments keep flipping
        // the waypoint behind the bot), HOLD the heading instead of chasing the
        // flipping target. A ~180° flip resolves the same rotational way each time, so
        // chasing it winds the camera one direction (raw yaw past -900 ≈ 2.5 turns in
        // the trace — the water "转圈"). Freezing stops the wind AND, since MC movement
        // follows body yaw, steadies the bot pressing one direction toward the climb-
        // out rather than U-turning. Launches still snap. Cleared as soon as progress
        // resumes (repathsNoProgress resets). */
        // "Over water" covers the bob-at-a-bank case too: bobbing into a climb-out
        // ledge the body pops to y+0.x above the surface (isInWater flickers false) yet
        // is still a water stall — gate on water UNDER the foot as well so the freeze
        // catches the surface/bank spin, not only the fully-submerged one.
        boolean overWater = p.isInWater() || world.isWater(foot.offset(0, -1, 0));
        boolean spinFreeze = !launch && overWater && repathsNoProgress > CHURN_REPATH_CAP;
        if (!spinFreeze && Math.abs(angleDiff(p.getYRot(), aimYaw)) > BotConfig.walkerYawHysteresisDeg) {
            float ny;
            if (launch) {
                ny = aimYaw;   // launches must snap — no mid-air course correction
                LookController.requestSnap();   // exempt this leap's takeoff heading from the global camera slew
            } else {
                // Cap the per-tick turn (see WALKER_MAX_YAW_SLEW_DEG) so even a transient
                // bad aim vector can only nudge the heading, never reverse it in one tick.
                float want = smoothAngle(p.getYRot(), aimYaw);
                float dyaw = angleDiff(p.getYRot(), want);
                if (Math.abs(dyaw) > WALKER_MAX_YAW_SLEW_DEG) dyaw = Math.copySign(WALKER_MAX_YAW_SLEW_DEG, dyaw);
                // PIVOT FAST-TURN (stop-go sawtooth): a step-up pivot cuts forward
                // drive entirely until the heading is inside the 40° gate, so every
                // zigzag staircase corner stalls ~0.5 s at the 8°/tick cruise slew —
                // the speed square-wave the path charts show. While pivoting the body
                // is STATIONARY (drive already zero), so a faster pan is a quick
                // turn-in-place, not a moving-view jerk: turn at 24°/tick and the
                // stall drops to ~0.2 s. Cruise turns keep the gentle cruise slew.
                float pivotErr = angleDiff(p.getYRot(), aimYaw);
                if (wp.getY() > foot.getY() && !parkourEdge && !p.isInWater()
                        && Math.abs(pivotErr) > STEPUP_AIM_TOLERANCE_DEG) {
                    dyaw = Math.abs(pivotErr) > 24f ? Math.copySign(24f, pivotErr) : pivotErr;
                }
                ny = p.getYRot() + dyaw;
            }
            p.setYRot(ny);
            p.yHeadRot = ny;
            p.yBodyRot = ny;
        }
        // DIVE to follow a submerged node under a ceiling. In water the prone swim
        // travels along the LOOK vector, so a level pitch pins the body at the surface
        // — when the next node is BELOW and the bot is HORIZONTALLY BLOCKED (it rams a
        // low overhang lip: a submerged tunnel whose stone ceiling sits at the
        // DESTINATION cell's foot+1, so a fixed foot+2 check at the bot's own cell
        // misses it — the lethal stuck: hCol=true, hSpd=0, bobbing y62↔63 into the
        // lip), the bot must DIVE: pitch down + the prone-swim sprint (below) sink the
        // ~0.6-tall body to the tunnel floor where it fits under the lip and threads on.
        // Triggered by the real symptom — a blocked submerged descent — and LATCHED a
        // few ticks so the dive holds through the sink even as the collision flickers
        // off mid-descent (else it flip-flops upright and bobs back into the lip). An
        // OPEN descent (no collision) never triggers, so it keeps a level camera there.
        // Only a CAPPED submerged descent — a real overhang LIP, solid at the bot's
        // head+1 OR (as the comment above notes) the DESTINATION cell's foot+1 — needs
        // this dive. An OPEN pool surface where wp.y==foot.y-1 over water is just a
        // buoyant FLAT crossing (the foot rides one above the water node) with AIR
        // above: pitching down there sabotages the flat swim and sinks the bot to the
        // pool floor instead of crossing to the node. Without the cap test, a bot that
        // bumps a pocket wall while reversing toward a 1-SW surface node arms diveLatch
        // → pitch 50 → dives → bobs forever (live 2026-06-15 (2358,1863) canyon-pocket
        // north tip: N/E walls, exit SW over open water, totStuck 1400+, ~3 min hard
        // deadlock). wp.offset(0,1,0) is exactly the lip the original tunnel fix targets.
        boolean cappedDescent = world.isSolid(foot.offset(0, 2, 0)) || world.isSolid(wp.offset(0, 1, 0));
        if (p.isInWater() && wp.getY() < foot.getY() && p.horizontalCollision && cappedDescent) diveLatch = 12;
        else if (diveLatch > 0) diveLatch--;
        boolean diveUnderCap = p.isInWater() && wp.getY() < foot.getY() && diveLatch > 0;
        // ACTIVE dive for a submerged target ≥2 below a floating body (computed here,
        // before the pitch/sneak actuators that need it). Merely releasing the float
        // (the old "diving" = no jump) NEVER sinks a surface swimmer — buoyancy +
        // forward stroke hold y constant (mangrove swamp live: A* commits a riverbed
        // route y62→54 because the surface columns are walled by roots; the bot rode
        // y62 forever, offPath kept safetyRepath true every tick → 6 s burst storm).
        // A real descent needs sneak (vanilla water-sink) + a downward pitch.
        boolean diveTarget = (edge != null && edge.move != null && edge.move.startsWith("swimDown"))
                || (p.isInWater() && wp.getY() <= foot.getY() - 2 && world.isWater(wp));
        // LATCH the dive across repaths (round45 water-well live): the sink takes
        // many ticks, and a mid-sink repath/quick-start re-plans from the bot's
        // buoyancy point — its first hop is then dy=1, which alone never re-arms
        // the dy≥2 trigger above, so jump/swimUp popped the bot straight back to
        // the surface and the well column looped forever (20 anti-stuck bursts).
        // Once armed, hold the dive while the bot is still in water with the
        // waypoint below its feet; surfacing routes (wp at/above foot) clear it.
        if (diveTarget) diveHold = 30;
        else if (diveHold > 0 && p.isInWater() && wp.getY() < foot.getY()) diveHold--;
        else diveHold = 0;
        boolean diving = diveTarget || (diveHold > 0 && p.isInWater());
        // CAMERA-THRASH fix (bridge "镜头上下剧烈跳变"): while bridging, each place tick
        // does aimAtBlockSnap → requestSnap, instantly pitching the camera DOWN onto the
        // block being placed; pulling pitch back to the horizon (0) on every non-place
        // tick made the view saw violently between "look down at feet" and "look at sky".
        // Hold pitch where the place-snap left it across the whole bridge so the camera
        // sits in a steady downward gaze (one entry dip, no per-block sawtooth). Pitch
        // does not affect movement, so freezing it here is motion-neutral.
        if (!bridging && !placingEdge)
            p.setXRot(smoothAngle(p.getXRot(), (diveUnderCap || diving) ? 50f : 0f));
        // STEP-UP HEADING GATE (卡碰撞箱 fix): if a +1 step is still badly mis-aimed,
        // pivot in place instead of ramming the riser. The jump gate (ascendJumpReady
        // below) already checks POSITION alignment, but neither it nor the forward key
        // checked HEADING — so a drift-off-column / sharp-turn arrival bob-jammed the
        // step. Gate both forward (keyUp) and the step jump on this. Excludes parkour
        // and water (own handling; launches snap heading so the error is ≈0 anyway).
        float stepHeadingErr = Math.abs(angleDiff(p.getYRot(), aimYaw));
        // STEP-UP FREEZE BREAKER (cur2≈0.64 ram): a dry stepUp/diagUp that has dwelt
        // past STEPUP_FREEZE_TICKS without closing on its node, while laterally CLOSE to
        // the step column, is ramming the riser — the close-node bearing swings on every
        // sub-block bob so stepHeadingErr never clears, pivotForStepUp cuts forward
        // forever, and the jump (gated on that pivot) never clears the riser (live
        // 2026-06-15: a river-bank diagUp AND a dirt/stone-notch cardinal stepUp each
        // held cur2=0.640 constant 300+ ticks ≈ 19 s until an anti-stuck burst yanked
        // the bot BACKWARD off the step). When that happens, STOP pivoting (so forward
        // drives at the column) and force a grounded jump below — pushing up-and-over
        // mounts the step instead of bobbing against it. Lateral-close gate (column
        // dist² < 1.6) keeps it from firing on a far / mis-routed node.
        double stepColDx = (wp.getX() + 0.5) - p.getX();
        double stepColDz = (wp.getZ() + 0.5) - p.getZ();
        // Shallow-water bank variant: a bot GROUNDED on a submerged ledge (onGround
        // AND in water — never the deep-water float, which stays !onGround) bobbing
        // against a +1/+2 bank never advances the lateral step. cappedHead (the bank
        // top caps foot+2) suppresses the swim-up jump and the dry !isInWater gate
        // locks it out of this freeze-breaker, so only the anti-stuck burst (~12 s)
        // frees it (live 2026-06-15 (2366,62,1875): x frozen at the bank, y bobbing
        // 62↔64, 212 ticks onG=true inW=true). Grounded-in-water IS the shallow bank
        // case — admit it so the grounded jump + forward mounts the step, like dry.
        boolean shallowBankStep = p.onGround() && p.isInWater();
        boolean stepUpFreeze = wp.getY() > foot.getY() && !parkourEdge
                && (!p.isInWater() || shallowBankStep)
                && noStepProgressTicks > STEPUP_FREEZE_TICKS
                && (stepColDx * stepColDx + stepColDz * stepColDz) < 1.6;
        boolean pivotForStepUp = wp.getY() > foot.getY() && !parkourEdge && !p.isInWater()
                && stepHeadingErr > STEPUP_AIM_TOLERANCE_DEG && !stepUpFreeze;
        // Parkour DESCEND landing brake: a leap onto a LOWER 1-wide block
        // touches down with more horizontal momentum than a flat leap (extra
        // airtime accelerating forward), so a sprint launch slides the bot off
        // the far edge into the void (observed: a parkourDescend2d1 landed but
        // overshot the 1-wide pad and fell). Once we're airborne AND already
        // horizontally over (or nearly over) the landing column — i.e. the gap
        // is cleared — cut forward+sprint and hold sneak so the bot coasts to a
        // stop ON the block instead of past it (same trick as the parkour-place
        // SETTLE phase). We brake only when close to the landing center, so
        // cutting thrust can never drop the leap short into the gap.
        boolean descendLeap = edge != null && edge.move != null && edge.move.startsWith("parkourDescend");
        double landDx = (wp.getX() + 0.5) - p.getX();
        double landDz = (wp.getZ() + 0.5) - p.getZ();
        boolean descendBrake = descendLeap && !p.onGround()
                && (landDx * landDx + landDz * landDz) < 1.4;   // within ~1.2 block of landing center
        // Lateral lane-keeping STRAFE: on a flat cardinal walk, hold the cross-axis
        // at the lane centre with a sideways strafe so the body clears a flush 1-wide
        // channel wall WITHOUT turning off the forward heading. Pure yaw steering
        // can't do both (turning to centre kills forward progress, so the bot only
        // creeps and grinds the wall); strafing centres while forward still drives
        // it down the lane. Projects the cross-axis error onto the player's right
        // vector to pick the key. Skipped during leaps/brakes/bridging.
        boolean strafeL = false, strafeR = false;
        // Baritone MovementAscend jump-timing for a DRY cardinal +1 step. Baritone
        // does NOT sprint an ascend and does NOT hold jump continuously — it walks
        // toward the step squaring up, then presses jump ONLY when CLOSE
        // (flatDist≤1.2 along the move axis), ALIGNED (sideDist≤0.2 on the cross
        // axis), and not drifting sideways (|lateralMotion|≤0.1). Our old "sprint +
        // hold jump for any wp.y>foot.y" bonked the step face off-axis and slid back
        // down → the 633-tick steep-mountain wedge. Mirror Baritone: centre on the
        // cross axis (strafe gate below now includes dryStepUp), drop sprint, and
        // gate the jump on alignment+proximity. Scoped to a single cardinal +1 step
        // on dry land — diagUp / +2 / water / parkour keep their own handling.
        // Attribute-driven step/jump (read the controlled entity — the vehicle when
        // mounted). On foot: step 0 (a +1 needs a jump), jump reaches +1. A horse:
        // step 1.0 (walks a full block up, no jump), jump up to +2.
        int upDy = wp.getY() - foot.getY();
        int maxStepUp = world.maxStepUpBlocks();
        int maxJumpUp = world.maxJumpUpBlocks();
        boolean cardinalUp = upDy >= 1 && ((wp.getX() == foot.getX()) ^ (wp.getZ() == foot.getZ()));
        // A DIAGONAL +1 step (BOTH axes change AND rising). The lane-keep strafe below
        // only centres cardinal lanes / waterClimb, so a diagUp got NO lateral
        // correction: the bot drifts off the diagonal, ends aligned on one axis but
        // ~0.8 off on the other, rams the perpendicular riser — and because the dry-land
        // aim points at the CLOSE waypoint (whose bearing swings on any sub-block drift)
        // stepHeadingErr never clears, so pivotForStepUp cuts forward FOREVER and the bot
        // FREEZES at the riser (live 2026-06-15: cur2=0.640 constant 300+ ticks ≈ 19 s,
        // hCol=true, until an anti-stuck burst routed around). Centre it on the target
        // column on BOTH axes (the strafe still runs while pivot cuts forward), so its
        // cross-axis component pulls the body back onto the diagonal line, the bearing
        // steadies, pivotForStepUp releases, and forward + stepUpJump mounts the corner.
        boolean diagUp = upDy >= 1 && wp.getX() != foot.getX() && wp.getZ() != foot.getZ()
                && !parkourEdge && !p.isInWater();
        // A jump is needed only to rise BEYOND the auto-step height.
        boolean needJumpForStep = upDy >= 1 && upDy > maxStepUp;
        // A step taller than we could clear even WITH a jump — we slid back below a
        // +1 start so it now reads +2, or terrain demands a height we can't make.
        // Don't bob against it: accelerate the stuck timer so the search blacklists
        // the node and reroutes (the steep-climb wedge), instead of jumping forever.
        boolean overJump = needJumpForStep && upDy > maxJumpUp && p.onGround() && !p.isInWater()
                && edge != null && !"pillarUp".equals(edge.move) && !parkourEdge;
        if (overJump) stuckTicks += 3;
        // Vertical recovery (execution hardening): the bot is BELOW the next node by more
        // than it can jump — it slid/fell below the committed climb path, and a plain stepUp
        // here just RAMS the wall (jump is suppressed for an unreachable height) → the
        // "被面前的高方块挡住不动" stall. If we carry blocks, PILLAR UP in place to regain
        // the height instead of ramming: look down, jump off the ground, and place a support
        // in the feet cell once risen clear of it — the same actuation as a planned pillarUp.
        // Latched across the jump's airborne phase (overJump needs onGround, so one tick
        // can't both jump and place) and re-armed each grounded tick while still too low;
        // ends when back within jump reach (overJump clears) or blocks run out, after which
        // the normal step logic resumes. Reuses ensureHolding + clientUseItemOn.
        if (overJump && world.canPlace() && a.holdPlaceable()) {
            pillarRecoverLatch = PILLAR_RECOVER_TICKS;
            pillarRecoverCell = foot;                 // grounded feet cell = the rung we fill
        }
        if (pillarRecoverLatch > 0 && pillarRecoverCell != null) {
            pillarRecoverLatch--;
            agentForward(a, false);
            p.setSprinting(false);
            agentSneak(a, false);
            p.setShiftKeyDown(false);
            // Clear the ceiling FIRST: a block where the head rises (tree-canopy leaves at
            // rung+2, a dirt overhang) makes the recovery jump RAM it — the bot can't gain
            // height, can't place, and wedges ("树下 pillar-up 撞树叶卡死", bug#1). The planned
            // pillarUp actuator clears its toBreak the same way; this recovery path had none.
            // Only with allowBreak, and only the single head cell, so it digs no more than the
            // one block needed to rise this rung (re-checked each rung as pillarRecoverCell rises).
            BlockPos recCeiling = pillarRecoverCell.offset(0, 2, 0);
            if (BotConfig.allowBreak && world.isSolid(recCeiling)) {
                agentJump(a, false);
                a.selectTool(recCeiling);
                a.aimAtBlock(recCeiling);
                a.breakHold(true);
                return Step.WALKING;
            }
            a.breakHold(false);
            p.setXRot(89.5f);                         // look straight down to aim the support
            if (p.onGround()) {
                agentJump(a, true);     // jump off the current rung
            } else {
                agentJump(a, false);
                // Place into the feet cell once risen clear of it (vanilla rejects the place
                // while the player AABB still overlaps the target cell — gate on real height).
                if (p.getY() >= pillarRecoverCell.getY() + 1.0) {
                    a.placeOn(pillarRecoverCell.offset(0, -1, 0), Direction.UP);
                }
            }
            return Step.WALKING;
        }
        // Baritone MovementAscend jump-timing applies whenever we actually JUMP a
        // cardinal step within reach (+1, or +2 for a horse/jump-boost) — align +
        // approach before the jump. A horse auto-walk-up needs no jump; water/parkour
        // differ; an over-jump (handled above) is excluded.
        boolean dryStepUp = needJumpForStep && cardinalUp && upDy <= maxJumpUp
                && !p.isInWater() && !parkourEdge;
        boolean ascendJumpReady = true;
        boolean sprintAscend = false;
        if (dryStepUp) {
            int xA = wp.getX() != foot.getX() ? 1 : 0;
            int zA = wp.getZ() != foot.getZ() ? 1 : 0;
            double flatDist = xA * Math.abs((wp.getX() + 0.5) - p.getX()) + zA * Math.abs((wp.getZ() + 0.5) - p.getZ());
            double sideDist = zA * Math.abs((wp.getX() + 0.5) - p.getX()) + xA * Math.abs((wp.getZ() + 0.5) - p.getZ());
            Vec3 vel = p.getDeltaMovement();
            double lateralMotion = xA * vel.z + zA * vel.x;
            // SPRINT-BUNNY-HOP a cardinal +1 step (丝滑 stair climb). The deterministic
            // ascentSpeedArena shows a gentle staircase costs ~40% speed: each step drops
            // sprint + the in-place jump rams the riser + the body re-accelerates from ~0.
            // Two levers were each A/B-disproven IN ISOLATION (2026-06-06): sprint + a LATE
            // jump (flatDist≤1.2) rams the riser HARDER; an early jump (≤1.7) WITHOUT sprint
            // lands short and re-approaches. TOGETHER they compose — the sprint forward-boost
            // carries the EARLY launch up and OVER the riser onto the step with momentum
            // intact, exactly how a vanilla player sprint-jumps stairs. Gate HARD on cross-
            // axis alignment (square-up sideDist≤0.2, not drifting |lateralMotion|≤0.1) so the
            // boosted launch flies straight at the step instead of bonking a corner; when
            // aligned, jump EARLY (flatDist≤1.7) AND keep sprint (sprintAscend → sprint gate
            // below). Misaligned → don't jump yet (the pivot/strafe centres first), matching
            // the old square-up-before-jump discipline. The arena A/B (not noisy live terrain,
            // which the prior disproofs used) is the gate for this.
            boolean aligned = Math.abs(lateralMotion) <= 0.1 && sideDist <= 0.2;
            sprintAscend = aligned;
            ascendJumpReady = aligned && flatDist <= 1.7;
        }
        // Climbing a +1 ledge OUT OF a water film: buoyancy drifts the body off the
        // target column so the cardinal stepUp approach goes diagonal and forward
        // just grinds the ledge side (trace: inW, foot drifted to a diagonal of the
        // stepUp node, cur2 stuck at 1.27, bobbed 797 ticks → budget expiry, "never
        // left spawn"). The lane-keep strafe below is gated to flat walks
        // (wp.y==foot.y), so a vertical climb gets NO lateral correction. Treat a
        // water climb-out like a lane-keep but centre on BOTH axes toward the target
        // column so the body sits under the ledge; the existing forward+jump then
        // mounts it (the aligned cardinal climb that already works on dry land).
        boolean waterClimb = p.isInWater() && wp.getY() > foot.getY();
        if (!descendBrake && !parkourEdge && !steppingOffFall && (wp.getY() == foot.getY() || waterClimb || cardinalUp || diagUp)) {
            int ddx = wp.getX() - foot.getX();
            int ddz = wp.getZ() - foot.getZ();
            double latX = 0, latZ = 0;
            if (waterClimb) { latX = (wp.getX() + 0.5) - p.getX(); latZ = (wp.getZ() + 0.5) - p.getZ(); } // centre on the target column
            else if (diagUp) { latX = (wp.getX() + 0.5) - p.getX(); latZ = (wp.getZ() + 0.5) - p.getZ(); } // centre on the diagonal toward the step corner
            else if (ddx == 0 && ddz != 0) latX = (wp.getX() + 0.5) - p.getX();        // N/S lane → hold X
            else if (ddz == 0 && ddx != 0) latZ = (wp.getZ() + 0.5) - p.getZ();   // E/W lane → hold Z
            // Anti-drift in a current: a flowing-water cell pushes the body
            // downstream, so steer upstream (subtract the flow vector from the
            // lateral target). The strafe is far stronger than the push, so this
            // holds the crossing on the planned line PROACTIVELY instead of letting
            // the lane-keep only react after the bot has already drifted. Only the
            // cross-lane component survives the right-vector projection below; the
            // along-lane part is ignored (handled by the upstream COST, not steering).
            if (BotConfig.waterFlowPenalty > 0 && p.isInWater()) {
                Vec3 flow = world.waterFlow(foot);
                latX -= flow.x; latZ -= flow.z;
            }
            if (Math.abs(latX) > 0.06 || Math.abs(latZ) > 0.06) {
                double yr = Math.toRadians(p.getYRot());
                double fx = -Math.sin(yr), fz = Math.cos(yr);   // forward unit (x,z)
                double rx = -fz, ry = fx;                       // player's right = forward rot +90°
                double dotR = latX * rx + latZ * ry;
                if (dotR > 0.04) strafeR = true;
                else if (dotR < -0.04) strafeL = true;
            }
        }
        // Camera-decoupled drive (see AgentInput). Rotate the body-frame movement intent
        // (forward + lane-keep strafe) from the desired travel heading (aimYaw) into the
        // camera frame by Δ = aimYaw − cameraYaw, so vanilla travel()'s rotate-by-yaw moves
        // the body ALONG the heading even while the camera is still slewing toward it — the
        // body no longer rams a wall waiting for the look to catch up (动态纠偏). At Δ=0
        // (camera caught up) the impulse equals the old keyed (dL,dF), so steady-state walking
        // is byte-identical; only the slew transient changes. spinFreeze deliberately holds
        // the heading (water anti-wind), so drive along the frozen camera there (Δ=0) to keep
        // its press-one-way climb-out behaviour. Special branches above return before here, so
        // they keep their own key-based actuation (AgentInput falls back to keys uncommanded).
        double driveF = (!descendBrake && !pivotForStepUp) ? 1.0 : 0.0;
        double driveL = strafeL ? 1.0 : (strafeR ? -1.0 : 0.0);
        double driveDelta = Math.toRadians(spinFreeze ? 0.0 : angleDiff(p.getYRot(), aimYaw));
        double driveCos = Math.cos(driveDelta), driveSin = Math.sin(driveDelta);
        a.commandMove(
                (float) (driveL * driveCos - driveF * driveSin),
                (float) (driveL * driveSin + driveF * driveCos));
        // Bridging a chasm one placed block at a time: sneak (so a sprint
        // overshoot can't carry the bot off the fresh 1-wide block into the
        // gap ahead) and don't sprint. Triggered when the edge we're walking
        // onto OR the next one is a place-bridge — Baritone sneaks for the
        // same reason. Released the moment we're back on real ground.
        // (`bridging` is computed once, hoisted up near the travel-aim pitch.)
        // Lethal-edge sneak-brake (DEATH #8 fix): if a fatal drop is one step ahead
        // in the heading, hold sneak so vanilla's ledge-guard pins the body at the
        // block edge — the controller can no longer drift off a cliff while fleeing
        // or walking a lip. Lethal-only, so it never blocks a legitimate planned
        // step-down (those are capped at survivableFall by the PathFinder).
        // A lethal drop borders the foot — but only PIN with sneak when we're NOT
        // making a planned step-DOWN. Vanilla's ledge-guard refuses to walk off ANY
        // edge, so if the brake stays on while the path needs to descend (a lethal
        // drop in some OTHER direction, e.g. a mountainside, while the safe planned
        // step is down-and-across), the bot DEADLOCKS — sneak on, hCol=false, creeping
        // at ~0 b/s on a ridge forever (the "速度陡降 / blocked" stall). The PathFinder
        // caps every planned step-down at survivableFall, so releasing sneak for the
        // intended descent is safe. Same-level lip-walking keeps the full pin (death #8),
        // and sprint stays OFF whenever a lethal edge is near (see sprint below) so the
        // released descent has no drift/overshoot momentum off the lip.
        boolean lethalNear = BotConfig.lethalEdgeBrake && p.onGround()
                && lethalDropAdjacent(world, p, foot);
        boolean plannedDescent = wp.getY() < foot.getY();
        boolean edgeBrake = lethalNear && !plannedDescent;
        // DYNAMIC fall-correction while bridging (user: 动态纠偏防止跌落,而不是一直蹲着牺
        // 牲速度). A 1-wide place-bridge has void on BOTH sides, so the lethal-edge pin
        // (edgeBrake) AND the old blanket bridge-sneak BOTH held shift for the ENTIRE
        // span — crouch-walking the whole bridge at a ~0.9 b/s crawl. Instead, walk the
        // already-placed blocks at full speed and brake (sneak — its ledge-guard pins the
        // body so it can't step off) ONLY on the ticks with an ACTUAL fall risk:
        //   • gapAhead — no solid footing ~0.6 blocks ahead toward wp (about to overshoot
        //     off the front edge into the not-yet-placed frontier), or
        //   • offCentre — drifted >0.3 off the foot→wp centreline (side fall on the 1-wide
        //     span; the camera-decoupled impulse already re-aims at wp's centre, so this
        //     only fires on real drift).
        // Planned step-downs still release entirely (a descending bridge deadlocks under
        // any pin — see the long edgeBrake note above). Sprint is OFF during bridging
        // (below), so a non-braked tick can't build enough momentum to overshoot a whole
        // block before the next look-ahead check brakes it.
        // Generalised beyond bridging (出桥 2 格 crawl): right after a bridge — or on
        // any cliff-lip walk — `edgeBrake` re-pinned the body the moment `bridging`
        // dropped, crouch-crawling until the void left the 8-neighbourhood. The SAME
        // dynamic gate is the cert'd fix for the same risk on a 1-wide span (the
        // strictly harder case), so apply it to every lethal-edge walk: full speed on
        // safe ticks, brake only on gapAhead/off-centre drift. Sprint stays OFF near
        // a lethal edge (below), bounding per-tick travel just like on the bridge.
        // HAZARD-AHEAD brake (lava ×4 live in two rounds): the danger ring only
        // PRICES a lava-hugging route — when no detour exists A* still commits
        // one, and the 0.6-wide body drifts into the neighbouring lava cell
        // mid-stroke (fire res masked what kills a naked bot). Hard guard at the
        // actuator: if the cell ~0.8 ahead along the heading is a hazard at foot
        // or head level, sneak-brake this tick (ledge-guard semantics keep the
        // body out of the cell; sneak still creeps ~0.9 b/s so a mandatory
        // lava-side passage stays passable, just slow — exactly right there).
        boolean hazardAhead = false;
        {
            double hax = (wp.getX() + 0.5) - p.getX();
            double haz = (wp.getZ() + 0.5) - p.getZ();
            double hal = Math.sqrt(hax * hax + haz * haz);
            if (hal > 1e-3) {
                BlockPos aheadCell = BlockPos.containing(
                        p.getX() + hax / hal * 0.8, p.getY(), p.getZ() + haz / hal * 0.8);
                hazardAhead = world.isHazard(aheadCell) || world.isHazard(aheadCell.above());
            }
        }
        boolean bridgeBrake = false;
        if ((bridging || edgeBrake) && !plannedDescent) {
            double bdx = (wp.getX() + 0.5) - p.getX();
            double bdz = (wp.getZ() + 0.5) - p.getZ();
            double blen = Math.sqrt(bdx * bdx + bdz * bdz);
            if (blen > 1e-3) {
                double ax = p.getX() + bdx / blen * 0.6;
                double az = p.getZ() + bdz / blen * 0.6;
                BlockPos aheadFoot = new BlockPos((int) Math.floor(ax), foot.getY(), (int) Math.floor(az));
                boolean gapAhead = !world.isSolid(aheadFoot.below()) && !world.isSolid(aheadFoot);
                double fx = foot.getX() + 0.5, fz = foot.getZ() + 0.5;
                double offCentre = Math.abs((p.getX() - fx) * bdz - (p.getZ() - fz) * bdx) / blen;
                bridgeBrake = gapAhead || offCentre > 0.30;
            }
        }
        // The dynamic brake REPLACES the constant lethal-edge pin everywhere (bridge
        // AND cliff-lip): the pin's job — don't drift off a lethal edge — is done by
        // the per-tick gapAhead/offCentre gate at full walking speed.
        boolean lavaBrake = hazardAhead && !p.isInWater();   // land-only: a surface swimmer sneaking beside lava would DIVE (active sink), not stop
        agentSneak(a, bridgeBrake || descendBrake || diving || lavaBrake);   // sneak in water = vanilla active sink (buoyancy never sinks a surface swimmer on its own)
        p.setShiftKeyDown(bridgeBrake || descendBrake || lavaBrake);
        // Jump for a real upward step, a parkour-leap edge (by move type, not
        // raw distance — string-pulling makes plain walk waypoints far apart
        // too), or a brief stuck-wiggle.
        // The stuck-wiggle jump unsticks a bot wedged on a corner, but on a
        // 1-wide bridge it would hop the bot clean off into the gap — so
        // never wiggle-jump while bridging. Stop pressing jump once braking
        // (we're descending onto the block — no more lift wanted).
        // Stay afloat while pathing through water. autoSwim is gated OFF during
        // goto/follow/explore/runAway (it would fight the Walker's jump), so the
        // Walker itself must swim up — otherwise the bot sinks in deep water and
        // drowns mid-path (the "swam into the ocean and died" failure). Hold jump
        // whenever the eyes are submerged, unless we're deliberately diving a
        // swimDown edge (then let it descend).
        // (diving is computed earlier, beside diveUnderCap, so the pitch/sneak
        // actuators can drive the ACTIVE sink — see that block for the rationale.)
        // DEBOUNCED (≥3 ticks submerged): on a surface cruise the eye line bobs in
        // and out of the waterline every few strokes, and each 1-2-tick dip pulsed
        // the swim-up jump — a +1y overshoot tooth every ~2-3 s with a synchronized
        // speed dip (round43b pathChart: regular sawtooth across the whole sea leg).
        // A genuine sink keeps the eyes under for many consecutive ticks, so a
        // 3-tick (150 ms) gate costs real buoyancy nothing; swimColumn (water at
        // head height) still rises a true column un-debounced.
        underwaterTicks = (p.isInWater() && p.isUnderWater()) ? underwaterTicks + 1 : 0;
        boolean swimUp = underwaterTicks >= 3 && !diving;
        // The stuck-wiggle hop unsticks a corner on DRY land, but in shallow water
        // on a flat walk it just bobs the bot off the floor into the buoyant drift
        // (it floats off its cell and slides — the very stall it's meant to break).
        // Suppress it there; treading + steady forward threads the channel instead.
        // A floating body rides ONE block above the in-water path nodes (A* places
        // water nodes at the cell whose head breaks the surface; the buoyant foot
        // is in the cell above), so a same-level open-water crossing shows up as
        // wp.y == foot.y − 1 with a WATER wp — treat that as flat too, or the
        // whole crossing loses the prone sprint-swim and treads at ~1.5 blk/s
        // (live 2026-06-09: sprint=false across a lake, hSpd 0.077, each 200-tick
        // repath advanced only ~8 blocks → zig-zag + stall feel). A real swimDown
        // edge keeps its own handling (diving), and climb-outs (wp above foot)
        // stay non-sprint as before.
        boolean flatWaterWalk = p.isInWater() && !diving
                && (wp.getY() == foot.getY()
                    || (foot.getY() - wp.getY() == 1 && world.isWater(wp)));
        boolean wiggle = !bridging && !flatWaterWalk && !pivotForStepUp && stuckTicks > 10 && stuckTicks < 18;
        // Climbing a +1 ledge out of a SHALLOW water film needs a BALLISTIC,
        // GROUNDED jump: |Δy|=0.8 exceeds the 0.6 auto-step, and a *held* jump in
        // water just swims the bot up to bob at the surface (y+0.2, onGround=false)
        // — it never clears the dry ledge (trace: stuck 797t at cur2≈1, |dY|=0.8).
        // So for a water climb-out, jump ONLY when grounded: releasing between
        // launches lets the body settle the 0.2 back onto the shaft floor, from
        // which a real jump (+1.25) tops the ledge while the strafe-centring above
        // holds it under the target column so forward carries it on. (swimUp below
        // still rescues a genuinely-submerged deep-water climb — undW there.)
        // Water climb-out jump rule covers BOTH depths:
        //  • SUBMERGED (deep water / flooded pit, undW): swim up — hold jump so the
        //    bot rises through the water column toward the surface (never sinks /
        //    drowns; this is the original swimUp behaviour).
        //  • AT THE SURFACE of a shallow film (inW, !undW): a held jump only bobs
        //    (y+0.2, onGround=false) and can't clear the 0.8 dry ledge, so jump
        //    ONLY when grounded — releasing lets the body settle the 0.2 onto the
        //    floor, from which a real jump (+1.25) tops the ledge (strafe-centring
        //    above holds it under the column so forward carries it on).
        // Still ASCENDING a water column when the head cell (or eyes) is water —
        // a deep/flooded pit is multiple blocks deep, so keep swimming up (hold
        // jump) until the head clears; checking only undW stops mid-column (eyes
        // pop out at y+0.5 while feet are still 2 below) and the bot sinks back,
        // oscillating at the pit bottom (trace: bobbed y60.0↔60.57, never rose).
        boolean swimColumn = p.isInWater() && (p.isUnderWater() || world.isWater(foot.above()));
        // Climb a +1 ledge OUT of water like a dry-land climb: hold jump CONTINUOUSLY
        // (vanilla "hold forward+jump to climb out of water"), not only when grounded.
        // The old `waterClimb ? (swimColumn || onGround)` fired jump ~never at a shallow
        // film — the buoyant body is onGround ≈1/10 ticks and swimColumn is false (no
        // water above the head), so it just treaded into the bank wall (trace: stuck
        // 814t at a lake bank, jump=false every tick). wp.getY()>foot.getY() is true for
        // ANY water climb-out, so this presses jump throughout it; swimColumn still
        // rises a deep submerged column and swimUp covers a fully-submerged climb.
        // For a dry cardinal +1 step, only jump once Baritone-aligned (ascendJumpReady):
        // close + squared-up + not drifting. Early/off-axis jumps bonk the step and
        // slide back (the steep-climb wedge); waiting to jump also keeps the body
        // moving smoothly instead of bobbing in place (no jittery camera on stream).
        // Jump a step only when a jump is actually needed (beyond auto-step) AND the
        // step is within reach (≤ maxJumpUp) — never bob-jump an unreachable height —
        // and, for the +1 cardinal case, only once Baritone-aligned.
        boolean stepUpJump = needJumpForStep && upDy <= maxJumpUp && (!dryStepUp || ascendJumpReady) && !pivotForStepUp;
        // Don't hold the swim-up jump when a SOLID cell caps the head (foot+2) while
        // in water: the path threads a submerged / stone-overhung tunnel (water
        // surface capped by solid above), so bobbing up just RAMS that ceiling and
        // the body can't move horizontally under it — the stuck-bobbing trace at a
        // stone-capped water surface (hCol=true, hSpd=0, y oscillating into the
        // ceiling, pos frozen). Staying low lets forward thread the tunnel. Only the
        // water jumps are gated; a dry stepUp / parkour launch is never suppressed
        // (their own gates apply), and an OPEN water column (head not capped) still
        // swims up so a deep crossing can't drown.
        boolean cappedHead = p.isInWater() && world.isSolid(foot.offset(0, 2, 0));
        boolean jump = !descendBrake
                && (stepUpJump || parkourEdge
                    // Freeze-breaker: force a GROUNDED jump straight up the step once a
                    // stepUp/diagUp has rammed the riser past STEPUP_FREEZE_TICKS — the
                    // normal stepUpJump gate (ascendJumpReady) can stay false there (the
                    // bot is a touch off the cross-axis), so without this it bobs forever.
                    || (stepUpFreeze && p.onGround())
                    // !diving: swimColumn is true for any submerged body, so during an
                    // ACTIVE dive the held jump cancelled the sneak-sink exactly —
                    // the bot hovered at constant depth (hSpd 0.02, jump+sneak both
                    // down) while the burst storm wound yaw 4.5 turns (mangrove live).
                    || ((swimUp || swimColumn) && !cappedHead && !diving) || wiggle);
        agentJump(a, jump);
        // Sprint in water ONLY on a FLAT crossing (flatWaterWalk: wp.y==foot.y). The
        // prone swim pose that sprint+forward forces is exactly what a wide open-ocean
        // crossing needs (vanilla's fast swim) — WITHOUT it the bot treads upright in
        // place and STALLS (full-HP naked bot stuck at the spawn bay edge, 0-1 blk over
        // 40s; the deepWaterMax routing fix made the path exist but the controller
        // couldn't execute it). Pitch is held ~horizontal (setXRot→0 above) so the prone
        // swim hugs the surface and doesn't dive; swimUp/swimColumn still hold jump if it
        // dips under, so it can't drown over a long crossing.
        // KEEP no-sprint for CLIMB-OUT nodes (wp.y>foot.y → !flatWaterWalk): there the
        // prone pose can't rise a bank — ROOT CAUSE of the old "stuck bobbing, can't climb
        // out" trace (sprint=true, |dY|≈2.4, bobbing y61 under a y64 node). Treading + jump
        // lets vanilla auto-climb the 1-block ledge out of the water.
        // EXCEPTION — diveUnderCap: a submerged tunnel capped by solid (water under a
        // stone lip) is only ~2 cells tall, and an UPRIGHT body (1.8) rams the ceiling
        // (the lethal stuck: hCol=true, hSpd=0, bobbing into the y+1 stone). Sprinting
        // in water forces the PRONE swim pose (~0.6 tall) which fits under the lip, and
        // with the dive pitch (above) + jump suppressed (cappedHead) it threads the
        // tunnel along the floor instead of bobbing into the cap. So force sprint here
        // even though it's a (downward) vertical node.
        // A parkour ASCEND leap (parkourEdge rising) is the ONE jumped ascend that MUST
        // sprint: a +2 (or gap) parkour clears only with the run-up momentum (Baritone's
        // MovementParkour sprints). The blanket no-sprint-on-needJumpForStep below was tuned
        // for a +1 CARDINAL stepUp (sprint rams the riser there), but it wrongly starved the
        // parkour leap of momentum → it jumped +1 in place and bob-stalled against the step
        // (live: parkourAscend2 frozen, hSpd=0, bobbing y82↔83). Let a parkour ascend sprint.
        boolean parkourAscend = parkourEdge && wp.getY() > foot.getY();
        boolean sprint = !bridging && !steppingOffFall && !steppingOffWaterFall
                && !hazardAhead   // never carry sprint momentum INTO a lava/hazard cell — in water too (no sneak there, but dropping sprint kills the drift that pushed the swimmer in)
                && !descendBrake && (!lethalNear || parkourAscend) && (!needJumpForStep || parkourAscend || sprintAscend)   // !lethalNear (not !edgeBrake): never sprint NEAR a lethal edge — incl. a planned descent past it — so no drift/overshoot momentum off the lip while sneak is released for the step-down. Baritone doesn't sprint a jumped CARDINAL ascend (overshoots/bonks) but DOES sprint a parkour leap; a horse auto-walk-up keeps sprint
                // A/B-DISPROVEN (2026-06-06): re-enabling sprint on an aligned ascend (sprintableAscend)
                // regressed hCol 13%→36% / mean hSpd .112→.082 — because the jump fires CLOSE to the riser
                // (ascendJumpReady flatDist≤1.2), the sprint forward-boost rams the riser face HARDER instead
                // of arcing over it. A sprint-jump only clears a step if launched EARLY (before the riser);
                // closing that gap needs an early-jump-timing change, not just flipping sprint on. Kept no-sprint.
                && (!p.isInWater() || flatWaterWalk || diveUnderCap);
        p.setSprinting(sprint);
        // Lily pads sit ON the water plane with a real collision box; the planner
        // deliberately treats the thin shape as passable (a fast prone swim slides
        // under), but the moment the swim slows, the upright treading body rams the
        // pad — hCol=true, hSpd→0 — and the swamp current + anti-stuck bursts spin
        // the bot in place (round28: 7×7 of open water + pads, yaw wound to -994°).
        // Pads are instabreak: punch the one ahead (or overhead) and keep swimming.
        if (p.isInWater() && p.horizontalCollision) {
            double bdx = (wp.getX() + 0.5) - p.getX(), bdz = (wp.getZ() + 0.5) - p.getZ();
            double bl = Math.sqrt(bdx * bdx + bdz * bdz);
            BlockPos surf = BlockPos.containing(p.getX(), p.getY() + 1.0, p.getZ());
            BlockPos aheadPad = bl > 1e-3
                    ? BlockPos.containing(p.getX() + bdx / bl, p.getY() + 1.0, p.getZ() + bdz / bl)
                    : surf;
            BlockPos pad = world.isBreakableObstruction(aheadPad) ? aheadPad
                    : world.isBreakableObstruction(surf) ? surf : null;
            if (pad != null) {
                a.aimAtBlock(pad);
                a.breakHold(true);
            }
        }
        if (BotConfig.walkerDebug) {
            // [dbgcollide] hard physics evidence for the hill speed-sawtooth: is the
            // bot actually COLLIDING (hitbox snagging a trunk/step face) or just
            // turning? hSpd = horizontal velocity magnitude (sprint≈0.28 b/tick);
            // hCol/minorCol = vanilla collision flags; pos = real feet so we can see
            // dwell (pos frozen while wp/yaw change = stuck, not moving).
            Vec3 dm = p.getDeltaMovement();
            double hSpd = Math.sqrt(dm.x * dm.x + dm.z * dm.z);
            LOG.info("[walker] walk-keys yaw={} wp={},{},{} up={} jump={} sprint={} sneak={} hCol={} minorCol={} hSpd={} pos={},{},{} onG={} attack={}",
                    String.format(Locale.ROOT, "%.0f", p.getYRot()),
                    wp.getX(), wp.getY(), wp.getZ(),
                    a.dbgForwardImpulse(), a.dbgJumping(), p.isSprinting(),
                    a.dbgSneak(),
                    p.horizontalCollision, p.minorHorizontalCollision,
                    String.format(Locale.ROOT, "%.3f", hSpd),
                    String.format(Locale.ROOT, "%.2f", p.getX()),
                    String.format(Locale.ROOT, "%.2f", p.getY()),
                    String.format(Locale.ROOT, "%.2f", p.getZ()),
                    p.onGround(), a.breakHeld());
        }
        return Step.WALKING;
    }

    /** Fire onTerminal and return the step verdict in one place, so every terminal
     *  return site stays a one-liner. */
    private Step terminal(Step s, PathTrace.Outcome outcome, String reason) {
        PathTraceHolder.SINK.onTerminal(outcome, reason);
        return s;
    }

    /** Externally cancelled (mc.bot.cancel / superseded by a new goto) before a
     *  natural terminal. Fire onTerminal(CANCELLED) so per-session trace observers
     *  finalize the partial run (e.g. a path archive is flushed for a wedge the
     *  agent cancelled out of). A no-op for the recorders if no session is open
     *  (they guard on sessionOpen), so a double-fire after a natural terminal is
     *  harmless. */
    public void abort(String reason) {
        PathTraceHolder.SINK.onTerminal(PathTrace.Outcome.CANCELLED, reason);
    }

    /** Splice in a freshly-searched route: string-pull it, reset the per-path
     *  follow state, and record whether it's a best-effort partial (so the next
     *  segment is precomputed from its end — see the kickoff/splice logic in
     *  {@link #tick}). {@code commitEnd} is the segment's last node, the launch
     *  point for that continuation search. */
    /** At a loaded-chunk frontier the (stale) eager continuation from commitEnd —
     *  computed before the bot arrived — found no onward route. Re-search FRESH from
     *  the frontier: now that the bot stands there, chunks ~render-distance further
     *  have loaded and the next segment is visible. Bounded by {@link #FRONTIER_WAIT_CAP}
     *  (the bot is stationary while waiting, so retrying more can't load new chunks)
     *  so a genuine box-in still terminates. Holds (keys released) and returns
     *  WALKING while retrying; ARRIVED when out of retries or the feature is off. */
    private Step frontierHoldOrArrive(Avatar a, WorldView world, Player p) {
        if (!replayMode && BotConfig.pathfinderFrontierCommit && commitEnd != null
                && frontierWaitTicks < FRONTIER_WAIT_CAP) {
            frontierWaitTicks++;
            if (activeSearch == null) {
                activeSearch = new PathFinder(world).newSearch(commitEnd, goal);
                searchFromEnd = true;
            }
            agentForward(a, false);
            agentJump(a, false);
            p.setSprinting(false);
            return Step.WALKING;
        }
        return terminal(Step.ARRIVED, PathTrace.Outcome.SUCCESS, null);
    }

    /** PROGRESSIVE QUICK-START (渐进式寻路): the big re-plan is still slicing in
     *  the background and the bot has nothing to walk — that gap is the visible
     *  inter-segment freeze (several seconds standing still at every hard
     *  obstacle). Spend a tiny SYNCHRONOUS node budget on a short best-effort
     *  segment toward the goal and start walking it right now; the big result
     *  supersedes the stub when it lands through the normal adoption path.
     *  Frame cost is irrelevant here: with no path the bot is frozen anyway
     *  (same argument as the idle-slice). Only a stub that actually nears the
     *  goal is adopted — a boxed-in micro-search returns pacing scraps that
     *  would jitter, so those back off for {@link #QUICK_RETRY_TICKS}.
     *  @return true if a stub segment was adopted (path != null, step = 1). */
    private boolean tryQuickStart(WorldView world, BlockPos foot, Goal goal) {
        if (BotConfig.pathfinderQuickNodes <= 0) return false;
        if (quickCooldown > 0) { quickCooldown--; return false; }
        PathFinder.Search q = new PathFinder(world, BotConfig.pathfinderQuickNodes, QUICK_MAX_MS)
                .newSearch(foot, goal);
        while (!q.advance(QUICK_MAX_MS)) { /* bounded by the node cap / QUICK_MAX_MS */ }
        PathFinder.Result res = q.result();
        boolean useful = res.hasPath() && res.path().size() > 1
                && goal.estimate(foot)
                   - goal.estimate(res.path().get(res.path().size() - 1)) >= 2.0;
        if (!useful) {
            quickCooldown = QUICK_RETRY_TICKS;
            return false;
        }
        if (BotConfig.walkerDebug)
            LOG.info("[walker] quick-start stub adopted: len={} expanded={} ms={} (big search still running)",
                    res.path().size(), res.expanded(), res.ms());
        adoptPath(res, world, null);    // stub starts at the foot — no fast-forward needed
        return true;
    }

    /** PROGRESSIVE OPEN-WATER BEE-LINE (渐进式水面直线 stub): over a wide deep-water
     *  crossing the sliced A* re-plan is expensive — it expands the whole 3-D water
     *  volume (canStandAt accepts every depth), thousands of nodes over several
     *  seconds, and even a bounded {@link #tryQuickStart} can't progress 2 blocks. So
     *  the bot consumes its short committed segment, has no fresh forward path, wedges,
     *  and anti-stuck bursts crab it across jerkily (live 2026-06-15 wide-water run:
     *  burst every ~6 s, the bot 30+ blocks past a stale segment, max step-stuck 106).
     *  A flat open-water crossing needs no search: greedily march toward the goal along
     *  the SURFACE — at each cell take the 8-neighbour that most reduces goal.estimate
     *  and is open surface water — and adopt that straight run as a long stub so the bot
     *  keeps swimming smoothly while the big search lands and supersedes it. A greedy
     *  local minimum is harmless: the stub is a stopgap, replaced the instant the real
     *  path arrives; it only ever drives the bot over open water it could swim anyway.
     *  Gated to a body of water under the feet. @return true if a bee-line was adopted. */
    private boolean tryWaterBeeline(WorldView world, BlockPos foot, Goal goal) {
        if (!world.isWater(foot)) return false;
        List<BlockPos> path = new ArrayList<>();
        List<Move.Edge> edges = new ArrayList<>();
        path.add(foot);
        edges.add(null);                       // start node carries no inbound edge
        BlockPos cur = foot;
        double curEst = goal.estimate(foot);
        for (int i = 0; i < BEELINE_MAX_STEPS; i++) {
            BlockPos best = null;
            double bestEst = curEst;
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    BlockPos n = cur.offset(dx, 0, dz);
                    if (!isOpenSurfaceWater(world, n)) continue;
                    double e = goal.estimate(n);
                    if (e < bestEst) { bestEst = e; best = n; }
                }
            if (best == null) break;            // no goal-ward open-water step (boxed / reached a bank)
            path.add(best);
            edges.add(new Move.Edge(best, 10, List.of(), List.of(), "walk"));
            cur = best;
            curEst = bestEst;
        }
        if (path.size() <= BEELINE_MIN_STEPS) return false;   // too short to be worth a stub
        if (BotConfig.walkerDebug)
            LOG.info("[walker] open-water bee-line stub adopted: len={} toward goal (big search still running)",
                    path.size());
        adoptPath(new PathFinder.Result(path, edges, false, 0, 0L, 0.0), world, null);
        return true;
    }

    /** A cell a buoyant body can swim across at the surface: water at the foot, a CLEAR
     *  non-water head (the surface — not a submerged mid-column cell), neither a hazard. */
    private static boolean isOpenSurfaceWater(WorldView w, BlockPos foot) {
        BlockPos head = foot.offset(0, 1, 0);
        return w.isWater(foot) && !w.isWater(head) && w.isPassable(head)
                && !w.isHazard(foot) && !w.isHazard(head);
    }

    /** @return false if the segment was REJECTED because its start is nowhere
     *  near the feet. A continuation is computed from the previous segment's
     *  commitEnd; when the bot never actually made it there (fumbled the climb,
     *  fell off the route) the spliced path STARTS in mid-air several blocks
     *  away — step 1 is unreachable, fast-forward finds nothing near, and the
     *  bot just hangs against the wall until the wedge timer fires (live
     *  2026-06-09: cave pocket, step1 4 blocks up / 4.5 away, 15 s freeze).
     *  Rejecting here lets the caller drop the stale segment and re-search
     *  from where the bot really is. */
    private boolean adoptPath(PathFinder.Result res, WorldView world, BlockPos foot) {
        if (foot != null && !res.path().isEmpty()) {
            // Anchor on the nearest node of the PREFIX, not just path[0]. While a
            // search runs (0.4-1.7 s) a SWIMMING bot keeps drifting 5-12 blocks
            // along its old segment — both routes head for the same goal, so the
            // fresh path's prefix IS the corridor the bot just swam. Judging only
            // path[0] turns that drift into a reject→re-search→drift-again
            // oscillation (live 2026-06-10: 6 rejects in 18 s, 35 min circling a
            // water-shore pocket, the 75-node highland exit killed twice). A
            // truly mis-anchored continuation (computed from a commitEnd the bot
            // never reached) has its WHOLE prefix far/high, so it still rejects.
            List<BlockPos> raw = res.path();
            int anchor = 0;
            double anchorD = raw.get(0).distSqr(foot);
            int lim = Math.min(raw.size() - 1, 16);
            for (int i = 1; i <= lim && anchorD > 1.0; i++) {
                Move.Edge e = i < res.edges().size() ? res.edges().get(i) : null;
                if (e != null && (!e.toBreak.isEmpty() || !e.toPlace.isEmpty())) break;
                double d2 = raw.get(i).distSqr(foot);
                if (d2 < anchorD) { anchorD = d2; anchor = i; }
            }
            // In WATER the gate must absorb CURRENT drift: a river pushes the
            // bot ~1.5 b/s downstream while it holds path-less, so by the time
            // a 0.4-1.7 s search lands the feet are 5-7 blocks away — with the
            // dry 4-block gate every fresh segment got rejected, the hold let
            // the river push further, and the loop swept the bot 50 blocks
            // downstream (live 2026-06-10: 10 rejects in 30 s straight down
            // z=-561, each segment's nearest prefix node = the previous foot).
            // Swimming 8 blocks back onto the route is always executable; the
            // tight gate only exists for dry falls/jumps that aren't.
            double rejectGate = world.isWater(foot) ? 64 : 16;
            if (anchorD > rejectGate
                    || Math.abs(raw.get(anchor).getY() - foot.getY()) > world.maxJumpUpBlocks() + 2) {
                if (BotConfig.walkerDebug)
                    LOG.info("[walker] reject mis-anchored segment: nearest prefix node {},{},{} (d2={}) vs foot {},{},{}",
                            raw.get(anchor).getX(), raw.get(anchor).getY(), raw.get(anchor).getZ(),
                            (int) anchorD, foot.getX(), foot.getY(), foot.getZ());
                return false;
            }
        }
        // String-pull flat walk runs so the heading stays steady over the
        // staircase (no left-right camera wobble) and the bot walks straight;
        // action/vertical/parkour nodes are preserved.
        SmoothResult sm = stringPull(world, res.path(), res.edges());
        path = sm.path;
        edges = sm.edges;
        pathBestEffort = !res.goalReached();
        commitEnd = (pathBestEffort && !path.isEmpty()) ? path.get(path.size() - 1) : null;
        step = 1;
        noPathWaitTicks = 0;            // a segment was found → the no-path wait starts over
        // A segment that ends FARTHER from the goal than it starts can only be
        // the last-resort ESCAPE (every other selector is strictly goal-ward):
        // its start is a PROVEN dead pocket. Charge it now so subsequent searches
        // stop folding back into it — without this the cave-funnel relapse loop
        // is fully deterministic (live 2026-06-09: escape→high route→fall→free
        // h-improving corridors all funnel back to the same pocket, ~3 min/lap,
        // and nothing on the lap ever calls penalizeStuckNode).
        if (goal != null && path.size() > 1
                && goal.estimate(path.get(path.size() - 1)) > goal.estimate(path.get(0))) {
            world.penalizeStuckNode(path.get(0));
            world.penalizeStuckNode(path.get(0).above());
            if (BotConfig.walkerDebug)
                LOG.info("[walker] escape segment adopted → penalize dead pocket at {}", path.get(0));
        }
        // Fast-forward past a prefix the bot has already covered. A quick-start
        // stub (progressive pathfinding) carries the bot a few blocks ahead of
        // where the superseding big search was LAUNCHED, so the fresh path can
        // start behind the feet — without this the bot visibly walks BACKWARD
        // to rejoin at path[0]. Skip to the nearest on-path node in the first
        // few steps, but never across an edge that still needs to break/place
        // blocks (skipping its action would desync the build state). Only jump
        // when a node is genuinely at the feet; a normal foot-search has
        // path[0] == foot and is untouched.
        if (foot != null && path.size() > 2) {
            // Loose rejoin ONLY when path[0] is already off the feet (the bot
            // drifted while the search ran — open water, quick-start carry).
            // A normally-anchored path keeps the strict gate: skipping to a
            // node 2-3 blocks out also skips the stair base / bridge lip
            // between here and there.
            boolean drifted = path.get(0).distSqr(foot) > 2.0;
            int window = drifted ? 16 : 8;
            // In water, accept a rejoin node as far as the reject gate lets a
            // segment in (river drift, see adopt-gate above): swimming back a
            // few blocks is always executable, and refusing leaves step=1
            // pointing far UPSTREAM of a drifted bot.
            double accept = drifted ? (world.isWater(foot) ? 64.0 : 9.0) : 2.0;
            int nearest = step;
            double nearestD = path.get(step).distSqr(foot);
            for (int i = step + 1; i < Math.min(path.size() - 1, step + window); i++) {
                Move.Edge e = i < edges.size() ? edges.get(i) : null;
                if (e != null && (!e.toBreak.isEmpty() || !e.toPlace.isEmpty())) break;
                // Never anchor onto a node beyond ±1 of the feet: a drifted bot
                // in water sits 2 below the bank nodes (3D distSqr still ranks
                // them "near", dy=2 → +4) — starting there is an impossible +2
                // climb (live: wedged at y62 vs a y64 diagUp for 100 ticks).
                // DOWN must be capped too: anchoring onto a node 5 BELOW a
                // cliff-top bot aims the walk at the column past the lip and it
                // just rams the wall (live: wp walk@y57 vs feet y62, hCol,
                // hSpd=0). ±1 keeps the rejoin on the bot's own walking layer.
                int ffDy = path.get(i).getY() - foot.getY();
                // A V-shaped underwater path (dive → ride the bed → climb out)
                // overlaps itself in XZ, so the climb-out branch ranks "nearest"
                // and skipping flattens the V: the bot anchors onto the node
                // ABOVE its head, the dive trigger never arms (wp not below),
                // and it pins against the well wall (live round46: wp=(3156,64)
                // vs feet y62, hCol, hSpd=0, 17 bursts in 2 min). A submerged
                // valley node is a hard rejoin barrier — stop the scan there:
                // everything beyond is only reachable THROUGH the dive.
                if (ffDy < -1 && world.isWater(path.get(i))) break;
                if (Math.abs(ffDy) > 1) continue;
                double d2 = path.get(i).distSqr(foot);
                if (d2 < nearestD) { nearestD = d2; nearest = i; }
            }
            if (nearest > step && nearestD <= accept) step = nearest;
        }
        stuckTicks = 0;
        frontierWaitTicks = 0;          // progress made → reset the frontier re-search budget
        stuckStep = -1;                 // new path geometry → restart the progress window
        noStepProgressTicks = 0;        // new path → restart the wedge timer (else a same-index step re-triggers instantly)
        noProgressStep = -1;
        // A freshly adopted PROGRESSIVE stub (quick-start / open-water bee-line —
        // the only adopts with foot==null) is a brand-new runway in a NEW
        // direction, so the wedge-burst counter accrued against the stale segment
        // is no longer valid: leaving it set fires a forced-displacement burst
        // ~1 s after adoption (the 40-tick count cooldown was already armed),
        // which yanks the bot BACKWARD off the runway it just got — live
        // 2026-06-15 wide-water run: bee-line adopted then burst 1 s later, on
        // repeat, crabbing the bot the WRONG way along the shore. Clear it so the
        // stub gets a clean ~6 s trial; if the bot genuinely can't follow it the
        // counter simply re-arms and bursts as before. The big-search continuation
        // (foot != null) keeps its burst intact — that's the deterministic
        // same-segment deadlock breaker and must not be reset away.
        if (foot == null) {
            wedgeRepathsHere = 0;
            lastWedgeFoot = null;
        }
        bestStepDist = Double.POSITIVE_INFINITY;
        actionTicks = 0;
        if (BotConfig.walkerDebug) {
            StringBuilder sbp = new StringBuilder();
            for (int i = 0; i < path.size(); i++) {
                Move.Edge e = i < edges.size() ? edges.get(i) : null;
                sbp.append(i).append(':').append(path.get(i).getX()).append(',').append(path.get(i).getY())
                   .append(',').append(path.get(i).getZ()).append('[').append(e != null ? e.move : "-").append("] ");
            }
            LOG.info("[walker] path = {}", sbp);
        }
        return true;
    }

    /** Emit a per-tick execution sample. Pure reads; cheap; gated to NOOP in release. */
    private void sampleTick(Player p) {
        // Cheap gate: skip the per-tick WalkerSample allocation entirely unless capture is on.
        // Keeps the hot path free in normal play and in a stripped (NOOP) release build.
        if (!BotConfig.pathDebug && !BotConfig.pathArchive) return;
        // Expose the bot's level to PathArchiveRecorder (and other PathTrace sinks) without
        // referencing the client-only Minecraft class.  Set here — before onSearchResult or
        // onSearchBegin can fire within the same tick (both sites are below line 451) — so
        // the first segment's NodePhysics and envelope sampling always see a non-null level.
        BotLevelHolder.current = p.level();
        double tx = Double.NaN, tz = Double.NaN;
        String mv = null;
        if (path != null && step >= 0 && step < path.size()) {
            BlockPos t = path.get(step);
            tx = t.getX() + 0.5;
            tz = t.getZ() + 0.5;
            Move.Edge e = edgeAt(step);
            mv = (e != null) ? e.move : null;
        }
        boolean overlap = !p.level().noCollision(p, p.getBoundingBox().deflate(1.0E-7));
        PathTraceHolder.SINK.onWalkerTick(new PathTrace.WalkerSample(
                p.tickCount, p.getX(), p.getY(), p.getZ(), p.getYRot(),
                tx, tz, step, mv, p.onGround(), p.isInWater(),
                p.getPose().name(), overlap));
    }

    private static float angleDiff(float a, float b) { return ((b - a) % 360f + 540f) % 360f - 180f; }

    /** Horizontal neighbour offsets (4 cardinals + 4 diagonals) of the foot cell. */
    private static final int[][] EDGE_NEIGHBOURS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** True if a LETHAL drop borders the cell the bot is standing on — any horizontal
     *  neighbour that is an open foot-cell with no floor, falling to the next solid/
     *  water surface deeper than {@link SurvivalMath#survivableFall} at the bot's HP.
     *  Checking ALL neighbours (not just the heading) catches lateral/momentum drift
     *  off a lip while walking ALONG it — the actual DEATH #8 mode. Vanilla sneak then
     *  pins the body to this block in every direction. Lethal-only, so it never blocks
     *  a legitimate planned step-down (those land within survivable, or in water). */
    private static boolean lethalDropAdjacent(WorldView world, Player p, BlockPos foot) {
        int survivable = SurvivalMath.survivableFall(p.getHealth());
        for (int[] o : EDGE_NEIGHBOURS) {
            BlockPos n = foot.offset(o[0], 0, o[1]);
            // A drop needs the foot-cell AND the cell below it both open (no floor).
            // A present floor = flat walk or a safe 1-block step-down; water = a splash.
            if (world.isSolid(n) || world.isWater(n)) continue;
            BlockPos below = n.below();
            if (world.isHazard(below)) return true;
            if (world.isSolid(below) || world.isWater(below)) continue;
            int fall = 1;
            BlockPos pr = below.below();
            while (fall <= survivable + 2 && !world.isSolid(pr) && !world.isWater(pr)) {
                // Lava is neither solid nor water, so the height scan used to fall
                // THROUGH it to the lake floor — a 2-deep lava pocket measured as a
                // "survivable" 2-block drop, edgeBrake stayed off, sprint stayed on,
                // and downhill momentum slid the bot in (round52: enteredLava ×3).
                // Any hazard in the fall column is lethal regardless of height.
                if (world.isHazard(pr)) return true;
                fall++;
                pr = pr.below();
            }
            if (fall > survivable) return true;
        }
        return false;
    }

    private Move.Edge edgeAt(int i) {
        return (edges != null && i >= 0 && i < edges.size()) ? edges.get(i) : null;
    }

    /** Total blocks a path would PLACE — the sum of each edge's toPlace size. Used
     *  by the block-budget reroute (搭桥前算够不够) to compare against inventory. */
    private static int countPlaceEdges(List<Move.Edge> edges) {
        int n = 0;
        for (Move.Edge e : edges) if (e != null && e.toPlace != null) n += e.toPlace.size();
        return n;
    }

    /** A continuously-sliding aim point {@code CARROT_DIST} blocks ahead
     *  along the path (interpolated between nodes), capped by line-of-sight —
     *  the pure-pursuit "carrot". Because it's interpolated (not snapped to a
     *  discrete node), the bearing to it changes smoothly as the player moves,
     *  so the heading doesn't wobble over the cardinal/diagonal staircase.
     *  Stops at a wall/turn or an action cell so it never aims through one.
     *  Returns {x, z} world coords. */
    private double[] carrotPoint(WorldView world, BlockPos foot, double px, double pz) {
        double remaining = CARROT_DIST;
        double cx = px, cz = pz, tx = px, tz = pz;
        for (int i = step; i < path.size() && i - step <= CARROT_MAX_NODES; i++) {
            BlockPos node = path.get(i);
            double nx = node.getX() + 0.5, nz = node.getZ() + 0.5;
            if (hasPendingEdge(world, edgeAt(i))) return new double[]{nx, nz}; // face the action cell
            if (!losWalkable(world, foot, node)) break;                        // don't aim past a wall
            double seg = Math.hypot(nx - cx, nz - cz);
            if (seg < 1e-6) { tx = nx; tz = nz; continue; }
            if (remaining <= seg) {
                double t = remaining / seg;
                return new double[]{cx + (nx - cx) * t, cz + (nz - cz) * t};
            }
            remaining -= seg;
            cx = nx; cz = nz; tx = nx; tz = nz;
        }
        return new double[]{tx, tz};
    }
}
