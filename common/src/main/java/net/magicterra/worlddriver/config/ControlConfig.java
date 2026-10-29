package net.magicterra.worlddriver.config;

import net.magicterra.worlddriver.api.methods.setting.ControlSettings;

/**
 * Settings read by the executor, the Navigator and its tick phases ({@code bot/control}).
 * Each sits in the lowest layer that reads it; {@link BotConfig} enumerates, persists and pins them all.
 */
public final class ControlConfig {
    private ControlConfig() {}

    /** Navigator repath cadence. Lower = more responsive to obstacles, higher = less CPU. */
    public static volatile int navigatorRepathEveryTicks = ControlSettings.DEFAULTS.navigatorRepathEveryTicks();

    /** Navigator total-tick safety budget; if no progress within this many ticks, fail. */
    public static volatile int navigatorTotalTickBudget = ControlSettings.DEFAULTS.navigatorTotalTickBudget();

    /** Consecutive completed A* searches with NO goal-distance improvement AND no bot
     *  displacement (>2 blocks) before the Navigator fails the journey as unreachable
     *  (gap #49-③). The tick budget above bounds the same loop, but it counts TICKS
     *  while each churn cycle burns a full search (live: 129 searches / ~36 s of A* CPU
     *  inside the 62 s wait; an arena with big slices stretched the same loop past
     *  9 minutes) — this counts the searches themselves, ends the journey in seconds,
     *  and reports a reason the agent can tell apart from a transient stall ("no route
     *  progress" vs "no progress for N ticks"). Real journeys reset the counter every
     *  time the bot moves >2 blocks or the best goal distance improves. Each futile
     *  search also arms a short exponential repath backoff so the wait itself stops
     *  burning CPU. 0 disables both. */
    public static volatile int navigatorFutileSearchCap = ControlSettings.DEFAULTS.navigatorFutileSearchCap();

    /** Consecutive ascent dead-zones from the SAME foot for the SAME node before the walk fail-stops.
     *
     *  <p>{@link #navigatorFutileSearchCap} cannot cover this case — it is gated on the search NOT
     *  reaching the goal, and a dead-zone is what happens when the search reaches it and the
     *  executor then refuses the edge. Measured on journey rung 20: a bot perched on a 0.16 sole
     *  beside the void spent 2400 ticks re-routing to the same {@code diagUp} node, silently, while
     *  every guard that could have moved it correctly declined. Bigger than the search cap because a
     *  dead-zone can be transient — a re-route from a slightly different position may legitimately
     *  produce a reachable edge — and the counter only advances while BOTH the foot cell and the
     *  target node are unchanged. 0 disables. */
    public static volatile int navigatorAscendDeadZoneCap = ControlSettings.DEFAULTS.navigatorAscendDeadZoneCap();

    /** Stride floor-guard (gap #53, the 2026-07-12 survival death; same family as #51):
     *  while GROUNDED and dry, if the cell one stride ahead along the drive heading has no
     *  floor within {@link RulesConfig#pathfinderMaxDryFall}+1 below — a drop the planner can never
     *  have routed (Fall.valid caps at maxDryFall), so the exposure is always UNPLANNED —
     *  hold vanilla sneak (its maybeBackOffFromEdge pins the bot at the edge) and, when a
     *  placeable is in inventory and allowPlace is on, plug the well mouth so the crossing
     *  becomes real (backfill-as-you-go, the Baritone MovementPillar behaviour). The live
     *  death: stairUpBreak dug two hollow columns and routed an UP node across the first
     *  one's open mouth; the #36 brake only checks the drive TARGET's dY (+1, up), never
     *  the real drop under the stride — the bot fell 10 blocks and died. Planned descents
     *  (waypoint below foot in the stride column) and parkour launches are exempt. */
    public static volatile boolean navigatorStrideFloorGuard = ControlSettings.DEFAULTS.navigatorStrideFloorGuard();

    /** Floor-gate the UNAIMED recovery hops (stuck-wiggle jump, unstuck displacement-burst
     *  jump): skip the jump when the arc would COME DOWN in a lethal drop column — probed
     *  along the drive bearing by {@code NavigatorGeometry.hopSuppressed}. ⚠️ It was a
     *  Chebyshev-≤2 ring around the foot until 2026-08-26; that radius held through a
     *  three-minute deterministic stall while letting a ring-3 hop carry a journey-ladder bot into
     *  a lava lake, so the reach was not widened, it was re-centred on where the arc lands.
     *  Those hops launch a ballistic arc along whatever the current (often mid-slew) heading
     *  is; on 1-wide elevated footing that arc clears the deck and the stride floor-guard
     *  cannot help (it only sees GROUNDED velocity — the jump rewrites it after launch).
     *  Bridge-battery t0 2026-07-20: every shed (breach@t=81/609) was a wiggle-window
     *  sprint-jump from a floored cell one stride inside the rim. AIMED jumps (stepUp,
     *  parkour, riser breakers) are untouched — hurdle-on-a-bridge legitimately jumps.
     *  Suppression degrades to a grounded stall → futile cap → honest repath/FAILED. */
    public static volatile boolean navigatorRecoveryHopFloorGate = ControlSettings.DEFAULTS.navigatorRecoveryHopFloorGate();

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
    public static volatile boolean navigatorFromEndNoProgressDiscard = ControlSettings.DEFAULTS.navigatorFromEndNoProgressDiscard();

    /** A best-effort tail is consumed only once the bot is past it along the path, or has
     *  stalled; OFF consumes it on distance, which spends the far tail of a walk past the
     *  planner's horizon on its first tick ({@code wd.bridgeLongFlatWalk}). */
    public static volatile boolean navigatorTailConsumeDirectional = ControlSettings.DEFAULTS.navigatorTailConsumeDirectional();

    /** Corner-clearance repulsion in the walk drive: pure-pursuit cuts corners by design,
     *  and the 0.6-wide player then grazes solid corners the carrot line passes within
     *  half-width of — the flat wall-corner wedge (§39's corner-cut variant; bridge
     *  battery bypass trio wedged on a barrier corner at z offset 0.91 with recovery
     *  hops floor-gated). Blends a small capped push away from any solid player-height
     *  cell whose closest face point is within 0.45 of the player's centre — PREVENTING the
     *  graze instead of escaping the wedge. Self-limiting: cell-centred walking beside a
     *  wall sits at ≥0.5 (no push) and corridor pushes cancel. */
    public static volatile boolean navigatorCornerClearance = ControlSettings.DEFAULTS.navigatorCornerClearance();

    /** Yaw delta below this is not written each tick — reduces jitter when already aligned. */
    public static volatile float navigatorYawHysteresisDeg = (float) ControlSettings.DEFAULTS.navigatorYawHysteresisDeg();

    /** Dry descent: camera on a stable path trend (no downhill spin); see {@code NavigatorConstants.DESCENT_CAM_FAR_DIST}. */
    public static volatile boolean descentCameraDecouple = ControlSettings.DEFAULTS.descentCameraDecouple();
    /** The dry trend centroid steers only while the body has room along it; see {@code AimPhase.headingClear}. */
    public static volatile boolean navigatorTrendNeedsRoom = ControlSettings.DEFAULTS.navigatorTrendNeedsRoom();

    /** Extend {@link #descentCameraDecouple} to ALL dry launches (parkourDescend / parkour / fall):
     *  the camera SLEWS smoothly through the turn instead of snapping ~180° to the landing node
     *  (the spin while falling or jumping). Safe because main-block leaps drive via commandMove (camera-decoupled),
     *  so the slewing camera never misses the landing — parkourPlace (commandForward) keeps its snap.
     *  Ground-truth replay A/B on the RENDERED camera (LookController): render-snaps≥30° 13→0,
     *  ≥90° 7→0; both runs reach the goal + all parkour/fall GameTest arenas pass. */
    public static volatile boolean descentDecoupleLaunches = ControlSettings.DEFAULTS.descentDecoupleLaunches();

    /** Sustain a vine climb on a FREE-HANGING (wall-less) vine — one that hangs from leaf
     *  canopy above with NO solid horizontal neighbour at climb height (live -711,67: an oak-leaf
     *  draped vine over a 1-deep water pocket). Vanilla vine ASCENT only forces {@code vy=+0.2}
     *  while {@code (horizontalCollision || jumping) && onClimbable} (LivingEntity.travel); on a
     *  WALL-BACKED vine the forward press into the wall keeps {@code horizontalCollision} live AND
     *  pins the bot in the 1-wide column. On a wall-less vine there is no wall to ram, so the
     *  forward press (aimed at the path-ahead node) instead WALKS the bot horizontally OUT of the
     *  column — it rises ~0.2, leaves {@code onClimbable}, gravity resumes, and it DETACHES into the
     *  pocket below (live: ~0.6 rise → fall → ~122 s / totStuck 2400+ bob-churn in the pocket). With
     *  this ON, a wall-less ascent instead JUMPS continuously (every tick → {@code jumping} sustains
     *  the +0.2 climb) and CENTER-SEEKS the vine column (aim/press toward the cell centre so any
     *  residual horizontal drive pushes INTO the column, re-centring, never ejecting) until it tops
     *  out level with the path-ahead exit, then steps off. Wall-BACKED vines keep the existing
     *  {@code vineWallYaw} wall-press path untouched (so the vineClingFidelityProbe is unaffected).
     *  Default ON; toggle for the deterministic vine-over-water A/B (mc.bot.setting). */
    public static volatile boolean navigatorVineFreeHangClimb = ControlSettings.DEFAULTS.navigatorVineFreeHangClimb();

    /** Grab a FREE-HANGING vine at the parkour LANDING apex — the "land-on-vine handoff". A
     *  {@code parkourAscend} that LANDS the bot ON a climbable vine still carries {@code parkourEdge=true}
     *  at the apex, so the plain {@code !parkourEdge} gate on the vine handler leaves NOTHING driving the
     *  cling at the one instant it matters: the bot touches the vine (foot=vine, climbable) but, with no
     *  handler engaged, gravity immediately pulls it PAST the vine into the 1-deep water pocket BELOW the
     *  curtain (live -711,67: parkourAscend2 onto the vine y63 UNDERSHOOTS to the pocket floor y60-61, where
     *  {@code foot=water} is not climbable so {@link #navigatorVineFreeHangClimb} can NEVER re-engage → the bot
     *  must slowly swim back UP to the vine before the climb fix kicks in → ~40 s of pocket churn). With this
     *  ON, the vine handler is permitted on a parkour edge the moment the bot has LANDED on the climbable
     *  column — foot=vine, airborne (!onGround), and arrested horizontally over the landing node (XZ within
     *  {@code OVERSHOOT_RESYNC_SQ}) — so the grab + {@link #navigatorVineFreeHangClimb} sustain fire on the
     *  SAME tick the foot first becomes the vine, before gravity pulls it past. It CANNOT fire mid-leap (over
     *  a parkour GAP the foot is air, not climbable). Strictly inert when the bot has NOT landed on a vine
     *  (identical to the old {@code !parkourEdge} gate), so non-parkour vine traversal + wall-backed vines are
     *  unaffected. Default ON; toggle for the deterministic vine-LANDING A/B (mc.bot.setting). */
    public static volatile boolean navigatorVineLandGrab = ControlSettings.DEFAULTS.navigatorVineLandGrab();

    /** Release the vine CLING when the immediate committed node is NOT a climb — a "descend off the
     *  hanging curtain" gate. The vine handler's {@code climbUp} is computed from the {@code step+2}
     *  look-ahead node ({@code ahead.y >= foot.y}); when the path skims a bank/inlet at one Y while a
     *  vine curtain HANGS over that bank, {@code ahead.y} sits exactly at the buoyant bot's bob floor,
     *  so {@code climbUp} OSCILLATES with the y-bob (foot.y 64↔65, ahead.y 64): the bot jumps UP at the
     *  low tick, slides DOWN at the high tick, pinned ON the vine with ZERO XZ progress toward the
     *  actual node — the live -672,64,311 inlet bob (≈200 ticks / 10 s; a {@code stepDown} node just
     *  WEST of a z311 vine curtain over a shallow water inlet, the bot bobbing y64↔65 in the vine while
     *  the committed node is the level/down step off the bank). With this ON, the cling forces
     *  {@code climbUp=false} whenever the IMMEDIATE node {@code wp} is at/below the foot ({@code wp.y <=
     *  foot.y}), so the bot drops off the vine onto the bank / into the inlet and the normal
     *  walk/stepDown resumes (the bank below the bob is solid → it grounds → the {@code !onGround} gate
     *  ends the cling). SCOPED to a non-climb intent: a genuine vine ASCENT always has the next node
     *  ABOVE the foot ({@code wp.y > foot.y}) — the {@link #navigatorVineFreeHangClimb} free-hang curtain
     *  climb (-711) and the {@code vineOverWaterClimbArena} climb-out both ascend, so {@code wp.y >
     *  foot.y} there and the cling is untouched. Strictly inert when OFF, and even ON it only fires when
     *  the bot is on a vine with a non-ascending immediate node. Default ON; flip OFF via
     *  {@code mc.bot.setting} for the inlet vine-bob A/B. */
    public static volatile boolean navigatorVineDescentDrop = ControlSettings.DEFAULTS.navigatorVineDescentDrop();

    /** Ticks the breaker waits before blacklisting a stuck block. */
    public static volatile int breakTimeoutTicks = ControlSettings.DEFAULTS.breakTimeoutTicks();

    /** Air-supply threshold (ticks; vanilla max 300) at/below which
     *  {@link ArbitrationConfig#autoDrownEscape} preempts an ACTIVE process. Deliberately far
     *  BELOW {@link BehaviorConfig#drownFloatAirThreshold} (240): an active goto/mine crossing
     *  water on purpose must not be interrupted while it still has a healthy
     *  reserve; 100 ≈ 5s of air left, and the preemption stops the process's own
     *  drive entirely so the whole reserve goes to the ascent. gap#76. */
    public static volatile int drownEscapeAirThreshold = ControlSettings.DEFAULTS.drownEscapeAirThreshold();

    /** Per-tick compute slice (ms) for the time-sliced A* search. The Navigator
     *  advances an in-flight search by at most this much each client tick, so a
     *  big search spreads over frames instead of blocking the render thread in
     *  one go (no stutter). The total search is still bounded by
     *  {@link PlanningConfig#pathfinderMaxNodes}/{@link PlanningConfig#pathfinderMaxMs}. ~6 ms keeps a tick
     *  well under one 16 ms frame. */
    public static volatile long pathfinderSliceMs = ControlSettings.DEFAULTS.pathfinderSliceMs();

    /** Per-tick compute slice (ms) used ONLY while the bot is IDLE waiting for a path (segment
     *  consumed, or no path yet). {@link #pathfinderSliceMs} (~6 ms) protects the frame-rate WHILE
     *  WALKING, but at a far-goal segment boundary that thin slice drags a 3 s CPU search out to
     *  ~27 s of wall-clock with the bot FROZEN (act → freeze → re-plan → act). With nothing to keep smooth,
     *  a few frame hitches are cheaper than the wait: finish ~5× sooner, still rendering between slices. */
    public static volatile long pathfinderIdleSliceMs = ControlSettings.DEFAULTS.pathfinderIdleSliceMs();
    /** Dry-descent heading EMA; the tangent-aim drive follows it, and 0.08 walked a one-wide stair off (1512.7,76,-240.5). */
    public static volatile double navigatorDescentYawAlpha = ControlSettings.DEFAULTS.navigatorDescentYawAlpha();
    /** Half-width {@code PathSmoothing.losWalkableBody} sweeps; 0.3 is the box exactly, leaving no margin. */
    public static volatile double navigatorCarrotBodyHalfWidth = ControlSettings.DEFAULTS.navigatorCarrotBodyHalfWidth();
    /** Half-width {@code AimPhase.trendWithRoom} sweeps a heading at; 0.3 = the body's own, no margin. */
    public static volatile double navigatorHeadingClearHalfWidth = ControlSettings.DEFAULTS.navigatorHeadingClearHalfWidth();
    /** The path projection also scans the leg the bot is on, not only the ones past its node; see {@code PathProjection.compute}. */
    public static volatile boolean navigatorProjectCurrentLeg = ControlSettings.DEFAULTS.navigatorProjectCurrentLeg();
    /** Take a stair flight riser by riser: carrot and trend centroid stop at its first riser, and a +1 jump aims at its node until it lands. */
    public static volatile boolean navigatorCarrotStopAtRise = ControlSettings.DEFAULTS.navigatorCarrotStopAtRise();
    /** Airborne on the step before a stairUpBreak, start its dig so the last break's destroyDelay burns in the air; see {@code Digging.preDigNextBreak}. */
    public static volatile boolean navigatorPreDigNextBreak = ControlSettings.DEFAULTS.navigatorPreDigNextBreak();
    /** Grounded a row below the node on a descent, skip ahead to the later node on the foot's row; see {@code ProgressPhase.dropAheadTarget}. */
    public static volatile boolean navigatorDropAheadResync = ControlSettings.DEFAULTS.navigatorDropAheadResync();
    /** A bot grounded on its node's row counts as standing for the climb-consume guard even on a thin corner; see {@code ProgressPhase.airborneClimbConsume}. */
    public static volatile boolean navigatorCornerFootingConsume = ControlSettings.DEFAULTS.navigatorCornerFootingConsume();
    /** Keep sprint through a diagonal +1 take-off (the diagAscent/stepJump vetoes), for the sprint-jump boost. */
    public static volatile boolean navigatorDiagAscentSprint = ControlSettings.DEFAULTS.navigatorDiagAscentSprint();
    /** Walk round a +1 block beside a level stretch instead of passing it as a riser; see {@code AimPhase.jumpedRises}. */
    public static volatile boolean navigatorAvoidLoneRisers = ControlSettings.DEFAULTS.navigatorAvoidLoneRisers();
    /** Start the from-end continuation only within this many blocks of the segment end (0 = OFF); see {@code RepathPhase}. */
    public static volatile int navigatorContinuationMaxLead = ControlSettings.DEFAULTS.navigatorContinuationMaxLead();

    /** PROGRESSIVE quick-start stub (0 = OFF). While a full re-plan is still
     *  time-slicing in the background (a hard obstacle search can take seconds),
     *  the bot has no path and stands frozen — the visible "inter-segment gap"
     *  stall. When that gap opens, the Navigator spends this many nodes on a tiny
     *  SYNCHRONOUS best-effort search and starts walking the resulting short
     *  segment toward the goal immediately; the big search's result replaces
     *  the stub when it lands (the adoption fast-forwards past the overlap, so no
     *  walking backward). Sized to finish within roughly one frame — the frame
     *  is frozen anyway while the bot has nothing to walk. */
    public static volatile int pathfinderQuickNodes = ControlSettings.DEFAULTS.pathfinderQuickNodes();

    /** PROGRESSIVE PATHFINDING: when ON, the Navigator overlaps search with
     *  movement more aggressively at the start-of-segment gap — in addition to the
     *  water bee-line stub it greedily marches over safe STANDABLE land toward the
     *  goal as a zero-search coarse-direction stub, so the bot starts moving almost
     *  instantly instead of holding frozen while the big sliced A* runs (the visible
     *  startup / inter-segment churn). The big search supersedes the stub when it
     *  lands (the adoption fast-forwards past the overlap). Default ON — gated so the
     *  GameTest suite (which exercises the non-progressive commit modes) is byte-for-
     *  byte unchanged until explicitly enabled; flipped ON after live A/B.
     *  2026-06-19: flipped ON by default after the proactive-pinch-escalation A/B at the
     *  deepwater bay (start edge-churn eliminated, crossing ~54 s vs ~162 s reactive) —
     *  the proactive arm only fires on a genuinely struggling best-effort commit and the
     *  land bee-line only on a clear flat run, so a healthy journey is untouched; GameTest
     *  re-verified 109/109 with it ON. */
    public static volatile boolean pathfinderProgressive = ControlSettings.DEFAULTS.pathfinderProgressive();

    /** Sibling of {@link WorldConfig#allowSwimEscapeBreak} but for PLACING instead of
     *  breaking. A floating bot cannot swim-jump onto a bank whose top sits
     *  ABOVE the water surface (swim-up tops out AT the surface, ~0.6 short of
     *  the step-up grab) — it bob-cycles forever against the bank. When the
     *  Navigator detects that stall it places ONE throwaway block on the surface
     *  against the bank to get GROUNDED, after which the ordinary dry climb
     *  finishes the +1/+2. Default ON as a water-escape safety net (independent
     *  of the general {@link WorldConfig#allowPlace}); needs a placeable block
     *  in the hotbar. Read only by the Navigator's climb-out actuator — never by the
     *  pathfinder, so land/route planning is byte-for-byte unchanged. */
    public static volatile boolean allowSwimEscapePlace = ControlSettings.DEFAULTS.allowSwimEscapePlace();

    /** After an MLG fall, scoop the placed water source back into the bucket so
     *  the world is left clean and the bucket is reusable for the next fall.
     *  Off → the water source is left in place (single-use bucket). On by default. */
    public static volatile boolean waterBucketScoop = ControlSettings.DEFAULTS.waterBucketScoop();

    /** Navigator sneak-brake guard: while walking, if a LETHAL drop (fall deeper than
     *  the bot can survive at its current HP) is one step ahead in the heading, hold
     *  sneak so vanilla's ledge-guard stops the bot at the block edge instead of
     *  letting the controller drift off the cliff. Fixes DEATH #8 (RetreatChain/
     *  RunAwayProcess flee path ran along a lip and the controller overshot off a
     *  23-block drop). The planner can't prevent it: PathFinder caps planned falls at
     *  survivableFall, so a lethal fall is pure controller drift, never a planned
     *  move — hence lethal-only here never blocks a legitimate planned step-down. */
    public static volatile boolean lethalEdgeBrake = ControlSettings.DEFAULTS.lethalEdgeBrake();

    /** Health at/below which the Navigator goes CAREFUL: sprint is suppressed (sprint
     *  momentum is the drift amplifier behind every unplanned fall) and the
     *  lethal-edge sneak pin is KEPT across planned descents instead of releasing
     *  (the release bets the bot lands exactly on the planned cell; at low HP that
     *  bet is fatal — survivableFall shrinks to 3-4 blocks, so residual walk/jump
     *  drift past the lip onto a deeper drop kills). A pinned descent stalls, the
     *  stuck detector repaths, the bot lives. Survival DEATH #3 (2026-07-11):
     *  HP=1 flee through cave terrain, executor drift, "hit the ground too hard".
     *  0 disables. */
    public static volatile double lowHealthCareful = ControlSettings.DEFAULTS.lowHealthCareful();

    /** Navigator step-advance guard for ASCENDING parkour leaps. A rising parkour edge
     *  ({@code parkourAscend*}, or a {@code parkour2d}/{@code parkour3} whose landing is
     *  higher than the launch) is the ONLY jumped move that lacks a "don't advance the
     *  step-pointer until the leap has actually been executed" gate — {@code pillarUp},
     *  {@code parkourPlace} and {@code parkourDescend} all have one. Without it the
     *  {@code within}/{@code passed} pure-pursuit re-sync CONSUMES the leap node before the
     *  bot reaches the launch and jumps, locking the pointer onto the NEXT node — which, for
     *  a water climb-out, is a flat {@code walk} node sitting +2 above the water. The bot then
     *  sinks into the pocket and bob-stalls on the underwater bank-dig (~25× slow) until a
     *  repath stumbles onto a gentler exit. When ON, the Navigator holds the step on a rising
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
    public static volatile boolean navigatorParkourAscendHold = ControlSettings.DEFAULTS.navigatorParkourAscendHold();

    /** Refuse a jump toward a node that is BOTH below the foot AND already within
     *  {@code navigatorNoLaunchWithin} blocks horizontally. In that geometry a jump cannot help — the
     *  cell is adjacent, so a step reaches it — and it is strictly harmful: the launch adds upward
     *  and forward momentum to a bot that is already on top of its target.
     *  <p><b>Measured, five leaps, cleanly separated</b> (2026-08-22, rung 14 corridor segment 5, the
     *  {@code fortress.wp5.leap.*} rows). Remaining horizontal distance at the launch tick against
     *  what the leap did:
     *  <pre>
     *  2.90  dy-1 → missed by 2.24, survived
     *  3.08  dy 0 → missed by 3.00, survived
     *  2.29  dy 0 → missed by 1.00, survived
     *  0.75  dy-1 → missed by 4.12, dy-14, LANDED IN LAVA
     *  0.64  dy-1 → (previous run, same segment) fell 15 blocks, LANDED IN LAVA
     *  </pre>
     *  Every fatal launch was under one block; every survivable one was over two. The two fatal ones
     *  are the whole reason the ladder cannot pass rung 14: the bot drifts one block above its route
     *  on a lava-sea rim, then jumps at a node it is already standing next to.
     *  <p><b>Why the existing suppression misses it.</b> {@code DrivePhase}'s below-node term is
     *  {@code !(p.horizontalCollision && p.onGround() && wp.getY() < foot.getY())} — it requires a
     *  WALL to ram. On a rim there is nothing to ram, so a bot one above its node jumps anyway.
     *  <p><b>Why it cannot hurt a real leap.</b> A genuine parkour gap puts its landing two or more
     *  blocks out; {@code wd.parkourVoid{Short,Long}Runway} both launch across a gap, not at an
     *  adjacent cell, and this term is inert for them. It is also inert for every ASCEND
     *  ({@code wp.y > foot.y}), which is the case {@link #navigatorParkourAscendHold} owns. */
    public static volatile boolean navigatorNoLaunchAtAdjacentBelowNode = ControlSettings.DEFAULTS.navigatorNoLaunchAtAdjacentBelowNode();

    /** The radius for {@link #navigatorNoLaunchAtAdjacentBelowNode}. One block, because one block is
     *  the width of a cell: inside it the node is not across anything and a step reaches it. The
     *  measurement it comes from separates at 0.75 vs 2.29, so this is not tuned to the edge of the
     *  data — anything in [1.0, 2.0] would classify all five samples identically. */
    public static volatile double navigatorNoLaunchWithin = ControlSettings.DEFAULTS.navigatorNoLaunchWithin();

    /** Navigator SPRINT brake for descending/walking ALONG a DEEP-water edge. The sibling
     *  of {@link #lethalEdgeBrake}/{@code steepDescentNear} for a water hazard instead of
     *  a dry cliff: when the bot is grounded and a DEEP floating-water cell
     *  ({@link WorldView#isFloatingWater}: water with water below — ≥2 deep, no floor in
     *  jump range, so a buoyant bot that drifts in cannot climb back out) borders the
     *  foot — directly, or at the bottom of a short open fall column off a neighbour —
     *  DROP sprint (no sneak-pin, so a planned descent still proceeds) so sprint momentum
     *  can't carry the bot laterally off the dry staircase into the pocket.
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
     *  <p>Default decided by live A/B on the replay-0008 test bench. Flip via {@code mc.bot.setting}. */
    public static volatile boolean navigatorDeepWaterDriftBrake = ControlSettings.DEFAULTS.navigatorDeepWaterDriftBrake();

    /** DRY sibling of {@link #navigatorDeepWaterDriftBrake} (task#36): latches the
     *  {@code steepDescentNear} sprint-drop across the airborne sub-arcs of a step-down descent.
     *  The raw brake is gated onGround, so on the airborne half of each step sprint re-arms and
     *  the accumulated FORWARD momentum walks the bot off a survivable-but-deep (&gt;4,
     *  &lt;survivableFall) lip into a fatal cumulative fall — live 2026-07-11 Mountains massif,
     *  telemetry-confirmed (grounded sprint=false at the lip, onG=false→sprint=true on every fall
     *  tick, the bot crept over a 19-block lip to death). With the latch on, sprint stays suppressed
     *  through the whole descent so momentum can't build over the lip. Drop-sprint only (no pin),
     *  so a gentle ≤4 staircase (no deep drop adjacent) is a byte-identical no-op. Flip via
     *  {@code mc.bot.setting} for live A/B. */
    public static volatile boolean navigatorSteepDescentLatch = ControlSettings.DEFAULTS.navigatorSteepDescentLatch();

    /** Descent step-skip SNEAK-brake (task#36). The planner is HARD-capped at
     *  {@link RulesConfig#pathfinderMaxDryFall} per single node, so a grounded bot can never legitimately
     *  have its drive target (wp = path.get(step)) more than that many blocks below the foot —
     *  read-only {@code mc.debug.plan} of the fatal Mountains descent routes a clean ≤4 staircase
     *  all the way down (maxStepDrop=4). Yet live the executor advances {@code step} DOWN the
     *  staircase ahead of the bot (arc-length advance runs the pointer forward along the
     *  descending path while the feet are still up top): grounded foot y94 while wp=y83 (11 below).
     *  The drive then aims the bot forward+DOWN at that far node and residual sprint momentum
     *  LAUNCHES it off the stair edge into a cumulative fatal fall (2026-07-11 Mountains,
     *  deterministic). Unlike the sprint-only {@link #navigatorSteepDescentLatch}, this holds vanilla
     *  SNEAK (maybeBackOffFromEdge clamps the whole movement delta so the bot cannot step off a
     *  block edge, yet still steps DOWN one block at a time).
     *  <p><b>REPURPOSED — AIRBORNE forward-drift clamp (task#36, 2026-07-12).</b> The original
     *  grounded gate was proven a literal NO-OP (the far-below wp only appears airborne; grounded
     *  max(foot.Y-wp.Y) is exactly 4.0). The flag now gates the ISOLABLE BACKUP half of the #36
     *  fix: when the steep-descent latch ({@link #navigatorSteepDescentLatch}) is armed and the bot is
     *  AIRBORNE over a descent, zero the forward drive ({@code driveF→0}, Navigator ~line 4259) so the
     *  bot drops onto the near tread instead of sailing off the lip on {@code driveF=1}. The arming
     *  half (cumulative path-lookahead → kills sprint) lives under {@code navigatorSteepDescentLatch};
     *  this clamp is the driveF-side backup.
     *  <b>Default ON (2026-07-12).</b> Live Mountains A/B (regen off, same start/goal) settled it:
     *  RED (both off) = 13 descent fall-damage (Fall A launch=8); arming-only (this OFF) = 9 (Fall A
     *  STILL launched for 5 — sprint-kill alone sails off the lip); arming+clamp (this ON) = 2, no
     *  launch. So the clamp is the decisive lever, not a backup — enabled by default. Airborne-only →
     *  gravity still drops the bot, so it can never stall/deadlock a descent (A/B: bot crossed every
     *  lip and kept descending, no stall-at-lip). Flip OFF via {@code mc.bot.setting} to isolate the
     *  arming half. */
    public static volatile boolean navigatorDescentStepSkipBrake = ControlSettings.DEFAULTS.navigatorDescentStepSkipBrake();

    /** Navigator step-advance fix for a {@code stepDown} (or short discrete descent) whose target
     *  foothold is a SHALLOW WATER-SURFACE cell — water at the node, a SOLID floor one block below,
     *  and a non-water (air / vine / lily-pad) head above (a 1-deep splash at a bank edge, NOT the
     *  deep {@link WorldView#isFloatingWater} pocket).
     *  <p><b>The bug</b> (live #47, ground-truth navigator telemetry at node -809,62,350; water y62,
     *  dirt floor y61, lily-pad/vine at y63): the bot steps down into the 1-deep cell and GROUNDS
     *  there ({@code onG=true}, foot y62.00, {@code |dY|=0.00} — vertically EXACTLY at the node), but
     *  settles pinned at {@code cur2≈0.546}, just 0.10 OVER the {@code REACH_DIST_SQ=0.45} reach gate
     *  (~0.74 b short of the node CENTRE in X, {@code hCol}, x frozen at -807.76). It cannot walk the
     *  last centring fraction in: buoyancy + the water-climb jump ({@code up=true}, because on each
     *  down-bob {@code wp.y ≥ foot.y} reads as a climb-out) keep lifting the foot off the floor and
     *  ramming it, and the prone sprint-swim can't nudge the buoyant bot that final 0.1 b. So
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
    public static volatile boolean navigatorWaterStepDownFloat = ControlSettings.DEFAULTS.navigatorWaterStepDownFloat();

    /** Navigator WATER-SURFACE walk relaxed-advance: the turn / terminal / WALL-CORNER freeze breaker for a
     *  buoyant surface swimmer. A flat {@code walk} water-surface node sits the bot ~0.67 b out (cur2 floor
     *  ~0.455, just over the tight {@code NavigatorConstants.REACH_DIST_SQ}=0.45) so {@code within} never closes; a
     *  straight crossing advances each node via {@code passed} (forward momentum carries the bot past), but at
     *  a TURN / terminal / wall-corner node the bot is not crossing toward the next node so {@code passed}
     *  can't fire either — the flat water walk node then has NO relaxed-advance and the bot orbits / freezes
     *  against the corner (live deep-water bay corner: cur2 1.142 FROZEN 321 ticks, {@code within}=0, drive yaw
     *  swinging 403° — the stuck-against-a-wall or stuck-in-water state the goal names; {@code attack}=0, NOT digging — the dig was a
     *  video misread). When ON, a CONFIRMED in-water stall ({@code noStepProgressTicks} — HORIZONTAL-only in
     *  water so the bob can't fake-reset it — past {@code WATER_WALK_STALL_TICKS}=24) at a flat {@code walk}
     *  water node within {@code WATER_WALK_REACH_SQ}=1.3 advances the step (next node {@code |Δy|<1.2} to bar a
     *  climb-skip) so the segment continues / repaths from here. STRICT extension / INERT off the bug: a clean
     *  crossing advances via {@code passed} in 1-2 ticks (noStepProgress stays low, never trips the stall gate);
     *  a dry walk and every non-walk edge are byte-identical inert. Wired to {@code mc.bot.setting} so a live
     *  run can flip it. */
    public static volatile boolean navigatorWaterWalkReach = ControlSettings.DEFAULTS.navigatorWaterWalkReach();

    /** Phase-1 of the arc-length pursuit refactor (#55): drive the {@code step} pointer from the path
     *  PROJECTION instead of the per-tick instantaneous gates. When ON, the step-advance decision becomes
     *  "the foot's forward projection has reached a later segment" ({@code arcProj.segIdx > step}) plus the
     *  legitimate close-node arrival ({@code within}); the seven bob-compensating gates (passed, tailConsumed,
     *  crossedDescendNode, crossedWalkNode, waterStepDownFloat, waterWalkReach) are bypassed
     *  — they only ever existed to fire an advance the buoyancy bob stopped {@code within}/{@code passed} from
     *  firing, and the projection is bob-immune by construction. Live P0 shadow proof (2026-06-27): at the
     *  stalls the bot's projSeg led the frozen live step by 2-5 segments — exactly the advance this drives.
     *  The edge-execution holds (pillar/parkour/bridge not yet executed) still gate advancement. Default ON. */
    public static volatile boolean navigatorArcLengthAdvance = ControlSettings.DEFAULTS.navigatorArcLengthAdvance();

    /** Refuse to CONSUME a path node that sits in a cell BELOW the bot's own foot cell while the bot
     *  is still standing on something — the descending twin of the airborne-climb refusal at the same
     *  outlet ({@code ProgressPhase#airborneClimbConsume}), whose javadoc named {@code within}'s
     *  {@code |dyNode| < 1.2} as a still-suspect sibling. It was: on nether rung 14 the crossing's last
     *  four hops (2026-08-19) all ended perched at {@code 159,53,187} on 0.125 of a sole, and the plan
     *  that would have walked the bot out — {@code [158,53,187 → 159,52,187 → 159,51,188]} — was spent
     *  in a single tick without one block of movement, {@code cause=within} with {@code |w.y-p.y|=1.000}
     *  printed on the very line that advanced. The pointer descended; the bot did not. Covers the
     *  LAST node of a plan as well as its middle: rung 13's doorstep was a one-node plan spent on the
     *  tick it was adopted, and the segment then ends {@code path-consumed} with the bot where it
     *  started. Default ON; the OFF arm is the control in {@code wd.serverStepsDownAPerchItPlanned}
     *  and {@code wd.serverStepsDownTheLastNodeOfItsPlan}. */
    public static volatile boolean navigatorDescentNodeHold = ControlSettings.DEFAULTS.navigatorDescentNodeHold();

    /** Aim BOTH the camera and the bot at the bob-immune
     *  path TANGENT ahead of the projection ({@code arcProj.tangentYaw}) instead of the immediate-node bearing.
     *  The node bearing flips ~180° the instant the foot overshoots the node — the backward-jump / repeated side-to-side hopping /
     *  facing-the-wall dead-corner stall (live P1 proof: with the step pointer correctly tracked the bot still
     *  stalled on-path with dYaw up to 129°, the camera/drive pointing nearly opposite the path). A tangent at
     *  s+lookahead never reverses, so it eliminates that whole failure mode AND makes the descent flip-rejection
     *  / smoothWaterDriveYaw / trendCam bandaid family unnecessary. Skipped for launches (a parkour/MLG leap
     *  snaps at its specific landing) and inside the aim dead-zone. Requires the projector (auto-computed when
     *  this or {@link #navigatorArcLengthAdvance} is on). Default ON. */
    public static volatile boolean navigatorTangentAim = ControlSettings.DEFAULTS.navigatorTangentAim();
    /** With {@link #navigatorTangentAim}: past {@code PURSUIT_PERP} off the path, aim at the path's point ahead
     *  ({@code PathProjection.pursuitYaw}) so the bot rejoins instead of walking parallel. Default ON. */
    public static volatile boolean navigatorTangentPursuit = ControlSettings.DEFAULTS.navigatorTangentPursuit();

    /** Phase-3 of the arc-length pursuit refactor: a bob/jitter-IMMUNE ram-wedge recovery. When the arc-length
     *  projection s makes no forward progress (|ds| &lt; 0.05/tick) while horizontalCollision for ARC_WEDGE_TICKS
     *  (~1.5 s), fold into the existing fellOffPath recovery (fresh foot-search that blacklists the un-advanceable
     *  node + re-routes from here). This is the structural replacement for the descentRamStuck / ascentRamSlide
     *  detector zoo, which all hang on noStepProgressTicks — a 3D-new-low counter that the ram's
     *  sub-block vertical jitter (and the buoyancy bob) zero every tick, so the gate never fills and the bot bobs
     *  ~5 s against a wall before the slow 100-tick wedge fires (live -672,94: descentRamStuck's exact node-1-below
     *  + hCol case, yet no recovery for 34+ t). The horizontal s is immune to that vertical noise. Requires the
     *  projector (auto-computed when this or another arc-length flag is on). Default ON. */
    public static volatile boolean navigatorArcLengthWedge = ControlSettings.DEFAULTS.navigatorArcLengthWedge();

    /** Dry repath-rechurn breaker (the COMMON wedge amplifier, REGRESSION.md §21). The 5 single-mechanism
     *  executor point-fixes all failed because the catastrophic wedges are HETEROGENEOUS per terrain
     *  (failed-parkour on steep-822, airborne-diagUp on crest-815, corner-block at a path start) yet share
     *  ONE amplifier: a move fails → the foot is far OFF-PATH → safetyRepath (stuck>60) re-commits a path that
     *  is ALSO unexecutable from the off-path spot → the bot oscillates toward it (node-orbit yaw-thrash) →
     *  re-fails → repath, and totStuck ratchets to 800-3000+. The existing anti-spin/anchor machinery
     *  (CHURN_REPATH_CAP, repathsNoProgress, reCentre) is IN-WATER gated or fires only when the foot is off
     *  the spine cell with the previous node not behind — none of it catches the dry far-off-path churn.
     *  When ON: dry + a sustained stall (stuckTicks > DRY_REANCHOR_STUCK) + the foot well off the current
     *  node (dist² > DRY_REANCHOR_OFFPATH_SQ) deterministically aims at the last cleanly-passed node centre
     *  (path[step-1]) — a FIXED point whose bearing doesn't thrash as the bot closes — walking the bot back
     *  ONTO the path before it resumes, instead of letting the repath loop re-churn. Default ON; validate via
     *  the hardened K≥6 same-build P(wedge>800) gate (K=3 median is bistable-noise-corrupted, §17). */
    public static volatile boolean navigatorDryReanchor = ControlSettings.DEFAULTS.navigatorDryReanchor();

    /** Wall-corner fast-churn recovery. The dry boxed-pocket churn detector (net XZ displacement < 8 blocks
     *  over a 20s window → blacklist the stuck nodes + escalate the planner + back off) is the ONLY recovery
     *  that catches a wall-corner stall (stuck against a rocky/dirt/water-boundary wall), because the node-relative
     *  counters (totStuck, noStepProgressTicks) get RESET by the orbit's node-churn so safetyRepath/UnstuckChain
     *  never fire. But the 20s window is too slow — live journey-A ground through 20-45s wall-stalls. When ON,
     *  a SUSTAINED horizontalCollision (≥4s continuous, the unambiguous wall-ram signature) shortens that window
     *  to ~8s so the same blacklist+escalate fires ~2.5× sooner. Gated HARD on sustained collision so legitimate
     *  slow-but-moving terrain (hCol=false, still net-progressing) keeps the full 20s window — that targeting is
     *  why this can succeed where the unconditional navigatorFasterChurnRepath (reverted, false-fired on slow climbs)
     *  could not. Default ON; validate LIVE on the journey-A repro (corpus totStuck-based gate is BLIND to this
     *  net-progress stall, REGRESSION.md §37). */
    public static volatile boolean navigatorWallCornerFastChurn = ControlSettings.DEFAULTS.navigatorWallCornerFastChurn();

    /** Drowning-escape reflex (LETHAL water climb-out deadlock, live 2026-06-29 New World (-21,60,-42)):
     *  the pillar↔repath deadlock below can pin a submerged bot under a bank lip until its air runs out —
     *  Peaceful does NOT prevent drowning (bot died at hp 3→0 while climbout-place spun). When ON: once the
     *  bot is underwater with air below ~3s (getAirSupply ≤ 60), LATCH a surface-for-air override that
     *  preempts every climb/dig/pillar actuator — hold the swim-up jump and, if a solid lip caps the head
     *  (or a wall blocks the rise), drive BACKWARD off the bank so buoyancy finds open surface. Released
     *  once air recovers (≥ 240) or the bot leaves water; the interrupted climb then resumes fresh. A
     *  survival reflex, not a pathfinding fix: it turns any unknown submerged deadlock from a death into a
     *  breathe-retry loop. Default ON. */
    public static volatile boolean navigatorDrowningEscape = ControlSettings.DEFAULTS.navigatorDrowningEscape();

    /** Make the water climb-out "pillar gave up" latch STICKY across repaths (the 2026-06-29 lethal loop):
     *  climbPillarGaveUp latches when the buoyant place proves futile (bob can't clear the surface fill
     *  cell, PILLAR_FUTILE_TICKS=50), but every repath swaps the climb node (-20,61,-43 ↔ -21,61,-42) which
     *  RESETS the climb context and clears the latch → the proven-futile pillar re-engaged 26× (~2.5s each)
     *  until the bot drowned, and the bank-dig fallback never got a full turn. When ON, the latch survives
     *  context resets while the foot stays within 3 blocks of where the pillar proved futile (15s TTL), so
     *  the dig/recovery actually takes over. Default ON. */
    public static volatile boolean navigatorClimbGaveUpSticky = ControlSettings.DEFAULTS.navigatorClimbGaveUpSticky();

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
    public static volatile boolean navigatorStepUpBackoffRetry = ControlSettings.DEFAULTS.navigatorStepUpBackoffRetry();

    /** Carrot LOS player-width honesty (the true fix for the jungle-trunk
     *  friction): carrotPoint's line-of-sight gate switches from the centre-line RAY test to
     *  {@code PathSmoothing.losWalkableBody} — a 0.6-wide corridor probe (4-corner AABB per
     *  interpolated sample). The far carrot then refuses bearings whose corridor clips a trunk
     *  column, stopping the pursuit at the last bot-walkable node instead of steering the
     *  hitbox into a slit only the ray fits. Path smoothing keeps the cheap ray. Default ON. */
    public static volatile boolean navigatorCarrotBodyLos = ControlSettings.DEFAULTS.navigatorCarrotBodyLos();

    /** §90 diagonal string-pull (the residual-zigzag lane of #15 path smoothing). stringPull
     *  historically straightens ONLY axis-aligned corridors — collapsing a zigzag into a long
     *  diagonal fabricated corner-cuts the navigator couldn't thread (spawn-maze stall) back when
     *  the merge guard was the centre-line ray. With {@code losWalkableBody} (0.6-wide corridor
     *  probe, same primitive navigatorCarrotBodyLos trusts for pursuit) a diagonal merge can be
     *  admitted honestly: the merged segment is kept only when the BOT corridor clears. Kills
     *  the cardinal staircase residue on open diagonal terrain — the last structural source of
     *  corner-scrape micro-slowdowns. */
    public static volatile boolean navigatorDiagonalStringPull = ControlSettings.DEFAULTS.navigatorDiagonalStringPull();

    /** Jump a dry +1 riser at its face distance along the velocity, keeping sprint (R1: 26/39 jumps fired at the face). */
    public static volatile boolean navigatorRiserLeadJump = ControlSettings.DEFAULTS.navigatorRiserLeadJump();
    /** Run {@link #navigatorRiserLeadJump} keeps beyond two airborne ticks' travel; see {@code DrivePhase.riserLead}. */
    public static volatile double navigatorRiserLeadMargin = ControlSettings.DEFAULTS.navigatorRiserLeadMargin();
    /** Hold a one-block step-down on its planned segment with the lane-keep strafe (see {@code DrivePhase.segmentLaneOffset}). */
    public static volatile boolean navigatorDescentLaneKeep = ControlSettings.DEFAULTS.navigatorDescentLaneKeep();
    /** {@link #navigatorRiserLeadJump} only for the riser under the node itself, not one beside it. */
    public static volatile boolean navigatorRiserLeadExact = ControlSettings.DEFAULTS.navigatorRiserLeadExact();
    /** Judge a dry step-up's heading against the raw aim too, not only the lagging smoothed one; see {@code AimPhase}. */
    public static volatile boolean navigatorStepUpRawHeading = ControlSettings.DEFAULTS.navigatorStepUpRawHeading();
    /** Plan a dry airborne bot from the cell it lands in, not the jump apex (see {@code PreludePhase}). */
    public static volatile boolean navigatorSearchFromLanding = ControlSettings.DEFAULTS.navigatorSearchFromLanding();

    /** task#82: weave the thin per-move AscendMovement episode tracker over ascent edges (stepUp/stairUpBreak/diagUp;
     *  B1 adjudication — the machine never actuates, PREP/RUNNING/SUCCESS fall through to the legacy drive; it owns
     *  only the per-edge episode + the dig-aware 72t dead-zone watchdog whose UNREACHABLE folds into
     *  forceFellOffPath→re-route). Default ON since 2026-07-20 (B1-3): replay A/B 2×2 over the ascent-heavy corpus
     *  subset — the only arrival in 16 case-runs was an ON arm (steep-822, lowest peak stuck of its four), 10 watchdog
     *  fires all landed on genuine dead-zone poses (zero false trips on progressing climbs), churn deltas stayed inside
     *  the identical-flag chaos envelope (×4-5 per-archive swings), and every ON-arm churn pocket was a pre-existing
     *  OFF family. OFF restores the byte-identical legacy branch. Wired to mc.bot.setting. */
    public static volatile boolean navigatorAscendMovement = ControlSettings.DEFAULTS.navigatorAscendMovement();

    /** Cut a best-effort segment whose tail sinks into a dip back to its ridge ({@code PathSmoothing.sunkTailCut}). */
    public static volatile boolean navigatorCommitTailPeak = ControlSettings.DEFAULTS.navigatorCommitTailPeak();

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
    public static volatile boolean navigatorRouteHysteresis = ControlSettings.DEFAULTS.navigatorRouteHysteresis();

    /** Dig-commit repath hold (the water-bank dig-vs-repath starvation, replay-0004 CLEAN-K5
     *  2026-07-02): an underwater bank dig takes 100-200t (25x mining penalty) but the periodic
     *  repath re-routes faster than that, and each route adoption discards the held break —
     *  vanilla resets the block's progress to zero, so the dig NEVER completes ([expect]
     *  DIG-dropped at 11-19t by adoption + DIG-slow 200t regrinding the re-chosen riser, then
     *  ADVANCE-deadzone starvation = the water-bank churn loop). When ON: while a committed
     *  bank dig's break is actually held (waterClimbDigRiser latched + breakHeld), a fresh
     *  search result is rejected and the current path kept. The futile-dig release
     *  (navigatorFutileBankDigRelease) still abandons a hopeless dig, which drops the hold and
     *  lets the next repath adopt normally — so this cannot starve real reroutes. Default ON. */
    public static volatile boolean navigatorDigCommitHoldRepath = ControlSettings.DEFAULTS.navigatorDigCommitHoldRepath();

    /** Water-surface pillar crest-place (the deterministic water-bank pillarUp deadlock,
     *  rig tp(371.5,62,348.5)→goto(378,65,347) 2026-07-02): a pillarUp whose destination
     *  cell is SURFACE water (air above) hit the flooded-shaft float-only branch, but a
     *  buoyant bot cannot float above the waterline (bob ceiling +0.08 vs the +0.9 the
     *  next rung needs) — while the crest ticks that COULD place (feet ≥ fill.y+1.0,
     *  ~1-2 ticks/bob) were spent in that branch not placing, and the separate climbout-
     *  place takeover clicked only below +1.0 where vanilla silently rejects the
     *  overlapping AABB. The two place paths missed each other's height windows forever.
     *  When ON: a water shaft cell with air above is treated as the dry-crest case —
     *  jump and place the support at the bob peak. Default ON. */
    public static volatile boolean navigatorPillarSurfacePlace = ControlSettings.DEFAULTS.navigatorPillarSurfacePlace();

    /** Descending-bridgePlace lip anchor (task#4, replay-0013, live 2026-07-20 23:11
     *  badlands): a bridgePlace node BELOW the foot means the bot stands at a lip
     *  about to bridge DOWN into a gap. The drive-phase bridge sneak-brake is
     *  deliberately gated {@code !plannedDescent} (a sneak pin across a planned
     *  step-down deadlocks — vanilla's ledge-guard refuses every edge), and the
     *  break/place actuator only zeroes the drive inputs — so residual walk momentum
     *  slid the bot off the lip during the 9 place-aim ticks (x 89.81→89.28), the
     *  place never landed, and the climb-back/slide/repath loop wedged 74×/80 s.
     *  When ON: while a descending place is still PENDING (support cell below the
     *  foot not yet solid), hold sneak — the ledge-guard arrests the slide AT the
     *  lip, the place lands, and the pin releases the moment the support exists, so
     *  the planned step-down proceeds exactly as before ("place first, step second").
     *  A same-level bridgePlace is byte-identical (cell not below the foot). */
    public static volatile boolean navigatorBridgeDescentPlaceAnchor = ControlSettings.DEFAULTS.navigatorBridgeDescentPlaceAnchor();

    /** Monotonic stuck-window (the open-water step-jitter starvation, A-4 44s stall
     *  2026-07-02): buoyant drift on a straight water path jitters the step pointer back
     *  and forth (wp 375↔387↔374), and the old `step != stuckStep` test treated every
     *  jitter as a fresh node — stuckTicks observed pinned at 0-6 (aimSrc telemetry),
     *  never reaching the nodeAim fallback at 12, so every stuck-gated recovery starved
     *  while the yaw swept 660° and thrust cancelled. When ON: only a step ADVANCE opens
     *  a fresh progress window; a retreat re-bases the distance reference but keeps the
     *  stall clock running. Default ON. */
    public static volatile boolean navigatorStuckStepMonotonic = ControlSettings.DEFAULTS.navigatorStuckStepMonotonic();

    /** Climbed-past-the-node stall recovery (the steep-mountain churn core, C26-J3
     *  2026-07-02): grinding up a slope carries the bot GROUNDED 2-3 blocks ABOVE its
     *  committed stepUp node — dY exactly 3.00 sits just outside the `> maxJumpUp+2`
     *  fell-off test (mirror of the 2026-06-24 slide-back gap), within/passed both
     *  starve (node below), and the bot pins against the wall for 300+ ticks with the
     *  yaw locked reverse. Airborne FALL edges legitimately have nodes 3+ below, so
     *  this gates on grounded + dry + noStepProgress > 90 before folding into
     *  fellOffPath (foot-search re-routes from the real, higher position). Default ON. */
    public static volatile boolean navigatorAboveNodeStallRecover = ControlSettings.DEFAULTS.navigatorAboveNodeStallRecover();

    /** Dig aim priority — the NON-exclusive successor to an exclusive dig latch that starved travel (§66):
     *  after the travel tick fully runs (drive, recovery, repath untouched), re-hold
     *  ONLY the crosshair + attack on the committed dig cell, so an interleaved travel
     *  tick can't release attack and zero vanilla mining progress. A human holding
     *  W+LMB against the wall being dug. Latch expires on solid-gone / 300t / beyond
     *  mining reach. Default ON. */
    public static volatile boolean navigatorDigAimPriority = ControlSettings.DEFAULTS.navigatorDigAimPriority();

    /** Dry wall-pin dig fallback (§71, C49): a route node behind a 1-block wall pins the
     *  bot hCol with the stall clock climbing while safetyRepath returns the same route
     *  and nothing ever digs the wall. Grounded + dry + hCol + stuckTicks>60 → punch the
     *  waypoint-facing block at head/feet height (digAimPriority latch holds it). Default ON. */
    public static volatile boolean navigatorWallDigFallback = ControlSettings.DEFAULTS.navigatorWallDigFallback();

    /** Ram-pinned node-aim release (§80, ultra#2): two deterministic dry-descent wall-pins
     *  where the DRIVE heading never points at the current node and every displacement
     *  recovery fires uselessly — (A) the aim-deadzone "hold heading" band is WIDER than
     *  the step-advance within-gate, so a bot parked 1.2 blocks beside a stepDown node
     *  holds its stale yaw forever (yawErr -115°, hCol, hSpd 0); (B) navigatorTangentAim
     *  projects a ~reversed tangent at a switchback corner (driveYaw -180° vs node
     *  bearing 14°) and the corner corrector tried for it oscillated (§13) and was removed. When dry + horizontalCollision + stuckTicks past the wall-dig
     *  gate AND the live yaw is >60° off the current-node bearing, re-aim at the node.
     *  Unlike the §25 REVERTED reanchor-to-step-1 (bounce oscillation), this aims at the
     *  CURRENT node under a collision gate: success clears hCol/stuckTicks and normal
     *  aim resumes. Default ON. */
    public static volatile boolean navigatorRamNodeAimRelease = ControlSettings.DEFAULTS.navigatorRamNodeAimRelease();

    /** Physical stall clock feed for stuck-gated recoveries (§84): a safety-repath loop
     *  swaps the path every few seconds and each swap resets stuckTicks (stepWindowFresh),
     *  so a physically frozen bot never crosses the stuckT>40 gates — wall-dig and
     *  ram-release starve while a jump-ram spins (canopy pin: 1200t frozen with leaves one
     *  instabreak punch away). ON = those recoveries also fire on 60+ ticks without XZ
     *  displacement (path/repath-independent anchor clock; vertical bob doesn't count).
     *  Default ON. */
    public static volatile boolean navigatorPhysicalStallClock = ControlSettings.DEFAULTS.navigatorPhysicalStallClock();

    /** Bridge-commit repath hold (§82): a mid-bridge PERIODIC repath swaps the committed
     *  bridgePlace chain for a fresh plan whose first node sits elsewhere, steering the
     *  bot off the end of the placed deck into air (falling off partway through a bridge). Arena A/B: 19-block
     *  deck finishes inside one repath period and never falls (3/3); diagonal zig-zag and
     *  40-block decks straddle it and fell 100%. ON = hold routine repaths while the
     *  current/next edge is bridgePlace; safety repaths stay live. Default ON. */
    public static volatile boolean navigatorBridgeHoldRepath = ControlSettings.DEFAULTS.navigatorBridgeHoldRepath();

    /** Bank-dig ground-blip immunity (the underground-pool climb-out grind, live 2026-07-02
     *  (-275,49,-38): the committed bank dig requires {@code !onGround}, but the buoyant bob
     *  touches bottom ~4 ticks/second — each blip drops {@code digCommitted}, the tick falls
     *  through to the normal drive with attack=false, and vanilla RESETS the block's break
     *  progress (17 dig + 4 drive ticks per second, 25x underwater mining => the riser NEVER
     *  breaks; observed as digging the same block over and over without effect). When ON: a grounded streak of <=5 ticks
     *  keeps the commit alive (a real climb-out grounds for good, ending it after the streak
     *  passes 5). Default ON. */
    public static volatile boolean navigatorBankDigGroundBlip = ControlSettings.DEFAULTS.navigatorBankDigGroundBlip();

    /** Actuator EXPECTATION alarms (live expected-versus-actual checks): every tick, compare what the pressed
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
    public static volatile boolean navigatorExpectAlarm = ControlSettings.DEFAULTS.navigatorExpectAlarm();

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
    public static volatile boolean navigatorAscentRamBobBreak = ControlSettings.DEFAULTS.navigatorAscentRamBobBreak();

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
     *  dig has committed to one still-fully-solid riser for {@code NavigatorConstants.FUTILE_BANK_DIG_TICKS} ticks
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
    public static volatile boolean navigatorFutileBankDigRelease = ControlSettings.DEFAULTS.navigatorFutileBankDigRelease();

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
    public static volatile boolean navigatorBankDigSkipOverhang = ControlSettings.DEFAULTS.navigatorBankDigSkipOverhang();

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
     *  from where the bot actually floats, never behind/below it. The global {@code foot} used by
     *  actuators/sampling is untouched — only {@code newSearch(foot,…)} sees the lifted cell.
     *  <p><b>Default ON</b> — must be live-A/B'd on the deterministic repro
     *  (tp -733 63 230 + goto -540,320 → bob-stall) before any default flip. */
    public static volatile boolean navigatorBuoyantSearchFromSurface = ControlSettings.DEFAULTS.navigatorBuoyantSearchFromSurface();

    /** Forward-hemisphere guard for the water climb-out block-less bank-dig riser selection. The
     *  omnidirectional nearest-dry-exit scan (Navigator, the {@code sx,sz in -4..4} loop) can pick an
     *  exit BEHIND the bot (opposite the path's current waypoint) and then dig the backward riser —
     *  the live -733/-710 "dig the west wall behind me while the goal is east" deadlock (1000+ digs
     *  at one backward riser, ~48 s frozen, bot reverses tens of blocks). When ON, the exit scan
     *  only considers offsets in the cwp-direction hemisphere (dot(offset, cwp-dir) {@code >= 0}); if
     *  no forward exit exists the cwp-direction fallback still drives a forward dig, so the climb-out
     *  never trenches backward. Independent of (and composable with) {@link #navigatorBuoyantSearchFromSurface}
     *  which fixes the search START — a correctly-forward path can still have its exit scan pick a
     *  closer backward exit. Default ON (committed-safe); validate on the deterministic -733 repro. */
    public static volatile boolean navigatorBankDigForwardExit = ControlSettings.DEFAULTS.navigatorBankDigForwardExit();

    /** Don't snap an elevated AIR goal DOWN in {@link Navigator#snapGoalToStandable} when it is
     *  reachable by PILLARING up (goal cell + head passable, a solid base within a few blocks
     *  below to pillar from, and {@code allowPlace}). The snap was added to settle random goals
     *  that land inside terrain, but it also defeats "pillar up to an elevated goal": the goal
     *  is "unstandable" only because its floor is air, which pillaring creates. Snapping it to
     *  the highest currently-standable cell makes the bot stop 1+ blocks short (live summitArena:
     *  goal y233 snapped to y232, bot arrives at y232, never pillars the last block, so an uphill
     *  goal in the air is never reached). When ON, a pillar-reachable elevated goal keeps its real target so A* finds the
     *  pillarUp path; a truly floating void goal (no base below) still snaps. Default ON
     *  (committed-safe); validate on the deterministic summitArena geometry. */
    public static volatile boolean navigatorPillarReachGoalNoSnap = ControlSettings.DEFAULTS.navigatorPillarReachGoalNoSnap();

    /** Skip the block-less water climb-out bank-dig when the committed path's NEXT node (cwp)
     *  is a WATER cell — the bot should SWIM into it and reach the real climb-out (a stepUp onto
     *  land) further along the path, not dig where it floats. The dig's omnidirectional exit scan
     *  picks the NEAREST dry exit, which on a tall sheer goal-side bank is the perpendicular wall
     *  face (dot≈0, slips past the forward-hemisphere guard {@link #navigatorBankDigForwardExit}); the
     *  bot then trenches the goal-side wall, yaw-locks at it, rams it, and bob-stalls forever while
     *  A*'s path swims around to a lower exit (live 2026-06-27 water-bank deadlock @ -646,62,351,
     *  stuck 1181 ticks, cwp=-648 WATER). A genuine climb-out HERE routes cwp to a LAND/stepUp node
     *  (not water) so the dig still fires for it. Default ON (committed-safe); validate via replay
     *  A/B on the archived deadlock before flipping. */
    public static volatile boolean navigatorBankDigSkipWhenCwpSwims = ControlSettings.DEFAULTS.navigatorBankDigSkipWhenCwpSwims();

    /** Pillar-place FALLBACK for a swimAshore +2 bank that the block-less bank-DIG never engages.
     *  The pillar-takeover (Navigator ~L2501) is gated {@code !deepDig}, deferring to the bank-DIG for
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
    public static volatile boolean navigatorSwimAshorePillarDespiteDeepDig = ControlSettings.DEFAULTS.navigatorSwimAshorePillarDespiteDeepDig();

    /** FOOTHOLD FIRST at a water bank: a floating bot holding a placeable block tries the pillar
     *  takeover BEFORE the bank dig, whatever {@code deepDig} says. The dig-first order rested on
     *  the belief that a buoyant bob can never lift its feet clear of the surface fill cell; the
     *  real client shows a bot pressed into the bank riding vanilla's collision boost to +1.8
     *  and placing within six ticks of engaging ({@code wd.clientOneHighBankPlaceOut}), where the
     *  dig-first order spent sixteen seconds on a hopeless bare-hand stone dig. The pillar's own
     *  {@code placeFutile} bail (50 ticks) still hands a bank the place cannot land on to the dig.
     *  Default ON; the gametest baseline pins it OFF like every other navigator flag. */
    public static volatile boolean navigatorFootholdBeforeBankDig = ControlSettings.DEFAULTS.navigatorFootholdBeforeBankDig();

    /** The water climb-out INTENT is read against the column's surface cell, not the bobbing foot.
     *  {@code cwp.y > foot.y} flickers with the bob, and a bot pressed into a bank rides the collision
     *  boost through a bob whose top outlasts the intent's sticky window, so the climb context was
     *  left and its stall counter zeroed every cycle — {@code wd.clientTwoHighBankPlaceOut} bobbed
     *  180 ticks at the wall with no takeover. With this ON, a dry waypoint beside the bot at or
     *  above the surface cell counts as a climb-out on every tick of the bob. Default ON; the
     *  gametest baseline pins it OFF like every other navigator flag. */
    public static volatile boolean navigatorClimbIntentFromSurface = ControlSettings.DEFAULTS.navigatorClimbIntentFromSurface();

    /** The water pillar takeover declares "topped out" only on a rung with a dry cell beside it
     *  that can be stepped onto flush or down. The rise test alone read the top on a 1×1 rung one
     *  course under the rim whenever the latched climb node sat in the pool wall, A* then asked for
     *  a diagonal step-up off that rung, and the bot walked off it back into the pool
     *  ({@code wd.clientOneHighBankPlaceOut}: a second climb-out, 500 ticks). Default ON; the
     *  gametest baseline pins it OFF like every other navigator flag. */
    public static volatile boolean navigatorPillarTopsOutAtFlushExit = ControlSettings.DEFAULTS.navigatorPillarTopsOutAtFlushExit();

    /** The LAST path node is aimed at directly (no path tangent) and approached at a walk over its
     *  final two blocks. Every earlier node is spent by crossing its plane, so the bob-immune
     *  tangent that carries the bot past it is right there; the last one is spent only by
     *  closing to within ~0.67 of its centre, and a sprinting bot on the previous segment's
     *  heading passes wider than that ({@code wd.clientGotoStartsMidAir}: nearest pass 0.8, then
     *  400 ticks of unstuck bursts around a goal already reached). Default ON; the gametest
     *  baseline pins it OFF like every other navigator flag. */
    public static volatile boolean navigatorFinalNodeDirectAim = ControlSettings.DEFAULTS.navigatorFinalNodeDirectAim();

    /** The last path node, when it IS the goal cell, is not spent until the foot stands in it
     *  (bounded by {@code FINAL_NODE_HOLD_TICKS}). Spending it from 0.67 away or from the air
     *  ended the walk "path-consumed goalReached=false" with the bot standing in the goal cell a few
     *  ticks later — half the client water scenes read that way on 2026-09-04, and the ladder
     *  acts on the field. Default ON; the gametest baseline pins it OFF like every other navigator
     *  flag. */
    public static volatile boolean navigatorHoldLastNodeUntilStanding = ControlSettings.DEFAULTS.navigatorHoldLastNodeUntilStanding();

    /** The water pillar takeover, afloat in ONE-DEEP water over a solid floor, places its first rung
     *  in a neighbour cell instead of under its own feet. A layer higher than 0.4 turns the jump key
     *  into a swim, so the feet never clear the foot cell and the own-column place is refused
     *  forever ({@code wd.clientFlowingTrenchPlaceOut}: 646 ticks to the bank). The side rung is
     *  clear of the bot, the +0.3 collision boost mounts it, and the dry ground-jump pillar
     *  continues from there. Default ON; the gametest baseline pins it OFF like every other navigator
     *  flag. */
    public static volatile boolean navigatorShallowWaterSideFoothold = ControlSettings.DEFAULTS.navigatorShallowWaterSideFoothold();

    /** Topping out of a water climb-out resets the aim's low-pass state. The takeover pins the
     *  heading for the whole climb while the EMA keeps smoothing toward nodes the bot never faced,
     *  so the first dry walk started up to 180° off and the dry-land EMA then orbited the node for
     *  hundreds of ticks ({@code wd.clientFlowingChannelPlaceOut}: yaw wound from 69 to -817).
     *  Default ON; the gametest baseline pins it OFF like every other navigator flag. */
    public static volatile boolean navigatorClimbOutResyncsAim = ControlSettings.DEFAULTS.navigatorClimbOutResyncsAim();

    /** Break the dry-land orbit: when the heading error sits between 45° and 170° for a dozen ticks
     *  while the bot moves, the trend camera's slow EMA is chasing a bearing that rotates as fast as
     *  it converges (the bot circles the node one cell out); switch to the cruise alpha until the
     *  error closes. Default ON; the gametest baseline pins it OFF like every other navigator flag. */
    public static volatile boolean navigatorOrbitBreaksAimLag = ControlSettings.DEFAULTS.navigatorOrbitBreaksAimLag();

    /** Cruise open water in vanilla's prone sprint-swim: dip for the pose, then sprint with a slight
     *  look-up, bobbing up to breathe. The held surface jump had kept the head out, so the sprint was
     *  cancelled every tick and the bot treaded at ~2 blocks/s. See docs/design/water-and-swimming.md.
     *  Default ON; the gametest baseline pins it OFF like every other navigator flag. */
    public static volatile boolean navigatorSurfaceSprintSwim = ControlSettings.DEFAULTS.navigatorSurfaceSprintSwim();

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
    public static volatile boolean navigatorFloatingBankBobFreeze = ControlSettings.DEFAULTS.navigatorFloatingBankBobFreeze();

    /** Water climb-out LATERAL gate (task#91, structural). A floating bot engages the bank climb-out
     *  (pillar takeover + block-less bank dig) ONLY when the climb waypoint sits horizontally BESIDE
     *  it ({@link net.magicterra.worlddriver.bot.control.NavigatorConstants#WATER_CLIMB_LATERAL_MAX} cells,
     *  Chebyshev). A higher waypoint that is laterally DISTANT is the routed exit further down an open
     *  corridor, not a bank to climb here: the open-river sheer-bank wedge (riverSheerBank) has the bot
     *  float against a +5 SHEER wall while A* correctly routes the committed exit +5 EAST across open
     *  water to a LOW (+1) bank — but the exit node is +1 higher, so the old {@code cwp.y>foot.y} climb
     *  intent fired and the block-less dig trenched the sheer wall the bot was merely PASSING
     *  (wallPressTicks) instead of swimming the last few cells to the walk-out. Gating the climb on
     *  lateral adjacency lets the swim-drive carry the bot along the corridor to the real exit, where
     *  the climb re-arms once adjacent (self-healing). A genuine bank climb-out has its node directly
     *  beside/below the float (Chebyshev 0-1) so it is unaffected.
     *  <p><b>Default ON and deliberately NOT in {@link #applyGameTestBaseline()}'s zero list.</b> This
     *  is a CORRECTNESS invariant (follow the committed path; do not climb a bank that isn't beside
     *  you), not a tunable heuristic — so it must stay active even under the test baseline the water
     *  scenes are pinned to, which is exactly what promotes riverSheerBank from a false-green/wedge to
     *  a genuine climb-out. The 9 sibling water scenes float directly below their banks (adjacent) and
     *  are byte-unchanged. */
    public static volatile boolean navigatorWaterClimbLateralGate = ControlSettings.DEFAULTS.navigatorWaterClimbLateralGate();

    /** Faster anti-churn repath: shorten the net-displacement churn-detection window from 400 ticks
     *  (≈20 s) to 240 (≈12 s) so a path-state churn (planner committed a suboptimal segment the
     *  executor grinds on — dry steep-ascent backtrack, boxed-pinch) arms its escalation/charge sooner,
     *  halving the per-cycle stall. SAFE because the trigger still requires <8 blocks NET XZ in the
     *  window (a healthy walk/swim nets ≫8 blocks even in 12 s) AND the !breakingEdge guard still
     *  protects a legitimate ~750-tick underwater dig from tripping. Default OFF (byte-identical).
     *  Validate via journeys (path-state churns only reproduce in continuous nav, not via tp). */
    public static volatile boolean navigatorFasterChurnRepath = ControlSettings.DEFAULTS.navigatorFasterChurnRepath();

    /** Segment anchor-gate exemption for a DEEP-WATER-FLOAT start: accept an otherwise-rejected
     *  continuation whose first node is a FAR node reachable by a straight clear-LOS swim over open water.
     *  <p><b>The bug</b> (live #47, deterministic ~6 s dead-stop): a bot floating in deep open water with a
     *  far open-water goal commits a BEST-EFFORT segment that string-pulls to {@code …[swimUp]·far[walk]}
     *  (an IN-PLACE {@code swimUp} at the foot, then ONE long {@code walk} node tens of blocks ahead). The
     *  eager continuation from that segment's {@code commitEnd} lands while the bot is still back at the
     *  start, so the continuation's {@code path[0]} is that FAR node — tens of blocks from the foot. The
     *  segment anchor-gate (the nearest-prefix-node {@code d2} check in {@code RouteAdoption.adopt}) finds NO
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
     *  crossing, never an impossible climb). The buoyant bot can swim straight to it, so the carrot
     *  bee-lines toward the far node and the bot makes forward progress (the deep-water-float analog of the
     *  quick-start stub / water bee-line) instead of churning the reject loop.
     *  <p>STRICTLY scoped so it can NOT exempt a truly mis-anchored segment: a fumbled continuation behind
     *  a wall / up a cliff fails the open-water LOS or the ±2 Y band and STILL rejects; a dry (non-floating)
     *  foot is never exempt. Verified by {@code DeepWaterFloatBeelineTest} (OFF rejects, ON accepts, a
     *  walled line still rejects, a dry foot still rejects).
     *  <p><b>Default ON</b> — the live A/B this note gated on has landed; flip OFF to A/B against it. Historic note: byte-identical no-op while OFF, which was the pre-flip state, and remains true if you flip it back win
     *  (the parent does the live acceptance). Wired to {@code mc.bot.setting} so a live run can flip it. */
    public static volatile boolean navigatorDeepWaterFloatBeeline = ControlSettings.DEFAULTS.navigatorDeepWaterFloatBeeline();

    /** Master gate for path-debug capture. When false the recorder early-returns and
     *  the chart tool renders whatever (empty) session exists. Default off — debug only. */
    public static volatile boolean pathDebug = ControlSettings.DEFAULTS.pathDebug();
    /** Master gate for path-archive recording (trajectory export/replay). When true the
     *  per-tick NavigatorSample is captured even if pathDebug is off. Default off. */
    public static volatile boolean pathArchive = ControlSettings.DEFAULTS.pathArchive();

    // Last, after every field above: see BotConfig.initialize.
    static { BotConfig.initialize(); }
}
