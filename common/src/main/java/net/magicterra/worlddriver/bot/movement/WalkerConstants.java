package net.magicterra.worlddriver.bot.movement;

/** Tuning constants for {@link Walker}, carved out verbatim to keep the executor's
 *  hot-path logic readable. Every value, name and doc comment (the team's pathology
 *  record) is preserved exactly as it lived in {@code Walker}; only the visibility was
 *  widened from {@code private} to {@code public} so {@code Walker} can static-import
 *  them ({@code import static WalkerConstants.*}). No behaviour change. */
final class WalkerConstants {
    private WalkerConstants() {}

    public static final int CARROT_MAX_NODES = 10;
    public static final double CARROT_DIST = 4.0;
    /** walkerTangentPursuit: cross-track distance (blocks) past which the tangent aim yields to the
     *  bearing of the path's point ahead. 0.6 = more than lane jitter, less than a whole cell. */
    public static final double PURSUIT_PERP = 0.6;
    /** Horizontal aim vector (blocks²) below which the heading is HELD instead of
     *  recomputed from atan2. During a vertical maneuver (stepUp/stepDown/pillar/
     *  fall) the bot sits almost directly over its target column, so adx/adz hover
     *  near zero and atan2 on that sub-block noise snaps the yaw between +90 and -90
     *  every tick — a 180° forward/backward thrash that also stalls forward thrust.
     *  A half-block (0.5) dead-zone keeps the last good heading until the bot is far
     *  enough from the column for the bearing to be meaningful again. Far larger than
     *  the old 1e-4 epsilon (which only caught standing-exactly-on-the-point). */
    public static final double YAW_DEADZONE_SQ = 0.25;
    /** walkerWallCornerNodeAim: how far (degrees) the immediate node must sit off the path tangent before a
     *  RAM (horizontalCollision) hands the aim back from the tangent to the direct node bearing — turn the
     *  corner off the wall instead of grinding along the trend into it. 20° = a real corner, not jitter. */
    public static final float WALL_CORNER_AIM_DEG = 20f;
    /** walkerOvershootReaim: ticks wedged (stuckTicks) before a dry overshoot at a cliff base re-aims BACK
     *  onto the overshot node. ~40 = 2s, well past a clean approach but before the multi-second churn. */
    public static final int OVERSHOOT_REAIM_STUCK = 40;
    /** walkerDryReanchor: sustained stuck ticks before the dry repath-rechurn breaker anchors back to the
     *  last cleanly-passed node. ~50 = 2.5s, past safetyRepath's stuck>60? no — fire BEFORE the repath
     *  churn deepens, but well past any clean slow move (water creep is in-water-excluded). */
    public static final int DRY_REANCHOR_STUCK = 50;
    /** walkerDryReanchor: foot-to-current-node dist² (blocks²) above which the bot is "far off-path" and the
     *  anchor engages. 4 = OVERSHOOT_RESYNC_SQ (2 blocks) — a genuinely-approaching node stays under it, so
     *  a legit slow climb/creep that hugs its node never triggers; only a real off-path churn does. */
    public static final double DRY_REANCHOR_OFFPATH_SQ = 9.0;
    /** WIDER aim dead-zone (blocks²) used only when aiming at a water bank-climb node that
     *  sits OVERHEAD (+1/+2 above the floating foot). The buoyant bot can't translate ONTO
     *  such a node, so once within ~a block of its XZ it orbits the column and atan2 sweeps
     *  the full 360° as it circles — the deep-water "spin in place" stall (step frozen on the
     *  climb node, pathChart maxYawErr ~179°, camera judder; archive replay-0002 tick3537 =
     *  5.3 s). A 2-block dead-zone HOLDS the approach heading there so the body keeps pressing
     *  the bank face and the climb-out dig/pillar can anchor and lift it out, instead of
     *  chasing the node round in a circle. Only for an ABOVE node; flat swims / dives keep the
     *  tight {@link #YAW_DEADZONE_SQ}. */
    public static final double CLIMB_AIM_DEADZONE_SQ = 4.0;
    /** Descent camera/movement decouple: how far ahead (blocks) the CAMERA looks on a dry
     *  descent. On a steep grid descent the immediate-waypoint bearing sweeps ~180° as the bot
     *  passes each close node, and chasing it winds the camera (the downhill spin — live yaw to 671°,
     *  replay 2953°). Fix: aim the CAMERA at the first path node ≥ this many blocks away (a far
     *  point's bearing is stable → no spin) while the MOVEMENT impulse keeps driving at the
     *  immediate node (precise foot-placement → still reaches). The two run on independent
     *  channels (camera = aimYaw, movement = driveTargetYaw, decoupled by AvatarInput's impulse
     *  pre-rotation), so neither damps the other — every prior single-channel fix (trend, EMA
     *  low-pass) failed precisely because damping the aim also damped the navigation. */
    public static final double DESCENT_CAM_FAR_DIST = 5.0;
    /** Descent camera trend window: the CAMERA aims at the CENTROID of the next this-many path
     *  nodes, spatially averaging a switchback staircase's alternating cardinal legs into the
     *  steady down-slope bearing. A single far node (DESCENT_CAM_FAR_DIST) SAMPLES the zigzag and
     *  itself swings ±50° as the bot descends — winding the camera ~9.7 turns on a steep slope
     *  (the live downhill and falling spin, ground-truthed via LookController WIND telemetry 2026-06-21). A
     *  centroid does not swing. Wide enough to span ≥1 zigzag period (~2-3 nodes); the decoupled
     *  drive (driveTargetYaw=node) keeps the body on every step so trend-aiming is nav-safe. */
    public static final int DESCENT_CAM_LOOKAHEAD = 12;
    /** No-step-progress ticks before a FLAT in-water aim also widens to {@link
     *  #CLIMB_AIM_DEADZONE_SQ}. A buoyant bot can't stop precisely on a water carrot/node, so
     *  within ~1 block the aim vector rotates fast as it drifts and the bearing sweeps —
     *  measured 96-176° yaw swings that collapse forward thrust to 0.4-0.9 b/s (vs 4.6 b/s
     *  with a steady heading) + the maxYawErr≈180° camera-swing. ~0.75 s is well past any
     *  normal arrival (which advances the step and resets the timer) yet early in the
     *  multi-second oscillation stall, so a precisely-progressing approach keeps the tight
     *  dead-zone (climb-out mount accuracy) and only a real thrash widens. */
    public static final int WATER_YAW_HOLD_STALL = 15;
    /** Decaying yaw-thrash score at which a buoyant bot on the (LOS-collapsed) near carrot
     *  switches to aiming at a stable FAR path node. +4 per raw-carrot-bearing reversal
     *  (cap 12), −1/tick, so 6 ≈ two reversals recently = genuine oscillation. */
    public static final int WATER_YAW_THRASH_SCORE = 6;
    /** Path nodes ahead the far-aim override targets, and the climb look-ahead that suppresses
     *  it (a precise mount within this many nodes keeps the normal carrot). */
    public static final int WATER_FAR_AIM_LOOKAHEAD = 3;
    /** Hard per-tick cap (degrees) on how far the commanded body yaw may turn during
     *  normal ground walking — independent of the cosmetic {@code smoothLook}. A single
     *  degenerate aim vector (reCentre pointing back at the previous node, atan2 on a
     *  near-zero vector, a fall's transient carrot) can otherwise snap the heading 180°
     *  in one tick — the repeated heading flips the pathfinding charts surfaced. Capping the slew
     *  turns any such transient into a small wobble the next (correct) tick undoes, so
     *  the body holds its forward line; a stuck/reCentre limit-cycle can no longer spin
     *  it, so forward progress resumes and the bot escapes the stall. 30°/tick = 600°/s,
     *  far faster than any real turn needs, so legitimate corners are unaffected.
     *  Launches (parkour / MLG fall) bypass this and snap — you can't steer mid-air. */
    public static final float WALKER_MAX_YAW_SLEW_DEG = 30f;
    /** Forward impulse magnitude for the FREE-HANGING vine climb (camera-decoupled commandMove
     *  toward the climb target). vanilla clamps a climbable's horizontal velocity to ≤0.15/tick, so
     *  this only needs to be firm enough to win the friction race and keep the body advancing toward
     *  the exit column (and re-centring off-axis drift) while it rides the jump up. Below 1.0 so it
     *  doesn't ram past the exit and shoot off the curtain top. See the onVine free-hang branch. */
    public static final float FREE_HANG_DRIVE = 0.6f;
    /** EMA factor for low-passing the TARGET heading. A near-45° goal makes A* emit a
     *  grid STAIRCASE whose immediate-waypoint bearing alternates ±~30° around the true
     *  diagonal every step; chasing that raw target (even clamped) leaves a bounded
     *  sawtooth. Averaging the target with this factor collapses the alternation to a
     *  steady bearing (a 20°/50° square wave settles to ~35° ±5° at 0.5) while still
     *  converging on a genuine turn in a few ticks. Launches bypass it (must snap). */
    public static final float YAW_SMOOTH_ALPHA = 0.5f;
    /** Stronger low-pass for the DRY-DESCENT camera (~6-tick time constant vs the cruise 2-tick).
     *  Smooths the residual centroid-quantisation jitter (nodes shifting in/out of the look-ahead
     *  window nudge the trend bearing ~3°/tick) into a near-still heading. Safe to lag this hard
     *  ONLY because the descent drive is camera-decoupled (driveTargetYaw=node) — the body keeps
     *  taking every step while the camera eases onto the trend. See DESCENT_CAM_LOOKAHEAD. */
    public static final float YAW_SMOOTH_ALPHA_DESCENT = 0.08f;
    /** The orbit signature the trend camera cannot converge out of: a heading error this wide, held
     *  this long while the body keeps moving, means the bearing rotates as fast as the slow EMA
     *  follows it (wd.clientGotoStartsMidAirOverWater: 300 ticks at ~90°, yaw wound 52→2453). Below
     *  the floor is ordinary tracking; above the ceiling is the per-repath ±180° flip that must stay
     *  damped (the antipode snap was tried and reverted). See BotConfig.walkerOrbitBreaksAimLag. */
    public static final float ORBIT_ERR_MIN_DEG = 45f;
    public static final float ORBIT_ERR_MAX_DEG = 170f;
    public static final int ORBIT_TICKS = 12;
    public static final double ORBIT_MOVE_SQ = 0.05 * 0.05;
    /** …and the body's own yaw must have wound this far in ONE direction meanwhile. A corridor
     *  detour whose trend centroid points elsewhere also holds a steady 90° error while moving
     *  (wd.bridgeStepTwoBypassNoPlace: centroid east, plan north), but its body turns at corners
     *  and then stops; only a circling body keeps turning the same way.
     *  <p>Ninety, down from 180: the trend alpha turns the yaw about 5° a tick, so 180° of winding
     *  was 36 ticks of circling before the break could fire (wd.clientTunnelsFarThroughStone: three
     *  breaks at 17-33 ticks, a full lap each around a node four cells out). One corner is at most
     *  90° of same-direction turn; only a lap goes past it. */
    public static final float ORBIT_WINDING_DEG = 90f;
    /** Surface sprint-swim cruise (walkerSurfaceSprintSwim): how long the dip keeps sinking after the
     *  pose appears so the server's lagging flag sync cannot knock it off (START_SPRINTING reaches the
     *  server a tick after the client's flip; its next flush of the shared-flags byte carries its own,
     *  still-off swim bit, and a body already back at the waterline then loses the sprint to
     *  {@code LocalPlayer.aiStep}'s "in water, not under" cancel — so the eyes stay under at crouch
     *  height until that flush has come and gone), the air band it breathes in, how far ahead a bank
     *  ends the cruise, and how long a dip may try for the pose before backing off. The sink runs a few
     *  ticks past the pose (0.36 block, standing eyes under), the hover lasts until the confirm tick. */
    public static final int CRUISE_SINK_TICKS = 3;
    public static final int CRUISE_CONFIRM_TICKS = 12;
    public static final int CRUISE_AIR_LOW = 130;    // above AutoSwim's yield floor (drownEscapeAirThreshold + its reserve), so the cruise breathes before the backstop takes over
    public static final int CRUISE_AIR_OK = 280;
    public static final int CRUISE_LOOKAHEAD = 8;
    public static final double CRUISE_BANK_DIST_SQ = 3.0 * 3.0;
    public static final int CRUISE_DIP_MAX_TICKS = 40;
    public static final int CRUISE_COOLDOWN_TICKS = 100;
    /** Longest string-pulled edge over water (cells). The off-path test is a 3-block cell distance
     *  that counts the sunk foot's extra y, so two cells keeps a cruising body on its path and
     *  passes a node every few strokes. */
    public static final int WATER_PULL_SPAN = 2;
    public static final float WATER_DRIVE_ALPHA = 0.3f;   // EMA on the water drive heading (damps ±180° node flip)
    /** Max one-tick turn (deg) the flat-water DRIVE heading will chase. A real swim turn — even the
     *  carrot rounding a corner — moves the heading gradually; a SUDDEN ±180° jump is a transient
     *  artifact (a node overshoot, a re-plan, or the carrot collapsing onto a wall/path-end and
     *  falling back to the flipping node bearing). Reject it — hold the forward heading — so the
     *  bot doesn't lurch backward and crawl (the open-water churn of circling around a node). */
    public static final float WATER_DRIVE_MAX_TURN = 120f;
    /** Consecutive flip-rejections after which the DRIVE SNAPS to the live source anyway. A genuine
     *  transient flip lasts 1–2 ticks (the carrot returns and tracking resumes); a PERSISTENT >120°
     *  disagreement means the route really did turn (or the carrot has collapsed for good at a wall)
     *  and the held heading is now stale — keep rejecting it and the swim strands itself pointing the
     *  wrong way (live -1648 / -1676: driveYaw froze ~ -99/103 while the node sat behind, totStuck
     *  climbed 500+). Snapping after a few ticks turns the body toward the live target so it makes
     *  progress, `step` advances past the overshot node, and the carrot recovers. */
    public static final int WATER_DRIVE_MAX_REJECT = 4;
    /** Max drop (blocks) below the floating foot that a NON-dive submerged path node is floated
     *  over (crossed horizontally at the surface) instead of followed down. A* routes wide
     *  deep-water crossings along the riverbed, placing walk/parkour nodes many blocks under the
     *  surface swimmer; following them dives the buoyant body and stalls it bobbing underwater (the
     *  stall where the bot sinks to the bottom and digs into the wall). A surface/land goal never needs to END deep underwater (real descents use
     *  fall/swimDown edges), so floating over any reasonable depth is safe; a swimDown dive keeps
     *  its own tight bound. */
    public static final double FLOATOVER_NONDIVE_MAX_DROP = 32.0;
    /** Ticks the flat-water swim trend stays latched after a surface bob momentarily lifts the foot
     *  out of the fluid (p.isInWater() blinks false for ~1 tick at the apex of the buoyant bob).
     *  Without the latch the DRIVE snaps to aimYaw for that one tick — a ±175° heading excursion
     *  twice per bob (the residual near-goal 2-tick driveYaw flip-pairs). A real climb-OUT/dive is
     *  still dropped immediately by the wp.y/swimDown gates, so this only rides out the bob. */
    public static final int WATER_SURFACE_LATCH_TICKS = 4;
    /** Ticks the deep-water drift sprint-brake stays latched after a grounded fire, to keep
     *  sprint OFF through the airborne sub-arcs of a step-down descent toward a deep pocket
     *  (a staircase step is ~3-4 airborne ticks; the brake re-fires and re-arms the latch on
     *  each grounded tick, so 8 comfortably bridges the gaps without lingering once the bot
     *  has finished the descent or moved off the deep edge). */
    public static final int DEEP_WATER_DRIFT_LATCH = 8;
    /** Ticks the steep-descent (dry) drift sprint-brake stays latched after a grounded fire —
     *  the dry sibling of {@link #DEEP_WATER_DRIFT_LATCH}. steepDescentNear drops sprint when a
     *  survivable-but-deep (&gt;4) drop borders a planned descent, but it is gated onGround, so
     *  across the airborne sub-arcs of each step-down sprint RE-ARMS and the accumulated FORWARD
     *  momentum walks the body off a survivable-deep lip into a fatal cumulative fall (live
     *  2026-07-11 Mountains massif, telemetry-confirmed: grounded sprint=false, but
     *  onG=false→sprint=true on every fall tick). 8 bridges a step-down's airborne arc and
     *  re-arms each grounded step; a genuine long free-fall decays past it harmlessly (nothing
     *  to brake mid-air). */
    public static final int STEEP_DESCENT_DRIFT_LATCH = 8;
    /** How many path nodes ahead of the current step the steep-descent brake looks to detect a
     *  CUMULATIVE deep descent (task#36, 2026-07-12). The single-edge {@code dropAdjacentExceeds}
     *  probe only sees a drop when ONE neighbour is a &gt;maxDryFall cliff; a mountain descent the
     *  planner routes as a run of individually-legal ≤maxDryFall steps (e.g. y79→77→73→72) has no
     *  such neighbour at any grounded tick, so the raw brake never armed and the body sprint-sailed
     *  off the slope (live Mountains: hSpd rising 0.21→0.23, sprint=T, 6+ block continuous fall).
     *  Summing the drop over the next few nodes (foot.Y − min node.Y) catches the slope the way the
     *  planner laid it. 3 spans the ~2-node horizon where a &gt;4 cumulative drop first appears. */
    public static final int STEEP_DESCENT_LOOKAHEAD_NODES = 3;
    /** Consecutive ticks the buoyant-climb-press condition (wp above foot) must hold before the
     *  press engages. A surface bob drops the foot one block under a SAME-LEVEL surface node for
     *  ~1 tick, spuriously satisfying wp.y > foot.y and firing a "mount" that overrides the drive
     *  to the already-passed (→ ±180° flipped) column heading + a jump (the residual near-goal
     *  driveYaw flip-pairs, jump=true on every flipped tick). A REAL +1 bank climb keeps the node
     *  above the foot EVERY tick (the body sits below the bank through the whole mount), so it
     *  satisfies the debounce immediately; the 1-tick bob never does. */
    public static final int CLIMB_PRESS_DEBOUNCE = 3;
    /** Per-tick smoothed-target change (deg) below which the aim target counts as STABLE.
     *  The anti-spin freeze ({@link #spinFreeze}) must only fire on a FLIPPING target (a
     *  ~180° swing each repath winds the camera). A stable target the capped slew converges
     *  to once cannot wind, so freezing it just pins a wrong heading. */
    public static final float AIM_STABLE_DEG = 8f;
    /** Consecutive STABLE ticks (≈0.5 s) after which the anti-spin freeze releases — long
     *  enough to ignore one transient flip, short enough to break the badlands-basin
     *  heading-freeze deadlock (live 2026-06-16: heldYaw frozen 500+ ticks pressing a bank
     *  while a stable bearing pointed ~150° away). */
    public static final int AIM_STABLE_TICKS = 10;
    /** Degrees past which a target-vs-smooth gap counts as a COURSE REVERSAL, not a turn
     *  the EMA may chase. At the ±180° antipode angleDiff's sign flips on sub-pixel body
     *  jitter, so EMA-chasing oscillates forever and the stability gate never releases
     *  the anti-spin freeze (Mountains notch live: raw target steady 700 ticks, smooth
     *  never stable, heading pinned into a wall — the badlands-basin deadlock's true
     *  root). Reversals are snapped (below), never smoothed. */
    public static final float AIM_REVERSAL_DEG = 170f;
    /** Consecutive reversal ticks before the smooth heading SNAPS to the live target —
     *  rides out 1-2 tick transient flips (node overshoot sweep) exactly like
     *  {@link #WATER_DRIVE_MAX_REJECT} does for the water drive heading. */
    public static final int AIM_REVERSAL_SNAP_TICKS = 6;
    /** Squared horizontal displacement (~0.15 b) under which a spin-frozen body counts as
     *  PINNED — pressing a wall, not swimming/climbing. See the freeze deadlock valve. */
    public static final double FREEZE_PRESS_MOVE_SQ = 0.15 * 0.15;
    /** Consecutive pinned-while-frozen ticks (~3 s) before the valve releases the freeze
     *  and snaps the heading to the live target. Long enough that every legitimate freeze
     *  use (bodies that move/bob) never trips it; short enough to break the notch
     *  deadlock ~200x faster than the 12000-tick goto budget it used to burn. */
    public static final int FREEZE_PRESS_STALL_TICKS = 60;
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
    public static final float STEPUP_AIM_TOLERANCE_DEG = 40f;
    /** Path-trend averaging window (nodes) for the dry +1 staircase aim. A ~45° goal makes A*
     *  emit a grid STAIRCASE whose immediate-node bearing alternates ±~30° around the true
     *  diagonal every step; aiming at it swings the heading past {@link #STEPUP_AIM_TOLERANCE_DEG}
     *  each step → the pivot gate cuts forward drive → the speed sawtooth + camera zigzag the path
     *  charts show. Averaging the next few SEGMENT unit-vectors (stopping at a real >60° bend so it
     *  never aims across a corner — the single-far-node aim that did was reverted) recovers the
     *  steady diagonal, holding the heading inside the gate so the climb keeps full drive. On a
     *  straight staircase the trend equals the immediate bearing, so cardinal climbs are unchanged. */
    public static final int STAIR_TREND_LOOKAHEAD = 5;

    public static final double REACH_DIST_SQ = 0.45;
    /** Squared distance to the LAST node inside which the body walks instead of sprinting: two
     *  blocks, one more than a sprint needs to bleed to walking speed under ground friction. */
    public static final double FINAL_APPROACH_WALK_SQ = 4.0;
    /** How far under the surface a submerged body's search start may still be lifted to the
     *  surface cell: two cells, the depth a fresh drop into a pool sinks to before it floats. */
    public static final int SURFACE_SEARCH_LIFT_MAX = 2;
    /** How long the pointer may be held on a final node that is the goal while the foot is not
     *  yet in it: a landing plus a walk of a block, with room for one bounce. */
    public static final int FINAL_NODE_HOLD_TICKS = 30;
    public static final int STUCK_TICKS = 60;
    /** Ticks a buoyant body may stay pinned ABOVE an in-water below-node before the
     *  step-advance gate surface-crosses past it. A* routes a deep-water crossing
     *  along the riverbed (nodes 1-2 below the floating foot); the executor can't
     *  sink onto them — a swimDown dive's buoyancy refuses the sink and a non-dive
     *  below-node has its float-over disabled the instant the eyes bob under the
     *  waterline (isUnderWater flickers true) — so without this the bot pins at the
     *  node's XZ forever (live 2026-06-15 deep-water arena: node 1.4 below, 2600+
     *  ticks at step 1). Short (≈1.2 s) so a legitimate in-progress dive — which
     *  keeps closing the Y gap and advancing — never trips it. */
    public static final int WATER_DESCEND_GIVEUP = 25;
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
    public static final double OVERSHOOT_RESYNC_SQ = 4.0;
    /**
     * Horizontal distance² inside which a node whose NEXT node is stacked on it (same column:
     * pillarUp, downBreak, swimUp) may count as passed: the body is on the column, only momentum
     * carried it off centre.
     *
     * <p>Why the stacked case needs its own rule: a stacked next node has {@code nd2 == cur2} from
     * every foot in the world, so the overshoot tie-break in the {@code passed} re-sync read
     * "passed" for a node the body had not reached at all — {@code overshot} is only "far from the
     * node", it has no direction. Live (wd.clientTunnelsFarThroughStone): a 25-cell string-pulled
     * walk ending on a downBreak column; the pointer skipped the walk node from 25 cells out, the
     * actuator dug the column's block from there (the client breaks it locally, the server refuses
     * by reach and sends it back), the next plan stepped down through the phantom air, and the
     * body orbited the column. From farther out than this the pointer holds unless the body is
     * actually BEYOND the node along the route ({@code PathSmoothing.beyondNode}): that keeps the
     * overshoot relaxation for a buoyant body that drifted past its climb column, and refuses it
     * for one that has not arrived.
     */
    public static final double STACKED_PASS_SQ = 1.0;
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
    public static final int WEDGE_TICKS = 100;
    /**
     * Ticks a walker-held dig latch survives without a dig driving it before the tail of
     * {@code Walker.tick} releases it.
     *
     * <p>Nothing released it before. The break actuator's own {@code breakHold(false)} sits past
     * its {@code toBreak} loop, which is reached only while a {@code toPlace} cell still keeps the
     * edge pending, and the sticky-dig release just drops the claim; so after every plain dig the
     * latch (then the attack key itself) stayed down until the whole process ended. Two things
     * followed: with the window focused vanilla's {@code continueAttack} mined whatever the
     * crosshair crossed while the body walked on — the key is gone now, that one cannot recur —
     * and {@code WalkerTickStallDetect} reads {@code breakHeld()} to decide {@code breakingEdge},
     * so every later edge with a break list got the {@link #WEDGE_TICKS}+breakTimeout leash and the
     * anti-stuck exemption even when its APPROACH was what wedged, the exact deadlock that reading
     * exists to refuse. Two ticks, not one: a phase that ends the tick early without reaching the
     * actuator (a place-off re-search kickoff, a breath bail) must not cost a server body its
     * accumulated progress, which {@code ServerPlayerBody.breakHold(false)} zeroes. Only a hold
     * the walker set is released; AntiSuffocate and the processes keep their own.
     */
    public static final int DIG_KEY_RELEASE_TICKS = 2;
    /** A dry stepUp / diagUp that has dwelt this many ticks WITHOUT closing on its node
     *  while grounded and laterally close to the step column is ramming the riser (the
     *  cur2≈0.64 freeze: pivotForStepUp keeps cutting forward on the noisy close-node
     *  bearing and the jump never clears). After this many stalled ticks, STOP pivoting
     *  and force a grounded jump straight at the column. ~1.2 s: a normal stepUp closes
     *  in <12 ticks so it never trips, yet this breaks a freeze far sooner than the ~6 s
     *  anti-stuck burst (which yanks the bot BACKWARD off the very step it needs). */
    public static final int STEPUP_FREEZE_TICKS = 24;
    /** task#82 dead-zone watchdog (AscendMovement, plan B1): delegated ascent-episode ticks with NO
     *  progress — no dy high-water gain, no horizontal gap-close beyond the episode's best, no active
     *  dig — before the machine returns UNREACHABLE and routes into the fellOffPath re-route. A
     *  healthy stepUp closes in well under STEPUP_FREEZE_TICKS (~1.2 s); 3× gives slow approaches and
     *  jump arcs full margin, while the task#82 pose (cur2 wedged in (0.45,4.0), no hCol, riser
     *  unbroken — every legacy gate misses it) times out in ~3.6 s instead of churning forever. Both
     *  progress marks are MONOTONIC high-waters, so a jump-land-slideback bob cannot keep resetting
     *  the clock (the crestOrbitTicks trick). Digging is exempt: bare-hand stone is 150 t+/block
     *  (#66) and an active BREAK is progress by definition. */
    public static final int ASCEND_DEADZONE_GIVEUP = STEPUP_FREEZE_TICKS * 3;
    /** Lateral-bank-follow: how many cells to scan along the bank (each way) for a mountable exit lip.
     *  Widened 6→10 (2026-06-28): tall +2 walls (e.g. -722 boxed-pinch) have their nearest steppable
     *  +1/flat exit further along the bank; a 6-cell reach missed it → 3-min floating deadlock. */
    public static final int BANK_FOLLOW_SCAN = 10;
    /** How little of the body's own sole may be on solid ground before the lethal-edge / bridge
     *  gate brakes, out of the 0.36 blocks² a player's 0.6-wide box has. Half a sole: a body
     *  centred on a one-wide ridge keeps the full 0.36 and one drifted more than 0.2 off that
     *  centre is past the point where an ordinary walk tick can recover. Measured on the nether
     *  crossing, where the two falls that ended runs launched from 0.118 and 0.000. */
    public static final double FOOTING_MIN = 0.18;
    /** No-step-progress ticks before the ≥2 ascentRamSlide desync recovers — DELIBERATELY well
     *  below STEPUP_FREEZE_TICKS. A node ≥2 above a GROUNDED foot is never a planned move (steps/
     *  parkour/pillar all rise +1), so it is ALWAYS an execution slide-back the bot can never
     *  jump-mount; waiting the full freeze-breaker window just lets the futile grounded jump bob
     *  (live 2026-06-24 round3: foot y84 / node y87 = +3, bounced jump→fall→jump ~32 ticks / 1.6 s
     *  on node -822,87,693 before the 24-tick gate let ascentRamSlide fire). noStepProgressTicks is
     *  jitter-immune (resets only on a real step-advance / path change), so a bot that is genuinely
     *  self-correcting never reaches even this low count — recovering here only ever fires on a
     *  truly frozen ram, and BEFORE the freeze-breaker wastes a jump on an unmountable step. */
    public static final int ASCENT_SLIDE_RECOVER_TICKS = 10;
    /** rawStepDwellTicks (bob-immune, resets only on step-change) a steep +2/+3 ascent slide-back must DWELL
     *  before {@link BotConfig#walkerAscentRamJitterImmune} folds it into fellOffPath. Higher than
     *  {@link #ASCENT_SLIDE_RECOVER_TICKS}=10 (which counts only non-progress ticks) because this counts EVERY
     *  tick on the step; 30 (~1.5 s) confirms a sustained ram (a transient 2-above overshoot resolves via
     *  within/passed in 1-3 t, resetting the dwell) while still recovering ~5× sooner than the slow
     *  {@link #WEDGE_TICKS}=100 burst the jitter-defeated noStepProgress gate falls back to. */
    public static final int RAM_JITTER_RECOVER_TICKS = 30;
    /** rawStepDwellTicks gap before {@link BotConfig#walkerAscentRamJitterImmune} may FOLD again within the
     *  SAME ram episode. Without a cap the recover fires every tick (10-85×/episode → a fellOffPath/repath
     *  cascade); but a pure once-per-step latch lets a STUBBORN ram — whose post-fold repath returns to the
     *  same step — never re-attempt, so it churns (live: -777 diagUp 244 t). A 20-tick (~1 s) debounce caps
     *  the rate to ~1 fold/s (no cascade) yet still re-attempts a stubborn ram every second (no long churn). */
    public static final int RAM_RECOVER_DEBOUNCE = 20;
    /** Ticks an in-place pillar-up recovery is latched once armed — long enough for a
     *  jump's airborne arc to crest and place a support (vanilla peak ~tick 6-8), short
     *  enough that it re-evaluates promptly. Re-armed each grounded tick while the bot is
     *  still below the next node beyond jump reach (see overJump). */
    public static final int PILLAR_RECOVER_TICKS = 14;
    // A pillar-recovery under a tree-canopy/dirt OVERHANG bobs in place without gaining height
    // (the recCeiling foot+2 break-probe misses a foot+3 ceiling; the vertical bob keeps resetting
    // the wedge timer) — 247 ticks looping at one canopy spot (live #47 replay -810,119, 339 pillarUp
    // total). Once the recovery hasn't risen for this many ticks, stop re-arming the pillar so the
    // foot-search re-routes around the overhang (blacklists the unreachable node) instead of forever.
    public static final int PILLAR_NORISE_GIVEUP = 50;
    /** A SLOW (but not hard-wedged) +1 diagUp converts to a deterministic pillar-up after this
     *  many no-step-progress ticks. On a steep √2 staircase the diagonal jump-traverse bob-rams
     *  the riser ~47 ticks/step (live 2026-06-24 west mountain) — visually a stutter — whereas
     *  placing a support under the foot + jumping is a clean +1. Set ABOVE STEPUP_FREEZE_TICKS so
     *  the cheaper grounded-jump freeze-breaker gets the first ~1.2 s to mount the step; only a
     *  diagUp still stalled past this escalates to the pillar. The dy==1 gate keeps it disjoint
     *  from the ≥2 ascentRamSlide (slide-BACK) case. */
    public static final int DIAGUP_PILLAR_TICKS = STEPUP_FREEZE_TICKS * 2;
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
    public static final int DEEP_PIT_ESCAPE_TICKS = 150;
    /** A pillarUp places its support directly below the bot and jumps in place, so the place
     *  only lands the bot on the fresh block when it stands over the node's column. Climb-drift
     *  can leave the bot 1-3 blocks to the side at the node's level; placing-in-place then rises
     *  onto a block BESIDE the column, the bot falls back, and it oscillates for seconds (the
     *  pillarUp-off-column wedge: live V2 5.8 s, J9 25 s). Squared XZ distance to the column
     *  beyond which the actuator aligns (walks to the column XZ) before pillaring; 0.25 = 0.5
     *  block, tight enough that the buoy-free vertical jump lands squarely on the placed block. */
    public static final double PILLAR_ALIGN_SQ = 0.25;
    /** noStepProgressTicks before the WALK overshoot-advance (crossedWalkNode) nudges a bot that
     *  has crossed a flat walk node and is orbiting it in the step-advance dead-zone past the
     *  wedge — short enough to cut the ~5.5 s wedge-repath wait, long enough that a normal walk
     *  (which advances via within/passed in 1-2 ticks, resetting noStepProgressTicks) never trips it. */
    public static final int WALK_OVERSHOOT_STUCK_TICKS = 16;
    /** noStepProgressTicks a bot pinned at a SHALLOW WATER-SURFACE step-down foothold must be stalled
     *  before {@link BotConfig#walkerWaterStepDownFloat} advances the step at the relaxed reach. Same
     *  spirit as {@link #WALK_OVERSHOOT_STUCK_TICKS}: a clean approach advances via within/passed in
     *  1-2 ticks (resetting noStepProgressTicks) and never trips it, so this ONLY rescues a confirmed
     *  buoyant bob-ram at the water surface. */
    public static final int WATER_STEPDOWN_STALL_TICKS = 14;
    /** Relaxed horizontal reach² for advancing a STALLED shallow water-surface step-down node
     *  ({@link BotConfig#walkerWaterStepDownFloat}). The buoyant body grounds vertically AT the node
     *  (|dY|≈0) but pins ~0.74 b short of centre (cur2≈0.55) against the surface ram, just over the
     *  tight {@link #REACH_DIST_SQ}=0.45. 1.2 (≈1.1 b) comfortably covers that pin yet stays well under
     *  {@link #OVERSHOOT_RESYNC_SQ}=4 so it can never skip a node the bot is still genuinely approaching
     *  / cut a live corner. */
    public static final double WATER_STEPDOWN_REACH_SQ = 1.2;
    /** noStepProgressTicks a bot ORBITING a +1 stepUp/diagUp CREST node must be stalled before
     *  {@link BotConfig#walkerStepUpCrestReach} advances the step at the relaxed crest reach. Same
     *  spirit as {@link #WATER_STEPDOWN_STALL_TICKS}/{@link #WALK_OVERSHOOT_STUCK_TICKS}: a clean
     *  stepUp tops out and advances via within/passed in <12 ticks (resetting noStepProgressTicks),
     *  so this ONLY rescues a confirmed crest bob-orbit, never a node still being climbed. */
    public static final int STEPUP_CREST_STALL_TICKS = 16;
    /** Relaxed horizontal reach² for advancing a STALLED +1 stepUp/diagUp crest node the bot has
     *  topped out on but ORBITS ({@link BotConfig#walkerStepUpCrestReach}). On a diagonal staircase
     *  crest (a +2 plateau lip) the body reaches the node's Y at the bob crest (|dyNode|≈0) but the
     *  buoyancy-free apex bob + the ±0.5 b lateral orbit keep cur2 pinned at ~0.49-1.2 — just over
     *  the tight {@link #REACH_DIST_SQ}=0.45 — so `within` never fires and `passed` never reads the
     *  next node STRICTLY closer while circling, freezing the step ~25-51 ticks (live -633,80,318:
     *  cur2 floor 0.492, py 79.0↔80.25 across node y80, 25 t orbit). 1.3 (≈1.14 b) comfortably covers
     *  that pin yet stays well under {@link #OVERSHOOT_RESYNC_SQ}=4 so it can never skip a node the
     *  bot is still genuinely approaching from afar / cut a live corner. */
    public static final double STEPUP_CREST_REACH_SQ = 1.3;
    /** noStepProgressTicks a buoyant bot ORBITING/FROZEN at a flat {@code walk} WATER-SURFACE node must be
     *  stalled before {@link BotConfig#walkerWaterWalkReach} advances the step at the relaxed reach. Same
     *  spirit as {@link #STEPUP_CREST_STALL_TICKS}: a clean surface crossing advances each walk node via
     *  {@code passed} (forward momentum) in 1-2 ticks — noStepProgressTicks (HORIZONTAL-only in water,
     *  Walker:~1666, so the bob can't fake-reset it) stays low — and never trips it; only a turn / terminal /
     *  WALL-CORNER freeze where neither {@code within} nor {@code passed} can fire accumulates it. */
    public static final int WATER_WALK_STALL_TICKS = 24;
    /** Relaxed horizontal reach² for advancing a STALLED flat {@code walk} water-surface node a buoyant body
     *  orbits ({@link BotConfig#walkerWaterWalkReach}). The surface swimmer sits ~0.67 b out (cur2 floor
     *  ~0.455, just over {@link #REACH_DIST_SQ}=0.45) and at a wall-corner freezes farther out still (live
     *  deep-water bay corner: cur2 1.142 frozen 321 t, within=0, aim swinging 403°). 1.3 (≈1.14 b) covers both
     *  yet stays well under {@link #OVERSHOOT_RESYNC_SQ}=4 so it can never skip a node the bot is still
     *  genuinely swimming toward from afar / cut a live corner. */
    public static final double WATER_WALK_REACH_SQ = 1.3;
    /** Eye-to-centre reach for the mid-leap parkour floor place ({@code WalkerTickClimb}). Was an
     *  inline {@code < 16} with the comment "~4 blocks of the eye"; named here so it sits beside
     *  the other reach numbers instead of hiding as a magic square. Deliberately short of the
     *  {@code blockInteractionRange + 0.5} the game actually grants: this fires while AIRBORNE, so
     *  the eye it measures from has already moved by the time the place resolves, and a place that
     *  is refused mid-leap costs one tick while a place that misses costs the landing. See
     *  {@code BotUtil#standingEye} for the full table of what each reach site in this repo pays. */
    public static final double PARKOUR_PLACE_REACH = 4.0;
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
    public static final int APPROACH_NODE_AIM_TICKS = 12;
    /** noStepProgressTicks a floating bot must be RAMMED (in-water hCol) before the lateral-pad
     *  break ({@link BotConfig#walkerPadRamBreak}) scans the body-overlap columns for a lily pad the
     *  head-on pad scan missed. Long enough that a normal swim through clean water — which advances its
     *  step every 1-2 ticks and resets noStepProgressTicks — never trips it (so a transient brush past
     *  a pad while still moving isn't broken), short enough to cut the ~270-tick (13.5 s) repath freeze
     *  the unbroken lateral pad otherwise causes. */
    public static final int PAD_RAM_STALL_TICKS = 10;
    /** Anti-spin: consecutive in-water repaths with no goal-progress before the camera
     *  heading is FROZEN. A failed water climb-out makes every repath return a
     *  swim-back/circle best-effort; following each one U-turns the bot and the
     *  repeated U-turns wind the camera (the water spin). Past this count the heading
     *  is held steady (see the heading block) — MC movement follows body yaw, so a
     *  frozen heading also steadies the bot pressing toward the climb-out instead of
     *  whipping around. Small so the spin is killed within ~1-2s of churn. */
    public static final int CHURN_REPATH_CAP = 3;
    /** Anti-spin: consecutive in-water repaths with no goal-progress before the goto
     *  gives up best-effort (ARRIVED) rather than pressing a wall forever. Well above
     *  {@link #CHURN_REPATH_CAP} so the freeze gets a fair chance to let the bot grind
     *  through a hard climb-out before we conclude it's truly walled. */
    public static final int CHURN_GIVEUP_CAP = 12;
    /** Fresh anti-spin baselines granted after a water stall before truly giving up —
     *  a legitimate go-around (e.g. rounding a lava arm via the river) raises the
     *  goal-distance for a dozen repaths and must not end the journey; only a churn
     *  that stalls through ALL fresh baselines is the unwinnable spin. */
    public static final int CHURN_MAX_RESETS = 3;
    /** DRY-LAND boxed-pocket churn detector — a fixed time window over which NET XZ
     *  displacement is measured. A per-repath "no goal-progress" counter is defeated
     *  by the pocket's limit-cycle: the bot pillars up a wall to an XZ-closer column
     *  (which RESETS a goal-distance counter), then falls back, looping 1416↔1454
     *  forever with net-zero ground travel (live round73). Net displacement over a
     *  window is immune to that oscillation. CHURN_WINDOW≈20 s, CHURN_MIN_MOVE_SQ =
     *  (8 blocks)²: a real journey clears 8 blocks/20 s even on hard terrain; a pocket
     *  churn does not. */
    public static final int CHURN_WINDOW = 400;
    public static final int CHURN_MIN_MOVE_SQ = 64;
    /** Wall-corner fast-churn (walkerWallCornerFastChurn): consecutive sustained-hCol ticks before the
     *  net-displacement churn window is SHORTENED to {@link #WALL_CHURN_WINDOW}. The stuck-against-a-wall stall
     *  (rocky/dirt/water-boundary wall-corner) makes NO net XZ progress with hCol pinned true, but the
     *  node-relative stuck counters (totStuck, noStepProgressTicks) get RESET by the orbit's node-churn so
     *  the 20s window is the only thing that catches it — too slow (live journey-A: 20-45s per stall). A
     *  SUSTAINED ram (hCol true ≥4s continuous) is the unambiguous wall-corner signature; gating the
     *  short window on it fires the existing blacklist+escalate in ~8s WITHOUT touching the legitimate
     *  slow-but-moving case (hCol=false → full 20s window), which is exactly why the unconditional
     *  walkerFasterChurnRepath was reverted. */
    public static final int HCOL_RAM_TICKS = 80;
    public static final int WALL_CHURN_WINDOW = 160;
    /** Net altitude gain (blocks) over a CHURN_WINDOW that still counts as a real climb.
     *  The churn charge normally needs a best-effort path, but a GOAL-REACHING path can
     *  ALSO limit-cycle: at a steep mountain base the XZ heuristic baits A* into cheap
     *  goal-ward canyon/cave floor-walks (live z3160: bob y62-68, net ground travel ≈0,
     *  never ascends toward an XZ goal high on the far side). Firing the charge whenever
     *  net XZ is tiny AND the bot gained ≤ this much altitude catches that base-oscillation
     *  / cave-descent without ever penalising a genuine upward climb (which gains ≫ this). */
    public static final int CHURN_MIN_Y = 4;
    /** How long (ticks ≈ 60 s) a single detected boxed churn keeps the steep-barrier
     *  planner escalation armed. Sticky so a couple of wandering windows that briefly
     *  show net progress mid-climb don't drop the escalation before the climb completes. */
    public static final long BOXED_ESCALATE_STICKY_TICKS = 1200;
    /** PROACTIVE PINCH escalation (progressive pinch prediction, gated by {@link BotConfig#pathfinderProgressive}):
     *  if a BIG search comes back best-effort having closed less than this many blocks of
     *  goal distance, the planner is wedged at a pinch and a low budget will keep
     *  re-committing the shallow scrap the bot churns on. Arm the deep-search escalation
     *  on that FIRST struggling commit — instead of waiting ~20 s for the reactive
     *  churn-window. A healthy segment clears far more than this (horizon commits ≈48
     *  blocks), so an open cruise never trips it. Matches the churn move threshold (8). */
    public static final double PINCH_MIN_PROGRESS = 8.0;
    /** Bounded fresh re-searches at a loaded-chunk frontier before giving up (the
     *  bot is stationary while waiting, so a couple of tries is plenty — see
     *  {@link #frontierHoldOrArrive}). */
    public static final int FRONTIER_WAIT_CAP = 3;
    /** Ticks between quick-start stub attempts after one finds nothing useful —
     *  a boxed-in micro-search keeps finding nothing until the terrain context
     *  changes, so retrying every tick would just burn frames. */
    public static final int QUICK_RETRY_TICKS = 10;
    /** Hard wall-clock cap for one synchronous quick-start stub search; the node
     *  cap ({@link BotConfig#pathfinderQuickNodes}) normally lands well under it. */
    public static final long QUICK_MAX_MS = 80;
    /** Open-water bee-line stub length: how many surface cells to march toward the
     *  goal when the sliced A* can't keep up over deep water. Long enough that the
     *  bot (sprint-swim ≈5.6 b/s) has multiple seconds of runway before consuming it,
     *  so it never outruns its own pathfinding and burst-crabs. */
    public static final int BEELINE_MAX_STEPS = 24;
    /** Don't bother adopting a bee-line shorter than this (a 1-3 cell run is just the
     *  bot already at a bank — let the normal search/climb-out own it). */
    public static final int BEELINE_MIN_STEPS = 4;
    /** Foot-to-current-node dist² (blocks²) past which a WATER-wedged bot is treated as
     *  having sprint-swum PAST its committed segment (vs pinned AT a node it can't
     *  reach) — the trigger to drop the stale segment and adopt an open-water bee-line.
     *  16 = 4 blocks: well past the 0.45 reach gate, comfortably short of the 30-block
     *  overshoot the burst-crab leaves. */
    public static final double BEELINE_OVERSHOOT_SQ = 16.0;
    /** Max ticks to hold a "no path" verdict while self-inflicted stuck-penalties
     *  decay (15 s window) before genuinely failing — three full decay windows. */
    public static final int NO_PATH_WAIT_CAP = 900;
    /** Max blocks BELOW a still-valid route from which the fall-below recovery
     *  pillars back up instead of re-searching (a deeper fall is a real
     *  divergence — the route above is likely stale). */
    public static final int PILLAR_RECOVER_MAX_DY = 8;
    /** Min reduction in distance² (blocks²) to the current node that counts as real
     *  forward progress for the {@link #stuckTicks} no-progress timer. Set above the
     *  sub-0.1 b/tick position jitter of a treading / water-creeping bot but below a
     *  normal sprint step, so slow-but-steady advance (esp. ~0.08 b/tick in water)
     *  keeps resetting the timer and never trips the reCentre / wiggle recovery. */
    // 0.02->0.05 (C40-J1 82s stall): a wall-pinned CREEP (hCol, hSpd 0.001, ~0.01 blk/tick)
    // 1.5 blocks from the node lowers sd2 by ~2*1.5*0.01=0.03 per tick — above the old
    // margin, so the crawl read as "real progress" every tick and the stall clock pinned
    // at 2 (the 4th starvation side-door after the three step-jitter resets). 0.05 still
    // clears a genuine slow approach: a 1.5 b/s water cruise only starves inside 0.33
    // blocks (2*d*v < 0.05 -> d < 0.33), well within the arrival gate.
    // DRY ONLY (§94 regression, git-bisect convicted on waterFarAimBankCornerArena):
    // in water the lateral speed while rounding an obstacle is ~0.02-0.05 blk/tick, so
    // 0.05 starves every water node approach — the stall clock trips mid-manoeuvre and
    // the recovery/repath cycle pins the bot on the obstacle corner (deterministic
    // dGoal=6.33 pin, 07-03..07-06). Water keeps 0.02 via STUCK_PROGRESS_EPS_WATER.
    public static final double STUCK_PROGRESS_EPS = 0.05;
    /** Water twin of {@link #STUCK_PROGRESS_EPS} — the pre-C40-J1 value. The C40-J1
     *  wall-pinned-creep starvation that 0.05 fixes was a DRY pathology (hCol creep on
     *  land); water's naturally slow manoeuvring must not read as "no progress". */
    public static final double STUCK_PROGRESS_EPS_WATER = 0.02;
    /** Per-step ticks of bob-stalling before the water climb-out actuator places
     *  a foothold to ground a floating bot against a too-high bank. Keyed off the
     *  per-step no-progress timer ({@code totalTicks}, which a bob can't reset —
     *  unlike {@code stuckTicks}, which the oscillating foot-Y clears each cycle).
     *  Well under {@code walkerTotalTickBudget}, comfortably past a normal flush /
     *  staircase climb-out (grounds in <10 ticks, never stalls). */
    public static final int WATER_CLIMB_STALL = 30;
    /** Max horizontal (Chebyshev) distance from the floating foot to the climb waypoint for the
     *  water climb-out to engage ({@link net.magicterra.worlddriver.bot.BotConfig#walkerWaterClimbLateralGate},
     *  task#91). A genuine bank climb-out has its waypoint directly beside/below the float
     *  (Chebyshev 0-1); a HIGHER waypoint that is laterally farther is the routed exit further
     *  down an open corridor (riverSheerBank: the low bank +5 EAST across open water, only +1
     *  up) and must be reached by SWIMMING to it, not by trenching the sheer wall the bot is
     *  merely passing. 2 admits a diagonal-adjacent bank / +2 staircase step while still
     *  excluding the ≥5-cell lateral exits; the swim-drive carries the body along the corridor
     *  and the climb re-arms once it swims adjacent (self-healing). NOTE: 2 is a judgment
     *  floor, not an A/B-measured boundary — the task#91 wedge sits at Chebyshev 5 and any
     *  value in [2,4] passes the scene; the water family is byte-identical at 2. If a future
     *  wedge appears at Chebyshev 3-4, re-derive this bound with a K>=6 A/B, don't nudge it. */
    public static final int WATER_CLIMB_LATERAL_MAX = 2;
    /** Stall threshold for the LAST-RESORT block-less bank DIG (vs the with-block
     *  pillar takeover at {@link #WATER_CLIMB_STALL}). Much higher so the dig is a
     *  genuine deadlock-breaker, not a first response: a buoyant climb-out that the
     *  bob or an anti-stuck burst resolves within a few seconds must NOT trip it, or
     *  in a long water canyon (every far bank a climb-out) the no-block bot turns
     *  into a compulsive digger — live journey fired it 1520× across ~6 min, gouging
     *  terrain + thrashing the climb/dig aim into camera judder, while burst-crab
     *  alone would have crossed many of those banks. Only a bank still un-mounted
     *  after ~4 s (bob + burst both failed) is a real wedge worth digging. */
    public static final int WATER_CLIMB_DIG_STALL = 80;
    /** Per-riser tick budget the toolless climb-out STAYS committed to breaking ONE latched
     *  riser, even while A* transiently repaths the climb away (wantClimb flicker). A buoyant
     *  bot mining a STONE bank by hand takes ~750 ticks/block (×5 not-on-ground penalty); any
     *  mid-break disengage drops the half-broken riser, so before this the bot abandoned each
     *  block partway, wandered 10+ columns, and drifted into a deep hole and sank (live
     *  2026-06-20: one stone block dug 517× then dropped; y62→y27). 1000 covers stone + margin;
     *  past it a genuinely stuck dig releases to repath. Reset per riser so a multi-block climb
     *  gets a fresh budget for each step. */
    public static final int WATER_CLIMB_DIG_COMMIT_CAP = 1000;
    /** Futile-overhang bank-dig early-release window (ticks), gated by
     *  {@link BotConfig#walkerFutileBankDigRelease} (default OFF → inert). A dig committed to one
     *  still-fully-solid {@code >= FUTILE_BANK_DIG_MIN_RISE}-above-foot riser for this many ticks
     *  WHILE the bot never grounded = an unreachable overhang it can never break (live -784: 1001
     *  swings, 0 breaks, onGround=false throughout). Well below {@link #WATER_CLIMB_DIG_COMMIT_CAP}
     *  (1000) so the bot recovers ~40 s sooner, yet a LEGIT +1 climb-out dig never reaches it (its
     *  riser is +1 — below the rise gate — and the bot grounds on the freed notch, resetting the
     *  never-grounded guard, and the riser BREAKS, resetting the tick counter). 200 ≈ 10 s. */
    public static final int FUTILE_BANK_DIG_TICKS = 200;
    /** Minimum riser-above-foot height (blocks) for the futile-overhang early-release to consider a
     *  dig hopeless. The legit toolless climb-out digs the LOWEST solid cell just above the water
     *  line (a +1 reachable notch); only a {@code >=2}-above-a-floating-foot riser is the
     *  unreachable overhang the buoyant bob can never break or ground on. */
    public static final int FUTILE_BANK_DIG_MIN_RISE = 2;
    /** Ticks the bank-DIG is blocked from re-engaging after a futile-overhang early-release
     *  (see {@link BotConfig#walkerFutileBankDigRelease}) so the reactive back-off burst can
     *  shove the bot off the unreachable riser before any dig re-latches it. ~1.5 s. */
    public static final int FUTILE_BANK_DIG_COOLDOWN = 30;
    /** Chebyshev radius searched around an UNSTANDABLE Goal.Block target for the nearest
     *  standable cell to snap to (see {@link #snapGoalToStandable}). 6 covers a goal a few
     *  blocks inside a hill / under a thin ceiling while staying cheap (one (2R+1)^3 scan
     *  per goal). Too small misses deeper burials; too large risks snapping past a wall to
     *  an unrelated pocket — A* still has to reach it, so an unreachable snap just fails as
     *  before, no worse than no snap. */
    public static final int GOAL_SNAP_RADIUS = 6;
    /** Fast dig-engage threshold when the bot is FLOATING over deep water (water directly
     *  below the foot). There a buoyant bot physically cannot swim-jump a +1 bank — the
     *  dig is the ONLY exit — so there is no point bob-stalling the full {@link
     *  #WATER_CLIMB_DIG_STALL} (~4 s) first; engage in ~1 s. Still requires a solid riser
     *  being rammed in a climb-out context, so open-water cruise (no adjacent solid bank)
     *  never trips it. Shallow water keeps the slow threshold (a swim-jump may still mount
     *  a low bank, so give it the benefit first). Cuts the dominant per-bank stall: the
     *  deep-water climb-out arena dropped its 80-tick wait, ashoreTick 98→~40. */
    public static final int WATER_CLIMB_DIG_DEEP_STALL = 20;
    /** Eye-Y bob (blocks) past which the block-less bank dig RE-AIMS its once-snapped look
     *  at the riser. The dig only runs while FLOATING, where buoyancy + a jump bob the eye
     *  ±0.9..1.5; the once-only snap then points the fixed ray OFF the 1-tall riser face
     *  (eye rises → ray passes above it) so the break never lands and the bot bobs ~30 s.
     *  ~0.4 ≈ the drift that walks the ray off a 1-block face → re-snap to hold it on, while
     *  staying far above the per-tick re-aim that judders the camera (so the view does not jump). */
    public static final double DIG_REAIM_EYE_DY = 0.4;
    /** Ticks the pillar takeover may bob WITHOUT a successful place before it's judged
     *  futile here (a buoyant bot can't lift its feet above a surface fill cell) and the
     *  bank-DIG takes over. ~50 ticks past the WATER_CLIMB_STALL engage ≈ the same ~4 s
     *  last-resort window as WATER_CLIMB_DIG_STALL, so place-banks and dig-banks converge
     *  on the dig at the same patience. */
    public static final int PILLAR_FUTILE_TICKS = 50;
    /** Ticks a body that swam INTO its own pending side foothold treads water for the server's
     *  verdict on that click before the takeover asks for a rung elsewhere; the server judged the
     *  click at its lagging copy of the body and often lands it (walkerShallowWaterSideFoothold). */
    public static final int PENDING_RUNG_WAIT_TICKS = 6;
    /** Horizontal speed² under which a body standing dry on its rung may jump for the next one; a
     *  jump taken with the swim's momentum still in it carries the body off a 1×1 rung. */
    public static final double SETTLED_SPEED_SQ = 0.05 * 0.05;
    /** Horizontal margin the body's box must keep from a candidate side foothold cell; vanilla
     *  refuses a block that meets the body, and the body drifts a tick or two before the answer. */
    public static final double SIDE_RUNG_CLEARANCE = 0.1;
    /** Grace ticks the "in a water climb-out" state stays LATCHED after the last
     *  water contact. A bob-cycling climb-out breaches the surface every cycle (head
     *  clears water, feet top a just-placed foothold), so the per-tick water test
     *  FLICKERS false at each bob peak; without this grace the stall counter reset on
     *  every flicker and the pillar takeover engaged only after 1-2 MINUTES of bobbing
     *  (live round71). 12 ticks (0.6 s) spans a bob peak without masking a genuine
     *  walk-away onto dry land. */
    public static final int WATER_TOUCH_STICKY = 12;
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
    public static final int WANT_CLIMB_STICKY = 12;
    /** Ticks after the jump press before placing the pillar block beneath —
     *  by then the player has cleared the old feet cell (matches TowerProcess). */
    public static final int PILLAR_PLACE_DELAY = 3;
}
