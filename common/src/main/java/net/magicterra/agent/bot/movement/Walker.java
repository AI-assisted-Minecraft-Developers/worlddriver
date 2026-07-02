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
    /** walkerWallCornerNodeAim: how far (degrees) the immediate node must sit off the path tangent before a
     *  RAM (horizontalCollision) hands the aim back from the tangent to the direct node bearing — turn the
     *  corner off the wall instead of grinding along the trend into it. 20° = a real corner, not jitter. */
    private static final float WALL_CORNER_AIM_DEG = 20f;
    /** walkerOvershootReaim: ticks wedged (stuckTicks) before a dry overshoot at a cliff base re-aims BACK
     *  onto the overshot node. ~40 = 2s, well past a clean approach but before the multi-second churn. */
    private static final int OVERSHOOT_REAIM_STUCK = 40;
    /** walkerDryReanchor: sustained stuck ticks before the dry repath-rechurn breaker anchors back to the
     *  last cleanly-passed node. ~50 = 2.5s, past safetyRepath's stuck>60? no — fire BEFORE the repath
     *  churn deepens, but well past any clean slow move (water creep is in-water-excluded). */
    private static final int DRY_REANCHOR_STUCK = 50;
    /** walkerDryReanchor: foot-to-current-node dist² (blocks²) above which the bot is "far off-path" and the
     *  anchor engages. 4 = OVERSHOOT_RESYNC_SQ (2 blocks) — a genuinely-approaching node stays under it, so
     *  a legit slow climb/creep that hugs its node never triggers; only a real off-path churn does. */
    private static final double DRY_REANCHOR_OFFPATH_SQ = 9.0;
    /** WIDER aim dead-zone (blocks²) used only when aiming at a water bank-climb node that
     *  sits OVERHEAD (+1/+2 above the floating foot). The buoyant bot can't translate ONTO
     *  such a node, so once within ~a block of its XZ it orbits the column and atan2 sweeps
     *  the full 360° as it circles — the deep-water "spin in place" stall (step frozen on the
     *  climb node, pathChart maxYawErr ~179°, camera judder; archive replay-0002 tick3537 =
     *  5.3 s). A 2-block dead-zone HOLDS the approach heading there so the body keeps pressing
     *  the bank face and the climb-out dig/pillar can anchor and lift it out, instead of
     *  chasing the node round in a circle. Only for an ABOVE node; flat swims / dives keep the
     *  tight {@link #YAW_DEADZONE_SQ}. */
    private static final double CLIMB_AIM_DEADZONE_SQ = 4.0;
    /** Descent camera/movement decouple: how far ahead (blocks) the CAMERA looks on a dry
     *  descent. On a steep grid descent the immediate-waypoint bearing sweeps ~180° as the bot
     *  passes each close node, and chasing it winds the camera (the 下山转圈 — live yaw to 671°,
     *  replay 2953°). Fix: aim the CAMERA at the first path node ≥ this many blocks away (a far
     *  point's bearing is stable → no spin) while the MOVEMENT impulse keeps driving at the
     *  immediate node (precise foot-placement → still reaches). The two run on independent
     *  channels (camera = aimYaw, movement = driveTargetYaw, decoupled by AgentInput's impulse
     *  pre-rotation), so neither damps the other — every prior single-channel fix (trend, EMA
     *  low-pass) failed precisely because damping the aim also damped the navigation. */
    private static final double DESCENT_CAM_FAR_DIST = 5.0;
    /** Descent camera trend window: the CAMERA aims at the CENTROID of the next this-many path
     *  nodes, spatially averaging a switchback staircase's alternating cardinal legs into the
     *  steady down-slope bearing. A single far node (DESCENT_CAM_FAR_DIST) SAMPLES the zigzag and
     *  itself swings ±50° as the bot descends — winding the camera ~9.7 turns on a steep slope
     *  (the live 下山/下落转圈, ground-truthed via LookController WIND telemetry 2026-06-21). A
     *  centroid does not swing. Wide enough to span ≥1 zigzag period (~2-3 nodes); the decoupled
     *  drive (driveTargetYaw=node) keeps the body on every step so trend-aiming is nav-safe. */
    private static final int DESCENT_CAM_LOOKAHEAD = 12;
    /** No-step-progress ticks before a FLAT in-water aim also widens to {@link
     *  #CLIMB_AIM_DEADZONE_SQ}. A buoyant bot can't stop precisely on a water carrot/node, so
     *  within ~1 block the aim vector rotates fast as it drifts and the bearing sweeps —
     *  measured 96-176° yaw swings that collapse forward thrust to 0.4-0.9 b/s (vs 4.6 b/s
     *  with a steady heading) + the maxYawErr≈180° camera-swing. ~0.75 s is well past any
     *  normal arrival (which advances the step and resets the timer) yet early in the
     *  multi-second oscillation stall, so a precisely-progressing approach keeps the tight
     *  dead-zone (climb-out mount accuracy) and only a real thrash widens. */
    private static final int WATER_YAW_HOLD_STALL = 15;
    /** Decaying yaw-thrash score at which a buoyant bot on the (LOS-collapsed) near carrot
     *  switches to aiming at a stable FAR path node. +4 per raw-carrot-bearing reversal
     *  (cap 12), −1/tick, so 6 ≈ two reversals recently = genuine oscillation. */
    private static final int WATER_YAW_THRASH_SCORE = 6;
    /** Path nodes ahead the far-aim override targets, and the climb look-ahead that suppresses
     *  it (a precise mount within this many nodes keeps the normal carrot). */
    private static final int WATER_FAR_AIM_LOOKAHEAD = 3;
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
    /** Forward impulse magnitude for the FREE-HANGING vine climb (camera-decoupled commandMove
     *  toward the climb target). vanilla clamps a climbable's horizontal velocity to ≤0.15/tick, so
     *  this only needs to be firm enough to win the friction race and keep the body advancing toward
     *  the exit column (and re-centring off-axis drift) while it rides the jump up. Below 1.0 so it
     *  doesn't ram past the exit and shoot off the curtain top. See the onVine free-hang branch. */
    private static final float FREE_HANG_DRIVE = 0.6f;
    /** EMA factor for low-passing the TARGET heading. A near-45° goal makes A* emit a
     *  grid STAIRCASE whose immediate-waypoint bearing alternates ±~30° around the true
     *  diagonal every step; chasing that raw target (even clamped) leaves a bounded
     *  sawtooth. Averaging the target with this factor collapses the alternation to a
     *  steady bearing (a 20°/50° square wave settles to ~35° ±5° at 0.5) while still
     *  converging on a genuine turn in a few ticks. Launches bypass it (must snap). */
    private static final float YAW_SMOOTH_ALPHA = 0.5f;
    /** Stronger low-pass for the DRY-DESCENT camera (~6-tick time constant vs the cruise 2-tick).
     *  Smooths the residual centroid-quantisation jitter (nodes shifting in/out of the look-ahead
     *  window nudge the trend bearing ~3°/tick) into a near-still heading. Safe to lag this hard
     *  ONLY because the descent drive is camera-decoupled (driveTargetYaw=node) — the body keeps
     *  taking every step while the camera eases onto the trend. See DESCENT_CAM_LOOKAHEAD. */
    private static final float YAW_SMOOTH_ALPHA_DESCENT = 0.08f;
    private static final float WATER_DRIVE_ALPHA = 0.3f;   // EMA on the water drive heading (damps ±180° node flip)
    /** Max one-tick turn (deg) the flat-water DRIVE heading will chase. A real swim turn — even the
     *  carrot rounding a corner — moves the heading gradually; a SUDDEN ±180° jump is a transient
     *  artifact (a node overshoot, a re-plan, or the carrot collapsing onto a wall/path-end and
     *  falling back to the flipping node bearing). Reject it — hold the forward heading — so the
     *  body doesn't lurch backward and crawl (the open-water "绕node打转" churn). */
    private static final float WATER_DRIVE_MAX_TURN = 120f;
    /** Consecutive flip-rejections after which the DRIVE SNAPS to the live source anyway. A genuine
     *  transient flip lasts 1–2 ticks (the carrot returns and tracking resumes); a PERSISTENT >120°
     *  disagreement means the route really did turn (or the carrot has collapsed for good at a wall)
     *  and the held heading is now stale — keep rejecting it and the swim strands itself pointing the
     *  wrong way (live -1648 / -1676: driveYaw froze ~ -99/103 while the node sat behind, totStuck
     *  climbed 500+). Snapping after a few ticks turns the body toward the live target so it makes
     *  progress, `step` advances past the overshot node, and the carrot recovers. */
    private static final int WATER_DRIVE_MAX_REJECT = 4;
    /** Max drop (blocks) below the floating foot that a NON-dive submerged path node is floated
     *  over (crossed horizontally at the surface) instead of followed down. A* routes wide
     *  deep-water crossings along the riverbed, placing walk/parkour nodes many blocks under the
     *  surface swimmer; following them dives the buoyant body and stalls it bobbing underwater (the
     *  "潜底/挖墙" stall). A surface/land goal never needs to END deep underwater (real descents use
     *  fall/swimDown edges), so floating over any reasonable depth is safe; a swimDown dive keeps
     *  its own tight bound. */
    private static final double FLOATOVER_NONDIVE_MAX_DROP = 32.0;
    /** Ticks the flat-water swim trend stays latched after a surface bob momentarily lifts the foot
     *  out of the fluid (p.isInWater() blinks false for ~1 tick at the apex of the buoyant bob).
     *  Without the latch the DRIVE snaps to aimYaw for that one tick — a ±175° heading excursion
     *  twice per bob (the residual near-goal 2-tick driveYaw flip-pairs). A real climb-OUT/dive is
     *  still dropped immediately by the wp.y/swimDown gates, so this only rides out the bob. */
    private static final int WATER_SURFACE_LATCH_TICKS = 4;
    /** Ticks the deep-water drift sprint-brake stays latched after a grounded fire, to keep
     *  sprint OFF through the airborne sub-arcs of a step-down descent toward a deep pocket
     *  (a staircase step is ~3-4 airborne ticks; the brake re-fires and re-arms the latch on
     *  each grounded tick, so 8 comfortably bridges the gaps without lingering once the bot
     *  has finished the descent or moved off the deep edge). */
    private static final int DEEP_WATER_DRIFT_LATCH = 8;
    /** Consecutive ticks the buoyant-climb-press condition (wp above foot) must hold before the
     *  press engages. A surface bob drops the foot one block under a SAME-LEVEL surface node for
     *  ~1 tick, spuriously satisfying wp.y > foot.y and firing a "mount" that overrides the drive
     *  to the already-passed (→ ±180° flipped) column heading + a jump (the residual near-goal
     *  driveYaw flip-pairs, jump=true on every flipped tick). A REAL +1 bank climb keeps the node
     *  above the foot EVERY tick (the body sits below the bank through the whole mount), so it
     *  satisfies the debounce immediately; the 1-tick bob never does. */
    private static final int CLIMB_PRESS_DEBOUNCE = 3;
    /** Per-tick smoothed-target change (deg) below which the aim target counts as STABLE.
     *  The anti-spin freeze ({@link #spinFreeze}) must only fire on a FLIPPING target (a
     *  ~180° swing each repath winds the camera). A stable target the capped slew converges
     *  to once cannot wind, so freezing it just pins a wrong heading. */
    private static final float AIM_STABLE_DEG = 8f;
    /** Consecutive STABLE ticks (≈0.5 s) after which the anti-spin freeze releases — long
     *  enough to ignore one transient flip, short enough to break the badlands-basin
     *  heading-freeze deadlock (live 2026-06-16: heldYaw frozen 500+ ticks pressing a bank
     *  while a stable bearing pointed ~150° away). */
    private static final int AIM_STABLE_TICKS = 10;
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
    /** Path-trend averaging window (nodes) for the dry +1 staircase aim. A ~45° goal makes A*
     *  emit a grid STAIRCASE whose immediate-node bearing alternates ±~30° around the true
     *  diagonal every step; aiming at it swings the heading past {@link #STEPUP_AIM_TOLERANCE_DEG}
     *  each step → the pivot gate cuts forward drive → the speed sawtooth + camera zigzag the path
     *  charts show. Averaging the next few SEGMENT unit-vectors (stopping at a real >60° bend so it
     *  never aims across a corner — the single-far-node aim that did was reverted) recovers the
     *  steady diagonal, holding the heading inside the gate so the climb keeps full drive. On a
     *  straight staircase the trend equals the immediate bearing, so cardinal climbs are unchanged. */
    private static final int STAIR_TREND_LOOKAHEAD = 5;
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
    /** Lateral-bank-follow: how many cells to scan along the bank (each way) for a mountable exit lip.
     *  Widened 6→10 (2026-06-28): tall +2 walls (e.g. -722 boxed-pinch) have their nearest steppable
     *  +1/flat exit further along the bank; a 6-cell reach missed it → 3-min floating deadlock. */
    private static final int BANK_FOLLOW_SCAN = 10;
    /** No-step-progress ticks before the ≥2 ascentRamSlide desync recovers — DELIBERATELY well
     *  below STEPUP_FREEZE_TICKS. A node ≥2 above a GROUNDED foot is never a planned move (steps/
     *  parkour/pillar all rise +1), so it is ALWAYS an execution slide-back the bot can never
     *  jump-mount; waiting the full freeze-breaker window just lets the futile grounded jump bob
     *  (live 2026-06-24 round3: foot y84 / node y87 = +3, bounced jump→fall→jump ~32 ticks / 1.6 s
     *  on node -822,87,693 before the 24-tick gate let ascentRamSlide fire). noStepProgressTicks is
     *  jitter-immune (resets only on a real step-advance / path change), so a bot that is genuinely
     *  self-correcting never reaches even this low count — recovering here only ever fires on a
     *  truly frozen ram, and BEFORE the freeze-breaker wastes a jump on an unmountable step. */
    private static final int ASCENT_SLIDE_RECOVER_TICKS = 10;
    /** rawStepDwellTicks (bob-immune, resets only on step-change) a steep +2/+3 ascent slide-back must DWELL
     *  before {@link BotConfig#walkerAscentRamJitterImmune} folds it into fellOffPath. Higher than
     *  {@link #ASCENT_SLIDE_RECOVER_TICKS}=10 (which counts only non-progress ticks) because this counts EVERY
     *  tick on the step; 30 (~1.5 s) confirms a sustained ram (a transient 2-above overshoot resolves via
     *  within/passed in 1-3 t, resetting the dwell) while still recovering ~5× sooner than the slow
     *  {@link #WEDGE_TICKS}=100 burst the jitter-defeated noStepProgress gate falls back to. */
    private static final int RAM_JITTER_RECOVER_TICKS = 30;
    /** rawStepDwellTicks gap before {@link BotConfig#walkerAscentRamJitterImmune} may FOLD again within the
     *  SAME ram episode. Without a cap the recover fires every tick (10-85×/episode → a fellOffPath/repath
     *  cascade); but a pure once-per-step latch lets a STUBBORN ram — whose post-fold repath returns to the
     *  same step — never re-attempt, so it churns (live: -777 diagUp 244 t). A 20-tick (~1 s) debounce caps
     *  the rate to ~1 fold/s (no cascade) yet still re-attempts a stubborn ram every second (no long churn). */
    private static final int RAM_RECOVER_DEBOUNCE = 20;
    /** Ticks an in-place pillar-up recovery is latched once armed — long enough for a
     *  jump's airborne arc to crest and place a support (vanilla peak ~tick 6-8), short
     *  enough that it re-evaluates promptly. Re-armed each grounded tick while the bot is
     *  still below the next node beyond jump reach (see overJump). */
    private static final int PILLAR_RECOVER_TICKS = 14;
    // A pillar-recovery under a tree-canopy/dirt OVERHANG bobs in place without gaining height
    // (the recCeiling foot+2 break-probe misses a foot+3 ceiling; the vertical bob keeps resetting
    // the wedge timer) — 247 ticks looping at one canopy spot (live #47 replay -810,119, 339 pillarUp
    // total). Once the recovery hasn't risen for this many ticks, stop re-arming the pillar so the
    // foot-search re-routes around the overhang (blacklists the unreachable node) instead of forever.
    private static final int PILLAR_NORISE_GIVEUP = 50;
    /** A SLOW (but not hard-wedged) +1 diagUp converts to a deterministic pillar-up after this
     *  many no-step-progress ticks. On a steep √2 staircase the diagonal jump-traverse bob-rams
     *  the riser ~47 ticks/step (live 2026-06-24 west mountain) — visually a stutter — whereas
     *  placing a support under the foot + jumping is a clean +1. Set ABOVE STEPUP_FREEZE_TICKS so
     *  the cheaper grounded-jump freeze-breaker gets the first ~1.2 s to mount the step; only a
     *  diagUp still stalled past this escalates to the pillar. The dy==1 gate keeps it disjoint
     *  from the ≥2 ascentRamSlide (slide-BACK) case. */
    private static final int DIAGUP_PILLAR_TICKS = STEPUP_FREEZE_TICKS * 2;
    /** LAST-RESORT deep-pit escape: when the bot fell into a SHEER pit DEEPER than the
     *  pillar-recover cap (committed node > PILLAR_RECOVER_MAX_DY above the foot), fellBelowRoute
     *  can't pillar it back up, and a foot-search inside a 1-wide sheer pit keeps returning the
     *  un-climbable rim route — a 15-21 s loop (live J8 2026-06-24: foot y71, node y83, 12 below,
     *  noStepProgressTicks 300 of fruitless repaths before it finally escaped). Gated on a long
     *  CONFIRMED stuck so it ONLY acts on an already-broken state a functioning bot never reaches
     *  (any step-progress resets noStepProgressTicks → tiny regression surface), then pillars
     *  straight up the open shaft until the gap closes to PILLAR_RECOVER_MAX_DY and the normal
     *  fellBelowRoute takes over. Set well above WEDGE_TICKS (=100) so the cheaper foot-search gets
     *  a full wedge cycle to find a real out first; only a STILL-looping deep pit escalates. */
    private static final int DEEP_PIT_ESCAPE_TICKS = 150;
    /** A pillarUp places its support directly below the bot and jumps in place, so the place
     *  only lands the bot on the fresh block when it stands over the node's column. Climb-drift
     *  can leave the bot 1-3 blocks to the side at the node's level; placing-in-place then rises
     *  onto a block BESIDE the column, the bot falls back, and it oscillates for seconds (the
     *  pillarUp-off-column wedge: live V2 5.8 s, J9 25 s). Squared XZ distance to the column
     *  beyond which the actuator aligns (walks to the column XZ) before pillaring; 0.25 = 0.5
     *  block, tight enough that the buoy-free vertical jump lands squarely on the placed block. */
    private static final double PILLAR_ALIGN_SQ = 0.25;
    /** noStepProgressTicks before the WALK overshoot-advance (crossedWalkNode) nudges a bot that
     *  has crossed a flat walk node and is orbiting it in the step-advance dead-zone past the
     *  wedge — short enough to cut the ~5.5 s wedge-repath wait, long enough that a normal walk
     *  (which advances via within/passed in 1-2 ticks, resetting noStepProgressTicks) never trips it. */
    private static final int WALK_OVERSHOOT_STUCK_TICKS = 16;
    /** noStepProgressTicks a bot pinned at a SHALLOW WATER-SURFACE step-down foothold must be stalled
     *  before {@link BotConfig#walkerWaterStepDownFloat} advances the step at the relaxed reach. Same
     *  spirit as {@link #WALK_OVERSHOOT_STUCK_TICKS}: a clean approach advances via within/passed in
     *  1-2 ticks (resetting noStepProgressTicks) and never trips it, so this ONLY rescues a confirmed
     *  buoyant bob-ram at the water surface. */
    private static final int WATER_STEPDOWN_STALL_TICKS = 14;
    /** Relaxed horizontal reach² for advancing a STALLED shallow water-surface step-down node
     *  ({@link BotConfig#walkerWaterStepDownFloat}). The buoyant body grounds vertically AT the node
     *  (|dY|≈0) but pins ~0.74 b short of centre (cur2≈0.55) against the surface ram, just over the
     *  tight {@link #REACH_DIST_SQ}=0.45. 1.2 (≈1.1 b) comfortably covers that pin yet stays well under
     *  {@link #OVERSHOOT_RESYNC_SQ}=4 so it can never skip a node the bot is still genuinely approaching
     *  / cut a live corner. */
    private static final double WATER_STEPDOWN_REACH_SQ = 1.2;
    /** noStepProgressTicks a bot ORBITING a +1 stepUp/diagUp CREST node must be stalled before
     *  {@link BotConfig#walkerStepUpCrestReach} advances the step at the relaxed crest reach. Same
     *  spirit as {@link #WATER_STEPDOWN_STALL_TICKS}/{@link #WALK_OVERSHOOT_STUCK_TICKS}: a clean
     *  stepUp tops out and advances via within/passed in <12 ticks (resetting noStepProgressTicks),
     *  so this ONLY rescues a confirmed crest bob-orbit, never a node still being climbed. */
    private static final int STEPUP_CREST_STALL_TICKS = 16;
    /** Relaxed horizontal reach² for advancing a STALLED +1 stepUp/diagUp crest node the bot has
     *  topped out on but ORBITS ({@link BotConfig#walkerStepUpCrestReach}). On a diagonal staircase
     *  crest (a +2 plateau lip) the body reaches the node's Y at the bob crest (|dyNode|≈0) but the
     *  buoyancy-free apex bob + the ±0.5 b lateral orbit keep cur2 pinned at ~0.49-1.2 — just over
     *  the tight {@link #REACH_DIST_SQ}=0.45 — so `within` never fires and `passed` never reads the
     *  next node STRICTLY closer while circling, freezing the step ~25-51 ticks (live -633,80,318:
     *  cur2 floor 0.492, py 79.0↔80.25 across node y80, 25 t orbit). 1.3 (≈1.14 b) comfortably covers
     *  that pin yet stays well under {@link #OVERSHOOT_RESYNC_SQ}=4 so it can never skip a node the
     *  bot is still genuinely approaching from afar / cut a live corner. */
    private static final double STEPUP_CREST_REACH_SQ = 1.3;
    /** noStepProgressTicks a buoyant bot ORBITING/FROZEN at a flat {@code walk} WATER-SURFACE node must be
     *  stalled before {@link BotConfig#walkerWaterWalkReach} advances the step at the relaxed reach. Same
     *  spirit as {@link #STEPUP_CREST_STALL_TICKS}: a clean surface crossing advances each walk node via
     *  {@code passed} (forward momentum) in 1-2 ticks — noStepProgressTicks (HORIZONTAL-only in water,
     *  Walker:~1666, so the bob can't fake-reset it) stays low — and never trips it; only a turn / terminal /
     *  WALL-CORNER freeze where neither {@code within} nor {@code passed} can fire accumulates it. */
    private static final int WATER_WALK_STALL_TICKS = 24;
    /** Relaxed horizontal reach² for advancing a STALLED flat {@code walk} water-surface node a buoyant body
     *  orbits ({@link BotConfig#walkerWaterWalkReach}). The surface swimmer sits ~0.67 b out (cur2 floor
     *  ~0.455, just over {@link #REACH_DIST_SQ}=0.45) and at a wall-corner freezes farther out still (live
     *  deep-water bay corner: cur2 1.142 frozen 321 t, within=0, aim swinging 403°). 1.3 (≈1.14 b) covers both
     *  yet stays well under {@link #OVERSHOOT_RESYNC_SQ}=4 so it can never skip a node the bot is still
     *  genuinely swimming toward from afar / cut a live corner. */
    private static final double WATER_WALK_REACH_SQ = 1.3;
    /** stuckTicks before a FLAT-node carrot-orbit falls back to aiming at the IMMEDIATE node
     *  instead of the look-ahead carrot. On a flat walk aimAtWaypoint is false (wp.y==foot.y), so
     *  the body follows the carrot; at a turn/corner node the carrot points ~60° off the close node
     *  and the body orbits it at ~0.75 b (cur2 just outside the within-gate), never closing — stuckTicks
     *  climbs (live 2026-06-24 FREEZE-DIAG at the -812/-813 pit approach: driveF=1, fwdComp>0, aimAtWp=
     *  false, ddeg≈-60, hSpd~0.07, cur2 frozen, 26-80 ticks). reCentre aims at the PREVIOUS node and the
     *  strafe only centres the cross-axis — neither pulls onto the IMMEDIATE node, so neither closes the
     *  orbit. Aiming straight at the fixed node centre closes the gap to the within-gate / a clean
     *  crossing → the step advances. Above the normal flat-walk stuckTicks (≤6, reset every tick the bot
     *  closes on its node via bestStepDist) so a healthy walk never trips it, and below the ~16-tick
     *  overshoot-advance so an actual overshoot still prefers the discrete step-skip. */
    private static final int APPROACH_NODE_AIM_TICKS = 12;
    /** noStepProgressTicks a floating bot must be RAMMED (in-water hCol) before the lateral-pad
     *  break ({@link BotConfig#walkerPadRamBreak}) scans the body-overlap columns for a lily pad the
     *  head-on pad scan missed. Long enough that a normal swim through clean water — which advances its
     *  step every 1-2 ticks and resets noStepProgressTicks — never trips it (so a transient brush past
     *  a pad while still moving isn't broken), short enough to cut the ~270-tick (13.5 s) repath freeze
     *  the unbroken lateral pad otherwise causes. */
    private static final int PAD_RAM_STALL_TICKS = 10;
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
    /** Wall-corner fast-churn (walkerWallCornerFastChurn): consecutive sustained-hCol ticks before the
     *  net-displacement churn window is SHORTENED to {@link #WALL_CHURN_WINDOW}. The §39 贴墙卡住 stall
     *  (rocky/dirt/water-boundary wall-corner) makes NO net XZ progress with hCol pinned true, but the
     *  node-relative stuck counters (totStuck, noStepProgressTicks) get RESET by the orbit's node-churn so
     *  the 20s window is the only thing that catches it — too slow (live journey-A: 20-45s per stall). A
     *  SUSTAINED ram (hCol true ≥4s continuous) is the unambiguous wall-corner signature; gating the
     *  short window on it fires the existing blacklist+escalate in ~8s WITHOUT touching the legitimate
     *  slow-but-moving case (hCol=false → full 20s window), which is exactly why the unconditional
     *  walkerFasterChurnRepath was reverted. */
    private static final int HCOL_RAM_TICKS = 80;
    private static final int WALL_CHURN_WINDOW = 160;
    /** Net altitude gain (blocks) over a CHURN_WINDOW that still counts as a real climb.
     *  The churn charge normally needs a best-effort path, but a GOAL-REACHING path can
     *  ALSO limit-cycle: at a steep mountain base the XZ heuristic baits A* into cheap
     *  goal-ward canyon/cave floor-walks (live z3160: bob y62-68, net ground travel ≈0,
     *  never ascends toward an XZ goal high on the far side). Firing the charge whenever
     *  net XZ is tiny AND the bot gained ≤ this much altitude catches that base-oscillation
     *  / cave-descent without ever penalising a genuine upward climb (which gains ≫ this). */
    private static final int CHURN_MIN_Y = 4;
    /** How long (ticks ≈ 60 s) a single detected boxed churn keeps the steep-barrier
     *  planner escalation armed. Sticky so a couple of wandering windows that briefly
     *  show net progress mid-climb don't drop the escalation before the climb completes. */
    private static final long BOXED_ESCALATE_STICKY_TICKS = 1200;
    /** PROACTIVE PINCH escalation (渐进式 pinch 预判, gated by {@link BotConfig#pathfinderProgressive}):
     *  if a BIG search comes back best-effort having closed less than this many blocks of
     *  goal distance, the planner is wedged at a pinch and a low budget will keep
     *  re-committing the shallow scrap the bot churns on. Arm the deep-search escalation
     *  on that FIRST struggling commit — instead of waiting ~20 s for the reactive
     *  churn-window. A healthy segment clears far more than this (horizon commits ≈48
     *  blocks), so an open cruise never trips it. Matches the churn move threshold (8). */
    private static final double PINCH_MIN_PROGRESS = 8.0;
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
    /** Per-riser tick budget the toolless climb-out STAYS committed to breaking ONE latched
     *  riser, even while A* transiently repaths the climb away (wantClimb flicker). A buoyant
     *  bot mining a STONE bank by hand takes ~750 ticks/block (×5 not-on-ground penalty); any
     *  mid-break disengage drops the half-broken riser, so before this the bot abandoned each
     *  block partway, wandered 10+ columns, and drifted into a deep hole and sank (live
     *  2026-06-20: one stone block dug 517× then dropped; y62→y27). 1000 covers stone + margin;
     *  past it a genuinely stuck dig releases to repath. Reset per riser so a multi-block climb
     *  gets a fresh budget for each step. */
    private static final int WATER_CLIMB_DIG_COMMIT_CAP = 1000;
    /** Futile-overhang bank-dig early-release window (ticks), gated by
     *  {@link BotConfig#walkerFutileBankDigRelease} (default OFF → inert). A dig committed to one
     *  still-fully-solid {@code >= FUTILE_BANK_DIG_MIN_RISE}-above-foot riser for this many ticks
     *  WHILE the bot never grounded = an unreachable overhang it can never break (live -784: 1001
     *  swings, 0 breaks, onGround=false throughout). Well below {@link #WATER_CLIMB_DIG_COMMIT_CAP}
     *  (1000) so the bot recovers ~40 s sooner, yet a LEGIT +1 climb-out dig never reaches it (its
     *  riser is +1 — below the rise gate — and the bot grounds on the freed notch, resetting the
     *  never-grounded guard, and the riser BREAKS, resetting the tick counter). 200 ≈ 10 s. */
    private static final int FUTILE_BANK_DIG_TICKS = 200;
    /** Minimum riser-above-foot height (blocks) for the futile-overhang early-release to consider a
     *  dig hopeless. The legit toolless climb-out digs the LOWEST solid cell just above the water
     *  line (a +1 reachable notch); only a {@code >=2}-above-a-floating-foot riser is the
     *  unreachable overhang the buoyant bob can never break or ground on. */
    private static final int FUTILE_BANK_DIG_MIN_RISE = 2;
    /** Ticks the bank-DIG is blocked from re-engaging after a futile-overhang early-release
     *  (see {@link BotConfig#walkerFutileBankDigRelease}) so the reactive back-off burst can
     *  shove the bot off the unreachable riser before any dig re-latches it. ~1.5 s. */
    private static final int FUTILE_BANK_DIG_COOLDOWN = 30;
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
    /** Eye-Y bob (blocks) past which the block-less bank dig RE-AIMS its once-snapped look
     *  at the riser. The dig only runs while FLOATING, where buoyancy + a jump bob the eye
     *  ±0.9..1.5; the once-only snap then points the fixed ray OFF the 1-tall riser face
     *  (eye rises → ray passes above it) so the break never lands and the bot bobs ~30 s.
     *  ~0.4 ≈ the drift that walks the ray off a 1-block face → re-snap to hold it on, while
     *  staying far above the per-tick re-aim that judders the camera (不跳变视角). */
    private static final double DIG_REAIM_EYE_DY = 0.4;
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
    private double lastDigAimEyeY = Double.NaN;   // eye-Y at the last dig aim-snap; re-snap once the buoyant bob has moved the eye far enough (DIG_REAIM_EYE_DY) that the fixed ray would drift OFF the 1-tall riser face (the ±0.5 once-only assumption fails for a ±1.5 deep-water bob → break never lands, 30s bob-stall)
    private BlockPos waterClimbDigRiser;   // LATCHED bank-dig riser cell — held while still solid so a buoyant bob (foot.y flickering ±1) or lateral drift (foot.z wandering) can't re-target a LOWER block of the same column or a neighbouring column mid-dig; cleared once the block breaks so the next +1 step is chosen fresh (drift-arena over-dig fix)
    private int waterClimbDigCommitTicks;  // ticks spent committed to the CURRENT latched riser; while >0 and <CAP the climb-out won't disengage on a transient wantClimb flicker, so a slow stone-bank break (~750 ticks by hand) finishes instead of being abandoned mid-dig → wander/sink; reset per riser
    private int waterClimbDigFloatTicks;   // consecutive ticks committed to the current latched riser while the bot stayed AFLOAT (onGround=false); reset to 0 on any ground contact. Feeds the futile-overhang early-release (walkerFutileBankDigRelease): a high count + a >=2-above-foot riser that breaks NOTHING = an unreachable overhang, not a legit grounded climb-out (which touches ground and resets this)
    private int futileBankDigCooldown;      // ticks left during which the bank-DIG must NOT re-engage after a futile-overhang early-release (walkerFutileBankDigRelease) — gives the reactive back-off burst time to physically move the bot off the unreachable riser before any dig can re-latch it; decremented per tick
    private boolean climbPillarGaveUp;     // latched once the pillar takeover proves futile (drifted off its locked column, or bob peak never clears the surface fill cell) → block pillar re-engage + let the bank-DIG take over even with a place block in hand; cleared when the climb context ends
    private BlockPos climbGaveUpPos;       // where the pillar proved futile (walkerClimbGaveUpSticky) — while the foot stays within 3 blocks and the TTL runs, the gave-up latch survives climb-context resets (repath node swaps) so the proven-futile pillar can't re-engage in a loop
    private int climbGaveUpTtl;            // ticks left on the sticky gave-up latch (walkerClimbGaveUpSticky); decremented per tick, 0 = expired
    private boolean drowningEscapeLatch;   // walkerDrowningEscape: air critically low while submerged → surface-for-air override active until air recovers
    private int pillarNoPlaceTicks;        // ticks the pillar takeover has been engaged without a successful place / height gain — buoyant bob can't lift feet above a surface fill cell, so beyond PILLAR_FUTILE_TICKS the place is hopeless and we fall to the dig
    private int waterClimbTargetY;         // safety ceiling Y for the pillar (engage foot + a few); bail if exceeded
    private int waterClimbColX, waterClimbColZ; // LOCKED column the takeover pillars in (don't chase repathing nodes)
    private float waterClimbYaw;           // LOCKED heading toward the bank at engage (no horizontal chase → no wander)
    private int diveLatch;            // ticks left forcing a dive-under-cap (set on a blocked submerged descent; holds the dive through the sink so it doesn't flip-flop)
    private int diveHold;             // ticks left holding an ACTIVE descent (diving) across repaths — a mid-sink repath re-plans from the buoyancy point with a dy=1 first hop, which alone never re-arms diving, so the bot pops back up (round45 water-well live)
    private int pillarRecoverLatch;   // ticks left driving an in-place pillar-up recovery (bot fell below the climb path beyond jump reach) — latched across the jump's airborne phase so a place can land
    private BlockPos pillarRecoverCell; // the (grounded) feet cell the recovery is filling this rung
    private int pillarRecoverPeakY;      // highest foot Y this pillar-recovery has reached (no-rise give-up tracking)
    private int pillarRecoverStallTicks; // consecutive recovery ticks with no height gain → PILLAR_NORISE_GIVEUP re-routes
    private int stepRamStuckTicks;       // GROUNDED ticks ramming an above-node riser (bob-immune; dry OR shallow water) → STEPUP_FREEZE_TICKS engages stepUpFreeze
    private int ascentRamBobTicks;       // foot-below-node + lateral-close ticks IGNORING onGround (bob-resettable; steep-bank +1 mount) → ORs into stepUpFreeze (gated walkerAscentRamBobBreak)
    private int floatingBankBobTicks;    // FLOATING +1 water-bank: !onGround + foot-below-node + lateral-ram, IGNORING the in/out-water bob → ORs into stepUpFreeze (gated walkerFloatingBankBobFreeze)
    private int bankFollowRamTicks;      // FLOATING water-bank lateral ram (ANY node-Y) → drives a slide ALONG the bank toward a mountable exit (gated walkerFloatingBankFollow)
    private boolean descending;       // ending creative flight; wait to land before pathing
    private PathFinder.Search activeSearch;  // in-flight time-sliced A* (null = none)
    private double bestDistToGoal = Double.POSITIVE_INFINITY;
    private double bestGoalDist = Double.POSITIVE_INFINITY;  // anti-spin: best goal-estimate across repaths (5-block margin ignores micro-lunges)
    private int repathsNoProgress;                           // anti-spin: consecutive in-water repaths that didn't improve bestGoalDist
    private int churnResets;                                 // anti-spin: fresh baselines granted after a stall (tolerates water go-arounds; real progress clears it)
    private BlockPos churnBase;                              // land boxed-pocket: foot at the start of the current net-displacement window
    private int churnWindowTicks;                            // land boxed-pocket: ticks elapsed in the current window
    private int churnEscapes;                                // land boxed-pocket: consecutive windows that detected churn (escalates the charge radius)
    private int hColRamTicks;                                // wall-corner: consecutive ticks of sustained horizontalCollision (the §39 贴墙卡住 ram signature)
    private long pfTickCounter;                               // monotonic per-tick counter (drives the sticky boxed-escalation timer)
    private long boxedEscalateUntilTick;                     // steep-barrier escalation armed until this tick (sticky so a few net-progress windows mid-climb don't drop it)
    private double bestStepDist = Double.POSITIVE_INFINITY; // closest approach² to the current node (drives the progress-based stuckTicks)
    private int stuckStep = -1;                             // path index bestStepDist tracks; a step change starts a fresh progress window
    private int noStepProgressTicks;                        // jitter-immune ticks on the SAME step (resets only when step advances/path changes) → wedge detector
    private int underwaterTicks;                            // consecutive eyes-under ticks → debounces the swim-up jump (surface bob ≠ sinking)
    private int noProgressStep = -1;                        // path index noStepProgressTicks tracks (independent of bridge/progress resets)
    private double noProgressBestD2 = Double.POSITIVE_INFINITY; // closest-ever approach² to the tracked step; monotonic, so a bob can't reset the wedge timer but a slow water cruise along a long string-pulled edge does
    private int crestOrbitTicks;                            // BOB-IMMUNE dwell on a dry stepUp/diagUp CREST step: resets ONLY on step-advance/path-change, never on the 3D new-low the vertical bob fakes on dry land → lets walkerStepUpCrestReach fire on a WIDE bob orbit where noStepProgressTicks keeps zeroing
    private int crestOrbitStep = -1;                        // path index crestOrbitTicks tracks
    private int rawStepDwellTicks;                          // PURELY step-tracked dwell (resets ONLY on step-change/path-change, NEVER on a 3D new-low) → jitter-immune wedge gate for the steep-ascent slide-back recovery (walkerAscentRamJitterImmune)
    private int ramRecoverLastFireDwell = -1;              // rawStepDwellTicks at the last walkerAscentRamJitterImmune fold (-1 = none this episode); a RAM_RECOVER_DEBOUNCE gap caps the fold rate AND re-attempts a stubborn same-step ram; cleared on step/path change
    /** Phase-3: |ds| (blocks of arc-length per tick) at/under which the bot counts as making NO forward
     *  path progress. A normal walk is ~1.0/tick, the slowest legit water-creep still clears ~0.1, so 0.05
     *  flags a true wall-ram (literally pinned) without misfiring on slow-but-moving travel. */
    private static final double ARC_WEDGE_DS = 0.05;
    /** Phase-3: consecutive ARC_WEDGE_DS+horizontalCollision ticks before the arc-wedge folds into fellOffPath.
     *  30 ticks (1.5 s) cuts the legacy ~100-tick (5 s) wedge wait by 70% while sitting well past a legit
     *  stepUp/diagUp jump-mount (which presses the riser only ~5-10 t before it tops out and ds jumps). */
    private static final int ARC_WEDGE_TICKS = 30;
    /** Phase-3b net-arc-progress window (walkerArcProgressWedge). 40 ticks (2 s): a healthy walk advances ~8 blocks
     *  of arc-length, the slowest legit climb still clears several, so requiring ≥ ARC_PROG_MIN net progress over the
     *  window flags an oscillating/frozen limit cycle (net ≈ 0) without misfiring on slow-but-moving travel. Shorter
     *  than the legacy 100-tick wedge so it recovers ~2.5× sooner. */
    private static final int ARC_PROG_WINDOW = 40;
    private static final double ARC_PROG_MIN = 2.0;
    // ===== Phase-0 SHADOW arc-length pursuit (walkerArcLengthShadow) — DRIVES NOTHING, logged only =====
    private List<BlockPos> arcShadowPath;                   // path identity the shadow s tracks (reset s/ds when the path object changes)
    private double arcShadowS = Double.NaN;                 // last tick's cumulative arc-length (XZ, from path[0] to the foot's polyline projection); for ds/dt + monotonic-violation detection
    private long arcShadowMonoViol;                         // count of backward-snap events (ds < -0.5) — must stay ~0 on clean runs for the projection to be trustworthy enough to drive in Phase 1
    private final PathProjection arcProj = new PathProjection();  // reusable arc-length projector (the carved-out, fully-readable core; the executor only holds onto its result)
    // Phase-3 (walkerArcLengthWedge): GROUNDED-RAM wedge timer measured in ARC-LENGTH, not 3D-new-low. The legacy
    // descentRamStuck/ascentRamSlide recoveries hang on noStepProgressTicks (a 3D-new-low counter) which the
    // buoyancy bob AND the sub-block ram-jitter (the bot sliding a few cm against a wall) ZERO every tick → the
    // gate never fills and the ram bobs ~5 s until the slow 100-tick wedge fires. The horizontal arc projection s
    // is immune to that vertical/jitter noise (a wall-ram makes no forward arc progress regardless of bob), so
    // ticks of |ds|<ARC_WEDGE_DS while horizontalCollision accumulate reliably → fold into fellOffPath far sooner.
    private int arcWedgeTicks;                              // consecutive ticks of ~zero arc-progress while ramming (bob/jitter-immune); reset on any real ds or path change
    // Phase-3b (walkerArcProgressWedge): NET arc-length progress over a WINDOW — catches an OSCILLATING limit cycle
    // the per-tick ram wedge (arcWedgeTicks, needs hCol + per-tick |ds|<0.05) and the anti-churn net-XZ both miss.
    // A steep-face diagUp churn (live 2026-06-28 -815, replay-0006) has the bot bob-jumping airborne (no hCol), making
    // small per-tick FORWARD ds then sliding back — net arc-s ≈ 0 over the cycle yet per-tick |ds| > 0.05 (so the ram
    // wedge resets) and net-XZ swings 35 blocks laterally (so the anti-churn is fooled). arc-s is the projection ONTO
    // the path, immune to the lateral swing AND the vertical bob, so net arc-s over a window cleanly flags "no path
    // progress despite motion". Folds into the SAME fellOffPath recovery (fresh foot-search blacklists the
    // un-advanceable node + re-routes). Window-based so it does NOT need hCol or onGround.
    private double arcProgBaseS = Double.NaN;               // arcProj.s at the start of the current progress window
    private int arcProgWindowTicks;                         // ticks elapsed in the current arc-progress window
    private boolean arcProgStall;                           // last completed window made < ARC_PROG_MIN net arc-s progress → stuck (consumed by fellOffPath)
    private boolean searchSuppressedPlace;                  // the in-flight search dropped placing moves (block-budget reroute) → adopt its result without re-checking
    private float smoothTargetYaw = Float.NaN;              // EMA-low-passed target heading (NaN = uninitialised; resync on launch/new goal)
    private float smoothWaterDriveYaw = Float.NaN;          // EMA-low-passed water DRIVE heading (separate from the camera trend)
    private float freeHangDriveYaw = Float.NaN;             // slew-limited world heading of the free-hang vine DRIVE (NaN = resync); smooths the step-jitter ±180° flips that would circle the body off a narrow column
    private int surfaceWaterLatch = 0;                      // ticks the open-water swim stays latched after a surface bob lifts the foot out of the fluid (rides out the isInWater blink)
    private int deepWaterDriftLatch = 0;                    // ticks the deep-water drift sprint-brake stays latched after firing while grounded, so sprint stays OFF through the airborne sub-arcs of a step-down descent toward a deep pocket (otherwise sprint re-arms each airborne tick and the accumulated forward momentum still overshoots into the water)
    private int climbPressConsec = 0;                       // consecutive ticks the buoyant-climb-press raw condition has held (debounces the surface-bob false trigger)
    private int waterDriveRejectStreak = 0;                 // consecutive flip-rejections of the water drive heading (escape-hatch snaps after WATER_DRIVE_MAX_REJECT)
    private int descentDriveRejectStreak = 0;               // consecutive back-hop rejections on a dry diagDown slope (escape-hatch snaps to the real node after WATER_DRIVE_MAX_REJECT)
    private float lastCarrotBearing = Float.NaN;            // previous tick's raw carrot bearing — feeds the in-water yaw-thrash detector
    private int lastCarrotBearingSign = 0;                  // sign of the last meaningful carrot-bearing turn (for reversal detection)
    private int yawThrashTicks = 0;                         // decaying score: +4 per carrot-bearing reversal in water (cap 12), −1/tick → steady turn winds to 0, oscillation holds high
    private float lastAimYaw = Float.NaN;                   // previous tick's smoothed aim heading — feeds the anti-spin target-stability gate (a flipping target winds; a stable one converges)
    private int aimStableTicks = 0;                         // consecutive ticks the smoothed aim target barely moved; once past AIM_STABLE_TICKS the anti-spin freeze releases (a stable target can't wind the camera)
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
        this.climbGaveUpPos = null;
        this.climbGaveUpTtl = 0;
        this.drowningEscapeLatch = false;
        this.pillarNoPlaceTicks = 0;
        this.lastDigRiser = null;
        this.waterClimbDigRiser = null;
        this.waterClimbDigCommitTicks = 0;
        this.waterClimbDigFloatTicks = 0;
        this.futileBankDigCooldown = 0;
        this.diveLatch = 0;
        this.diveHold = 0;
        this.deepWaterDriftLatch = 0;
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
        this.boxedEscalateUntilTick = 0;
        BotConfig.pathfinderBoxedEscalate = false;          // never leak the steep-barrier escalation into the next goto
        this.bestStepDist = Double.POSITIVE_INFINITY;
        this.stuckStep = -1;
        this.noStepProgressTicks = 0;
        this.noProgressStep = -1;
        this.smoothTargetYaw = Float.NaN;
        this.lastCarrotBearing = Float.NaN;
        this.lastCarrotBearingSign = 0;
        this.yawThrashTicks = 0;
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
        // walkerPillarReachGoalNoSnap: don't snap an elevated AIR goal DOWN when it's reachable
        // by PILLARING up. canStandAt(t) here fails only because t's floor is air — but pillaring
        // creates that floor, so the goal IS reachable. Snapping it to the highest currently-
        // standable cell makes the bot stop 1+ blocks short (live 2026-06-27 summitArena: goal
        // y233 → snapped to standable y232, bot pillars 2 then arrives at y232, never pillars the
        // last block; "上坡跳不上高空目标"). Skip the snap so A* keeps the real goal and finds the
        // pillarUp path. Guard against a truly FLOATING void goal (no base to pillar from): require
        // the goal cell + head to be passable AND a solid base within a few blocks below.
        if (BotConfig.walkerPillarReachGoalNoSnap && BotConfig.allowPlace
                && !world.isSolid(t) && !world.isSolid(t.above())) {
            boolean baseBelow = false;
            for (int d = 1; d <= 5; d++)
                if (world.isSolid(t.offset(0, -d, 0))) { baseBelow = true; break; }
            if (baseBelow) return;   // pillar-reachable elevated goal — let A* pillar up to it
        }
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
        this.lastCarrotBearing = Float.NaN;
        this.lastCarrotBearingSign = 0;
        this.yawThrashTicks = 0;
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
        this.climbGaveUpPos = null;
        this.climbGaveUpTtl = 0;
        this.drowningEscapeLatch = false;
        this.pillarNoPlaceTicks = 0;
        this.lastDigRiser = null;
        this.waterClimbDigRiser = null;
        this.waterClimbDigCommitTicks = 0;
        this.waterClimbDigFloatTicks = 0;
        this.futileBankDigCooldown = 0;
        this.diveLatch = 0;
        this.diveHold = 0;
        this.deepWaterDriftLatch = 0;
        this.pillarRecoverLatch = 0;
        this.wantClimbRecent = 0;
        this.descending = false;
        this.boxedEscalateUntilTick = 0;
        BotConfig.pathfinderBoxedEscalate = false;          // never leak the steep-barrier escalation across a forced repath
    }

    /** Replay a fixed archived plan. Caller has already teleported the bot to the plan
     *  start and restored the block envelope. Disables A* entirely — the plan executes
     *  with real physics; a wedge is recorded, not re-planned. */
    public void beginReplay(WorldView world, List<BlockPos> plan, List<Move.Edge> planEdges, Goal endGoal, BlockPos startFoot) {
        setGoal(endGoal);
        this.replayMode = true;
        adoptPath(new PathFinder.Result(plan, planEdges, true, 0, 0L, 0.0), world, startFoot);
    }

    /**
     * TEST SEAM — adopt a hand-built plan with the LIVE recovery/repath machinery left ON
     * (unlike {@link #beginReplay}, which sets {@code replayMode} and disables A*), then
     * point {@code step} at an arbitrary mid-plan node. The harness uses this to force the
     * exact step-pointer-mismatch STATE the live wedge lands in — bot grounded N blocks off a
     * descend node — INDEPENDENT of how the bot drifted there live (which a momentum overshoot
     * on a synthetic 2-D arena could not reliably reproduce). Because {@code replayMode} stays
     * false, the real {@code fellOffPath}/{@code safetyRepath}/blacklist recovery runs, giving
     * the clean A/B the live wedge couldn't. Pass {@code foot==null} so {@link #adoptPath} does
     * NOT fast-forward/re-anchor the step it just set. Not wired to any transport; test-only.
     */
    public void beginScriptedFollow(WorldView world, List<BlockPos> plan, List<Move.Edge> planEdges, Goal endGoal, int startStep) {
        setGoal(endGoal);
        this.replayMode = false;
        adoptPath(new PathFinder.Result(plan, planEdges, false, 0, 0L, 0.0), world, null);
        this.step = Math.max(0, Math.min(startStep, (path == null ? 1 : path.size())));
        this.noProgressStep = -1;          // force a fresh noStepProgressTicks baseline at the injected step
        this.noStepProgressTicks = 0;
    }

    public int pathLen() { return path == null ? 0 : path.size(); }
    public int pathStep() { return step; }
    /** Node the step-pointer currently targets (null when no path / consumed). Test seam. */
    public BlockPos pathNode() { return (path != null && step >= 0 && step < path.size()) ? path.get(step) : null; }

    /** TEST SEAM — run {@link #adoptPath} with an explicit {@code foot} so a harness can exercise the
     *  segment anchor-gate (the {@code mis-anchored segment} reject vs the deep-water-float open-water
     *  bee-line exemption) deterministically, INDEPENDENT of the full continuation/repath machinery the
     *  headless server-sim doesn't reproduce for this live water dead-stop. @return adoptPath's verdict:
     *  true = ACCEPTED (segment adopted, the bot will drive it), false = REJECTED as mis-anchored. */
    public boolean adoptForTest(WorldView world, List<BlockPos> plan, List<Move.Edge> planEdges, BlockPos foot) {
        return adoptPath(new PathFinder.Result(plan, planEdges, false, 0, 0L, 0.0), world, foot);
    }

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

    /** The 4 horizontal unit offsets, scanned when locating a vine's backing wall. */
    private static final Direction[] HORIZ = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    /**
     * Yaw to face the SOLID wall a vine column hangs on, so a vine-climb press drives the
     * body INTO that wall and sustains horizontalCollision (the condition vanilla
     * {@code LivingEntity} requires to keep applying the +0.2 vine ascent — see
     * {@code vineClingFidelityProbe}). Returns {@code null} when no horizontal neighbour of
     * the climb column is solid (a free-standing/ladder-like climbable with no backing face),
     * so the caller falls back to the path-ahead bearing.
     *
     * A vine attaches to exactly one (or, in a corner, two) horizontal faces; that face's
     * neighbour cell is solid. We scan the foot cell's 4 horizontal neighbours and, to keep a
     * stable heading when a corner exposes two walls, prefer the candidate most aligned with
     * the path-ahead direction {@code (prefDx,prefDz)} (the bot's travel intent) before falling
     * back to the first solid neighbour found. Uses only {@link WorldView#isSolid}, so it stays
     * decoupled from Minecraft block-state types and works in the headless GameTest view.
     */
    private static Float vineWallYaw(WorldView world, BlockPos foot, double prefDx, double prefDz) {
        Direction best = null;
        double bestDot = -2.0;
        for (Direction d : HORIZ) {
            if (!world.isSolid(foot.relative(d))) continue;
            // Bias toward the wall the path wants us to face (corner disambiguation); a single
            // wall always wins regardless of prefDot since it is the only solid candidate.
            double dot = d.getStepX() * prefDx + d.getStepZ() * prefDz;
            if (best == null || dot > bestDot) { best = d; bestDot = dot; }
        }
        if (best == null) return null;
        // MC yaw: 0=+z(S), 90=-x(W), 180=-z(N), -90=+x(E); atan2(-dx,dz) faces offset (dx,dz).
        return (float) Math.toDegrees(Math.atan2(-best.getStepX(), best.getStepZ()));
    }

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

        // Steep-barrier planner escalation: a confirmed boxed churn (below) arms a sticky
        // timer; while it's live, route the planner's horizon/soft-commit/depth-penalty
        // reads through their escalated values so A* commits a climb-OVER route instead of
        // re-committing a cheap shallow/cave segment. Off (back to configured defaults)
        // once the timer lapses, so easy-terrain searches are never slowed.
        pfTickCounter++;
        boolean wasEscalating = BotConfig.pathfinderBoxedEscalate;
        boolean escalating = pfTickCounter < boxedEscalateUntilTick;
        BotConfig.pathfinderBoxedEscalate = escalating;
        if (escalating && !wasEscalating && BotConfig.walkerDebug)
            LOG.info("[walker] steep-barrier escalation ARMED (boxed churn) → horizon=0 depthPenalty>=25 softCommit>=35000 for {} ticks",
                    boxedEscalateUntilTick - pfTickCounter);

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
        // Search start for a bot floating AT the water surface. foot=floor(p.y) DIPS underwater on a
        // down-bob (p.y 61.64↔62.02 → foot.y 61↔62), so a repath fired mid-down-bob starts A* one
        // cell UNDER the surface; A* prefixes the plan with submerged nodes the buoyant bot can't
        // descend to, those cells sit behind/below the bot, the waypoint never leaves them and the
        // climb-out dig aims BACKWARD (live -733→-540: yaw-locked west digging a wall while the goal
        // is east) → permanent churn (replay: 20 repaths, identical y61-prefix path). Anchor ONLY the
        // search start to the surface cell so the plan extends FORWARD from where the body floats.
        // Global `foot` (actuators/sampling) is untouched. Gated to surface-floating (eye above water)
        // so deep underwater navigation is unaffected.
        BlockPos searchFoot = foot;
        if (BotConfig.walkerBuoyantSearchFromSurface
                && p.isInWater() && !p.onGround() && !p.isUnderWater()) {
            int sy = foot.getY();
            while (world.isWater(new BlockPos(foot.getX(), sy, foot.getZ()))) sy++;
            // sy = first non-water cell above the column; the top water cell (sy-1) is where the body
            // floats. Clamp >= foot.y so this only ever LIFTS the start, never sinks it.
            searchFoot = new BlockPos(foot.getX(), Math.max(foot.getY(), sy - 1), foot.getZ());
        }
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
        if (!goalSnapChecked && goal instanceof Goal.Block gb && world.isKnown(gb.target())) {
            // Gate on isKnown: at journey start a far goal sits in an UNLOADED chunk, where
            // canStandAt reads void (false) with nothing standable nearby — a one-shot check
            // there would no-op and, with the flag set, never retry once the chunk loads. So
            // defer the single snap until the goal cell's chunk is actually loaded (the bot
            // has come within render range), then evaluate it for real.
            goalSnapChecked = true;
            snapGoalToStandable(world, foot);
        }
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
        } else if (a.breakHeld() || waterClimbDigging) {
            // Actively mining a block — a planned break edge swinging (breakHeld) OR the
            // block-less climb-out dig from last tick (waterClimbDigging, still set; reset
            // below at line ~755). The bot IS progressing: slowly breaking a riser, not
            // translating, so goal-distance stays flat for the whole ~750-tick hand-mine of
            // a stone block. HOLD the no-progress give-up here, or it aborts a legitimate
            // slow climb-out mid-break (faithful-stone arena: committed 843 ticks to break
            // one stone, reached y207.85, then FAILED at tick 1221 = walkerTotalTickBudget).
            // True deadlocks are still caught by the per-riser dig-commit cap, the
            // breakTimeoutTicks wedge watchdog, and the fact that each completed break lifts
            // Y toward the goal → bestDistToGoal drops → totalTicks resets on the next step.
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
        // +2/+3 ASCENT-RAM SLIDE-BACK (steep diagonal-staircase recovery gap): on a steep
        // mountain staircase the bot drifts off a √2 diagUp and slides 2-3 blocks BELOW the
        // ascent node, so that node becomes an effective +2/+3 cardinal ram — too tall for a
        // single-jump stepUp (≤+1), yet UNDER the fellOffPath >3 fall threshold, so it gets
        // NEITHER the stepUp actuator NOR the fall recovery and bob-rams the riser ~16 s until
        // the slow wedge burst yanks it BACKWARD off the step (live 2026-06-24 west mountain:
        // move=diagUp, node -1987,111 from grounded foot y109, cur2=0.640 constant, hCol=true,
        // 332 ticks ≈ 16.6 s on ONE node; pillar-recover never armed because the only blocks
        // left were falling sand/gravel that holdPlaceable rejects). Detect the grounded,
        // persisted ram (node ≥2 above the feet, no step progress for ASCENT_SLIDE_RECOVER_TICKS) and
        // fold it into fellOffPath so the SAME proven recovery fires NOW: pillar back up with
        // blocks (fellBelowRoute), else a fresh foot-search that blacklists the rammed node
        // (line ~1069) and re-routes from the actual lower position — both replace the futile
        // bob with a fast reachable climb. No PLANNED move places a node ≥2 above a grounded
        // foot (steps/parkour/pillar all rise +1), so ≥2 reliably means a slide-back; pillarUp
        // and parkour edges keep their own dedicated handling.
        boolean ascentRamSlide = path != null && step < path.size() && p.onGround()
                && wedgeEdge != null && !"pillarUp".equals(wedgeEdge.move)
                && (wedgeEdge.move == null || !wedgeEdge.move.startsWith("parkour"))
                && path.get(step).getY() - foot.getY() >= 2
                && noStepProgressTicks > ASCENT_SLIDE_RECOVER_TICKS;
        // JITTER-IMMUNE +2/+3 ascent-ram recovery (the steep-climb-failure deep fix): ascentRamSlide above
        // hangs on noStepProgressTicks, which a slide-back's re-approach / vertical bob zeroes on every 3D
        // new-low — so on a WIDE steep ram the gate never fills and the bot bob-rams 150+ ticks before the
        // slow WEDGE_TICKS(100) burst (live J3b -877,75,241: diagUp node 3 above the grounded/airborne foot,
        // cur2 3.2-4.1, 反复横跳, 156 t / 7.8 s). Mirror crestOrbitTicks: a stepUp/diagUp edge (a PLANNED +1
        // the bot has slid 2-3 BELOW, so its node now sits >=2 above the foot) that has DWELT rawStepDwellTicks
        // past the bar — bob-immune, and NO onGround requirement so it ALSO catches the airborne-bob ram the
        // grounded ascentRamSlide misses — folds into fellOffPath so the fresh foot-search blacklists the
        // unreachable node and re-routes from HERE now. The move MUST be stepUp/diagUp: a broad "any node >=2
        // above" test MISFIRES on climbUp (a vine ascent legitimately spans 3-8 blocks in one edge — the
        // planner's intended route up, NOT a ram; live regression: a climbUp folded 85× → repath storm,
        // totStuck 908), and on pillarUp/swimUp/parkour multi-block climbs. DRY only (the water bank-climb-out
        // owns its own dig recovery). LATCHED to ONE fold per ram episode (ramRecoverLatchedStep): without it
        // the gate re-fires every tick from dwell 31 up until the step changes, folding into fellOffPath
        // 10-85× → a repath cascade that inflates churn even on a legit ram. A clean climb advances via
        // within/passed in 1-3 t (step changes, rawStepDwellTicks + the latch reset) and never trips it.
        boolean ascentRamSlideJitterImmune = BotConfig.walkerAscentRamJitterImmune
                && path != null && step < path.size() && !p.isInWater()
                && wedgeEdge != null && wedgeEdge.move != null
                && (wedgeEdge.move.equals("stepUp") || wedgeEdge.move.equals("diagUp"))
                && path.get(step).getY() - foot.getY() >= 2
                && rawStepDwellTicks > RAM_JITTER_RECOVER_TICKS
                && (ramRecoverLastFireDwell < 0 || rawStepDwellTicks - ramRecoverLastFireDwell >= RAM_RECOVER_DEBOUNCE);
        if (ascentRamSlideJitterImmune) {
            ramRecoverLastFireDwell = rawStepDwellTicks;   // next fold ≥RAM_RECOVER_DEBOUNCE later; the line-1640 step/path reset clears it
            if (BotConfig.walkerDebug)
                LOG.info("[walker] ascent-ram-jitter RECOVER step={}/{} node={} move={} nodeDy={} dwell={} noStep={}",
                        step, path.size(), path.get(step), wedgeEdge.move,
                        path.get(step).getY() - foot.getY(), rawStepDwellTicks, noStepProgressTicks);
        }
        // +1 DESCENT-RAM blind spot (mirror of ascentRamSlide above): the bot drifted 1 block ABOVE
        // the planned y-level onto a dead-end platform whose only forward exit is a step-down the
        // planner CORRECTLY rejected — StepDown's passthrough+1 / DiagonalDescend's to+2 head-clearance
        // bars the move when an overhang sits at the destination column's launch-head height, so A* never
        // routes THROUGH this cell; the bot reached it by execution drift. 1 below is UNDER the
        // fellOffPath >3 threshold AND not >=2 above (ascentRamSlide), so it lands in the mirror blind
        // spot and only the slow WEDGE_TICKS (100) burst rescues it (live 2026-06-24 V2 climb replay:
        // foot y85, node -771,84 one below, head-rams the -771,86 overhang, cur2=0.640 constant, ~66
        // ticks / 3.3 s on one node, ~50% of climb runs via replan). Detect grounded + node EXACTLY 1
        // below + horizontalCollision (the head/body ram) + persisted stall → fold into fellOffPath so
        // the fresh foot-search blacklists the unreachable node and re-routes from the actual position
        // NOW. The hCol + STEPUP_FREEZE_TICKS gate keeps it OFF a normal step-down (lands clean, no ram,
        // advances in 1-3 ticks) and off any non-ramming stall (the general wedge timer owns those).
        boolean descentRamStuck = path != null && step < path.size() && p.onGround()
                && wedgeEdge != null && !"pillarUp".equals(wedgeEdge.move)
                && (wedgeEdge.move == null || !wedgeEdge.move.startsWith("parkour"))
                && foot.getY() - path.get(step).getY() == 1
                && p.horizontalCollision
                && noStepProgressTicks > STEPUP_FREEZE_TICKS;
        // VERTICAL step-pointer re-sync dead-zone (the descent-OVERSHOOT blind spot; mirror also
        // covers an ascent SLIDE-BACK). The bot is GROUNDED but its step-pointer node sits ≥2
        // blocks off VERTICALLY (|foot.y − node.y| ≥ 2, either sign) while the foot is in the
        // horizontal step-advance dead-zone — cur2 ∈ (REACH_DIST_SQ, OVERSHOOT_RESYNC_SQ): too FAR
        // for `within` (cur2<0.45) yet too NEAR for the horizontal overshoot re-sync (cur2>4) — and
        // there is NO horizontalCollision (so it is NOT a ram: descentRamStuck/ascentRamSlide own
        // those). The classic case: the bot crosses a steep crest/shoulder with un-braked momentum,
        // free-falls 2 PAST a stepDown/fall node, and grounds on the terrace ~1.6 b shy of it — node
        // 2 BELOW, cur2≈2.5, hCol=false (live -1037, totStuck oscillating ~80 t until a WEDGE_TICKS
        // burst yanks it BACKWARD). Every step-advance gate then misses by a hair: `within` fails
        // (|Δy|=2>1.2); `passed`/`crossedDescendNode` are blocked by their |nx.y−p.y|<1.2 / forward-
        // projection reachability gates (the next node is also ~2 below); `fellOffPath`'s 3D-fall
        // test is one block short (|Δy|=2 ≤ maxJumpUp+2=3); ascentRamSlide needs ≥2 ABOVE and
        // descentRamStuck needs EXACTLY 1 below + hCol — a genuine hole. Fold the confirmed dead-zone
        // stall into fellOffPath so the SAME proven recovery (continuation-cancel → fresh foot-search
        // that blacklists the un-advanceable node → re-route from the actual position) fires NOW
        // instead of after the ~5 s burst. STRICT extension: the STEPUP_FREEZE_TICKS gate + the tight
        // cur2 band + the no-hCol + grounded conditions are all FALSE on a normal descent (lands clean
        // and advances via within/passed in 1-3 t) and on every other stall (the general wedge timer
        // owns those), so outside this exact state the bot behaves identically. pillarUp/parkour
        // incoming edges keep their own handling. (The ≥2-ABOVE branch is normally caught FIRST by
        // ascentRamSlide at its earlier tick-10 gate; this generic clause is the mirror that also
        // covers a no-hCol slide-back, and the disjoint descent-overshoot the family was missing.)
        boolean verticalResync = BotConfig.walkerVerticalResync           // flag FIRST → OFF is a true zero-cost no-op (nothing below runs)
                && path != null && step < path.size() && p.onGround() && !p.horizontalCollision
                && wedgeEdge != null && !"pillarUp".equals(wedgeEdge.move)
                && (wedgeEdge.move == null || !wedgeEdge.move.startsWith("parkour"))
                && Math.abs(foot.getY() - path.get(step).getY()) >= 2
                && noStepProgressTicks > STEPUP_FREEZE_TICKS
                && deadZoneCur2(path.get(step), p);   // horizontal cur2 ∈ (REACH_DIST_SQ, OVERSHOOT_RESYNC_SQ)
        // Phase-3 bob/jitter-immune ram wedge: the arc-length s has not advanced for ARC_WEDGE_TICKS while
        // horizontalCollision (the count lives in arcShadowTick). This is the STRUCTURAL replacement for the
        // descentRamStuck/ascentRamSlide family above — they hang on noStepProgressTicks, which the ram-jitter's
        // sub-block 3D-new-lows zero so the gate never fills (live -672,94: driveF=1, aim=4°, hCol, node 1 below,
        // descentRamStuck's exact case, yet it bobbed >34 t with no recovery). s is immune to that vertical noise,
        // so it folds into the SAME proven recovery (fresh foot-search blacklists the un-advanceable node + re-
        // routes from here) at ~1.5 s instead of the 5 s wedge burst. Default-OFF behind walkerArcLengthWedge.
        boolean arcWedge = BotConfig.walkerArcLengthWedge && arcWedgeTicks > ARC_WEDGE_TICKS
                && path != null && step < path.size();
        // Phase-3b: an OSCILLATING limit cycle (net arc-s ≈ 0 over a window) the per-tick ram wedge + anti-churn miss.
        boolean arcProgWedge = BotConfig.walkerArcProgressWedge && arcProgStall
                && path != null && step < path.size();
        if (arcProgWedge) arcProgStall = false;   // consume once so the recovery isn't re-fired before the next window
        boolean fellOffPath = arcWedge || arcProgWedge || ascentRamSlide || ascentRamSlideJitterImmune || descentRamStuck || verticalResync || (path != null && step < path.size()
                && Math.abs(path.get(step).getY() - foot.getY()) > world.maxJumpUpBlocks() + 2);
        if (arcWedge && BotConfig.walkerDebug)
            LOG.info("[walker] arc-wedge RECOVER step={}/{} node={} nodeDy={} wedgeT={} (bob-immune ram → fellOffPath)",
                    step, path.size(), path.get(step), path.get(step).getY() - foot.getY(), arcWedgeTicks);
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
        // Gate on holdPillarBlock (not world.canPlace) so a bot carrying ONLY sand/gravel can
        // still pillar back up: the recovery places on the SOLID rung directly below the grounded
        // foot (supported → a falling block won't drop), unlike a planned pillar over air/water
        // that world.canPlace() rightly rejects. Strict superset of the old gate (identical when
        // a non-falling support block is held).
        boolean fellBelowRoute = fellOffPath
                && path.get(step).getY() > foot.getY()
                && path.get(step).getY() - foot.getY() <= PILLAR_RECOVER_MAX_DY
                && pillarRecoverStallTicks <= PILLAR_NORISE_GIVEUP   // a no-rise pillar-trap (canopy/overhang) gives up → foot-search re-routes
                && BotConfig.allowPlace && a.holdPillarBlock();
        // DEEP-PIT ESCAPE (last resort, see DEEP_PIT_ESCAPE_TICKS): a sheer pit DEEPER than the
        // recover cap leaves fellBelowRoute false (gap > cap) so only the foot-search runs, and in
        // a 1-wide sheer pit it loops on the un-climbable rim route for 15-21 s. Once that loop is
        // CONFIRMED (no step progress past DEEP_PIT_ESCAPE_TICKS), drive the SAME pillar-recover
        // actuator up the open shaft; each grounded tick re-arms it until the gap closes to the cap
        // and fellBelowRoute carries it the rest. Hard-gated on the long stall + grounded +
        // node-above + blocks-in-hand → inert in all normal motion (a working bot never stalls this
        // long: any step advance resets noStepProgressTicks).
        boolean deepPitEscape = fellOffPath && p.onGround()
                && path.get(step).getY() > foot.getY()
                && path.get(step).getY() - foot.getY() > PILLAR_RECOVER_MAX_DY
                && noStepProgressTicks > DEEP_PIT_ESCAPE_TICKS
                && BotConfig.allowPlace && a.holdPillarBlock();
        if ((fellBelowRoute || deepPitEscape) && p.onGround()) {
            if (pillarRecoverLatch <= 0) { pillarRecoverPeakY = foot.getY(); pillarRecoverStallTicks = 0; }   // new recovery → fresh peak
            else if (foot.getY() > pillarRecoverPeakY) { pillarRecoverPeakY = foot.getY(); pillarRecoverStallTicks = 0; } // rose a rung → reset stall
            else pillarRecoverStallTicks++;                          // bobbing under a ceiling, no height gain
            pillarRecoverLatch = PILLAR_RECOVER_TICKS;
            pillarRecoverCell = foot;
        } else if (!fellOffPath) {
            pillarRecoverStallTicks = 0;                             // back on the route → clear the give-up for the next genuine recovery
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
        // Wall-corner ram signature: count consecutive sustained-hCol ticks (§39). A clean walk brushes
        // a wall for a tick or two; only a genuine wall-corner stall pins hCol true for seconds.
        if (p.horizontalCollision) hColRamTicks++; else hColRamTicks = 0;
        int effChurnWindow = BotConfig.walkerFasterChurnRepath ? 240 : CHURN_WINDOW;
        // A sustained ram shortens the net-displacement window so the existing blacklist+escalate
        // (below) fires in ~8s instead of 20s — but ONLY while genuinely wall-pinned, so legitimate
        // slow-but-moving terrain keeps the full window (no false-fire). Default OFF.
        if (BotConfig.walkerWallCornerFastChurn && hColRamTicks >= HCOL_RAM_TICKS)
            effChurnWindow = Math.min(effChurnWindow, WALL_CHURN_WINDOW);
        if (churnBase == null) { churnBase = foot; churnWindowTicks = 0; }
        else if (++churnWindowTicks >= effChurnWindow) {
            int cdx = foot.getX() - churnBase.getX(), cdz = foot.getZ() - churnBase.getZ();
            int cdy = foot.getY() - churnBase.getY();
            // Fire on a best-effort churn (existing cases — all net ≈0 Y, unchanged) OR on a
            // GOAL-REACHING limit-cycle that gained no altitude (steep-mountain base / cave),
            // never on a genuine upward climb (cdy > CHURN_MIN_Y is real vertical progress).
            // ...but NEVER while actively MINING (breakingEdge): a slow climb-out stone dig makes
            // zero XZ progress for ~750 ticks BY DESIGN, tripping this window — and the burst
            // below turns the camera (unstuckYaw) + shoves the bot OFF the riser, RESETTING the
            // vanilla break progress. That is the live climb-out's core failure the user watched:
            // "almost broke it, then suddenly gave up, turned the view, moved 2 steps, progress
            // reset, all wasted" — and the shove toward deep water is what then sank the bot. A
            // dig in progress IS progress; let it finish (the per-riser commit cap bounds a truly
            // stuck dig).
            if ((cdx * cdx + cdz * cdz) < CHURN_MIN_MOVE_SQ && (pathBestEffort || cdy <= CHURN_MIN_Y)
                    && !breakingEdge) {
                churnEscapes++;
                // Arm the sticky steep-barrier planner escalation (see top of tick()): the
                // planner suppresses its receding horizon and grinds deeper so it can find a
                // climb-OVER route instead of re-committing the cheap shallow/cave segment
                // this churn is stuck on. Sticky window tolerates a few net-progress blips.
                // Fires IN WATER too (2026-06-19): the same escalation that climbs a land
                // barrier also crosses a deepwater→elevated-bank pinch. Its raised depth
                // penalty DISCOURAGES diving (it prices descent), and its deeper / horizon-
                // suppressed search finds the surface route around/over the bank instead of
                // the cheap shallow segment a low budget commits — which IS the coastal
                // dive-churn. The earlier "pushes the bot to dive" worry was the planner
                // committing swimDown→swimTraverseBreak (dive + dig a coastal sandbar); that
                // dig is now gated to the water surface (SwimTraverseBreak), so the dive
                // route is gone. LIVE end-to-end: a ~9-deep bay before a y64-74 bank was
                // crossed AT THE SURFACE (no dive) → climbed the far bank → ARRIVED.
                boxedEscalateUntilTick = pfTickCounter + BOXED_ESCALATE_STICKY_TICKS;
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
            activeSearch = new PathFinder(world).newSearch(searchFoot, goal);
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
                maybeArmPinchEscalation(foot, res);
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
                    || (!tryLandBeeline(world, foot, goal)
                        && !tryQuickStart(world, foot, goal) && !tryWaterBeeline(world, foot, goal))) {
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
            rawStepDwellTicks = 0;
            ramRecoverLastFireDwell = -1;   // step/path changed → re-arm the ascent-ram-jitter recover
        } else {
            // Raw step dwell — this `else` is reached ONLY when the step is unchanged, so the counter
            // accumulates pure ticks-on-the-same-step and resets only via the step-change branch above,
            // NEVER on a 3D new-low. noStepProgressTicks (below) zeroes on every closest-approach
            // improvement, and a steep-ascent slide-back's re-approach / vertical bob manufactures those,
            // jitter-defeating the ram-recovery stall gates that hang on it (live J3b -877,75: diagUp node
            // 3 above the foot, 156 t / 7.8 s before recovery). This gives walkerAscentRamJitterImmune a
            // bob-immune wedge timer so it recovers on time.
            rawStepDwellTicks++;
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
            // A buoyant body bobs ±1.5 vertically; folding wdy² into the closest-approach
            // progress test makes wd2 oscillate even while the bot creeps FORWARD toward a
            // water goal, so noStepProgressTicks falsely climbs → wedged → safetyRepath churn:
            // every bob tick a cheap goal-reaching search resets path+step and the aim chases
            // the fresh node-1 (live J5 z1743: 3 repaths/s, yaw 96-131° while still closing on
            // the goal). In water, measure progress HORIZONTALLY only — the bob can't fake a
            // forward creep, while a truly stuck bot (circling, or pinned in the water below an
            // unmountable bank without gaining XZ) still fails to close XZ and trips the wedge
            // exactly as before (so deep-water climb-out detection is unchanged). Dry land keeps
            // the full 3D test (a stepUp/pillar that gains height IS progress there).
            // walkerDryWedgeFootY: at an ABOVE node on dry land, the continuous p.getY() vertical term lets the
            // jump/buoyant bob (p.y oscillates ~0.1 toward the node) manufacture a wd2 new-low every tick →
            // resets the wedge timer → a stalled +1 climb (stepUp ram / stairUpBreak whose break never completes)
            // never trips recovery and churns. Quantize the vertical term to foot.getY() so only a REAL climb
            // (foot rises a whole block) counts; an in-place bob does not. See BotConfig.walkerDryWedgeFootY.
            double wdyEff = (BotConfig.walkerDryWedgeFootY && !p.isInWater() && wn.getY() > foot.getY())
                    ? (wn.getY() - foot.getY())
                    : wdy;
            double wd2 = p.isInWater()
                    ? wdx * wdx + wdz * wdz
                    : wdx * wdx + wdyEff * wdyEff + wdz * wdz;
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

        // ===== Phase-0 SHADOW arc-length pursuit (walkerArcLengthShadow) — DRIVES NOTHING =====
        // Projects the continuous foot XZ onto the path polyline within a forward window and reports the
        // projected segment, cumulative arc-length s, horizontal perpendicular distance, and the tangent
        // heading at s+lookahead. The structural replacement (refactor plan §7) for the ~8 instantaneous
        // step-advance gates + the bob-immune dwell zoo, which exist ONLY because the buoyancy bob defeats
        // the per-tick cur2<0.45 / |dyNode|<1.2 gates. Logged only, so a replay can confirm s is monotonic
        // and the projected segment tracks the live `step` on clean runs BEFORE Phase 1 drives off it.
        if ((BotConfig.walkerArcLengthShadow || BotConfig.walkerArcLengthAdvance || BotConfig.walkerTangentAim
                || BotConfig.walkerArcLengthWedge || BotConfig.walkerArcProgressWedge || BotConfig.walkerFellBelowAlign)
                && path != null && step < path.size() && foot != null) {
            arcShadowTick(world, foot, p.getX(), p.getZ(), p.getYRot(), p.isInWater(), p.horizontalCollision);
        } else {
            arcWedgeTicks = 0;   // no projection this tick → don't carry a stale ram count into the next path
            arcProgBaseS = Double.NaN; arcProgWindowTicks = 0; arcProgStall = false;   // reset the net-progress window too
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
            // the node so flatWaterWalk swims it on to the next node. Ride out the
            // isInWater bob-blink with surfaceWaterLatch: at a water EDGE p.isInWater()
            // flickers false at the bob apex, and without the latch the exemption drops
            // out mid-bob and re-pins the step on the parkour node — the overshoot-resync
            // below never runs, so the bot drives BACKWARD to the stale node instead of
            // advancing to the climb-out it already overshot into (live 2026-06-24 -582
            // pool: parkour landed at -581,62 past node -582,62,536, ~15 s of backward bob).
            if (se != null && se.move != null && se.move.startsWith("parkourDescend")
                    && !p.onGround() && !p.isInWater() && surfaceWaterLatch <= 0) break;
            // An ASCENDING parkour leap (parkourAscend / a rising parkour2d-3d that lands
            // higher than the launch) is the ONE jumped move WITHOUT a "don't advance until
            // executed" gate — pillarUp (above), parkourPlace and parkourDescend all have one,
            // but a rising parkour does not, so the within/passed re-sync CONSUMES the leap node
            // before the bot ever reaches the launch + jumps. The live -870 water climb-out
            // (2026-06-25 journey, 270-tick stall): the plan was
            //   …4 walk -870,62,379 · 5 stepUp -870,63,380 · 6 parkourAscend2 -872,64,380 · 7 walk -872,64,379
            // and at tick 1 `step` advanced 5→6→7 in ONE pass — node 5 `within` (foot at the
            // bank corner), node 6 `passed` (node 7 horizontally closer + both |Δy|<1.2 gates met)
            // — so the +2 climb-out leap was skipped and the bot was left pointed at node 7, a flat
            // `walk` node sitting +2 ABOVE the water. It then sank into the y62 pocket and bob-stalled
            // for ~14 s (a buoyant body can't WALK up a +2 bank; the toolless underwater bank-dig is
            // ~25× slow) until a repath happened to find a gentler exit. HOLD the step on the rising
            // parkour node until the feet have actually RISEN to it (grounded at its Y, ±0.5) so the
            // parkour actuator (sprint+jump, aimed at the destination — line ~3946) owns the launch and
            // a too-far approach can't skip straight onto the unreachable landing. Mirrors the pillarUp
            // height-gate exactly. NOT water-exempt (unlike parkourDescend): the failure here ENDS in
            // water, so the hold must persist there to keep the bot anchored at the climb-out node — if
            // the leap still can't complete, noStepProgressTicks climbs and the existing fellOffPath /
            // wedge-repath re-routes from the real position (the deterministic escape the original took,
            // just sooner and from the correct node). Strict extension: a rising parkour mid-leap is
            // ALREADY below its landing and not grounded, so this is inert on a clean leap (it was going
            // to hold via the airborne state anyway); only the premature drift-skip is suppressed.
            if (BotConfig.walkerParkourAscendHold
                    && se != null && se.move != null && se.move.startsWith("parkour")
                    && !se.move.startsWith("parkourDescend") && !se.move.startsWith("parkourPlace")
                    && path.get(step).getY() > foot.getY()
                    && !(p.onGround() && p.getY() >= path.get(step).getY() - 0.5)) break;
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
            // Float over a NON-dive submerged node at ANY depth: A* routes a wide deep-water
            // crossing along the riverbed (live 2026-06-23: walk/parkour nodes placed at y58, FOUR
            // below the floating foot in 9-deep water), and the surface swimmer must cross
            // horizontally ABOVE them — never follow them down. The old -2.5 floor let the bot dive
            // to and bob at deep crossing nodes (totStuck 100+, the "潜底/挖墙" stall). A deliberate
            // swimDown dive (diveEdge) keeps the tight -2.5 + give-up so a real descent isn't skipped.
            double floatOverFloor = diveEdge ? -2.5 : -FLOATOVER_NONDIVE_MAX_DROP;
            boolean floatOverSubmerged = p.isInWater() && dyNode < -0.5 && dyNode > floatOverFloor
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
            // DESCENT OVERSHOOT-ADVANCE (the 原地后跳 back-hop fix the in-place-hop comment
            // points to): on a dry descent step the body drives the IMMEDIATE node
            // (driveTargetYaw = descentNodeYaw). The instant the foot crosses PAST that node
            // toward the next one, the node sits behind the body and the decoupled drive
            // reverses — the ~0.1-0.25/tick backward hop seen on every slope / stepDown
            // (descentYawArena backSteps=42, worstBack=-0.25). `passed` only advances at the
            // w→nx MIDPOINT (nd2<cur2), leaving a 2-3 tick window where the drive rides the
            // overshot node and hops back. Advance one tick earlier — the moment the foot is on
            // the FORWARD side of node w along the w→nx segment (projection of w→foot onto w→nx
            // positive) — so the drive never rides a node behind it. Dry + descend-edge gated
            // (water/climb keep their own gates); the |Δy|<1.2 bar keeps it off an impossible
            // climb, and the forward-projection test means a switchback leg (foot past w but NOT
            // toward nx) never trips it.
            // DISCRETE descents only (fall off a lip / a single stepDown): a continuous diagDown
            // SLOPE legitimately rides the immediate node for trend-camera smoothing, and advancing
            // eagerly there over-leans the descent into MORE back-correction (descentYawArena
            // backSteps 42→71). A discrete drop has one clean overshoot to consume.
            boolean discreteDescend = se != null && se.move != null
                    && (se.move.startsWith("fall") || se.move.equals("stepDown"));
            boolean crossedDescendNode = false;
            if (!within && !passed && discreteDescend && !p.isInWater() && step + 1 < path.size()) {
                BlockPos nxd = path.get(step + 1);
                double segx = nxd.getX() - w.getX(), segz = nxd.getZ() - w.getZ();
                double offx = p.getX() - (w.getX() + 0.5), offz = p.getZ() - (w.getZ() + 0.5);
                crossedDescendNode = (offx * segx + offz * segz) > 0
                        && Math.abs(nxd.getY() - p.getY()) < 1.2;
            }
            // WALK overshoot-advance — mirror of crossedDescendNode for a FLAT walk node the bot has
            // crossed and is now ORBITING in the step-advance dead-zone (cur2 ∈ [REACH_DIST_SQ,
            // OVERSHOOT_RESYNC_SQ]: too far for `within`, too near for the overshoot re-sync, and
            // circling so the next node never reads STRICTLY closer → `passed` never fires). Live V2
            // shore node -783,64,609: orbited z609±1 for 111 ticks ~5.5 s until the wedge-repath
            // rescued it. Gated on a CONFIRMED stuck (noStepProgressTicks) so a normally-approaching
            // walk advances via within/passed FIRST — this ONLY nudges an already-wedged orbit, never
            // cuts a live corner. walk-incoming-edge only (descents own crossedDescendNode and slopes
            // are deliberately excluded there; climbs/water keep their bases); |Δy|<1.2 bars a climb.
            boolean crossedWalkNode = false;
            if (!within && !passed && !crossedDescendNode
                    && se != null && se.move != null
                    && (se.move.equals("walk")
                        || (BotConfig.walkerTraverseBreakOvershootResync && se.move.equals("traverseBreak")))
                    && !p.isInWater() && noStepProgressTicks > WALK_OVERSHOOT_STUCK_TICKS
                    && step + 1 < path.size()) {
                BlockPos nxw = path.get(step + 1);
                double segx = nxw.getX() - w.getX(), segz = nxw.getZ() - w.getZ();
                double offx = p.getX() - (w.getX() + 0.5), offz = p.getZ() - (w.getZ() + 0.5);
                crossedWalkNode = (offx * segx + offz * segz) > 0
                        && Math.abs(nxw.getY() - p.getY()) < 1.2;
            }
            // WATER-SURFACE STEP-DOWN float-and-advance: a stepDown (or short discrete descent) whose
            // node is a SHALLOW water-surface foothold (water at the node, SOLID floor one below, head
            // not water) lands a buoyant body that grounds vertically AT the node (|dyNode|≈0) but pins
            // ~0.74 b short of centre (cur2≈0.55) — just over the tight REACH_DIST_SQ=0.45 — because
            // buoyancy + the water-climb jump keep lifting/ramming the foot and the prone swim can't
            // nudge the last 0.1 b in. `within` (cur2<0.45) never fires and the step freezes (live #47
            // -809,62,350: 12 s+ bob-ram). `floatOverSubmerged` misses it (node AT the foot, not below).
            // Treat arrival at the surface cell as reaching the node and advance at a RELAXED reach,
            // mirroring floatOverSubmerged/deepWaterRise — gated, like crossedWalkNode, on a CONFIRMED
            // stall so a clean approach advances via within/passed first and this only rescues the pin.
            // Strictly scoped: stepDown/fall/diagDown incoming edge + shallow water-surface node; a dry
            // step-down, a deep floating-water landing, a submerged node, and climb/walk/parkour edges
            // are all byte-identical inert (the gate never matches them).
            boolean waterStepDownFloat = false;
            if (BotConfig.walkerWaterStepDownFloat && !within && !passed
                    && !crossedDescendNode && !crossedWalkNode
                    && se != null && se.move != null
                    && (se.move.equals("stepDown") || se.move.startsWith("fall") || se.move.startsWith("diagDown"))
                    && (p.isInWater() || surfaceWaterLatch > 0)
                    && noStepProgressTicks > WATER_STEPDOWN_STALL_TICKS
                    && cur2 < WATER_STEPDOWN_REACH_SQ
                    && Math.abs(dyNode) < 1.2
                    && world.isWater(w) && world.isSolid(w.below()) && !world.isWater(w.above())) {
                waterStepDownFloat = true;
                if (BotConfig.walkerDebug)
                    LOG.info("[walker] water-stepdown-float ADVANCE step={}/{} node={} move={} cur2={} dyNode={} stall={}",
                            step, path.size(), w, se.move, String.format(Locale.ROOT, "%.3f", cur2),
                            String.format(Locale.ROOT, "%.2f", dyNode), noStepProgressTicks);
            }
            // STEPUP-CREST float-and-advance: a +1 stepUp/diagUp CREST node (a diagonal-staircase plateau
            // lip) that the buoyancy-free body has TOPPED OUT on but ORBITS — it reaches the node's Y at
            // the apex bob (|dyNode|≈0) yet the small lateral orbit (±0.5 b) keeps cur2 pinned at
            // ~0.49-1.2, just over the tight REACH_DIST_SQ=0.45, so `within` never fires, and while
            // circling the next node never reads STRICTLY closer so `passed` never fires either — the
            // step freezes ~25-51 ticks (live 2026-06-26 -633,80,318 diagonal-staircase crest: move=stepUp,
            // cur2 floor 0.492, py 79.0↔80.25 across node y80, pz 317.7↔318.7 orbit, onGround flickering,
            // ~1.25 s pin; worst documented 2.55 s). NO actuator catches it: ascentRamSlide needs the node
            // ≥2 ABOVE a GROUNDED foot (here +1 above a bobbing/airborne foot); descentRamStuck needs the
            // node 1 BELOW + hCol; stepUpFreeze needs a grounded riser-ram (the bot is airborne-orbiting,
            // no hCol). Mirror waterStepDownFloat/crossedWalkNode: once the bot has reached the crest node's
            // Y and STALLED there orbiting, treat the apex arrival as reaching the node and ADVANCE at a
            // RELAXED reach (STEPUP_CREST_REACH_SQ, well under OVERSHOOT_RESYNC_SQ=4 so it can't cut a live
            // corner). STRICTLY scoped: a stepUp/diagUp incoming edge + the foot ALREADY risen to the node
            // (|dyNode|<0.5 — a node still being climbed from a full block below has |dyNode|≥0.5, so it is
            // never skipped) + a CONFIRMED stall (a clean stepUp advances via within/passed in <12 t,
            // resetting noStepProgressTicks, and never trips the 16-tick gate) + DRY land (water ascents
            // are owned by floatOverSubmerged / the in-water climb gate). A non-ascent edge, a node not yet
            // reached vertically, a fast clean climb, and any in-water case are all byte-identical inert.
            // BOB-IMMUNE crest-orbit dwell: noStepProgressTicks (the stall gate below) resets on every new
            // 3D-low (Walker:1669), and a DRY topped-out crest's vertical bob folds wdy into wd2 and
            // manufactures a new low each bob cycle, so on a WIDE bob orbit the gate is never reached and the
            // crest never advances (live -677,80: cur2 0.5→8.6, ADVANCE never fired, 120-173 t orbit). Count
            // raw ticks the body dwells on the SAME dry stepUp/diagUp crest step without within/passed
            // advancing, reset ONLY on a step-advance/path-change — the bob can't zero it, so a genuine orbit
            // accumulates past the gate. The crest-reach's |dyNode|<0.5 topped-out guard still picks the firing
            // tick, so this never advances a node the foot hasn't risen to (no skip-node strand).
            boolean onAscentCrest = se != null && se.move != null
                    && (se.move.equals("stepUp") || se.move.equals("diagUp")) && !p.isInWater();
            if (step != crestOrbitStep) {
                crestOrbitStep = step;
                crestOrbitTicks = 0;
            } else if (onAscentCrest && !within && !passed) {
                crestOrbitTicks++;
            }
            boolean stepUpCrestReach = false;
            if (BotConfig.walkerStepUpCrestReach && !within && !passed
                    && !crossedDescendNode && !crossedWalkNode && !waterStepDownFloat
                    && se != null && se.move != null
                    && (se.move.equals("stepUp") || se.move.equals("diagUp"))
                    && !p.isInWater()
                    && crestOrbitTicks > STEPUP_CREST_STALL_TICKS
                    && cur2 < STEPUP_CREST_REACH_SQ
                    && Math.abs(dyNode) < 0.5) {
                stepUpCrestReach = true;
                if (BotConfig.walkerDebug)
                    LOG.info("[walker] stepup-crest-reach ADVANCE step={}/{} node={} move={} cur2={} dyNode={} crestStall={} noStep={}",
                            step, path.size(), w, se.move, String.format(Locale.ROOT, "%.3f", cur2),
                            String.format(Locale.ROOT, "%.2f", dyNode), crestOrbitTicks, noStepProgressTicks);
            }
            // WATER-SURFACE WALK relaxed-advance (the turn / wall-corner FREEZE breaker): a flat `walk`
            // water-surface node the buoyant body sits ~0.67 b out from (cur2 floor ~0.455, just over
            // REACH_DIST_SQ=0.45) so `within` never closes. On a straight crossing forward momentum fires
            // `passed`, but at a TURN / terminal / WALL-CORNER node the bot isn't crossing toward the next node
            // so `passed` can't fire either — the flat water walk node then has NO relaxed-advance and the bot
            // orbits / freezes against the corner (live deep-water bay corner: cur2 1.142 FROZEN 321 ticks,
            // within=0, aim swinging 403° — the "贴墙/水里卡住" jank; attack=0, NOT digging). Mirror
            // waterStepDownFloat / stepUpCrestReach: on a CONFIRMED in-water stall at a flat walk water node
            // within a relaxed reach, advance so the segment continues / repaths from here. In water
            // noStepProgressTicks is HORIZONTAL-only (Walker:~1666) so the bob can't fake-reset it — only a
            // genuine non-closing freeze accumulates it past the gate; a clean crossing advances via `passed`
            // in 1-2 ticks (noStepProgress stays low) and never trips it. The |Δy|<1.2 gate on the next node
            // bars an impossible climb-skip; dry walk and every non-walk edge are byte-identical inert.
            boolean waterWalkReach = false;
            if (BotConfig.walkerWaterWalkReach && !within && !passed
                    && !crossedDescendNode && !crossedWalkNode && !waterStepDownFloat && !stepUpCrestReach
                    && se != null && se.move != null && se.move.equals("walk")
                    && p.isInWater()
                    && noStepProgressTicks > WATER_WALK_STALL_TICKS
                    && cur2 < WATER_WALK_REACH_SQ
                    && step + 1 < path.size()
                    && Math.abs(path.get(step + 1).getY() - p.getY()) < 1.2) {
                waterWalkReach = true;
                if (BotConfig.walkerDebug)
                    LOG.info("[walker] water-walk-reach ADVANCE step={}/{} node={} cur2={} stall={}",
                            step, path.size(), w, String.format(Locale.ROOT, "%.3f", cur2), noStepProgressTicks);
            }
            // [STEP-ADV-DIAG temp — remove before commit] why a grounded grossly-overshot node won't
            // advance (-823 dimple churn): logs which advance fired + the descend-geometry sub-conditions.
            if (BotConfig.walkerDebug && !within && p.onGround() && cur2 > OVERSHOOT_RESYNC_SQ
                    && noStepProgressTicks > 6 && step + 1 < path.size()) {
                BlockPos dN = path.get(step + 1);
                double dsegx = dN.getX() - w.getX(), dsegz = dN.getZ() - w.getZ();
                double doffx = p.getX() - (w.getX() + 0.5), doffz = p.getZ() - (w.getZ() + 0.5);
                LOG.info("[walker] STEP-ADV-DIAG step={}/{} w={} cur2={} footNodeDy={} se={} disc={} | passed={} crossDesc={} crossWalk={} | nx={} nxY={} pY={} fwdDot={} nxYgate={}",
                        step, path.size(), w, String.format("%.2f", cur2),
                        foot.getY() - w.getY(), (se != null && se.move != null ? se.move : "null"),
                        (se != null && se.move != null && (se.move.startsWith("fall") || se.move.equals("stepDown"))),
                        passed, crossedDescendNode, crossedWalkNode,
                        dN, dN.getY(), String.format("%.2f", p.getY()),
                        String.format("%.2f", doffx * dsegx + doffz * dsegz),
                        Math.abs(dN.getY() - p.getY()) < 1.2);
            }
            // Phase-1 (walkerArcLengthAdvance): the bob-immune path projection ADDS a step-advance the legacy
            // gates miss — advance when the foot's forward projection has reached a later segment
            // (arcProj.segIdx > step). It is a SUPPLEMENT, not a replacement: the seven legacy gates
            // (passed/tailConsumed/crossedDescend/crossedWalk/waterStepDownFloat/stepUpCrestReach/waterWalkReach)
            // still fire because they advance in NEAR-reach cases the projection is too strict for — e.g. the bot
            // orbiting just below a crest stepUp (cur2~0.55, the foot hasn't projected past the node so segIdx is
            // pinned, but stepUpCrestReach legitimately advances it over the crest; stepUpCrestOrbitArena /
            // waterStepDownFloatArena assert exactly this). The union (legacy || projection) is strictly more
            // advancing than either alone, so it both kills the bob-defeated step-freeze (projection drives) AND
            // keeps the reach helpers (legacy drives). Edge-execution holds above still gate advancement.
            boolean legacyAdvance = within || passed || tailConsumed || crossedDescendNode || crossedWalkNode
                    || waterStepDownFloat || stepUpCrestReach || waterWalkReach;
            boolean doAdvance = legacyAdvance
                    || (BotConfig.walkerArcLengthAdvance && arcProj.segIdx > step);
            if (doAdvance) {
                // Don't CONSUME the final node of a disk goal while it sits inside the goal
                // radius but the bot's FOOT cell is still one block short of it. The node-reach
                // gate (~0.67 blk) fires ~1 block out, so consuming here ends the path with
                // goal.reached(foot) still FALSE: the segment-end handler hits frontierHoldOrArrive,
                // which STOPS the drive and declares ARRIVED a block short — the bot freezes at
                // the radius edge and only autoSwim drift carries it in (the near-goal open-water
                // "stall"; live 2026-06-23: XZ -1700,900 r6 pinned the bot at foot -1693, dx=7,
                // OUTSIDE r6, then fake-ARRIVED). Hold on the last node so the normal pure-pursuit
                // walks the foot ONTO it and the goal.reached check at the top of step() fires for
                // real. Scoped to radius>0 disk goals (XZ/Near) so an exact-cell goal's
                // long-standing within-arrival — and a floating bot's radius-0 water arrival —
                // are unchanged.
                boolean diskGoal = (goal instanceof Goal.XZ xz && xz.radius() > 0)
                        || (goal instanceof Goal.Near nr && nr.radius() > 0);
                if (step + 1 >= path.size() && diskGoal
                        && !goal.reached(foot) && goal.reached(path.get(path.size() - 1)))
                    break;
                step++;
            }
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
                        || (!tryLandBeeline(world, foot, goal)
                            && !tryQuickStart(world, foot, goal) && !tryWaterBeeline(world, foot, goal))) {
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
            // bearing TO the node (MC yaw: 0=+z south, atan2(-dx,dz)); yawErr = how far the bot's body
            // faces OFF that bearing. With hCol this separates "rammed a wall, facing right" (贴墙卡住) from
            // "facing the wrong way, not driving toward the node" (aim/drive bug) — the missing axis that
            // forced guessing on every "won't close" stall.
            double bearing = Math.toDegrees(Math.atan2(-ddx, ddz));
            double yawErr = angleDiff(p.getYRot(), (float) bearing);
            LOG.info("[walker] t={} step={}/{} move={} node={},{},{} p=({},{},{}) pitch={} yaw={} bear={} yawErr={} hCol={} lastAim={} cur2={} (gate {}) |dY|={} (gate 1.2) within={} onG={} inW={} undW={} stuck={} totStuck={} pend={} break0={}{}",
                    dbgTicksOnStep, step, path.size(), edge != null ? edge.move : "-",
                    nd.getX(), nd.getY(), nd.getZ(),
                    String.format(Locale.ROOT, "%.2f", p.getX()), String.format(Locale.ROOT, "%.2f", p.getY()), String.format(Locale.ROOT, "%.2f", p.getZ()),
                    String.format(Locale.ROOT, "%.0f", p.getXRot()),
                    String.format(Locale.ROOT, "%.0f", p.getYRot()),
                    String.format(Locale.ROOT, "%.0f", bearing),
                    String.format(Locale.ROOT, "%.0f", yawErr),
                    p.horizontalCollision,
                    String.format(Locale.ROOT, "%.0f", lastAimYaw),
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
            // DROWNING-ESCAPE reflex (walkerDrowningEscape, default OFF): a submerged climb-out
            // deadlock (e.g. the pillar↔repath loop below) can pin the bot under a bank lip until
            // its air runs out — Peaceful does not prevent drowning (live 2026-06-29: died at
            // (-21,60,-42) while climbout-place spun 26 engage/bail cycles). Air below ~3s while
            // underwater → LATCH a surface-for-air override that preempts every climb/dig/pillar
            // actuator this tick: hold the swim-up jump, and if a solid lip caps the head (or a
            // wall blocks the rise) drive BACKWARD off the bank so buoyancy finds open surface.
            // Released once air recovers (or out of water); the interrupted climb resumes fresh.
            if (BotConfig.walkerDrowningEscape) {
                // WorldView truth gate: the client's isUnderWater/air flags survive a teleport
                // (and a corpse) un-ticked — a replay tp'd the bot onto DRY land with stale
                // undW=true/air=0 and the reflex latched + froze the whole drive. Engage (and
                // hold) only while the WORLD actually has water at the foot/eye and the bot is
                // alive; a stale-flag body falls through to the normal drive.
                boolean reallyInWater = p.getHealth() > 0
                        && (world.isWater(foot) || world.isWater(foot.above()));
                if (reallyInWater && p.isUnderWater() && p.getAirSupply() <= 60) {
                    if (!drowningEscapeLatch && BotConfig.walkerDebug)
                        LOG.info("[walker] DROWNING-ESCAPE engaged: air={} foot={},{},{} → surface for air",
                                p.getAirSupply(), foot.getX(), foot.getY(), foot.getZ());
                    drowningEscapeLatch = true;
                } else if (!reallyInWater || !p.isInWater() || p.getAirSupply() >= 240) {
                    if (drowningEscapeLatch && BotConfig.walkerDebug)
                        LOG.info("[walker] DROWNING-ESCAPE released: air={} → resume", p.getAirSupply());
                    drowningEscapeLatch = false;
                }
                if (drowningEscapeLatch && reallyInWater && p.isInWater()) {
                    agentJump(a, true);
                    a.breakHold(false);
                    p.setSprinting(false);
                    boolean riseBlocked = world.isSolid(foot.offset(0, 2, 0)) || p.horizontalCollision;
                    if (riseBlocked) {
                        // Back straight off the lip/wall: reverse the current body yaw and swim
                        // away — one or two cells of open water is all the buoyant rise needs.
                        float backYaw = p.getYRot() + 180f;
                        p.setYRot(backYaw); p.yHeadRot = backYaw; p.yBodyRot = backYaw;
                        p.setXRot(0f);
                        agentForward(a, true);
                    } else {
                        agentForward(a, false);
                    }
                    return Step.WALKING;
                }
            }
            // walkerClimbGaveUpSticky: run down the sticky gave-up TTL; expire the anchor once
            // the foot leaves the futile bank (>3 blocks) or the TTL runs out.
            if (climbGaveUpTtl > 0) {
                climbGaveUpTtl--;
                if (climbGaveUpTtl == 0 || climbGaveUpPos == null
                        || Math.abs(foot.getX() - climbGaveUpPos.getX()) > 3
                        || Math.abs(foot.getZ() - climbGaveUpPos.getZ()) > 3) {
                    climbGaveUpTtl = 0;
                    climbGaveUpPos = null;
                }
            }
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
            // wantClimbNow normally needs the waypoint ABOVE the foot. But a FLOATING bot ramming a
            // water-bank LIP (walkerFloatingBankBobFreeze, live #47 repro -638,418→-652): the next wp can
            // be at the SAME Y across a 1-block lip, so cwp.y>foot.y is false and waterClimbing never arms
            // — the bot just jumps+rams the lip (hCol, hSpd~0) for 20+ s with no bank-dig/pillar recovery.
            // Treat "afloat + horizontally colliding while touching water" as wanting to climb so the
            // existing climb-out recovery (bank-dig / foothold-pillar) engages over the lip.
            boolean floatingBankRam = BotConfig.walkerFloatingBankBobFreeze
                    && !p.onGround() && p.horizontalCollision
                    && (world.isWater(foot) || world.isWater(foot.below()));
            boolean wantClimbNow = edge != null && (cwp.getY() > foot.getY() || floatingBankRam);
            boolean touchingWater = p.isInWater() || world.isWater(foot) || world.isWater(foot.below());
            if (touchingWater) waterTouchRecent = WATER_TOUCH_STICKY;
            else if (waterTouchRecent > 0) waterTouchRecent--;
            boolean nearWater = touchingWater || waterTouchRecent > 0;
            if (wantClimbNow) wantClimbRecent = WANT_CLIMB_STICKY;
            else if (wantClimbRecent > 0) wantClimbRecent--;
            boolean wantClimb = wantClimbNow || wantClimbRecent > 0;
            // Durable dig-commit: while we're still mid-breaking a LATCHED riser that is
            // solid and right beside the bot, STAY committed even if A* transiently repaths
            // the climb away (wantClimb flicker). A buoyant bot mines a STONE bank by hand at
            // ~750 ticks/block (×5 not-on-ground); the old code dropped the half-broken riser
            // the instant wantClimb fell for WANT_CLIMB_STICKY ticks, so the bot abandoned
            // each block partway, wandered 10+ columns, and drifted into a deep hole and SANK
            // (live 2026-06-20: one stone block dug 517× then dropped; y62→y27). The commit is
            // capped per-riser (WATER_CLIMB_DIG_COMMIT_CAP, reset on each fresh riser) so a
            // genuinely stuck dig still releases to repath. Held only while afloat and still
            // beside the riser — grounding out (climbed) or drifting >2 off it ends it.
            boolean digCommitted = waterClimbDigRiser != null
                    && world.isSolid(waterClimbDigRiser) && !p.onGround()
                    && Math.abs(foot.getX() - waterClimbDigRiser.getX()) <= 2
                    && Math.abs(foot.getZ() - waterClimbDigRiser.getZ()) <= 2
                    && waterClimbDigCommitTicks < WATER_CLIMB_DIG_COMMIT_CAP;
            boolean waterClimbing = (wantClimb && nearWater && !p.onGround()) || digCommitted;
            // Floating over DEEP water (water directly below the foot) with the dig
            // available: a buoyant bot can't swim-jump a +1 bank AND can't clear a surface
            // fill cell to pillar, so the pillar is ALWAYS futile here — pure wasted bob.
            // Skip it and engage the fast dig directly (the live journey's banks are all
            // this case, and it was eating ~50 ticks of futile pillaring per bank before
            // climbPillarGaveUp fell through to the dig). When break is OFF (place-only
            // arena) this is false → the pillar is kept as the only exit.
            // ...and more broadly for ANY buoyant float: the pillar can't clear the +0.9 fill
            // regardless of whether the cell DIRECTLY below is water — the bot may bob over a
            // solid-floored shallow shelf beside the bank yet still be too buoyant to stand a
            // rung, so the old isWater(foot.below()) test missed it and let the futile pillar
            // re-engage (live 2026-06-24 -67x pit: 264 climbout-place ticks, foot.below() solid,
            // pillar↔dig↔repath thrash ~105 s). Ride the isInWater bob-blink with the latch.
            boolean buoyantFloat = !p.onGround() && (p.isInWater() || surfaceWaterLatch > 0);
            boolean deepDig = (world.isWater(foot.below()) || buoyantFloat)
                    && BotConfig.allowBreak && BotConfig.allowSwimEscapeBreak;
            if ((!wantClimb || !nearWater) && !digCommitted) {
                // Left the climb context (grounded on the bank, or A* now routes
                // down/along) → clear the per-attempt accounting AND the "pillar
                // gave up" latch, so the NEXT genuine climb-out starts fresh.
                // walkerClimbGaveUpSticky: EXCEPT while the sticky anchor is live — a repath
                // that swaps the climb node resets this context every ~2.5s, and clearing the
                // latch here is what let the proven-futile pillar re-engage 26× until the bot
                // drowned (live 2026-06-29). While the foot is still at the futile bank, keep it.
                waterClimbStall = 0;
                if (!(BotConfig.walkerClimbGaveUpSticky && climbGaveUpTtl > 0)) climbPillarGaveUp = false;
                pillarNoPlaceTicks = 0;
                lastDigRiser = null;
                waterClimbDigRiser = null;
                waterClimbDigCommitTicks = 0;
                waterClimbDigFloatTicks = 0;
            } else {
                waterClimbStall++;
                if (digCommitted) waterClimbDigCommitTicks++;
                // Track how long this dig has run while the bot stayed AFLOAT. Any ground
                // contact resets it: a LEGIT climb-out bob-jumps onto the freed +1 notch and
                // grounds, so it never accumulates; only a perpetual float on an unreachable
                // riser does (live -784: onGround=false for all ~1000 stall ticks).
                if (digCommitted) {
                    if (p.onGround()) waterClimbDigFloatTicks = 0;
                    else waterClimbDigFloatTicks++;
                }
                // FUTILE-OVERHANG early-release (BotConfig.walkerFutileBankDigRelease, default
                // OFF → byte-identical no-op). The dig has committed to one still-fully-solid
                // riser that sits >= FUTILE_BANK_DIG_MIN_RISE above the foot (an overhang the
                // buoyant bob can never reach) and has done so AFLOAT for FUTILE_BANK_DIG_TICKS
                // without ever grounding and without the riser breaking → it is provably
                // hopeless. Release it NOW (well before WATER_CLIMB_DIG_COMMIT_CAP=1000, ~40 s
                // sooner): latch climbPillarGaveUp so the futile pillar/dig don't re-engage at
                // this exact spot, drop the riser + the waterClimbDigging flag so breakingEdge
                // falls THIS tick, and price the pocket cell out — handing the wedge to the
                // already-working reactive churn-charge + anti-stuck back-off burst (both gated
                // !breakingEdge, which is why the unbroken dig kept them suppressed). The legit
                // +1 grounded staircase dig can't reach here: its riser is +1 (below the rise
                // gate), it grounds (resets floatTicks), and the riser breaks (resets the
                // counter via the fresh-riser path).
                if (BotConfig.walkerFutileBankDigRelease && digCommitted
                        && waterClimbDigRiser != null && world.isSolid(waterClimbDigRiser)
                        && waterClimbDigFloatTicks > FUTILE_BANK_DIG_TICKS
                        && waterClimbDigRiser.getY() - foot.getY() >= FUTILE_BANK_DIG_MIN_RISE) {
                    if (BotConfig.walkerDebug)
                        LOG.info("[walker] futile bank-dig release: riser {},{},{} (+{} above foot) unbroken for {} afloat ticks → drop dig, hand to recovery",
                                waterClimbDigRiser.getX(), waterClimbDigRiser.getY(), waterClimbDigRiser.getZ(),
                                waterClimbDigRiser.getY() - foot.getY(), waterClimbDigFloatTicks);
                    world.penalizeStuckNode(foot);
                    world.penalizeStuckNode(foot.above());
                    climbPillarGaveUp = true;
                    waterClimbDigRiser = null;
                    lastDigRiser = null;
                    waterClimbDigCommitTicks = 0;
                    waterClimbDigFloatTicks = 0;
                    waterClimbDigging = false;
                    futileBankDigCooldown = FUTILE_BANK_DIG_COOLDOWN;
                }
            }
            // Trigger once bob-stalled below a bank we can't mount, with a placeable in
            // hand — then LATCH a pillar-up that runs to completion. Suppressed once the
            // pillar has proven futile here (climbPillarGaveUp): a buoyant bob can't lift
            // its feet above a surface fill cell, so re-engaging just bobs again — the
            // bank-DIG below takes over instead.
            // swimAshore +2 with no toBreak block never commits a dig (waterClimbDigging stays
            // false), so deepDig suppresses the pillar yet the dig never runs → bob-churn. After the
            // ~4 s dig window with NO dig swinging, fall back to the pillar despite deepDig so the
            // placeable lifts the bot onto the bank. Flag-gated; default OFF keeps this byte-identical.
            boolean swimAshorePillarFallback = BotConfig.walkerSwimAshorePillarDespiteDeepDig
                    && deepDig && !waterClimbDigging && waterClimbStall > WATER_CLIMB_DIG_STALL;
            if (waterClimbing && waterClimbStall > WATER_CLIMB_STALL && !climbPillarGaveUp
                    && (!deepDig || swimAshorePillarFallback)
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
                        && !world.isWater(foot) && !world.isWater(foot.below())
                        // ...AND actually topped out — not a MID-WALL rung. On a tall sheer
                        // climb the takeover places a rung, grounds on it dry, and this fired
                        // "topped out" at every +1 → flush-walk → repath → re-engage; without
                        // this the climb-out sometimes never completes (buoyantWallArena run
                        // reached only y-mid-wall, onPlateau=false). While the climb node is
                        // still ≥2 above the foot the wall continues up; only a node at ~foot
                        // level is the real bank top.
                        && !(wantClimbNow && cwp.getY() - foot.getY() >= 2);
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
                    // Only "give up" the pillar (block re-engage, hand off to the bank-DIG)
                    // when that DIG can actually fire — i.e. breaking is allowed. With break
                    // OFF (no pickaxe: the live +2 mud-bank case) there is NO fallback, so
                    // latching climbPillarGaveUp would strand the bot bobbing forever. Leaving
                    // it false lets the pillar RE-ENGAGE next tick, re-locking the column to the
                    // bot's current foot — which the locked-heading forward press has nudged
                    // toward the bank — so the column RATCHETS to the supported bank-adjacent
                    // cell and the foothold-place finally lands (the pre-3160836 behavior the
                    // self-correcting latch regressed: waterLowBankArena went red for ~5 days).
                    boolean digFallbackHere = BotConfig.allowBreak && BotConfig.allowSwimEscapeBreak;
                    if ((drifted || placeFutile || tooHigh) && digFallbackHere) {
                        climbPillarGaveUp = true;
                        // walkerClimbGaveUpSticky: anchor the latch to THIS bank so repath-driven
                        // context resets can't clear it while the bot is still here (15s TTL).
                        if (BotConfig.walkerClimbGaveUpSticky) {
                            climbGaveUpPos = foot;
                            climbGaveUpTtl = 300;
                        }
                    }
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
            if (futileBankDigCooldown > 0) futileBankDigCooldown--;   // post-release dig lockout (walkerFutileBankDigRelease)
            // walkerBankDigSkipWhenCwpSwims: the committed path's NEXT node (cwp) being a WATER cell
            // means the bot should SWIM into it, not dig — the real climb-out (a stepUp onto land)
            // sits FURTHER along the path, not here. Digging at a water-routed waypoint is premature:
            // the omnidirectional exit scan below picks the NEAREST dry exit, which on a tall sheer
            // bank (goal-side wall ~9 blocks) is the perpendicular wall face (dot≈0, passes the
            // forward-hemisphere guard) — so the bot trenches the goal-side wall, aimAtBlock locks the
            // yaw at it, the forward drive rams it, and it bob-stalls forever while A*'s actual path
            // swims west around to a lower climb-out (live 2026-06-27 deadlock @ -646,62,351, stuck
            // 1181 ticks: cwp=-648,62,352 WATER, dug east wall instead of swimming the path). Skipping
            // the dig when cwp is water lets the normal swim-drive follow the path to the real exit.
            // A genuine climb-out HERE routes cwp to a LAND/stepUp node (not water) so the dig still
            // fires for it. Default OFF; validate via replay A/B on the archived deadlock.
            boolean cwpSwims = BotConfig.walkerBankDigSkipWhenCwpSwims && world.isWater(cwp);
            if (!waterClimbPillaring && waterClimbing && waterClimbStall > digStall
                    && futileBankDigCooldown <= 0 && !cwpSwims
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
                // 兜底 anti-wander column LOCK: lock ONE chimney column at engage and dig
                // it straight up. Without it the dig re-derives the target from the live
                // (repathing) cwp every time a riser breaks, so the buoyant bot drifts along
                // the bank digging a fresh column each time and never tops out (live
                // 2026-06-20: 4545 digs across 10+ columns x2320-2342, never grounded). The
                // pillar takeover locks its column the same way; the toolless dig now does too.
                BlockPos riser = waterClimbDigRiser;
                // walkerBankDigForwardExit, latch re-validation: a LATCHED riser is only re-chosen
                // when it goes null/non-solid (below), but the 25× underwater mining penalty means a
                // backward riser can NEVER break — so it stays latched and the forward-hemisphere
                // guard in the re-scan branch never re-applies (live isolation 2026-06-27: 1024 digs
                // all at the backward riser even with the guard ON). Drop a latched riser that now
                // points BACKWARD of cwp so the scan re-runs and re-picks a forward exit (or null →
                // no dig → swim the path). cwp follows the planned path, so this respects a path that
                // legitimately routes backward (its cwp points backward too).
                if (riser != null && BotConfig.walkerBankDigForwardExit
                        && (cwp.getX() != foot.getX() || cwp.getZ() != foot.getZ())) {
                    int rdx = riser.getX() - foot.getX();
                    int rdz = riser.getZ() - foot.getZ();
                    int gdx0 = Integer.signum(cwp.getX() - foot.getX());
                    int gdz0 = Integer.signum(cwp.getZ() - foot.getZ());
                    if (rdx * gdx0 + rdz * gdz0 < 0) riser = null;   // latched backward → force re-scan
                }
                if (riser == null || !world.isSolid(riser)) {
                    riser = null;
                    // Climb toward the NEAREST dry-standable EXIT (a cell the bot can stand on:
                    // solid floor, 2 air above, no water), NOT the far lateral goal (cwp). The
                    // old cwp direction made the bot trench the waterline layer SIDEWAYS toward a
                    // far goal — tunnelling INTO the bank at one Y and never stepping up onto the
                    // land 1-2 blocks above (live 2026-06-20: buried in the hill at y63 for 5 min
                    // digging +x toward the 2600 goal instead of the +2 to the y65 land). Aim at
                    // the closest way OUT; the onward path resumes once grounded on dry land.
                    int dx = Integer.signum(cwp.getX() - foot.getX());   // fallback: goal direction
                    int dz = Integer.signum(cwp.getZ() - foot.getZ());
                    int bestExitD2 = Integer.MAX_VALUE;
                    // walkerBankDigForwardExit: the omnidirectional exit scan below picks the NEAREST
                    // dry exit in ANY direction — including BEHIND the bot. When the nearest exit is
                    // backward (opposite the path's cwp) the climb-out digs AWAY from the goal into a
                    // churn — the live -733/-710 "dig the west wall behind me while the goal is east"
                    // deadlock (1000+ digs at the backward riser, ~48 s frozen, bot reverses 46 blocks).
                    // Bias the scan to the FORWARD hemisphere (dot(offset, cwp-dir) >= 0) so it only
                    // digs toward where the planned path actually leads. If NO forward exit exists the
                    // dx/dz fallback above (= cwp direction = forward) still drives a forward dig, so
                    // the bot never trenches backward. A genuinely backward path routes cwp backward
                    // too, so "forward" follows the PATH (the immediate waypoint), not the absolute goal.
                    // Independent of walkerBuoyantSearchFromSurface (which fixes the search START): even
                    // a correctly forward path can have its exit scan pick a closer backward exit.
                    boolean fwdExitGuard = BotConfig.walkerBankDigForwardExit
                            && (cwp.getX() != foot.getX() || cwp.getZ() != foot.getZ());
                    int gdx = Integer.signum(cwp.getX() - foot.getX());
                    int gdz = Integer.signum(cwp.getZ() - foot.getZ());
                    for (int sx = -4; sx <= 4; sx++)
                        for (int sz = -4; sz <= 4; sz++) {
                            if (sx == 0 && sz == 0) continue;
                            if (fwdExitGuard && (sx * gdx + sz * gdz) < 0) continue;   // skip backward-hemisphere exits
                            for (int sy = 1; sy <= 4; sy++) {
                                BlockPos land = new BlockPos(foot.getX() + sx, foot.getY() + sy, foot.getZ() + sz);
                                if (world.isSolid(land.below()) && !world.isSolid(land)
                                        && !world.isSolid(land.above()) && !world.isWater(land)
                                        && !world.isWater(land.below())) {
                                    int d2 = sx * sx + sz * sz;
                                    if (d2 < bestExitD2) {
                                        bestExitD2 = d2;
                                        dx = Integer.signum(sx);
                                        dz = Integer.signum(sz);
                                    }
                                    break;   // nearest (lowest) exit in this column
                                }
                            }
                        }
                    BlockPos[] cands = {
                            (dx != 0 || dz != 0) ? new BlockPos(foot.getX() + dx, foot.getY(), foot.getZ() + dz) : null,
                            dx != 0 ? new BlockPos(foot.getX() + dx, foot.getY(), foot.getZ()) : null,
                            dz != 0 ? new BlockPos(foot.getX(), foot.getY(), foot.getZ() + dz) : null};
                    // Dig the forward column's LOWEST solid cell ABOVE the waterline — a DRY notch
                    // whose floor (the cell below it) stays solid, so the bob-jump can ground on
                    // it (the +1 climb-out). Anchored on the bot's water-column surface Y (steady),
                    // not the bobbing foot/eye (an eye anchor broke buoyantWallArena). The cands
                    // follow the path's cwp, so as the bot climbs they advance +z+y → a DIAGONAL
                    // staircase up into the solid hill (a vertical column-lock chimney can't be
                    // ascended — the bot digs out its own floor; a real bank is a solid massif the
                    // staircase climbs THROUGH).
                    int surfY = foot.getY();
                    while (world.isWater(new BlockPos(foot.getX(), surfY + 1, foot.getZ()))) surfY++;
                    for (BlockPos cand : cands) {
                        if (cand == null) continue;
                        int ry = surfY + 1;
                        while (ry <= surfY + 5 && !world.isSolid(new BlockPos(cand.getX(), ry, cand.getZ()))) ry++;
                        BlockPos step = new BlockPos(cand.getX(), ry, cand.getZ());
                        // Overhang rejection (walkerBankDigSkipOverhang): the riser must be a genuine
                        // bank-face step whose FLOOR (cell below) is solid — the invariant this comment
                        // already states ("a DRY notch whose floor stays solid") but the lowest-solid
                        // scan above omits. An air-floored riser is a CEILING/overhang the buoyant bob
                        // can never ground beside: digging it does nothing, the bot yaw-locks into it
                        // and bob-stalls while A*'s real climb-out (a pillarUp ~8 blocks along the
                        // water) goes unfollowed. Rejecting it leaves riser null → no dig → the bot
                        // swims the committed path to the real exit. The legit staircase-dig tunnels a
                        // SOLID massif (floor always solid) so it is unaffected.
                        boolean overhang = BotConfig.walkerBankDigSkipOverhang && !world.isSolid(step.below());
                        if (ry <= surfY + 5 && world.isSolid(step) && !overhang) { riser = step; break; }
                    }
                    waterClimbDigRiser = riser;
                    waterClimbDigCommitTicks = 0;   // fresh riser → fresh per-block commit budget
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
                    // A TALL riser (>=2 above the floating foot) sits far enough above the
                    // bobbing eye that the once-only snap lets the mining ray drift OFF the
                    // block face as the bot bobs +-0.5 — the break never completes and the
                    // dig re-fires forever (live z2744 confined deep shaft: riser y64 vs foot
                    // y61, 1000+ swimUp/stairUpBreak ticks, full-shaft bob y62<->y47, no exit).
                    // Re-aim EVERY tick for a tall riser to hold the look ray on the block face
                    // (a little camera judder is the lesser evil vs a hard deadlock; tall
                    // confined digs are rare). A +1 riser keeps the steady once-only snap so
                    // the common shallow bank-dig camera stays smooth.
                    // Aim-stabilisation, dynamic-correction half: re-aim at the riser
                    // EVERY tick. The buoyant bot bobs at a bank (and dips underwater), so
                    // any tick we SKIP the re-aim the mining ray slips off the riser face
                    // and vanilla continueDestroyBlock resets the break — and on the 25×
                    // underwater+afloat mining penalty (dirt ≈375 ticks/block) it then NEVER
                    // completes (live 2026-06-20: same riser dug 1118 ticks, 0 breaks; the
                    // old CONDITIONAL re-aim left exactly those gaps). A per-tick re-aim pins
                    // the crosshair to the block face through the bob so the break
                    // accumulates to completion. Paired with the bob-tame jump below (holds
                    // the eye near the riser → near-horizontal ray), the residual camera
                    // motion stays small. Reliable digging is the priority at a climb-out.
                    a.aimAtBlock(riser);
                    lastDigRiser = riser;
                    lastDigAimEyeY = p.getEyeY();
                    a.breakHold(true);
                    // Mark the dig active: next tick's breakingEdge holds the leash and
                    // exempts the burst so the dig can finish (see the breakingEdge note).
                    // Clear any burst count accrued during the pre-dig bob-stall so the
                    // very first dig tick can't fire a stale burst before the exemption.
                    waterClimbDigging = true;
                    wedgeRepathsHere = 0;
                    // Aim-stabilisation, BALANCE half — the jump (space) is corrected, not
                    // held flat-out and not bang-banged to the riser. Both extremes bob:
                    // holding jump CONTINUOUSLY over-swims the bot up; bang-banging to the
                    // riser centre overshoots on the jump impulse into a 2-block limit cycle
                    // (y61↔63 live) that slings the ray off the face. The minimal-bob hold a
                    // human uses to tread water: swim up ONLY when the head goes underwater —
                    // just enough to STAY AT THE SURFACE. Head out → no jump → it settles
                    // with a tiny natural bob; the instant it dips under → one correction
                    // pops it back up. The waterline riser then sits just below the steady
                    // surface eye → a near-horizontal, bob-tolerant mining ray. (Covers
                    // "松手空格就会沉下去": it still swims up the moment it submerges.)
                    // ...PLUS a controlled climb term: rise when the eye is clearly BELOW
                    // the current riser (more than 0.3 under its base). For a riser at eye
                    // level this is false → pure tread-water (the stable cycle-4 hold); once
                    // a cell breaks and the next riser sits a block higher, the eye drops
                    // below it → the bot swims UP to it and re-anchors there → it ascends the
                    // staircase instead of treading in place. The 0.3 deadband + no-sprint
                    // keep the rise from overshooting back into a bob.
                    boolean needRise = p.isUnderWater() || p.getEyeY() < riser.getY() - 0.3;
                    agentJump(a, needRise);
                    // No sprinting: a sprinting bot swim-DIVES into the prone pose and dunks
                    // its head underwater (the live "潜入水底/仰头空挖" thrash + the 25× mining
                    // penalty). Upright tread keeps the head out and the dig fast.
                    p.setSprinting(false);
                    // Press INTO the bank to enter the broken notch — but ONLY once the foot has
                    // risen to the notch floor (foot.y ≳ riser.y − 0.6). In DEEP water the bot
                    // floats with its foot ~2 below the surface, so pressing forward while still
                    // low RAMS the riser's solid floor-cell (riser.below()) and pins the bot below
                    // the +1 step — it breaks the block but never steps onto it and slides back
                    // into the water (live 2026-06-20: stuck at y61 ramming the y62 bank, "挖穿后
                    // 掉回水里"). Below the notch, suppress forward and just SWIM UP (the jump
                    // above); once the foot reaches the ledge, press in and ground on it. (Forward
                    // while submerged also drops the bot into the prone-swim pose and it sinks.)
                    if (!p.isUnderWater() && p.getY() >= riser.getY() - 0.6) agentForward(a, true);
                    return Step.WALKING;
                }
            }
        }

        // Pillar-up actuator: clear the ceiling if one blocks the rise, then
        // jump and place the support block beneath at the apex. Distinct from
        // the generic place actuator because it owns the airborne timing
        // (you can't place a block in the cell you're standing in).
        if (edge != null && "pillarUp".equals(edge.move) && hasPendingEdge(world, edge)) {
            // Off-column guard (pillarUp-off-column wedge, see PILLAR_ALIGN_SQ): the pillar is
            // placed below the bot and it jumps in place, so it only lands the bot a level up
            // when standing over the column. If climb-drift left the bot to the side, WALK to
            // the column XZ first (no place/jump) — once within PILLAR_ALIGN_SQ the normal
            // pillar below engages. Self-recovering: a blocked approach rams → noStepProgress
            // climbs → the fellOffPath/repath path re-routes, so this can't deadlock.
            BlockPos pcol = path.get(step);
            double pcdx = (pcol.getX() + 0.5) - p.getX();
            double pcdz = (pcol.getZ() + 0.5) - p.getZ();
            // DRY pillars only: a buoyant/water pillar bobs and drifts off-column BY DESIGN and
            // has its own float-up + place-on-crest handling below; aligning would fight the bob
            // (regressed buoyantWallArena to 192-tick WATERLINE thrash). The thrash is AT the
            // waterline, where the bob peak lifts the bot out of the water cell so node-based
            // isWater reads "dry" — so probe the bot's own FOOT column (foot, -1, -2) for water,
            // not just the node. p.isInWater() flickers false at the bob peak, hence world-based.
            boolean nearWater = p.isInWater()
                    || world.isWater(foot) || world.isWater(foot.offset(0, -1, 0)) || world.isWater(foot.offset(0, -2, 0))
                    || world.isWater(pcol) || world.isWater(pcol.offset(0, -1, 0));
            if (p.onGround() && !nearWater && pcdx * pcdx + pcdz * pcdz > PILLAR_ALIGN_SQ) {
                float ayaw = (float) Math.toDegrees(Math.atan2(-pcdx, pcdz));
                float dyaw = angleDiff(p.getYRot(), ayaw);
                if (Math.abs(dyaw) > WALKER_MAX_YAW_SLEW_DEG) dyaw = Math.copySign(WALKER_MAX_YAW_SLEW_DEG, dyaw);
                float ny = p.getYRot() + dyaw;
                p.setYRot(ny); p.yHeadRot = ny; p.yBodyRot = ny;
                p.setXRot(0f);
                a.breakHold(false);
                p.setSprinting(false);
                agentForward(a, true);
                agentJump(a, p.horizontalCollision);   // hop only to clear a riser; flat-smooth otherwise
                return Step.WALKING;
            }
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
        // fix Q (re-applied 2026-06-25, now A/B-validatable via the DETERMINISTIC vineOverWaterClimbArena
        // — the journey replay's replan-variance, which forced the earlier revert, no longer blocks it):
        // parkour→vine HANDOFF. A parkourAscend that LANDS on a vine still has parkourEdge=true at the apex,
        // so the plain !parkourEdge gate leaves nothing driving the cling at the one instant it matters →
        // the bot fails to grab → drops into the y62 pocket (foot becomes water, isClimbable=false → handler
        // can't re-engage → bank-DIG churn, -711,67). Permit the handler on a parkour edge when the bot has
        // LANDED on the climbable column: foot=vine AND arrested horizontally over the landing node wp (XZ
        // within OVERSHOOT_RESYNC_SQ — the live landing arrests at cur2~1.24, too far for REACH_DIST_SQ) AND
        // airborne. CANNOT fire mid-leap (over a parkour GAP foot=air, isClimbable=false). !p.isInWater()
        // stays (the apex is BEFORE the drop); if the grab still misses, control falls through to the
        // unchanged water actuator. Strict ADD: inert when landedOnVine=false → identical to the old gate.
        double vineDx = (wp.getX() + 0.5) - p.getX();
        double vineDz = (wp.getZ() + 0.5) - p.getZ();
        boolean landedOnVine = BotConfig.walkerVineLandGrab
                && world.isClimbable(foot) && !p.onGround()
                && (vineDx * vineDx + vineDz * vineDz) < OVERSHOOT_RESYNC_SQ;
        boolean onVine = (!parkourEdge || landedOnVine)
                && !p.isInWater() && !p.onGround() && world.isClimbable(foot);
        // VINEDIAG (behind walkerDebug): capture the parkour→vine landing decision at the apex. Fires
        // while airborne OR in-water (the bug window), whether or not onVine engages — so an A/B can see
        // if the foot EVER becomes the climbable vine while parkourEdge is still true (the landedOnVine
        // case) or if the arc skips the vine straight to the water pocket (foot air→water, never climbable).
        if (BotConfig.walkerDebug && (!p.onGround() || p.isInWater())) {
            BlockPos footBelow = foot.below();
            LOG.info("[VINEDIAG] py={} foot={},{},{} climb(foot)={} climb(below)={} parkourEdge={} inWater={} onGround={} landedOnVine={} onVine={} cur2={} wp={},{},{}",
                    String.format(Locale.ROOT, "%.2f", p.getY()),
                    foot.getX(), foot.getY(), foot.getZ(),
                    world.isClimbable(foot), world.isClimbable(footBelow), parkourEdge,
                    p.isInWater(), p.onGround(), landedOnVine, onVine,
                    String.format(Locale.ROOT, "%.2f", vineDx * vineDx + vineDz * vineDz),
                    wp.getX(), wp.getY(), wp.getZ());
        }
        if (onVine) {
            BlockPos ahead = path.get(Math.min(step + 2, path.size() - 1));
            // Vanilla vine ASCENT (LivingEntity.travel) only fires while
            // (horizontalCollision || jumping) && onClimbable, and SUSTAINS only while the
            // body keeps colliding with a wall — proven by vineClingFidelityProbe (the avatar
            // climbs the full 5-block column when it presses FORWARD into the backing wall).
            // So aim the heading at the vine's SOLID BACKING WALL (forward = into the wall =
            // sustained horizontalCollision), NOT at the overhead path node. The old aim used
            // path.get(step+2): once that node's XZ drifted off the column the atan2 bearing
            // swung the yaw away from the wall (live trace 0°→-90°→-206°), the forward press
            // stopped ramming the wall, the climb stalled, and the bot bobbed back down into
            // the water pocket. Bias the wall pick toward the path-ahead direction so a corner
            // (two walls) still yields a stable heading; if no neighbour is solid (free climb),
            // fall back to the path-ahead bearing.
            double vdx = (ahead.getX() + 0.5) - p.getX();
            double vdz = (ahead.getZ() + 0.5) - p.getZ();
            Float wallYaw = vineWallYaw(world, foot, vdx, vdz);
            // NOTE (fix Q): a broad slide-guard-over-water (force climbUp when water-below) was tried
            // here and REMOVED — it misfired on LEGIT vine descents over water (live -702,489: path
            // intentionally goes DOWN to y61, ahead.y < foot.y, but the guard forced a climb UP →
            // over-climb → detour stall at z485, never reaching -711). The slide-loop fix-P saw was a
            // CONSEQUENCE of the fall into the pocket; Part 2's parkour→vine handoff prevents the fall,
            // so the bot climbs the clean stepUp chain (path up → climbUp naturally true) and never
            // oscillates. Plain over-climb semantics restored; a narrower pocket-only guard can be
            // added later if a real slide-into-pocket is observed WITH the handoff in place.
            boolean climbUp = ahead.getY() >= foot.getY();   // path ahead up/level → climb; below → over-climbed, descend
            // DESCEND-OFF-THE-CURTAIN gate (walkerVineDescentDrop, default OFF). When the path skims a
            // bank/inlet at one Y under a HANGING vine curtain, `ahead` (step+2) sits at the bob floor and
            // `climbUp` OSCILLATES with the y-bob → the bot is pinned ON the vine, jumping up / sliding down
            // with ZERO XZ progress toward the actual node (live -672,64,311 inlet bob ~10 s). If the
            // IMMEDIATE committed node `wp` is at/below the foot it is NOT a climb (a genuine ascent always
            // has wp ABOVE the foot — the free-hang -711 curtain + vineOverWaterClimbArena both ascend, so
            // wp.y > foot.y there, untouched). Force the slide-down so the body drops off the vine onto the
            // bank / into the inlet and the normal walk/stepDown resumes (it grounds → the !onGround gate
            // ends the cling). Strict ADD: inert when OFF or when wp.y > foot.y (a real climb).
            if (BotConfig.walkerVineDescentDrop && wp.getY() <= foot.getY()) climbUp = false;
            // FREE-HANGING vine sustain (walkerVineFreeHangClimb). A wall-less vine (wallYaw==null:
            // no solid horizontal neighbour of the foot column) cannot be climbed by ramming a wall —
            // vanilla's (horizontalCollision||jumping) ascent has no wall to keep horizontalCollision
            // live, and the path-ahead forward press WALKS the buoy-free body horizontally OUT of the
            // vine and it runs out of climbable / DROPS into the pocket (live -711: 65.02→65.20→detach→
            // 61.9 inW, totStuck 2400+). Sustain it by (a) holding JUMP every tick (jumping → vy=+0.2,
            // the only wall-less ascent drive) and (b) driving the body via the CAMERA-DECOUPLED impulse
            // straight at the immediate climb target wp — the live -711 vine is a WIDE curtain whose top
            // reaches the dismount ONLY on the exit side, so the body must keep advancing UP-AND-TOWARD
            // the exit (a deadzone that goes pure-vertical when "aligned" stalls the exit advance and the
            // bot wedges — live-verified). commandMove tracks wp exactly while the camera slews, so the
            // off-axis drift is corrected without the camera lag mis-aiming a raw forward press. Wall-
            // backed cells (wallYaw!=null) hand back to the wall-press path to top out + step off.
            boolean freeHang = BotConfig.walkerVineFreeHangClimb && wallYaw == null && climbUp;
            float vYaw;
            boolean pressForward = false;           // for the wall-backed / legacy path below
            boolean freeHangDrive = false;          // true → horizontal driven by commandMove (decoupled)
            float fhBearing = 0f;                   // radians; world bearing to the climb target
            if (freeHang) {
                // Steer toward the immediate next node `wp` (this is the LIVE-validated target: the body
                // climbs the curtain up-and-across toward each successive node). `wp` can flip ±180° when
                // the pure-pursuit `step` advances under the foot, so the heading is SLEW-LIMITED below to
                // keep that from circling the body off a narrow column.
                double tx = (wp.getX() + 0.5) - p.getX();
                double tz = (wp.getZ() + 0.5) - p.getZ();
                if (tx * tx + tz * tz < 0.04) {
                    // wp is DIRECTLY above (within 0.2 block): atan2 of a ~zero vector is pure noise.
                    // Climb PURE-VERTICAL — hold the camera, no horizontal drive, just the jump.
                    vYaw = p.getYRot();
                    freeHangDrive = false;
                    freeHangDriveYaw = Float.NaN;            // resync the slew when a real bearing returns
                } else {
                    // SLEW-LIMIT the drive heading: turn a persistent heading at most ~30°/tick toward the
                    // bearing to wp. When `step` advances and wp flips, the heading averages the flip into
                    // a smooth arc rather than walking the body in CIRCLES off the column (the arena's
                    // narrow-column flake). On the live -711 curtain the bearing is steady (the climb tracks
                    // the exit) so the slewed value just hugs it — the proven up-and-across path is intact.
                    float target = (float) Math.toDegrees(Math.atan2(-tx, tz));
                    if (Float.isNaN(freeHangDriveYaw)) {
                        freeHangDriveYaw = target;            // snap on the first tick of the climb
                    } else {
                        float dyaw = angleDiff(freeHangDriveYaw, target);   // shortest turn toward target
                        if (Math.abs(dyaw) > WALKER_MAX_YAW_SLEW_DEG) dyaw = Math.copySign(WALKER_MAX_YAW_SLEW_DEG, dyaw);
                        freeHangDriveYaw += dyaw;
                    }
                    freeHangDrive = true;
                    fhBearing = (float) Math.toRadians(freeHangDriveYaw);   // drive along the slewed heading
                    vYaw = freeHangDriveYaw;                  // camera follows the same slewed heading
                }
            } else if (wallYaw != null) {
                vYaw = wallYaw;
                pressForward = climbUp;
            } else if (vdx * vdx + vdz * vdz < YAW_DEADZONE_SQ) {
                vYaw = p.getYRot();
                pressForward = climbUp;
            } else {
                vYaw = (float) Math.toDegrees(Math.atan2(-vdx, vdz));
                pressForward = climbUp;
            }
            if (Math.abs(angleDiff(p.getYRot(), vYaw)) > BotConfig.walkerYawHysteresisDeg) {
                float want = smoothAngle(p.getYRot(), vYaw);
                float dyaw = angleDiff(p.getYRot(), want);
                if (Math.abs(dyaw) > WALKER_MAX_YAW_SLEW_DEG) dyaw = Math.copySign(WALKER_MAX_YAW_SLEW_DEG, dyaw);
                float ny = p.getYRot() + dyaw;
                p.setYRot(ny); p.yHeadRot = ny; p.yBodyRot = ny;
            }
            p.setXRot(smoothAngle(p.getXRot(), 0f));
            // Horizontal drive: free-hang uses the camera-DECOUPLED impulse toward the climb target
            // (exact bearing regardless of the slewing camera); otherwise the raw forward press (into
            // the wall / along the camera). A modest forward magnitude keeps the body advancing toward
            // the exit column without overshooting off the curtain (the climbable clamp caps it ≤0.15).
            if (freeHangDrive) {
                // Drive the body toward wp via the impulse channel (vanilla travel() rotates it by the
                // camera yaw). Empirically LIVE-tuned: the world-bearing impulse + the camera slewing
                // toward fhBearing converges the body onto a steady up-and-toward-the-exit climb across
                // the wide -711 curtain (in-pocket→0, tops out, advances). A "fully decoupled" Δ-form was
                // tried and REGRESSED live (it wedged the bot in the pocket — the wide-curtain climb needs
                // THIS trajectory, not a geometrically-pure one), so do NOT change it without a live A/B.
                a.commandMove((float) (-Math.sin(fhBearing) * FREE_HANG_DRIVE),
                              (float) (Math.cos(fhBearing) * FREE_HANG_DRIVE));
            } else {
                agentForward(a, pressForward);      // forward INTO the wall (wall-backed climb)
            }
            // On a free-hanging climb hold JUMP every tick (jumping sustains the wall-less vy=+0.2);
            // otherwise jump tracks climbUp (off → slide back down a wall-backed vine to be re-planned).
            agentJump(a, freeHang || climbUp);      // continuous jump on a wall-less climb
            agentSneak(a, false);              // sneak would HALT the vine climb
            p.setShiftKeyDown(false);
            p.setSprinting(false);
            if (BotConfig.walkerDebug)
                LOG.info("[walker] vine-climb foot={},{},{} ahead={},{},{} climbUp={} freeHang={} fhDrive={} yaw={} py={}",
                        foot.getX(), foot.getY(), foot.getZ(),
                        ahead.getX(), ahead.getY(), ahead.getZ(), climbUp, freeHang, freeHangDrive,
                        String.format(Locale.ROOT, "%.0f", p.getYRot()),
                        String.format(Locale.ROOT, "%.2f", p.getY()));
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
            // Dry +1 staircase camera-spin fix: once the bob carries the body
            // horizontally ON TOP of the close +1 step node, that node's bearing flips
            // ±180° each tick (it's now beside/behind the foot) and a multi-step stair
            // accumulates a full 360°+ camera swing (live 2026-06-15 z1864 yawRange 405°)
            // even though the climb keeps progressing. When within ~1 block of the step
            // node horizontally AND a further node exists, aim at the NEXT node instead —
            // a genuine forward-up heading (no freeze, so it can't pin a wrong direction
            // like a held yaw does, and the buoyant water-exit mount whose next node is a
            // forward ledge walk only steadies). Land + gentle +1 only; parkour leaps
            // (precise launch aim) and steeper jumps keep the exact-waypoint aim.
            // NOTE: extending this trend-average to DESCENTS (the live 2026-06-21 "下山转圈":
            // close-node carrot-swing winds the yaw 600°+ down a steep slope) was A/B-DISPROVEN on
            // the deterministic descentYawArena — every variant was WORSE than no fix (off=1050°
            // thrash/242tk; descent-trend=1559°/569tk i.e. 2.3× SLOWER; parkour-inclusive=1529° @
            // 6.4°/tick). A steep descent routes through fall/parkour-descend nodes the trend
            // excludes, and where it engages it flickers between trend- and exact-aim, ADDING
            // thrash. The descent spin needs a different mechanism (damp the swinging target
            // bearing itself / cut switchback node density), not this aim swap. Up-steps only.
            if (!p.isInWater() && !parkourEdge && (wp.getY() - foot.getY()) == 1
                    && step + 1 < path.size()) {
                // Path-trend averaging (the "路径趋势平均" true fix). Sum the UNIT direction of
                // each upcoming segment while they stay consistent (<60° turn), so a diagonal
                // staircase's ±30° per-step alternation collapses to the steady diagonal and the
                // heading holds inside the pivot tolerance (no per-step drive cut → no sawtooth).
                // Stopping at a real bend keeps it from aiming across a corner (the single-far-node
                // aim that did was reverted). Also subsumes the old close-node camera-spin swap:
                // once the bob carries the body onto the close +1 node, the trend still points up
                // the stair instead of flipping ±180°. Forward-only (dot>0) never reverses.
                double tx = 0, tz = 0;
                BlockPos prev = foot;
                int last = Math.min(step + STAIR_TREND_LOOKAHEAD, path.size() - 1);
                for (int k = step; k <= last; k++) {
                    BlockPos nd = path.get(k);
                    double sx = nd.getX() - prev.getX(), sz = nd.getZ() - prev.getZ();
                    double sl = Math.sqrt(sx * sx + sz * sz);
                    if (sl < 1e-6) { prev = nd; continue; }
                    sx /= sl; sz /= sl;
                    if ((tx != 0 || tz != 0) && (sx * tx + sz * tz) / Math.sqrt(tx * tx + tz * tz) < 0.5)
                        break;                                  // segment turns >60° from the trend → stop
                    tx += sx; tz += sz;
                    prev = nd;
                }
                // Only a GENUINE diagonal run (≥2 consistent horizontal nodes accumulated) may
                // override the aim — a near-vertical climb (pillar-up / a tight dig-staircase
                // mount) sums to ~0 horizontal, so its noisy trend is rejected and the precise
                // immediate-node aim is kept (else the summit pillar + bank-dig plateau mount
                // mis-aim). Forward-only (dot>0) never reverses.
                if (Math.sqrt(tx * tx + tz * tz) >= 2.0 && (tx * adx + tz * adz) > 0) { adx = tx; adz = tz; }
            }
        } else if (reCentre) {
            adx = recX; adz = recZ;
        } else if (stuckTicks > APPROACH_NODE_AIM_TICKS) {
            // FLAT-node carrot-orbit fallback (see APPROACH_NODE_AIM_TICKS). On a flat walk the body
            // follows the look-ahead carrot; at a turn/corner node the carrot points ~60° off the close
            // node and the body orbits it at ~0.75 b without ever closing the within-gate (live FREEZE-
            // DIAG: aimAtWp=false, driveF=1, fwdComp>0, hSpd~0.07, cur2 frozen, 26-80 ticks). reCentre
            // (previous node) and the strafe (cross-axis) don't pull onto the IMMEDIATE node, so the
            // orbit only breaks on the slow wedge timer. Aim straight at the fixed node centre so the
            // body closes onto it (within / clean crossing → step advances) instead of circling the carrot.
            adx = (wp.getX() + 0.5) - p.getX();
            adz = (wp.getZ() + 0.5) - p.getZ();
        } else {
            double[] c = carrotPoint(world, foot, p.getX(), p.getZ());
            adx = c[0] - p.getX();
            adz = c[1] - p.getZ();
            // Carrot-swing thrash fix (the residual deep-water stall cluster). When a bank
            // blocks LOS to the next node, carrotPoint collapses the carrot to a CLOSE node
            // (~1 block); a buoyant bot drifting ±0.5 then makes that short aim vector rotate
            // fast and the bearing sweeps 96-176° — thrust cancels (0.4-0.9 b/s) and the
            // camera swings (maxYawErr≈180°). The dead-zone can't catch it (it fires only when
            // aim2 is small, but here aim2 > the wide dead-zone). Detect the oscillation
            // behaviourally (raw carrot bearing reversing repeatedly), and when it's genuine
            // AND no precise mount is imminent, aim at a STABLE far path node instead — a long
            // aim vector whose bearing barely moves as the bot drifts. A climb approach turns
            // monotonically (no reversals) AND is suppressed by the look-ahead, so the +1-exit
            // mount keeps its exact carrot (waterClimbOutRouteArena).
            float carrotBearing = (float) Math.toDegrees(Math.atan2(-adx, adz));
            if (p.isInWater() && !Float.isNaN(lastCarrotBearing)) {
                float db = angleDiff(lastCarrotBearing, carrotBearing);
                if (Math.abs(db) > 10f) {
                    int sign = db > 0 ? 1 : -1;
                    if (lastCarrotBearingSign != 0 && sign != lastCarrotBearingSign)
                        yawThrashTicks = Math.min(yawThrashTicks + 4, 12);
                    lastCarrotBearingSign = sign;
                }
            } else {
                lastCarrotBearingSign = 0;
            }
            lastCarrotBearing = carrotBearing;
            if (yawThrashTicks > 0) yawThrashTicks--;
            boolean climbAhead = false;
            for (int q = step; q < Math.min(path.size(), step + WATER_FAR_AIM_LOOKAHEAD + 1); q++)
                if (path.get(q).getY() > foot.getY()) { climbAhead = true; break; }
            if (p.isInWater() && yawThrashTicks >= WATER_YAW_THRASH_SCORE && !climbAhead) {
                BlockPos far = path.get(Math.min(step + WATER_FAR_AIM_LOOKAHEAD, path.size() - 1));
                adx = (far.getX() + 0.5) - p.getX();
                adz = (far.getZ() + 0.5) - p.getZ();
            }
        }

        // Hold heading when the horizontal aim vector is tiny (within the dead-zone) so
        // atan2 on sub-block noise can't snap the yaw each tick — see YAW_DEADZONE_SQ. Two
        // cases get the WIDER dead-zone (CLIMB_AIM_DEADZONE_SQ, ~2 blocks):
        //  (a) a water bank-climb node OVERHEAD — the floating bot can't translate onto it, so
        //      without this it orbits the column and the bearing sweeps 360° (the deep-water
        //      spin-in-place stall); holding the approach heading presses the bank for the
        //      climb-out actuator. (Original f4da16f behaviour — kept verbatim.)
        //  (b) any in-water aim once the bot is WEDGED/oscillating (noStepProgressTicks past a
        //      threshold). A buoyant bot can't stop precisely on a water carrot/node; within
        //      ~1 block the aim vector rotates fast as it drifts and the bearing sweeps —
        //      measured 96-176° yaw swings that collapse forward thrust to 0.4-0.9 b/s (vs
        //      4.6 b/s on the same path with a steady heading) AND drive the maxYawErr≈180°
        //      camera-swing anomaly. Gating on no-progress keeps a precisely-advancing
        //      approach on the TIGHT dead-zone, so the climb-out mount stays accurate
        //      (deepWaterClimboutNoBlockArena regressed when this widened unconditionally).
        // walkerOvershootReaim: a dry walk-node OVERSHOOT at a cliff base wedges hard — the foot blew PAST
        // the node (cur2 > OVERSHOOT_RESYNC_SQ) but the NEXT node is the climb (>1 up) so `passed` can't
        // advance onto it, and the carrot/tangent aim points the body the wrong way (backward into a wall)
        // so it never re-centres — a ram-frozen wedge (journey 2026-06-29 -558,82: yaw -179 / yawErr -120 /
        // hCol / cur2 6.28 / 260 ticks; even safetyRepath at stuck>60 re-commits the same path). When wedged
        // there, aim BACK at the overshot node so the body walks onto it (the 略微后退 it should do) and
        // relaunches the climb from the aligned base. Dry + grounded + next-too-high + long stuck only.
        // hCol-gate refinement REVERTED 2026-06-29: requiring horizontalCollision made it WORSE (rev-897, a
        // STABLE archive, regressed 161→380 — the hCol subset is where backing up HURTS, i.e. ram-and-push-
        // through, not ram-and-retreat; gating to it dropped the beneficial non-ram firings). Keep the
        // unconditional grounded-overshoot trigger that was NET-POSITIVE (corpus 4811→4104, long-540 −81%).
        if (BotConfig.walkerOvershootReaim && path != null && step < path.size()
                && step + 1 < path.size() && !p.isInWater() && p.onGround()
                && stuckTicks > OVERSHOOT_REAIM_STUCK) {
            BlockPos ow = path.get(step);
            double ocx = (ow.getX() + 0.5) - p.getX(), ocz = (ow.getZ() + 0.5) - p.getZ();
            if (ocx * ocx + ocz * ocz > OVERSHOOT_RESYNC_SQ
                    && path.get(step + 1).getY() - foot.getY() > 1) {
                adx = ocx;
                adz = ocz;   // walk BACK onto the overshot cliff-base node, then climb cleanly
            }
        }
        // walkerDryReanchor: the dry repath-rechurn breaker (REGRESSION.md §21). When a move has failed and
        // the foot is FAR off the current node (dist² > DRY_REANCHOR_OFFPATH_SQ) with a sustained stall
        // (stuckTicks > DRY_REANCHOR_STUCK) on dry land, override the carrot/tangent/node aim with a FIXED
        // aim at the last cleanly-passed node centre (path[step-1]). That fixed point's bearing barely moves
        // as the body closes, so the node-orbit yaw-thrash that paces the repath churn collapses and the bot
        // walks deterministically back ONTO the path before resuming — breaking the amplifier common to every
        // heterogeneous wedge. Excludes water (the in-water anti-spin machinery owns that) and the very first
        // node (no prior anchor). Independent of walkerOvershootReaim (this is the general off-path case; that
        // is the specific cliff-base-overshoot case) — both may compute, the later assignment wins.
        if (BotConfig.walkerDryReanchor && !p.isInWater() && step > 0 && step < path.size()
                && stuckTicks > DRY_REANCHOR_STUCK) {
            BlockPos cn = path.get(step);
            double cnx = (cn.getX() + 0.5) - p.getX(), cnz = (cn.getZ() + 0.5) - p.getZ();
            // ram-while-facing-wrong extension REVERTED 2026-06-29 (§25): adding an `|| (hCol && |yawErr|>90)`
            // trigger made the live -671 diagDown stall MUCH worse (oscillating limit cycle, totStuck 6009 vs
            // 509 slow-recover) — anchoring BACK to step-1 on a CLOSE ram just bounces the body back and forth
            // (pull to step-1 → re-approach → re-ram → anchor), the same oscillation that killed WallCornerNodeAim
            // (§13) and the hCol-gate (§16). A close ram is NOT fixable by aim-back. Keep the FAR-off-path-only
            // trigger (corpus-validated net-positive, §22-24); the close diagDown-ram is a separate open problem.
            if (cnx * cnx + cnz * cnz > DRY_REANCHOR_OFFPATH_SQ) {
                BlockPos anchor = path.get(step - 1);
                adx = (anchor.getX() + 0.5) - p.getX();
                adz = (anchor.getZ() + 0.5) - p.getZ();
            }
        }
        double aim2 = adx * adx + adz * adz;
        boolean climbAim = aimAtWaypoint && p.isInWater() && wpAimDy > 0.5;
        boolean waterThrash = p.isInWater() && noStepProgressTicks > WATER_YAW_HOLD_STALL;
        double aimDeadzone = (climbAim || waterThrash) ? CLIMB_AIM_DEADZONE_SQ : YAW_DEADZONE_SQ;
        float targetYaw = (aim2 < aimDeadzone)
                ? p.getYRot()   // essentially on the aim column — hold heading, don't thrash atan2
                : (float) Math.toDegrees(Math.atan2(-adx, adz));
        // A launch into a leap (parkour or MLG fall) must SNAP the heading even when
        // smoothLook is on — you can't course-correct mid-air, so a lagged launch sends
        // the bot off at an angle (drifts off a narrow landing). Launches bypass both the
        // EMA and the slew cap; everything else is smoothed + capped.
        boolean launch = parkourEdge || steppingOffFall || steppingOffWaterFall;
        // Phase-2 (walkerTangentAim): replace the immediate-node bearing with the bob-immune path TANGENT
        // ahead of the projection. The node bearing reverses ~180° on a node overshoot — the backward-jump /
        // 反复横跳 / facing-the-wall dead-corner stall; the tangent never flips, so the camera (via
        // smoothTargetYaw/aimYaw below) and the captured descentNodeYaw both follow the path smoothly. Skipped
        // for launches (a leap snaps at its landing node) and inside the aim dead-zone (hold heading). The
        // projector ran this tick (call site gates on the same flag).
        // EXCEPTION — an ASCENT to the immediate node (a dry +1 stepUp/diagUp riser). A step-up mounts by
        // driving STRAIGHT at the riser node + auto/stepUpJump; the tangent points along the path PAST the
        // riser (often across a terrain corner), which steers the body off-axis so it bonks the riser edge
        // and never mounts (live P2: a +1 riser at -750 churned with perp drifting to 4+). For an above-foot
        // immediate node, keep the legacy node bearing so pivotForStepUp/stepUpJump align onto the block —
        // the same reason the buoyant water-mount (buoyantClimbPress) keeps its column bearing, not the trend.
        if (BotConfig.walkerTangentAim && !launch && aim2 >= aimDeadzone
                && path != null && step < path.size()
                && path.get(step).getY() <= foot.getY()) {
            targetYaw = arcProj.tangentYaw;
            // walkerWallCornerNodeAim: the tangent steers along the path TREND, but at a CORNER where the
            // immediate node sits well off the tangent AND a wall is on the tangent heading, the body RAMS
            // the wall (horizontalCollision) instead of turning the corner toward the node — it then only
            // creeps across as drift sweeps the geometry (live dry-627 start: yaw frozen 91° / node bearing
            // 122° / hCol=true / 350-tick churn, the "贴墙卡住" signature). When ramming with the node well
            // off the tangent, yield back to the DIRECT node bearing so the body turns off the wall onto the
            // node. Gated on hCol so a clean trend-cruise (no wall) keeps the bob-immune tangent unchanged.
            if (BotConfig.walkerWallCornerNodeAim && p.horizontalCollision) {
                BlockPos wn3 = path.get(step);
                float nodeBear = (float) Math.toDegrees(Math.atan2(
                        -((wn3.getX() + 0.5) - p.getX()), (wn3.getZ() + 0.5) - p.getZ()));
                if (Math.abs(angleDiff(arcProj.tangentYaw, nodeBear)) > WALL_CORNER_AIM_DEG)
                    targetYaw = nodeBear;
            }
        }
        // ── Overland camera/movement decouple (anti-spin) — see DESCENT_CAM_FAR_DIST ─────────
        // Point the CAMERA at a stable trend heading (no spin) while the MOVEMENT keeps driving the
        // immediate node (driveTargetYaw, below). Capture the immediate-node heading (the carrot/node
        // bearing) BEFORE re-aiming the camera at the trend — that captured heading drives the body.
        float descentNodeYaw = targetYaw;
        // GENERALISED from descents to FLAT + descending dry overland travel: a live 2026-06-21
        // winding-by-bucket diagnosis showed the trend camera had ALREADY smoothed descending nodes
        // (dryDesc=true: 2.5 turns over the journey) but 90% of the residual spin sat in the dry
        // NON-descending nodes (flat + ascending steps on the same hill, 7.8 turns) where the decouple
        // wasn't engaging — a switchback's flat legs swing the per-node bearing exactly like its down
        // legs. Driving the body off the captured node heading keeps navigation byte-identical; only
        // the camera is trend-averaged. ASCENDING (wp.y > foot.y) is EXCLUDED: a dig+climb-mount or
        // pillar-up needs the exact target heading, and trend-aiming it regressed tallbankdigclimb
        // (the bot couldn't mount the bank).
        // Launches ride descentDecoupleLaunches (ascending leaps slew safely via commandMove).
        boolean dryDescent = BotConfig.descentCameraDecouple && !p.isInWater() && !steppingOffWaterFall
                && ( (!launch && wp.getY() <= foot.getY())                 // flat or descending walk
                   || (launch && BotConfig.descentDecoupleLaunches) );     // any dry launch
        // Water 摇头 (head-shake) fix: extend the trend camera to FLAT water swimming. A buoyant bot
        // crossing open water swims slowly (~1.4 b/s); near a waypoint the immediate-node bearing flips
        // ±170°/tick and the camera — coupled to it in water — swings, the visible head-shake (live
        // 2026-06-21 crossing: camera mean|dyaw|=3°/tick with ±170° driveYaw flips in the slow bursts).
        // Averaging the look-ahead window into a steady trend kills it, exactly as on dry land. EXCLUDED:
        // climb-out (wp above foot — the bank mount needs the exact column heading) and swimDown dives
        // (they aim precisely), so the water-climb / dive arenas stay byte-unchanged. Unlike dry land the
        // body stays COUPLED to the camera trend (driveTargetYaw=aimYaw in water, below): on an open
        // crossing swimming toward the trend is correct, whereas driving the raw flipping node would
        // re-introduce a swim-back-and-forth.
        // The Y gate is bob-tolerant (+1): a buoyant body bobs foot y±1 against a surface node, so a
        // strict wp.y <= foot.y FLICKERS the trend on/off each bob and the camera still snaps to the
        // flipping node on the off-ticks (live: yaw==driveYaw on alternating ticks). +1 keeps the trend
        // latched across the bob while still excluding a real climb-OUT (+2 bank); a +1 node it might
        // include is harmless — buoyantClimbPress (below) drives that mount off the column regardless.
        // isInWater is LATCHED across the surface bob: at the bob apex the foot lifts out of the
        // fluid for ~1 tick (isInWater false) though the bot is still swimming the open crossing.
        // Hold "in water" for WATER_SURFACE_LATCH_TICKS after the blink so the trend (and the
        // smoothed water drive) survives it instead of snapping to aimYaw for one tick.
        if (p.isInWater()) surfaceWaterLatch = WATER_SURFACE_LATCH_TICKS;
        else if (surfaceWaterLatch > 0) surfaceWaterLatch--;
        boolean inWaterLatched = p.isInWater() || surfaceWaterLatch > 0;
        boolean flatWaterTrend = BotConfig.descentCameraDecouple && inWaterLatched && !launch
                && wp.getY() <= foot.getY() + 1
                && !(edge != null && edge.move != null && edge.move.startsWith("swimDown"));
        boolean trendCam = dryDescent || flatWaterTrend;
        // Smoothed water DRIVE: the raw immediate-node bearing flips ±180° when the slow buoyant body
        // overshoots a node, so driving it raw makes the body swim-wobble (live: 52% path efficiency,
        // "突然转身背离目标"). A light EMA damps the per-tick flip while still tracking the node. WATER
        // ONLY: extending this EMA to dry descent was A/B-DISPROVEN in descentYawArena (raw backSteps=42
        // winding=211° → ema backSteps=54 winding=370° — the dry body has traction and needs the precise
        // node bearing; lagging it makes it overshoot/correct MORE). Dry back-hop's real fix is a
        // step-pointer advance, not drive-smoothing (override was also disproven — both wedge/worsen).
        if (flatWaterTrend) {
            // DRIVE the path-following CARROT (CARROT_DIST ahead), not the raw immediate-node bearing.
            // A slow buoyant body drifts off-axis, and the immediate node's bearing rotates faster the
            // closer it gets — within ~2 blocks it sweeps and flips ±180° as the body crosses it, so
            // the EMA still swings ±55°/tick and the swim wobbles/crawls (live: open-water hSpd
            // collapses 0.078→0.02 at every node, the "绕node打转" churn). The carrot is a STABLE far
            // heading (small angular sensitivity) that still ROUNDS corners (it walks the path) and
            // STOPS at a wall (carrotPoint's losWalkable break) — so it can't ram a divider the way
            // driving the far CENTROID did (waterFarAimBankCorner).
            // Bob-stable LOS foot: an UP-bob lifts foot.y into the air block ABOVE the water surface,
            // where carrotPoint's per-cell losWalkable (needs floor-solid / water / climbable) fails on
            // every sample and collapses the carrot onto the body — the drive then loses its forward
            // heading and the swim stalls (live -1676/-1678: carrot dead, driveYaw frozen, totStuck
            // 200+). Clamp the ray's foot DOWN to the current node's surface level so it samples water,
            // not the bob's air gap.
            BlockPos losFoot = foot.getY() > wp.getY()
                    ? new BlockPos(foot.getX(), wp.getY(), foot.getZ()) : foot;
            double[] cp = carrotPoint(world, losFoot, p.getX(), p.getZ());
            double cdx = cp[0] - p.getX(), cdz = cp[1] - p.getZ();
            if (BotConfig.walkerDebug && cdx * cdx + cdz * cdz <= 1.0)
                LOG.info("[walker] carrot-collapse foot={} wp.y={} posY={} losRaw={} losClamp={}",
                        foot.getY(), wp.getY(), String.format("%.2f", p.getY()),
                        losWalkable(world, foot, wp), losWalkable(world, losFoot, wp));
            // Drive source: the carrot ahead, or — when it has collapsed onto the body (a wall / path
            // end) — the immediate node bearing as a fallback.
            float driveSrcYaw = (cdx * cdx + cdz * cdz > 1.0)
                    ? (float) Math.toDegrees(Math.atan2(-cdx, cdz)) : descentNodeYaw;
            if (Float.isNaN(smoothWaterDriveYaw)) {
                smoothWaterDriveYaw = driveSrcYaw;
                waterDriveRejectStreak = 0;
            } else {
                float turn = angleDiff(smoothWaterDriveYaw, driveSrcYaw);
                if (Math.abs(turn) <= WATER_DRIVE_MAX_TURN) {
                    // Gradual (real) turn — track it; the EMA damps the carrot's re-plan / wall jumps.
                    smoothWaterDriveYaw = angleDiff(0f, smoothWaterDriveYaw + WATER_DRIVE_ALPHA * turn);
                    waterDriveRejectStreak = 0;
                } else if (++waterDriveRejectStreak > WATER_DRIVE_MAX_REJECT) {
                    // Persistent reversal — not a transient flip. Snap so the swim can't strand itself
                    // pointing the wrong way (see WATER_DRIVE_MAX_REJECT).
                    smoothWaterDriveYaw = driveSrcYaw;
                    waterDriveRejectStreak = 0;
                }
                // else: reject this tick's flip — HOLD the forward heading.
            }
        } else {
            smoothWaterDriveYaw = Float.NaN;
        }
        if (trendCam) {
            // Aim the CAMERA at the CENTROID of the lookahead window (see DESCENT_CAM_LOOKAHEAD):
            // a switchback staircase's alternating cardinal legs average to the steady down-slope
            // trend, so the heading holds instead of chasing the ±50° per-step zigzag (the spin).
            // MOVEMENT stays on the immediate node via driveTargetYaw=descentNodeYaw below.
            // CENTROID of the look-ahead window: averaging EVERY node in the window cancels a
            // switchback's alternating legs into the steady down-slope trend. (A chord/far-node
            // samples only an endpoint, which itself lands on alternating legs and swings; the
            // full average does not.) MOVEMENT stays on the immediate node via driveTargetYaw below.
            double sumX = 0, sumZ = 0; int cnt = 0;
            int lastNode = Math.min(step + DESCENT_CAM_LOOKAHEAD, path.size() - 1);
            int firstNode = (lastNode - step >= 4) ? step + 2 : step;   // skip the at-foot nodes
            for (int k = firstNode; k <= lastNode; k++) { sumX += path.get(k).getX() + 0.5; sumZ += path.get(k).getZ() + 0.5; cnt++; }
            if (cnt > 0) {
                double mdx = sumX / cnt - p.getX(), mdz = sumZ / cnt - p.getZ();
                if (mdx * mdx + mdz * mdz > 1.0)
                    targetYaw = (float) Math.toDegrees(Math.atan2(-mdx, mdz));
            }
        }
        // Low-pass the TARGET heading (EMA on the shortest angle, kept in [-180,180]) so a
        // grid staircase on a diagonal — whose immediate-waypoint bearing alternates ±~30°
        // around the true diagonal each step — averages to a steady bearing instead of a
        // bounded sawtooth (see YAW_SMOOTH_ALPHA). Resync on launch / first use — EXCEPT a
        // decoupled descending launch (dryDescent), which must NOT resync/snap: the leap is
        // drive-decoupled (driveTargetYaw=node), so the camera can keep its continuous EMA and
        // SLEW smoothly through the turn over the airborne ticks instead of snapping ~180° (the
        // 下落转圈). Snapping stays for ascending leaps / water-falls where the leap aims via yaw.
        boolean snapLaunch = launch && !dryDescent;
        if (Float.isNaN(smoothTargetYaw) || snapLaunch) {
            smoothTargetYaw = targetYaw;
        } else {
            float alpha = trendCam ? YAW_SMOOTH_ALPHA_DESCENT : YAW_SMOOTH_ALPHA;
            smoothTargetYaw = angleDiff(0f, smoothTargetYaw + alpha * angleDiff(smoothTargetYaw, targetYaw));
        }
        float aimYaw = snapLaunch ? targetYaw : smoothTargetYaw;
        // ── 原地后跳 (in-place backward hop) fix ───────────────────────────────────────────────
        // The decoupled descent drive rides the IMMEDIATE node (descentNodeYaw). When the bot
        // OVERSHOOTS that node on a fall landing or a step (lands a hair past it), the node is now
        // BEHIND the body, so its bearing flips ~180° and the drive reverses — the body hops
        // backward INTO the node, overshoots again, and oscillates. (Live 2026-06-21 telemetry: at a
        // fall2 landing descentNodeYaw flipped 177°↔-5° while the camera held steady at -5°, so the
        // body hopped back/forth in place — the spin used to MASK this, but the now-steady trend
        // camera exposes it as a visible backward jump.) When the captured node lies sharply behind
        // the steady trend heading (aimYaw = the look-ahead centroid, which already points along the
        // path), drive ALONG the trend instead of reversing: the body keeps moving forward through
        // the overshot node, and the step pointer advances via the normal overshoot re-sync. Only
        // a >120° gap counts as an overshoot — a switchback's legs sit ~±50° off the trend, so
        // (A drive-override here — descentNodeYaw = aimYaw on a behind+close node — was tried and
        // REVERTED: overriding the heading breaks node-following and self-amplifies into a descent
        // wedge (descentYawArena onSlope 226→700, reached=false, under every gate incl. a stall gate,
        // because firing increases noStepProgressTicks which keeps it firing). The correct fix is a
        // step-pointer OVERSHOOT-ADVANCE in the re-sync block, which keeps the drive on REAL nodes.)
        // ANTI-SPIN camera freeze: while churning in water (consecutive repaths with no
        // goal-progress — a failed climb-out whose best-effort segments keep flipping
        // the waypoint behind the bot), HOLD the heading instead of chasing the
        // flipping target. A ~180° flip resolves the same rotational way each time, so
        // chasing it winds the camera one direction (raw yaw past -900 ≈ 2.5 turns in
        // the trace — the water "转圈"). Freezing stops the wind AND, since MC movement
        // follows body yaw, steadies the bot pressing one direction toward the climb-
        // out rather than U-turning. Launches still snap. Cleared as soon as progress
        // resumes (repathsNoProgress resets). */
        // Target-stability gate: the freeze must catch a FLIPPING target (chasing a ~180°
        // per-repath swing winds the camera) but NOT a STABLE one (the capped slew converges
        // to it once and stops — no wind). Freezing a stable-but-wrong heading is the
        // badlands-basin deadlock: heldYaw frozen pressing a bank for 500+ ticks while a
        // steady bearing pointed ~150° off and the foot never moved. Count ticks the smoothed
        // aim barely moved; a flip resets it, so the freeze re-arms instantly on the next
        // swing yet releases once the target has been steady ~0.5 s, letting the bot turn.
        if (!Float.isNaN(lastAimYaw) && Math.abs(angleDiff(aimYaw, lastAimYaw)) < AIM_STABLE_DEG)
            aimStableTicks = Math.min(aimStableTicks + 1, AIM_STABLE_TICKS + 1);
        else
            aimStableTicks = 0;
        lastAimYaw = aimYaw;
        boolean targetFlipping = aimStableTicks < AIM_STABLE_TICKS;
        // The anti-spin freeze stays WATER-gated: a dry-land extension (to catch the dry-churn
        // cliff-stall spin) spuriously engaged during a slow dry pillar-up — the goal-XZ barely
        // moves while pillaring, so repathsNoProgress climbs and the placement aim flips, tripping
        // the freeze and pinning the heading off the column (regressed summitarena). The dry-churn
        // spin is rarer (only on an actual nav stall) and better fixed at the stall itself than by
        // freezing the camera here, so keep the overWater scope.
        boolean overWater = p.isInWater() || world.isWater(foot.offset(0, -1, 0));
        boolean spinFreeze = !launch && overWater && repathsNoProgress > CHURN_REPATH_CAP && targetFlipping;
        if (!spinFreeze && Math.abs(angleDiff(p.getYRot(), aimYaw)) > BotConfig.walkerYawHysteresisDeg) {
            float ny;
            if (snapLaunch) {
                ny = aimYaw;   // launches must snap — no mid-air course correction (decoupled
                LookController.requestSnap();   // descending leaps slew instead — see snapLaunch)
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
        // Bob-immune ram counter (live #47 R2: -706,85 dry stepUp + -723,63 shallow-water bank, both
        // ~16-24s creep): the noStepProgressTicks gate below is reset by the vertical bob toward an
        // above-node (line ~1506, wd2 includes wdy), so a misaligned/shallow step creeps for seconds
        // before stepUpFreeze ever fires. This counter ticks ONLY on a GROUNDED horizontal collision
        // against an above-node (the actual ram) → immune to the bob, so the freeze-breaker engages
        // on time for BOTH dry and shallow-water banks (the dryStepUp ascendJumpReady path is water-
        // excluded, and waterClimbing's pillar takeover needs !onGround so it misses the shallow bank).
        if (p.horizontalCollision && p.onGround() && wp.getY() > foot.getY()) stepRamStuckTicks++;
        else stepRamStuckTicks = 0;
        // Bob-immune ascent-ram (walkerAscentRamBobBreak): the steep-bank +1 mount that jumps off the diagonal
        // corner & slides back keeps the foot >0.3 BELOW the node while laterally close, but its airborne apex
        // zeroes BOTH stepRamStuckTicks (onGround-gated) and noStepProgressTicks (3D new-low). Tick a counter
        // that ignores onGround so stepUpFreeze engages on time; a clean climb tops out before the bar.
        // NOTE: this gate is bob-RESETTABLE by design (a big-bob apex crossing the node Y zeroes it) — that is
        // a SAFETY feature: on a genuinely-unwinnable +2 mount the bob keeps it under the bar so stepUpFreeze
        // does NOT pin the bot (a bob-IMMUNE v2 variant over-pinned ~157t on unwinnable mounts in live A/B — reverted).
        boolean ascentNotTopped = wp.getY() > foot.getY() && p.getY() < wp.getY() - 0.3
                && !parkourEdge && !p.isInWater()
                && (stepColDx * stepColDx + stepColDz * stepColDz) < 1.6;
        if (BotConfig.walkerAscentRamBobBreak && ascentNotTopped) ascentRamBobTicks++;
        else ascentRamBobTicks = 0;
        // FLOATING water-bank bob (walkerFloatingBankBobFreeze): a buoyant bot at a +1..+3 water bank bobs
        // y(water)↔(air) every 2-3 t with onGround NEVER true, alternating stepUp/climbUp at the riser but
        // frozen in XZ. All other freeze counters miss it: stepRamStuck/shallowBank need onGround (floating
        // has none), ascentRamBob's !isInWater zeroes on each water-dip, and noStepProgress is zeroed by the
        // 3D bob. Use a STABLE water-bank indicator (water at the foot or just below — true through the WHOLE
        // bob so the down-phase does NOT reset the counter) + !onGround + a lateral riser-ram + node-above
        // (cap +3). Restricted to a WATER bank (isWater) so a DRY unwinnable +2 mount is never pinned (the
        // ascentRam-v2 over-pin regression); an unwinnable water bank simply repaths. Fed into stepUpFreeze
        // past a 2× bar below.
        boolean atWaterBank = world.isWater(foot) || world.isWater(foot.below());
        boolean floatingBankNotTopped = atWaterBank && !p.onGround()
                && wp.getY() > foot.getY() && (wp.getY() - foot.getY()) <= 3
                && (stepColDx * stepColDx + stepColDz * stepColDz) < 1.6;
        if (BotConfig.walkerFloatingBankBobFreeze && floatingBankNotTopped) floatingBankBobTicks++;
        else floatingBankBobTicks = 0;
        // Lateral-bank-follow (walkerFloatingBankFollow): a FLOATING bot ramming a water bank at
        // ANY node-Y — including the walk-ram facet the ascending freeze counter above misses (node
        // AT/BELOW the foot across a 1-block lip). The dominant residual is NON-DETERMINISTIC: the
        // same route lands the buoyant approach on a mountable spot (~0s, steps up) OR a dig-required
        // spot (~30s underwater dig, replay-proven 0s/0s/33s). Count sustained floating-water-rams to
        // drive a SLIDE ALONG the bank (perpendicular strafe, see the lane-keep block) so the body
        // sweeps to the nearest mountable exit instead of grinding/digging the dead spot. Self-
        // terminating: any climb-out progress drops onGround/hCol → counter resets → normal mount.
        boolean bankFollowRam = atWaterBank && !p.onGround() && p.horizontalCollision;
        if (BotConfig.walkerFloatingBankFollow && bankFollowRam) bankFollowRamTicks++;
        else bankFollowRamTicks = 0;
        boolean stepUpFreeze = wp.getY() > foot.getY() && !parkourEdge
                && (!p.isInWater() || shallowBankStep
                    || (BotConfig.walkerFloatingBankBobFreeze && floatingBankBobTicks > 2 * STEPUP_FREEZE_TICKS))
                && (noStepProgressTicks > STEPUP_FREEZE_TICKS || stepRamStuckTicks > STEPUP_FREEZE_TICKS
                    || ascentRamBobTicks > STEPUP_FREEZE_TICKS
                    || floatingBankBobTicks > 2 * STEPUP_FREEZE_TICKS)
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
        // The DESCENDING mirror of diagUp: a diagonal step DOWN (both axes change AND falling).
        // The lane-keep strafe below centres waterClimb/diagUp/cardinal lanes but NOT diagDown,
        // so a diagDown got NO lateral correction — the bot drifts off the diagonal descend line,
        // ends ~0.8 off on one axis, and RAMS the perpendicular corner block (hCol=true, |yawErr|
        // large because descentNodeYaw points at the close node whose bearing swings on drift):
        // the close diagDown-ram wedge (live -671, REGRESSION.md §26 — anchor-back re-aim made it
        // OSCILLATE worse, totStuck 6009). A lateral centre (NOT an aim change) pulls the body back
        // onto the diagonal line so the corner clears and forward drives through. Mirror of 4262.
        boolean diagDown = upDy <= -1 && wp.getX() != foot.getX() && wp.getZ() != foot.getZ()
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
        // holdPillarBlock (not world.canPlace): pillar-recover places on the solid rung below the
        // grounded foot, so supported sand/gravel work here (see fellBelowRoute note above).
        // SLOW DIAGUP → PILLAR-UP (steep-diagonal stutter): a planned +1 diagUp that bob-rams the
        // riser past DIAGUP_PILLAR_TICKS (the √2 jump-traverse ~47 ticks/step sawtooth) escalates to
        // a deterministic pillar — place a support under the foot, jump +1, walk the now-level node —
        // via the SAME actuator as overJump/fellBelowRoute (no new placement code). Scoped hard: a
        // true +1 diagUp by planner intent AND geometry, grounded, dry, blocks in hand, slower than
        // the freeze-breaker's first attempt; dy==1 keeps it disjoint from the ≥2 ascentRamSlide.
        boolean slowDiagUpPillar = "diagUp".equals(edge != null ? edge.move : null)
                && diagUp && upDy == 1 && p.onGround() && !p.isInWater()
                && noStepProgressTicks > DIAGUP_PILLAR_TICKS;
        if ((overJump || slowDiagUpPillar) && BotConfig.allowPlace && a.holdPillarBlock()) {
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
        // NOTE: extending the cardinal sprint-bunny-hop to DIAGONAL step-ups was tried + A/B-
        // DISPROVEN here (2026-06-20) via diagonalAscentSpeedArena: forcing sprint on a diagonal
        // climb RAMS the riser CORNER (two perpendicular faces) — sprint% 48→94 dropped diagBps
        // 3.04→2.71 and raised hcol 7→12; jumping √2-earlier was worse still (2.95, hcol 22, and
        // it broke tallBankDigClimb's diagonal dig-staircase). The partial-sprint 3.04 b/s (≈71%
        // of walk) baseline is the achievable limit under the corner-ram constraint; the camera
        // (yaw) is already steadied by the STAIR_TREND_LOOKAHEAD path-trend aim. Left cardinalUp-
        // only on purpose. The diagonalAscentSpeedArena guards that 3.04 baseline from regressing.
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
        // Lateral-bank-follow active once the floating-water-ram has been sustained past the freeze
        // bar (2× STEPUP_FREEZE_TICKS, same threshold as the ascending freeze). Drives a perpendicular
        // slide along the bank regardless of node-Y (catches the walk-ram facet too).
        // YIELD to apw: a DRY diagUp limit cycle (live replay-0006 -818) is a large vertical oscillation that dips into
        // a water cell at its bottom, transiently tripping atWaterBank → bankFollow HIJACKS the apw/FBA recovery and
        // drags the bot down a non-existent "bank exit" (FBA+apw 1530 → +FloatingBankFollow 2198). When apw is the
        // active stall owner (its arcProgStall has fired), defer to it — apw's repath resolves the cycle, and a GENUINE
        // water bank with apw OFF is unaffected (the && walkerArcProgressWedge guard).
        boolean bankFollow = BotConfig.walkerFloatingBankFollow && bankFollowRamTicks > 2 * STEPUP_FREEZE_TICKS
                && !(BotConfig.walkerArcProgressWedge && arcProgStall);
        if (!descendBrake && !parkourEdge && !steppingOffFall && (wp.getY() == foot.getY() || waterClimb || cardinalUp || diagUp || bankFollow)) {
            int ddx = wp.getX() - foot.getX();
            int ddz = wp.getZ() - foot.getZ();
            double latX = 0, latZ = 0;
            if (bankFollow) {
                // Geometry-aware CONVERGENT slide (v2): the chaotic alternating sweep (v1) sometimes slid
                // PAST the goal and churned (live A/B: ON#2 ran to -654, worse than OFF). Instead, SCAN
                // along the bank for the nearest cell the bot can actually mount — a canStandAt lip at
                // foot.y (flat exit) or foot.y+1 (a +1 step) — and steer deterministically toward it. The
                // bank NORMAL ≈ the dominant cardinal toward the node (the face being rammed); the bank
                // runs perpendicular, so probe ±BANK_FOLLOW_SCAN cells along that perpendicular. Nearest
                // mountable lip wins; forward drive (untouched) mounts it the moment the body lines up.
                // No mountable lip in range → leave lat 0 so the existing bank-dig/repath recovery runs.
                int ndx = wp.getX() - foot.getX(), ndz = wp.getZ() - foot.getZ();
                if (Math.abs(ndx) >= Math.abs(ndz)) { ndx = Integer.signum(ndx); ndz = 0; }
                else { ndz = Integer.signum(ndz); ndx = 0; }
                int pdx = -ndz, pdz = ndx;                 // along-bank perpendicular unit
                int bestK = 0;
                for (int k = 1; k <= BANK_FOLLOW_SCAN && bestK == 0; k++) {
                    for (int s = -1; s <= 1 && bestK == 0; s += 2) {
                        int kk = k * s;
                        BlockPos flat = foot.offset(ndx + pdx * kk, 0, ndz + pdz * kk);  // same-level exit
                        BlockPos lip = foot.offset(ndx + pdx * kk, 1, ndz + pdz * kk);   // +1 step lip
                        if (world.canStandAt(flat) || world.canStandAt(lip)) bestK = kk;
                    }
                }
                if (bestK != 0) {
                    int dir = Integer.signum(bestK);
                    latX = pdx * dir; latZ = pdz * dir;    // steer along the bank toward the mountable lip
                }
                if (BotConfig.walkerDebug && bankFollowRamTicks % 20 == 0)
                    LOG.info("[walker] bank-follow scan foot={} normal=({},{}) bestK={} ramT={} → {}",
                            foot, ndx, ndz, bestK, bankFollowRamTicks,
                            bestK != 0 ? "STEER to lip" : "NO LIP in range (dig/repath)");
            }
            else if (waterClimb) { latX = (wp.getX() + 0.5) - p.getX(); latZ = (wp.getZ() + 0.5) - p.getZ(); } // centre on the target column
            else if (diagUp) { latX = (wp.getX() + 0.5) - p.getX(); latZ = (wp.getZ() + 0.5) - p.getZ(); } // centre on the diagonal toward the step corner
            else if (BotConfig.walkerDiagDownCenter && diagDown) { latX = (wp.getX() + 0.5) - p.getX(); latZ = (wp.getZ() + 0.5) - p.getZ(); } // §26: mirror diagUp — centre on the diagonal descend line so the body doesn't ram the perpendicular corner
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
        // is byte-identical; only the slew transient changes. Special branches above return
        // before here, so they keep their own key-based actuation (AgentInput falls back to
        // keys uncommanded).
        //
        // The DRIVE always targets aimYaw — even while the camera is frozen by the anti-wind
        // spinFreeze. The freeze exists ONLY to stop the visual 转圈 (camera chasing a ~180°-
        // flipping target winds one way); it must NOT also freeze the body's travel. The old
        // `spinFreeze ? 0` here drove the body along the STALE frozen camera heading — in a
        // badlands water basin that heading pointed straight at the bank, so the body rammed it
        // for 500+ ticks while a steady bearing pointed ~150° off (the 18-min near-deadlock).
        // Decoupling them lets the body crab toward the waypoint (Δ = aimYaw − cameraYaw) and
        // make net progress, which resets repathsNoProgress and releases the freeze on its own,
        // so the vicious cycle (frozen drive → no progress → freeze stays frozen) can't form.
        double driveF = (!descendBrake && !pivotForStepUp) ? 1.0 : 0.0;
        double driveL = strafeL ? 1.0 : (strafeR ? -1.0 : 0.0);
        // Drive heading: normally aimYaw (decoupled from the slewing camera). EXCEPTION —
        // a BUOYANT slope-mount. A floating bot at a +1/+2 bank top bobs UP to the bank
        // height but, with forward zeroed by pivotForStepUp (aim not yet aligned) and the
        // drive pointed at a CHURNING aimYaw, never translates ONTO the ledge — it falls
        // back and bob-stalls (live 2026-06-20 sand slope: py peaked 64.17 at the y64 bank
        // with z frozen at 3572.30, then the dig/anti-stuck burst kicked in, ~30 s + 镜头甩).
        // The whole shoreline is a deep-water-base slope (planner can't route around it), so
        // the mount MUST be actuated: press forward HARD and STEADILY straight at the target
        // column (not the oscillating aimYaw), so vanilla's swim-step carries the bob peak up
        // the +1. Gated tight — floating, climbing (wp above foot), laterally ON the column —
        // so flat water travel and dry steps are byte-unchanged. (Special climb-out takeovers
        // — pillar / dig — return before here, so this only drives the plain-stepUp bob.)
        // Trend camera (dry descent OR flat water swim): the camera faces the far trend heading
        // (above) but the body must still drive the IMMEDIATE node so it follows the path step by
        // step — keep movement on the captured node heading. In WATER this decouple is essential, not
        // just polish: coupling the drive to the far trend made the body swim straight at the centroid
        // THROUGH a divider and ram it (waterFarAimBankCorner regressed); driving the immediate node
        // rounds the corner while the camera trend still kills the head-shake.
        // Phase-2 (walkerTangentAim): drive the body along the smoothed path tangent (aimYaw, which Edit A
        // above set to the tangent) in ALL cases — including water, where the legacy smoothWaterDriveYaw EMA
        // is a separate node-following heading that the tangent supersedes. The flip-rejection block below is
        // skipped when this is on (the tangent already never reverses, so there is no back-hop to reject).
        float driveTargetYaw = BotConfig.walkerTangentAim ? aimYaw
                : flatWaterTrend ? smoothWaterDriveYaw
                : trendCam ? descentNodeYaw : aimYaw;
        // Dry diagDown SLOPE back-hop damping (the visible "雪山横跳" jitter). On a continuous
        // diagonal descent the decoupled drive rides the IMMEDIATE node (descentNodeYaw); each time
        // the foot overshoots that node it sits BEHIND the body and the bearing reverses ~180°, so
        // the drive hops backward for a few ticks until `passed` advances the pointer. The discrete
        // overshoot-advance above is gated OFF for diagDown (eager-advancing a slope regressed
        // descentYawArena backSteps 42→71), and a plain heading-override was reverted because it had
        // no escape and self-amplified into a wedge. Port the WATER drive's flip-rejection+ESCAPE:
        // when the node bearing points sharply behind the steady trend (aimYaw, the look-ahead
        // centroid that already aims down-path), hold the trend so the body keeps descending forward,
        // but SNAP back to the real node after WATER_DRIVE_MAX_REJECT ticks so node-following always
        // re-syncs (a true switchback leg sits ~±50° off trend < the 120° gate, so only a real
        // overshoot trips this). dryDescent-only; water/discrete keep their own handling.
        if (!BotConfig.walkerTangentAim && dryDescent && !flatWaterTrend && edge != null && edge.move != null
                // parkourDescend is a diagonal descender too: a parkourDescend staircase suffers the
                // SAME overshoot back-hop (live 2026-06-24 combined journey at -813,71: a
                // parkourDescend2d1 chain, yaw/driveYaw flipped +33→-103 on each overshoot → ~65 t
                // bob-jump zigzag). trendCam/aimYaw already give it the centroid trend (dryDescent==true);
                // only this flip-rejection gate excluded it, so the drive kept chasing the flipping node.
                // ...and fall / stepDown edges off a ledge: same overshoot back-hop. Live 2026-06-24
                // journey4 at -857,70 (move=fall4, bot overshot a stepUp up to y75 then the path falls
                // back to y71): FREEZE-DIAG showed dTgtYaw flip -148↔+32 with fwdComp +1↔-1 (the drive
                // ran FORWARD then BACKWARD = the "moving-backward jump"/略微后退), while camYaw held
                // steady at -148 — i.e. the camera trend was fine, only the drive chased the flipping
                // below-node bearing. Holding the trend lets it commit forward off the edge and drop.
                && (edge.move.startsWith("diagDown") || edge.move.startsWith("parkourDescend")
                    || edge.move.startsWith("fall") || edge.move.startsWith("stepDown"))
                && Math.abs(angleDiff(aimYaw, descentNodeYaw)) > WATER_DRIVE_MAX_TURN) {
            // DISCRETE drop (fall/stepDown): the backward escape pushes a bot that landed 1-above /
            // short-of the node AWAY from it (cur2 grows, the visible 180° back-hop). Hold the trend
            // continuously so it keeps closing on the node; the position-based pure-pursuit advance +
            // safetyRepath cover the strand the escape guarded against. A continuous slope keeps the
            // bounded escape (it rides the immediate node for trend smoothing and could strand). */
            boolean discreteDrop = BotConfig.walkerDescentFlipHold
                    && (edge.move.startsWith("fall") || edge.move.startsWith("stepDown"));
            if (discreteDrop || ++descentDriveRejectStreak <= WATER_DRIVE_MAX_REJECT) {
                driveTargetYaw = aimYaw;
            } else {
                descentDriveRejectStreak = 0;   // escape: drive the real node this tick to re-sync
            }
        } else {
            descentDriveRejectStreak = 0;
        }
        boolean rawClimbPress = p.isInWater() && !p.onGround() && !parkourEdge
                && wp.getY() > foot.getY()
                && (stepColDx * stepColDx + stepColDz * stepColDz) < 2.5;
        // Debounce the surface bob (see CLIMB_PRESS_DEBOUNCE): only a PERSISTENT wp-above-foot is a
        // real bank mount; a 1-tick down-bob under a same-level surface node is not.
        climbPressConsec = rawClimbPress ? climbPressConsec + 1 : 0;
        boolean buoyantClimbPress = rawClimbPress && climbPressConsec >= CLIMB_PRESS_DEBOUNCE;
        if (buoyantClimbPress) {
            driveF = 1.0;
            driveL = 0.0;
            driveTargetYaw = (float) Math.toDegrees(Math.atan2(-stepColDx, stepColDz));
        }
        // Near-goal DISK CLOSURE. On the final node of a radius>0 disk goal with the foot still
        // OUTSIDE the disk, the immediate drive bearing is the very-close final node — for a floating
        // bot that bearing collapses/bobs and the body freezes a block short of the radius (live
        // 2026-06-23 XZ -1700,900 r6: pinned at foot dist 7, never closed). The disk CENTRE is still
        // 6-7 blocks away, so its bearing is STABLE (no collapse). Drive straight at the centre to
        // close the last fraction INTO the disk, where the goal.reached check at the top of step()
        // fires for real. radius>0 only (exact-cell / radius-0 water arrivals unchanged).
        if (step + 1 >= path.size() && !goal.reached(foot)) {
            int gcx = Integer.MIN_VALUE, gcz = 0;
            if (goal instanceof Goal.XZ xz && xz.radius() > 0) { gcx = xz.x(); gcz = xz.z(); }
            else if (goal instanceof Goal.Near nr && nr.radius() > 0) {
                gcx = nr.target().getX(); gcz = nr.target().getZ();
            }
            if (gcx != Integer.MIN_VALUE) {
                double gdx = (gcx + 0.5) - p.getX(), gdz = (gcz + 0.5) - p.getZ();
                if (gdx * gdx + gdz * gdz > 0.5) {            // not already essentially on centre
                    driveTargetYaw = (float) Math.toDegrees(Math.atan2(-gdx, gdz));
                    driveF = 1.0;
                    driveL = 0.0;
                }
            }
        }
        double driveDelta = Math.toRadians(angleDiff(p.getYRot(), driveTargetYaw));
        double driveCos = Math.cos(driveDelta), driveSin = Math.sin(driveDelta);
        a.commandMove(
                (float) (driveL * driveCos - driveF * driveSin),
                (float) (driveL * driveSin + driveF * driveCos));
        // TEMP FREEZE-DIAG (approach-freeze / pit-cascade phase-3 investigation): capture the exact
        // drive state when a dryDescent stalls (stuckTicks high) — which steering branch + driveF +
        // forward component + brakes — so the cur2-frozen hSpd~0 stall mechanism is identified, not guessed.
        if (BotConfig.walkerDebug && dryDescent && stuckTicks > 18) {
            double fwdComp = driveL * driveSin + driveF * driveCos;
            LOG.info("[walker] FREEZE-DIAG stuckT={} driveF={} fwdComp={} ddeg={} dTgtYaw={} camYaw={} aimAtWp={} reCentre={} pivotSU={} descBrake={} stepUpFreeze={} sneak={} sprint={} wpY-footY={} stepCol2={}",
                    stuckTicks, String.format(Locale.ROOT, "%.2f", driveF),
                    String.format(Locale.ROOT, "%.2f", fwdComp),
                    String.format(Locale.ROOT, "%.0f", Math.toDegrees(driveDelta)),
                    String.format(Locale.ROOT, "%.0f", driveTargetYaw),
                    String.format(Locale.ROOT, "%.0f", p.getYRot()),
                    aimAtWaypoint, reCentre, pivotForStepUp, descendBrake, stepUpFreeze,
                    a.dbgSneak(), p.isSprinting(), (wp.getY() - foot.getY()),
                    String.format(Locale.ROOT, "%.2f", (stepColDx * stepColDx + stepColDz * stepColDz)));
        }
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
        // Steep-descent SPRINT brake (NOT a sneak-pin): a survivable-but-deep drop in an
        // edge-neighbour while descending is invisible to lethalNear (its threshold is
        // survivableFall ≈ 23 blk, even higher with resist buffs), so the bot SPRINTS a
        // diagDown/step-down and the momentum drifts it OFF the lip off-path into a 13-19
        // blk fall + 80 t fellOffPath deadlock (live 2026-06-24 random journey at -679,83:
        // sprinted a diagDown, drifted -680→-677 off a 12-drop). Drop sprint when a drop
        // deeper than a normal step (>4 blk) sits adjacent during a planned descent so the
        // body decelerates onto the node instead of overshooting the lip; sneak stays
        // released (a survivable drop needn't pin the body, so the step-down still proceeds).
        boolean steepDescentNear = p.onGround() && plannedDescent
                && dropAdjacentExceeds(world, foot, 4);
        // DEEP-WATER drift SPRINT brake (mechanism b, sibling of steepDescentNear for a water
        // hazard): a dry descending/flush staircase that runs ALONG a deep floating-water pocket
        // is invisible to the dry-drop brakes (dropAdjacentExceeds skips water as a splash), so the
        // bot sprints the stepDown/diagDown and the momentum overshoots laterally off the dry edge
        // into the pocket — where a buoyant body bob-stalls on the un-climbable-out surface for
        // tens of seconds (live replay-0008 -870: foot drifted x-870.5→-866.3 off the staircase,
        // 800+ frozen pocket ticks). Drop sprint (NOT sneak — a sneak-pin would deadlock the
        // step-down, see the long edgeBrake note) when a deep-water cell borders the foot during a
        // descent or flat edge-walk, so the body decelerates onto the node instead of overshooting.
        // Never fires when the path DELIBERATELY enters the water (planned next node is itself deep
        // water — a river/lake crossing WANTS the bot in): then there is no dry line to hold and the
        // brake would only slow a legitimate entry. Ascents keep sprint (parkour/swim-approach need
        // the momentum; an ascent isn't drifting DOWN into the pocket).
        // radius 2, maxDrop 7: a deep pocket sits against a TALL bank with a stepped face, so
        // while the bot is still GROUNDED on the upper steps the open water is ~2 cells away and
        // ~6 below (live -870: grounded foot x-871/y68, floating water begins at x-869/-868,y62 —
        // a 1-neighbour/4-deep scan never reached it and the brake stayed inert; the bot went
        // airborne, dropping the onGround precondition, before the water became an immediate
        // neighbour). A dry fall column self-terminates at its first solid/shallow floor, so a
        // wider/deeper scan only adds reach toward genuinely deep water, never a dry-cliff false
        // positive. 2 covers the lateral overshoot a sprint adds; 7 the 6-deep drift with margin.
        boolean deepWaterEdgeRaw = BotConfig.walkerDeepWaterDriftBrake && p.onGround()
                && wp.getY() <= foot.getY()                       // descending or flush walk along the edge (not an ascent)
                && !world.isFloatingWater(wp)                     // not a deliberate deep-water entry (the path means to swim in)
                && deepWaterDropAdjacent(world, foot, 2, 7);
        // LATCH the brake across the airborne sub-arcs of a step-down descent: a staircase is a
        // chain of small drops, so the bot is airborne (onGround false) for ~half the ticks, and
        // without the latch sprint re-arms on every airborne tick and the accumulated forward
        // momentum still carries the body off the dry line into the pocket (live -870 bottom node:
        // grounded sprint=false, but the overshoot to x-866 / inW happened across the un-braked
        // airborne ticks between steps). Hold the brake DEEP_WATER_DRIFT_LATCH ticks after each
        // grounded fire so sprint stays off through the whole descent past the edge; it decays
        // once the bot stops re-triggering (moved away from the deep edge / finished the descent).
        // Released the instant the path turns to deliberately ENTER the water (wp is deep water),
        // so a real crossing isn't slowed. Off entirely when the flag is off (byte-identical).
        if (deepWaterEdgeRaw) deepWaterDriftLatch = DEEP_WATER_DRIFT_LATCH;
        else if (deepWaterDriftLatch > 0
                && (!BotConfig.walkerDeepWaterDriftBrake || world.isFloatingWater(wp))) deepWaterDriftLatch = 0;
        else if (deepWaterDriftLatch > 0) deepWaterDriftLatch--;
        boolean deepWaterDriftNear = deepWaterEdgeRaw || deepWaterDriftLatch > 0;
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
        // sneak in water = vanilla active SINK (buoyancy never sinks a surface swimmer on its
        // own) — so the dry-land safety brakes (bridge / cliff-descend) must NOT sneak in water,
        // exactly like lavaBrake above: a brake's job is to STOP, but shift in water DIVES the bot
        // to the floor. Live 2026-06-24 deep-pool crossing: a descending parkour edge fired
        // descendBrake → shift → the buoyant bot sank y62→58 and churned ~100 t clawing back to the
        // surface, then re-planned a parkour-onto-water it cannot execute. Only a real dive (diving)
        // sneaks in water; buoyancy alone already keeps a surface swimmer off any lethal edge.
        boolean brakeSneak = (bridgeBrake || descendBrake) && !p.isInWater();
        agentSneak(a, brakeSneak || diving || lavaBrake);
        p.setShiftKeyDown(brakeSneak || lavaBrake);
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
        // Y-MISLABELED-RISER RAM (executor riser-detection). A* can emit an edge it labels a LEVEL
        // {@code walk} (the Move has dy=0) whose DESTINATION floor is actually +1 — a mislabeled
        // ridge step. The bot, told the ground is level, SPRINTS into it (a walk edge keeps sprint),
        // walks off the lower approach block, drops onto it ONE below the node, and now rams the +1
        // riser it never squared up for (live 2026-06-25 -731,80,301: approached at py=80.0 sprint=
        // true, dropped py80→79, then hCol=true hSpd 0.018-0.065 dY=1.00 ramming, jump=false). A
        // genuine stepUp edge would have squared-up + dropped sprint; the mislabel skips that, so the
        // bot bonks the corner. The normal recovery (stepUpFreeze / stepUpJump once pivot aligns) DOES
        // fire, but only after the off-axis drop + a several-tick stall. Detect the unambiguous
        // signature — a WALK-labeled edge whose node sits exactly +1 above a GROUNDED foot
        // (upDy==1: a true level walk is upDy==0, so +1 IS the mislabel) with a mountable riser dead
        // ahead and a confirmed ram — and fire the grounded jump up-and-over AT ONCE, before the
        // off-axis pivot/freeze wait. Forward already drives at the column (driveF=1 unless pivot cuts
        // it), so this adds only the JUMP. Gated HARD on the walk mislabel + grounded ram, so a
        // correctly-labeled stepUp (own gates), a clean level walk (no riser/no collision → never
        // fires, no bunny-hop), and any non-walk edge are all byte-identical INERT.
        boolean levelRiserRam = edge != null && "walk".equals(edge.move) && upDy == 1
                && p.onGround() && !p.isInWater() && !parkourEdge && !bridging && !placingEdge
                && !descendBrake && p.horizontalCollision && stepRamStuckTicks >= 2
                && (stepColDx * stepColDx + stepColDz * stepColDz) < 2.6
                && forwardRiserMountable(world, foot, stepColDx, stepColDz);
        if (BotConfig.walkerDebug && levelRiserRam) {
            LOG.info("[walker] LEVEL-RISER-RAM wp={},{},{} foot={},{},{} upDy={} stepRam={} stepCol2={} act={}",
                    wp.getX(), wp.getY(), wp.getZ(), foot.getX(), foot.getY(), foot.getZ(), upDy,
                    stepRamStuckTicks,
                    String.format(Locale.ROOT, "%.2f", (stepColDx * stepColDx + stepColDz * stepColDz)),
                    BotConfig.walkerLevelRiserJump);
        }
        boolean levelRiserJump = levelRiserRam && BotConfig.walkerLevelRiserJump;
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
        // A buoyant bot reaching the surface of DEEP water (water below its feet) on a level or
        // slightly-rising WATER node has NO swim-up and sinks to the floor: swimUp needs the eyes
        // already submerged ≥3t, and swimColumn needs water at foot+1 — but a body whose foot
        // BLOCK sits AT the surface cell has AIR at foot+1, so both miss, jump=false, and it drops
        // straight to the bottom and wedges in a crevice (live 2026-06-24 walk edge to -798,62,649:
        // y62 surface→y47 floor, jump=false the whole way down, then 117t wedged hCol at y47).
        // descendBrake is a parkourDescend-only brake (not this walk edge) and diving is false
        // (sneak=false), so nothing else holds it up. Hold the swim-up jump whenever DEEP water is
        // under the feet and the target is a WATER cell at/above us, so it stays afloat across the
        // crossing. Excludes dives (diving, or wp below the foot) and dry bank climb-outs
        // (!isWater(wp) → the grounded ballistic climb-out jump still applies); cappedHead below
        // still threads a stone-lipped submerged tunnel low.
        boolean deepWaterRise = p.isInWater() && !diving && world.isWater(foot.below())
                && wp.getY() >= foot.getY() && world.isWater(wp);
        // FELL-BELOW ALIGN BREAKER (walkerFellBelowAlign) — the diagUp limit-cycle ROOT, one layer below apw's repath.
        // The -815/-809 churn (live replay-0006) is a LARGE vertical limit cycle (y64-84) where the bot oscillates
        // ACROSS the path nodes; it is AIRBORNE ~97% (bob), so EVERY grounded/hCol-gated jump suppression is defeated
        // and the bot re-launches a futile jump every grounded tick, never settling. When arcProgStall (bob-immune:
        // <2.0 net arc-s over 40t) confirms the no-progress oscillation, SUPPRESS the jump at any VERTICAL node
        // (wp.y != foot.y, where a jump would otherwise relaunch the bob) so the bot SETTLES to ground and the existing
        // fellOffPath repath fires from a stable grounded pose (mirrors the pillarUp off-column align). Validated
        // isolated: replay-0006 apw-alone 657 → FBA+apw 515 (the -809 spot drops to ~ts17); replay-0005 escapes the
        // -818 cycle apw-alone gets stuck on (1485 → 627). EXCLUDE a water-edge / shallow climb-out (replay-0005 -890
        // lakeside start, bot at y62): there the bot is legitimately climbing out and suppressing the jump only blocks
        // it (regressed the start 790 → 1176); the diagUp cycle is a DRY steep oscillation, so require dry ground under
        // the foot. Level walks (wp.y==foot.y) keep their jump (none fires there anyway). Default OFF (byte-identical).
        // NOTE: end-to-end maxStuck is still dominated by OTHER domains (downstream water stalls) and the movement
        // flags INTERACT badly (FBA+apw+water-stack froze the -818 cycle at 3577) — a silky combination needs the
        // systematic replay-corpus flag search, not hand-tuning. This fix is validated for the diagUp domain only.
        boolean fellBelowNearWater = world.isWater(foot) || world.isWater(foot.below())
                || world.isWater(foot.offset(0, -2, 0));
        // DEPTH GATE (corpus gate caught this, 2026-06-28): the original `wp.y != foot.y` fired at ANY vertical
        // node, so a NORMAL +1 diagUp/stepUp the bot could simply jump up (transiently arcProgStall) got its jump
        // suppressed too → regressed corpus-dry-627 (194 → 280; added a diagUp churn). A single jump clears ~1.25,
        // so only a node ≥2 ABOVE the foot is genuinely UNreachable by one jump = a real fell-below worth settling
        // for. Require that depth; a +1 climb keeps its jump. (wp-below-foot descents are handled by below-node-ram.)
        boolean fellBelowMisaligned = BotConfig.walkerFellBelowAlign && arcProgStall
                && (wp.getY() - foot.getY()) >= 2 && !fellBelowNearWater;
        if (BotConfig.walkerDebug && fellBelowMisaligned) {
            LOG.info("[walker] FELL-BELOW-ALIGN wp={},{},{} foot={},{},{} dY={} arcProgStall=true → suppress jump, settle to ground",
                    wp.getX(), wp.getY(), wp.getZ(), foot.getX(), foot.getY(), foot.getZ(), wp.getY() - foot.getY());
        }
        boolean jump = !descendBrake && !fellBelowMisaligned
                // Below-node ram (the +1 desync-ram wedge, see descentRamStuck): the bot drifted 1 above the
                // route and rams an overhang at a node BELOW it. Every jump term here is futile going DOWN (no
                // riser to mount), and the bob-jump (y+1.25) just bonks the overhang AND lifts the foot off the
                // ground — STARVING the grounded-gated descentRamStuck recovery, so the wedge bob-drags ~46t
                // instead of resolving at ~24t (live 2026-06-24 V2 climb replay: y85<->86.25 bob, cur2 frozen
                // 0.640, reroute re-wedged once). Suppress the jump while grounded + ramming + node-below so the
                // body stays planted and descentRamStuck fires on time. A real descent never rams (clean drop),
                // and a jump-to-a-lower-node is a parkour/fall edge (own gates) not this walk-drive — so this
                // kills ONLY the futile overhang bob-jump.
                && !(p.horizontalCollision && p.onGround() && wp.getY() < foot.getY())
                && (stepUpJump || parkourEdge
                    // Freeze-breaker: force a GROUNDED jump straight up the step once a
                    // stepUp/diagUp has rammed the riser past STEPUP_FREEZE_TICKS — the
                    // normal stepUpJump gate (ascendJumpReady) can stay false there (the
                    // bot is a touch off the cross-axis), so without this it bobs forever.
                    || (stepUpFreeze && p.onGround())
                    // Level-riser breaker (y-mislabeled-riser RAM): a WALK-labeled edge whose node is
                    // actually +1 above the grounded foot (the mislabel) — the bot sprinted in level,
                    // dropped onto it, and rams the +1; fire the grounded jump up-and-over AT ONCE
                    // instead of waiting out the off-axis pivot/freeze. Flag-gated; ram confirmed above.
                    || levelRiserJump
                    // !diving: swimColumn is true for any submerged body, so during an
                    // ACTIVE dive the held jump cancelled the sneak-sink exactly —
                    // the bot hovered at constant depth (hSpd 0.02, jump+sneak both
                    // down) while the burst storm wound yaw 4.5 turns (mangrove live).
                    || ((swimUp || swimColumn || deepWaterRise) && !cappedHead && !diving) || wiggle);
        // TEMP-DIAG (sunken-start deadlock): dump every jump term while submerged & stuck
        if (BotConfig.walkerDebug && p.isInWater() && p.isUnderWater() && stuckTicks > 20 && stuckTicks % 20 == 1) {
            LOG.info("[walker] JUMP-DIAG jump={} swimUp={} swimCol={} dwRise={} capped={} diving={} descBrake={} fbMis={} belowRam={} stepUpJump={} wiggle={} uwT={} wp={},{},{} foot={},{},{}",
                    jump, swimUp, swimColumn, deepWaterRise, cappedHead, diving, descendBrake, fellBelowMisaligned,
                    (p.horizontalCollision && p.onGround() && wp.getY() < foot.getY()), stepUpJump, wiggle, underwaterTicks,
                    wp.getX(), wp.getY(), wp.getZ(), foot.getX(), foot.getY(), foot.getZ());
        }
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
        // Sprint the climb-out APPROACH (swimming toward a bank while still >~1.6 blk lateral
        // from it), not just flat crossings. The no-sprint-on-climb-out rule (water term below)
        // was tuned for the CLOSE press where the prone pose can't rise the bank — but it also
        // killed sprint on the long swim UP TO the bank, so the bot tread-bobbed in at hSpd ~0.06
        // for 2-3 blocks before even reaching it (live 2026-06-24 -597 / -671 water edges, slow
        // swim throughout = the shared root behind the water-edge churn fix I didn't cover).
        // rawClimbPress (latDist²<2.5) still drops sprint for the final rise, so the "stuck
        // bobbing, can't climb out" trace stays fixed; this only speeds the approach swim.
        boolean climbApproach = p.isInWater() && !p.onGround() && wp.getY() > foot.getY()
                && (stepColDx * stepColDx + stepColDz * stepColDz) >= 2.5;
        // LATERAL DRIFT on steep diagonal climbs (live 2026-06-25 replay-0004 -787 ridge): sprint is
        // already dropped on the diagonal STEP-UP itself (sprintAscend is cardinal-only), but the bot
        // still sprints the flat run-up BETWEEN diagonal steps, and that momentum — carried through the
        // next jump arc — pushes it laterally OFF the narrow √2 staircase line. It drifts 1-2 blocks
        // sideways, then EITHER slides 2-3 below an ascent node (→ ascentRamSlide pillar-spam: one climb
        // burned 56 cobble / 292 pillarUp) OR descends a crest early and rams the ridge block sideways
        // (x frozen at -786.70, hCol, bobbing, ~2 s grind per node, totStuck 928). Dropping sprint while
        // the NEXT node is a dry diagonal ascent keeps the body on the line so it tracks the staircase
        // instead of overshooting the corner. Parkour leaps + water climb-approaches still sprint (they
        // need the momentum). Distinct from the A/B-disproven diagonal sprint-bunny-hop (that flipped
        // sprint ON to JUMP a diagonal; this drops it to stop drift). A/B vs the 292-pillar baseline.
        boolean diagAscent = !parkourEdge && !p.isInWater()
                && wp.getX() != foot.getX() && wp.getZ() != foot.getZ() && wp.getY() > foot.getY();
        boolean sprint = !bridging && !steppingOffFall && !steppingOffWaterFall && !diagAscent
                && !hazardAhead   // never carry sprint momentum INTO a lava/hazard cell — in water too (no sneak there, but dropping sprint kills the drift that pushed the swimmer in)
                && !descendBrake && (!lethalNear || parkourAscend) && !steepDescentNear && !deepWaterDriftNear && (!needJumpForStep || parkourAscend || sprintAscend)   // !lethalNear (not !edgeBrake): never sprint NEAR a lethal edge — incl. a planned descent past it — so no drift/overshoot momentum off the lip while sneak is released for the step-down. !deepWaterDriftNear: same, for a deep-water pocket bordering a descent/edge-walk (drift-in bob-stall). Baritone doesn't sprint a jumped CARDINAL ascend (overshoots/bonks) but DOES sprint a parkour leap; a horse auto-walk-up keeps sprint
                // A/B-DISPROVEN (2026-06-06): re-enabling sprint on an aligned ascend (sprintableAscend)
                // regressed hCol 13%→36% / mean hSpd .112→.082 — because the jump fires CLOSE to the riser
                // (ascendJumpReady flatDist≤1.2), the sprint forward-boost rams the riser face HARDER instead
                // of arcing over it. A sprint-jump only clears a step if launched EARLY (before the riser);
                // closing that gap needs an early-jump-timing change, not just flipping sprint on. Kept no-sprint.
                && (!p.isInWater() || flatWaterWalk || diveUnderCap || climbApproach);
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
            // LATERAL-pad rescue: the head-on scan above samples ONLY the cell toward the
            // waypoint at the eye plane, so a pad in an ADJACENT column the BODY overlaps
            // (BlockPos.containing floors the body centre into a different cell) is missed —
            // attack stays false and the floating bot bobs against the pad until a repath
            // (~13.5 s; live -771: frozen at (-770.10,..,317.76), pad at -770,63,318 toward the
            // bank, head-on scan looking at the -771 water cell). When the bot is CONFIRMED
            // rammed (noStepProgressTicks past the gate) and nothing was found ahead, scan the
            // four cells the body's AABB (half-width 0.3) overlaps at the surface (head) cell and
            // break the nearest breakable pad. Flag-gated OFF → byte-identical; isBreakableObstruction
            // is instabreak-by-hand only (pad/surface plant, destroySpeed 0) so a real wall is never
            // touched, and !isInWater / pad-free crossings never reach here.
            if (pad == null && BotConfig.walkerPadRamBreak
                    && noStepProgressTicks > PAD_RAM_STALL_TICKS) {
                pad = nearestBodyPad(world, p);
                if (pad != null && BotConfig.walkerDebug)
                    LOG.info("[walker] lateral pad-ram break: pad={},{},{} pos={},{},{} wp={},{},{} noStepProg={}",
                            pad.getX(), pad.getY(), pad.getZ(),
                            String.format(Locale.ROOT, "%.2f", p.getX()),
                            String.format(Locale.ROOT, "%.2f", p.getY()),
                            String.format(Locale.ROOT, "%.2f", p.getZ()),
                            wp.getX(), wp.getY(), wp.getZ(), noStepProgressTicks);
            }
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
            LOG.info("[walker] walk-keys yaw={} wp={},{},{} up={} jump={} sprint={} sneak={} hCol={} minorCol={} hSpd={} pos={},{},{} onG={} attack={} dryDesc={} driveYaw={}",
                    String.format(Locale.ROOT, "%.0f", p.getYRot()),
                    wp.getX(), wp.getY(), wp.getZ(),
                    a.dbgForwardImpulse(), a.dbgJumping(), p.isSprinting(),
                    a.dbgSneak(),
                    p.horizontalCollision, p.minorHorizontalCollision,
                    String.format(Locale.ROOT, "%.3f", hSpd),
                    String.format(Locale.ROOT, "%.2f", p.getX()),
                    String.format(Locale.ROOT, "%.2f", p.getY()),
                    String.format(Locale.ROOT, "%.2f", p.getZ()),
                    p.onGround(), a.breakHeld(),
                    dryDescent, String.format(Locale.ROOT, "%.0f", driveTargetYaw));
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

    /** PROACTIVE PINCH escalation — see {@link #PINCH_MIN_PROGRESS}. When the big search
     *  commits a best-effort segment that barely closed the goal distance, the planner is
     *  wedged at a pinch (deepwater→bank, boxed canyon, steep barrier); arm the deep-search
     *  escalation on that FIRST struggling commit so the NEXT search suppresses its horizon,
     *  raises the depth penalty and routes AROUND the obstacle — instead of waiting for the
     *  executor to churn ~20 s until the reactive churn-window fires. Re-arms (extends) on
     *  each struggling commit while the pinch persists; logs only on the transition. Gated
     *  to {@link BotConfig#pathfinderProgressive} (default OFF → behaviour unchanged). */
    private void maybeArmPinchEscalation(BlockPos foot, PathFinder.Result res) {
        if (!BotConfig.pathfinderProgressive) return;
        if (res.goalReached() || commitEnd == null) return;      // healthy full route → no escalation
        double progress = goal.estimate(foot) - goal.estimate(commitEnd);
        if (progress >= PINCH_MIN_PROGRESS) return;              // real headway → not a pinch
        boolean wasArmed = pfTickCounter < boxedEscalateUntilTick;
        boxedEscalateUntilTick = pfTickCounter + BOXED_ESCALATE_STICKY_TICKS;
        if (BotConfig.walkerDebug && !wasArmed)
            LOG.info("[walker] proactive pinch escalation ARMED: best-effort commit gained only {} blocks toward goal → deepen next search",
                    String.format("%.1f", progress));
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

    /** PROGRESSIVE LAND COARSE-DIRECTION stub (渐进式陆地直行 stub): the dry-land twin of
     *  {@link #tryWaterBeeline}. Over open / gently-sloped land the big sliced A* re-plan
     *  can burn thousands of nodes before it commits, leaving the bot frozen at the
     *  start-of-segment gap (the visible startup / inter-segment churn). No search is
     *  needed to START moving the right way: greedily march toward the goal — at each
     *  cell take the 8-neighbour, at the SAME Y, that most reduces {@code goal.estimate}
     *  and is safely STANDABLE on dry ground — and adopt that run as a coarse stub the
     *  bot walks immediately while the big search runs and supersedes it. A greedy local
     *  minimum (a wall, a slope, a cliff, water) is harmless: the march simply stops
     *  there and the real path replaces the stub the instant it lands; the stub only ever
     *  drives the bot over flat ground it could walk anyway. Same-Y only (like the water
     *  bee-line) so it never steps off a ledge or rams a riser — slopes hand back to A*.
     *  Gated to {@link BotConfig#pathfinderProgressive} + dry land under the feet (water
     *  is {@link #tryWaterBeeline}'s job). @return true if a land run was adopted. */
    private boolean tryLandBeeline(WorldView world, BlockPos foot, Goal goal) {
        if (!BotConfig.pathfinderProgressive) return false;
        if (world.isWater(foot)) return false;          // water is tryWaterBeeline's job
        List<BlockPos> path = new ArrayList<>();
        List<Move.Edge> edges = new ArrayList<>();
        path.add(foot);
        edges.add(null);                                // start node carries no inbound edge
        BlockPos cur = foot;
        double curEst = goal.estimate(foot);
        for (int i = 0; i < BEELINE_MAX_STEPS; i++) {
            BlockPos best = null;
            double bestEst = curEst;
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    BlockPos n = cur.offset(dx, 0, dz);
                    if (!isDryWalkable(world, n)) continue;
                    double e = goal.estimate(n);
                    if (e < bestEst) { bestEst = e; best = n; }
                }
            if (best == null) break;            // no goal-ward dry step (slope / wall / water / cliff)
            path.add(best);
            edges.add(new Move.Edge(best, 10, List.of(), List.of(), "walk"));
            cur = best;
            curEst = bestEst;
        }
        if (path.size() <= BEELINE_MIN_STEPS) return false;   // too short to be worth a stub
        if (BotConfig.walkerDebug)
            LOG.info("[walker] land coarse-direction stub adopted: len={} toward goal (big search still running)",
                    path.size());
        adoptPath(new PathFinder.Result(path, edges, false, 0, 0L, 0.0), world, null);
        return true;
    }

    /** A cell a walking bot can stand in on DRY ground — the land analogue of
     *  {@link #isOpenSurfaceWater}: standable (solid floor, clear body, no hazard) and
     *  not water (a water cell is the bee-line's domain, and a buoyant exit differs). */
    private static boolean isDryWalkable(WorldView w, BlockPos foot) {
        return w.canStandAt(foot) && !w.isWater(foot);
    }

    /** A cell a buoyant body can swim across at the surface: water at the foot, a CLEAR
     *  non-water head (the surface — not a submerged mid-column cell), neither a hazard. */
    private static boolean isOpenSurfaceWater(WorldView w, BlockPos foot) {
        BlockPos head = foot.offset(0, 1, 0);
        return w.isWater(foot) && !w.isWater(head) && w.isPassable(head)
                && !w.isHazard(foot) && !w.isHazard(head);
    }

    /** Find the breakable surface obstruction (lily pad / instabreak plant) the floating body
     *  actually OVERLAPS but the head-on pad scan in the drive missed. The player AABB is ~0.6
     *  wide (half-width 0.3), so at the surface the body can straddle up to four XZ columns; a pad
     *  in any of them blocks horizontal motion even though it isn't the single cell directly toward
     *  the waypoint. Scans the columns spanned by the body footprint ({@code x±0.3, z±0.3}) at the
     *  HEAD cell ({@code floor(p.y)+1} — where a pad resting on the water surface sits) and returns
     *  the one nearest the body centre. {@code isBreakableObstruction} is instabreak-by-hand only
     *  (destroySpeed 0, has a collision box, not a fluid) so solid terrain — a genuine wall — is never
     *  returned; null when no breakable cell is overlapped (a pad-free ram against real geometry, so
     *  the caller leaves it to the normal wedge recovery). */
    private static BlockPos nearestBodyPad(WorldView w, Player p) {
        int headY = BlockPos.containing(p.getX(), p.getY(), p.getZ()).getY() + 1;
        BlockPos best = null;
        double bestD2 = Double.MAX_VALUE;
        for (double ox = -0.3; ox <= 0.3; ox += 0.6) {
            for (double oz = -0.3; oz <= 0.3; oz += 0.6) {
                BlockPos col = BlockPos.containing(p.getX() + ox, p.getY(), p.getZ() + oz);
                BlockPos c = new BlockPos(col.getX(), headY, col.getZ());
                if (!w.isBreakableObstruction(c)) continue;
                double dx = (c.getX() + 0.5) - p.getX(), dz = (c.getZ() + 0.5) - p.getZ();
                double d2 = dx * dx + dz * dz;
                if (d2 < bestD2) { bestD2 = d2; best = c; }
            }
        }
        return best;
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
            // DEEP-WATER-FLOAT BEE-LINE EXEMPTION (live #47 deterministic dead-stop): a bot floating in
            // deep open water commits a best-effort segment whose continuation (from commitEnd) starts a
            // long clear-LOS open-water WALK node tens of blocks ahead — the only intermediate node is an
            // IN-PLACE swimUp (same XZ as the foot), so the anchor finds NO near forward node and rejects
            // (d2≈2300). The reject drops the path, the foot-search returns the SAME swimUp+far-walk
            // best-effort, and it re-pins → 13 repaths, no progress, hSpd=0, anti-spin ends best-effort
            // (~6 s dead-stop; telemetry "reject mis-anchored: -812,62 (d2=2305) vs foot -860,58"). But
            // that far node IS reachable: the buoyant body can swim a STRAIGHT line to it over open water
            // (no wall, all water/air). Accepting the segment lets the carrot bee-line toward the far node
            // and make forward progress (the deep-water-float analog of the quick-start stub / water
            // bee-line). STRICTLY gated so it can NOT exempt a truly mis-anchored / walled / climb segment:
            //   • the foot must be FLOATING in water (the rejectGate's own water test);
            //   • the straight line foot→anchor must be a CLEAR OPEN-WATER bee-line (losWalkable AND every
            //     sampled cell water/air — a bank, ledge or dry walkway fails it);
            //   • the anchor node must sit within ±4 Y of the floating foot — a SURFACE crossing the buoyant
            //     body swims up+across to (the live foot bobs y58↔61 under a y62 surface node, dy up to 4),
            //     never an impossible bank climb (a real climb-out has a solid bank IN the line → the
            //     open-water LOS already rejects it, so this band only bounds the swim-up reach).
            // A genuinely fumbled continuation (computed from a commitEnd behind a wall / up a cliff the
            // bot never reached) fails the open-water LOS or the ±4 Y band, so it still rejects.
            // The exemption suppresses BOTH reject conditions (the d2 distance gate AND the |Δy| jump gate):
            // for an open-water bee-line the Y difference is a buoyant swim-UP to the surface node, not a dry
            // jump/fall, so the maxJumpUp gate (which exists to bar impossible dry climbs) must not veto it.
            boolean openWaterBeeline = BotConfig.walkerDeepWaterFloatBeeline
                    && world.isWater(foot)
                    && Math.abs(raw.get(anchor).getY() - foot.getY()) <= 4
                    && losWalkable(world, foot, raw.get(anchor))
                    && isOpenWaterLine(world, foot, raw.get(anchor));
            if (!openWaterBeeline
                    && (anchorD > rejectGate
                        || Math.abs(raw.get(anchor).getY() - foot.getY()) > world.maxJumpUpBlocks() + 2)) {
                if (BotConfig.walkerDebug)
                    LOG.info("[walker] reject mis-anchored segment: nearest prefix node {},{},{} (d2={}) vs foot {},{},{}",
                            raw.get(anchor).getX(), raw.get(anchor).getY(), raw.get(anchor).getZ(),
                            (int) anchorD, foot.getX(), foot.getY(), foot.getZ());
                return false;
            }
            if (openWaterBeeline && anchorD > rejectGate && BotConfig.walkerDebug)
                LOG.info("[walker] accept open-water bee-line segment: far node {},{},{} (d2={}) reachable by clear-LOS swim from foot {},{},{}",
                        raw.get(anchor).getX(), raw.get(anchor).getY(), raw.get(anchor).getZ(),
                        (int) anchorD, foot.getX(), foot.getY(), foot.getZ());
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
        return dropAdjacentExceeds(world, foot, SurvivalMath.survivableFall(p.getHealth()));
    }

    /** Like {@link #lethalDropAdjacent} but against an arbitrary depth {@code threshold}
     *  (a hazard anywhere in a fall column always counts). Used for BOTH the lethal
     *  sneak-pin (threshold = survivableFall, ~23 blk at full HP — higher with resist
     *  buffs) AND the steep-descent SPRINT brake (a small fixed threshold): a deep but
     *  SURVIVABLE drop never trips the lethal pin, so sprint momentum carries the body off
     *  the lip OFF-PATH and it falls 13-19 blk into a fellOffPath deadlock (live 2026-06-24
     *  random journey at -679,83: sprinted a diagDown, drifted -680→-677 off a 12-drop, 80 t
     *  stuck). The sprint brake just drops sprint there — it does NOT pin the body (sneak),
     *  so a legitimate planned step-down still proceeds. */
    private static boolean dropAdjacentExceeds(WorldView world, BlockPos foot, int threshold) {
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
            while (fall <= threshold + 2 && !world.isSolid(pr) && !world.isWater(pr)) {
                // Lava is neither solid nor water, so the height scan used to fall
                // THROUGH it to the lake floor — a 2-deep lava pocket measured as a
                // "survivable" 2-block drop, edgeBrake stayed off, sprint stayed on,
                // and downhill momentum slid the bot in (round52: enteredLava ×3).
                // Any hazard in the fall column is lethal regardless of height.
                if (world.isHazard(pr)) return true;
                fall++;
                pr = pr.below();
            }
            if (fall > threshold) return true;
        }
        return false;
    }

    /** Y-MISLABELED-RISER RAM detector (executor-side, see {@code levelRiserJump} below).
     *  A* can label an edge a LEVEL {@code walk} (Move dy=0) whose DESTINATION floor is actually
     *  +1 — a mislabeled ridge step. The bot, expecting level ground, sprints in, walks off the
     *  lower approach block and drops onto the cell ONE below the node ({@code upDy==1}), then rams
     *  the +1 riser it never squared up for (hCol=true, hSpd≈0). The destination column is standable
     *  (so {@code canStandAt} passed, the move was legal) — only the dy LABEL was wrong.
     *  <p>Detect the riser the body is pressed against: take the unit step toward the node and
     *  sample the cell one block ahead at the GROUNDED FOOT level (= node.y-1 when {@code upDy==1}).
     *  It is a mountable +1 riser iff that forward cell is solid ({@code canStandOn} — a real floor
     *  to stand on, not a fence/cocoa nub), the cell above it (foot+1) is passable (the jump arc
     *  clears), and foot+2 is passable (head room once standing on the riser, feet at node.y). Pure
     *  geometry; the caller additionally gates on the walk-mislabel signature + a CONFIRMED grounded
     *  ram (horizontalCollision + stepRamStuck), so a clean level walk over flat ground — where the
     *  forward foot cell is air — is INERT (never fires, no bunny-hop). */
    private static boolean forwardRiserMountable(WorldView world, BlockPos foot, double dx, double dz) {
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-3) return false;
        int fx = (int) Math.round(dx / len);
        int fz = (int) Math.round(dz / len);
        if (fx == 0 && fz == 0) return false;
        BlockPos riser = foot.offset(fx, 0, fz);                 // the +1 block dead ahead at foot level
        return world.canStandOn(riser)                          // a solid top to stand on after the hop
                && world.isPassable(riser.offset(0, 1, 0))      // jump arc clears the cell above the riser
                && world.isPassable(riser.offset(0, 2, 0));     // head room once standing on the riser
    }

    /** True if a DEEP floating-water cell ({@link WorldView#isFloatingWater}: water with
     *  water directly below — ≥2 deep, no floor in jump range) lies within {@code radius}
     *  blocks horizontally of the foot AND within a short ({@code ≤maxDrop}) open fall column
     *  from foot level, with a clear (un-walled) drop to it. The water analogue of
     *  {@link #dropAdjacentExceeds}: that helper counts only DRY drops (it {@code continue}s
     *  past any water as a harmless splash) and only the 8 immediate neighbours, so a descent
     *  that borders a deep pocket SET BACK behind a stepped/sloped bank face is invisible to
     *  both the lethal sneak-pin and the steep-descent sprint brake. A buoyant body that drifts
     *  into such a cell floats and cannot climb out, so the deep-water drift brake drops sprint
     *  to keep the body on the dry staircase line.
     *  <p>Why a horizontal RADIUS (not just the 8 neighbours like the dry brakes): a dry cliff
     *  edge IS an immediate neighbour, but a deep pocket sits against a TALL bank whose face is
     *  stepped, so while the bot is still GROUNDED on the upper steps the open water is 2 cells
     *  away (live -870: grounded foot x-871, the floating-water column begins at x-869/-868) and
     *  the bot only goes airborne — losing the {@code onGround} precondition — once it has
     *  already drifted off. A 2-cell reach catches the water before the launch, matching the
     *  ~1.5-2 blocks of lateral overshoot a sprint adds on a descent.
     *  <p>For each cell in the ring: skip a SOLID cell (a wall the body can't drift through);
     *  if it is itself floating water at foot level, hit. Otherwise scan its fall column — deep
     *  water within {@code maxDrop} = a drift-in hazard; a solid or SHALLOW-water floor first
     *  (1-deep splash, not floating water) terminates the column as a safe landing, so it never
     *  fires walking a 1-deep ford or onto a flush bank. */
    private static boolean deepWaterDropAdjacent(WorldView world, BlockPos foot, int radius, int maxDrop) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos n = foot.offset(dx, 0, dz);
                if (world.isSolid(n)) continue;                 // a wall/step face — no drift through it
                if (world.isFloatingWater(n)) return true;      // deep water at foot level within reach
                if (world.isWater(n)) continue;                 // shallow water at foot level (floor below) — a safe splash
                // Open cell: scan the fall column. Deep water under it (within maxDrop) = a
                // drift-in hazard; a solid/shallow floor first = a DRY drop (left to the lethal /
                // steep-descent brakes), not ours.
                BlockPos c = n.below();
                for (int d = 1; d <= maxDrop; d++) {
                    if (world.isFloatingWater(c)) return true;
                    if (world.isSolid(c) || world.isWater(c)) break;   // hit a floor (or shallow splash) before any deep water
                    c = c.below();
                }
            }
        }
        return false;
    }

    /** True if the straight horizontal line {@code a}→{@code b} is a CLEAR OPEN-WATER corridor — every
     *  sampled cell (and the one above it, head room) is water or passable air, with NO solid block in the
     *  way. The open-water analogue of {@link PathSmoothing#losWalkable}: losWalkable accepts a DRY
     *  walkway (solid floor below + clear foot/head), whereas this insists the corridor itself be
     *  water/air — so it admits ONLY a buoyant swim across open water, never a bank, ledge or dry path.
     *  Used by the deep-water-float bee-line anchor-gate exemption to verify a far continuation node is
     *  reachable by a straight swim before accepting an otherwise mis-anchored segment. Same per-cell
     *  sampling as losWalkable (cells the swim would cross, excluding the start). */
    private static boolean isOpenWaterLine(WorldView w, BlockPos a, BlockPos b) {
        int steps = Math.max(Math.abs(b.getX() - a.getX()), Math.abs(b.getZ() - a.getZ()));
        if (steps == 0) return true;
        for (int s = 1; s <= steps; s++) {
            double t = (double) s / steps;
            int x = (int) Math.round(a.getX() + (b.getX() - a.getX()) * t);
            int y = (int) Math.round(a.getY() + (b.getY() - a.getY()) * t);
            int z = (int) Math.round(a.getZ() + (b.getZ() - a.getZ()) * t);
            BlockPos c = new BlockPos(x, y, z);
            // foot cell + head cell must each be water OR (passable air, not a wall); a solid block in the
            // corridor (a bank / pillar) fails it. Hazards are never an open-water swim.
            if (w.isHazard(c) || w.isHazard(c.above())) return false;
            if (!(w.isWater(c) || w.isPassable(c))) return false;
            if (!(w.isWater(c.above()) || w.isPassable(c.above()))) return false;
            // Reject a DRY corridor: at least the foot cell should be water somewhere along the line
            // (an all-air line above ground is a fall, not a swim). Require water at THIS cell or below it
            // within 1 (the floating foot rides ~1 above the surface crossing nodes).
            if (!w.isWater(c) && !w.isWater(c.below())) return false;
        }
        return true;
    }

    private Move.Edge edgeAt(int i) {
        return (edges != null && i >= 0 && i < edges.size()) ? edges.get(i) : null;
    }

    /** Phase-0 SHADOW arc-length pursuit (walkerArcLengthShadow). Projects the continuous foot XZ onto the
     *  path polyline within a FORWARD window [step, step+W] (so it can't snap backward onto a self-overlapping
     *  earlier leg — the dominant projection risk, §7), honouring the adoptPath overlap barriers (a submerged
     *  below-node is a hard dive barrier; a pending break/place edge stops the scan). Reports the projected
     *  segment, cumulative XZ arc-length s (from path[0]), horizontal perpendicular distance, and the tangent
     *  heading at s+lookahead — and counts backward-snap events (ds&lt;-0.5). DRIVES NOTHING; this only proves
     *  (via replay) that s is monotonic and the projected segment tracks the live {@code step} on clean runs,
     *  the precondition for Phase 1 driving the step pointer off the projection. */
    private void arcShadowTick(WorldView world, BlockPos foot, double px, double pz, float liveYaw, boolean inW, boolean hCol) {
        arcProj.compute(path, edges, world, step, foot.getY(), px, pz, 16, 2.5);
        // Monotonic / backward-snap check vs the previous tick (reset on a fresh path object).
        boolean reset = (path != arcShadowPath);
        double ds = (reset || Double.isNaN(arcShadowS)) ? 0.0 : arcProj.s - arcShadowS;
        if (!reset && ds < -0.5) arcShadowMonoViol++;
        // Phase-3 arc-wedge accumulator: a grounded DRY forward-RAM that makes no arc progress. Excluded when the
        // forward window is blocked by a pending vertical edge (arcProj.barrierHit = a legit pillar/bridge/parkour
        // hold where zero ds is expected) or on a fresh path. The horizontalCollision gate keeps it off deliberate
        // non-ramming pauses (brakes, goal-dwell). EXCLUDES water: in water |ds|≈0 while hCol is the NORMAL state of
        // a swim-into-bank / climb-out, which owns its own dedicated recovery (waterCellTax / swimBankClimb / dig);
        // firing the wedge there repath-storms the climb-out and resets its aim every reroute (live -665 water-gap:
        // 7× arc-wedge at step 1 in water, dYaw stuck 95°, 频繁回头/横跳). The targeted ram is the DRY mountain
        // wall-ram. Bob/jitter-immune: vertical motion doesn't move the XZ projection.
        if (reset || inW || arcProj.barrierHit || !hCol || Math.abs(ds) > ARC_WEDGE_DS) arcWedgeTicks = 0;
        else arcWedgeTicks++;
        // Phase-3b NET arc-progress window: an oscillating limit cycle makes per-tick |ds| > ARC_WEDGE_DS (so the ram
        // counter above resets) yet zero NET path progress. Measure s over a whole window instead. Excludes water
        // (own recovery) and a legit pending-vertical-edge hold (barrierHit = expected zero ds). No hCol/onGround gate
        // — a bob-jumping airborne churn has neither.
        if (reset || inW) { arcProgBaseS = arcProj.s; arcProgWindowTicks = 0; arcProgStall = false; }
        else if (++arcProgWindowTicks >= ARC_PROG_WINDOW) {
            arcProgStall = !arcProj.barrierHit && (arcProj.s - arcProgBaseS) < ARC_PROG_MIN;
            if (arcProgStall && BotConfig.walkerDebug)
                LOG.info("[walker] arc-progress-wedge: net s={} < {} over {} ticks at step={} node={} → fellOffPath",
                        String.format("%.2f", arcProj.s - arcProgBaseS), ARC_PROG_MIN, ARC_PROG_WINDOW, step,
                        (step < path.size() ? path.get(step) : null));
            arcProgBaseS = arcProj.s;
            arcProgWindowTicks = 0;
        }
        arcShadowPath = path;
        arcShadowS = arcProj.s;
        float dYaw = liveYaw - arcProj.tangentYaw;    // wrap to [-180,180] for the camera-vs-tangent gap
        while (dYaw > 180) dYaw -= 360;
        while (dYaw < -180) dYaw += 360;
        if (BotConfig.walkerDebug)
            LOG.info("[walker] arc-shadow step={} projSeg={} segFrac={} s={} ds={} perp={} tanYaw={} liveYaw={} dYaw={} monoViol={} inW={}",
                    step, arcProj.segIdx, String.format("%.2f", arcProj.segFrac), String.format("%.2f", arcProj.s),
                    String.format("%.2f", ds), String.format("%.2f", arcProj.perp),
                    String.format("%.1f", arcProj.tangentYaw), String.format("%.1f", liveYaw),
                    String.format("%.1f", Math.abs(dYaw)),
                    arcShadowMonoViol, inW);
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

    /** True when the foot's horizontal distance² to {@code node} is in the step-advance DEAD-ZONE
     *  (REACH_DIST_SQ, OVERSHOOT_RESYNC_SQ) — too far for {@code within}, too near for the horizontal
     *  overshoot re-sync. Sole caller is the {@code walkerVerticalResync} gate (so the cur2 maths only
     *  runs when that flag is on). */
    private static boolean deadZoneCur2(BlockPos node, Player p) {
        double dx = (node.getX() + 0.5) - p.getX();
        double dz = (node.getZ() + 0.5) - p.getZ();
        double cur2 = dx * dx + dz * dz;
        return cur2 > REACH_DIST_SQ && cur2 < OVERSHOOT_RESYNC_SQ;
    }
}
