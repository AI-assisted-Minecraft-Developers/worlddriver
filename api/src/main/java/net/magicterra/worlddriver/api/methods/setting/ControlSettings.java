package net.magicterra.worlddriver.api.methods.setting;

import com.google.gson.JsonObject;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.schema.annotation.Wire;

/**
 * The control layer's keys of {@code mc.bot.setting}, one component each. A component is named as the field that holds
 * the key, and {@code @Wire} gives the key where the two differ. {@link #DEFAULTS} holds every default, and each field
 * starts from it; a key with no field of its own is optional and has none.
 */
public record ControlSettings(
        @Doc("Lower=more responsive; a value past any trip's length keeps the first route.")
        @Wire("navigator.repathEveryTicks")
        @Default("200")
        @Range(min = 20, max = 1000000)
        int navigatorRepathEveryTicks,

        @Doc("Fail after N no-progress ticks.")
        @Wire("navigator.totalTickBudget")
        @Default("1200")
        @Range(min = 200, max = 36000)
        int navigatorTotalTickBudget,

        @Doc("Skip yaw write below this delta.")
        @Wire("navigator.yawHysteresisDeg")
        @Default("5.0")
        @Range(min = 0, max = 30)
        double navigatorYawHysteresisDeg,

        @Doc("Blacklist stuck block after N ticks.") @Default("200") @Range(min = 20, max = 2000)
        int breakTimeoutTicks,

        @Doc("When bob-stalled climbing a bank whose top is ABOVE the water surface (a floating bot can't swim-jump "
                + "that high), the Navigator PLACES one throwaway hotbar block on the surface to get grounded, then "
                + "climbs out normally. Needs a placeable block in the hotbar; independent of allowPlace, fires "
                + "only at the water's edge.")
        @Default("true")
        boolean allowSwimEscapePlace,

        @Doc("Scoop the MLG water source back into the bucket after landing (reusable bucket, clean world).")
        @Default("true")
        boolean waterBucketScoop,

        @Doc("Hold the step-pointer on a RISING parkour leap until the feet have risen to its landing (grounded), "
                + "like the pillarUp/parkourPlace gates, so the within/passed re-sync can't skip the un-executed "
                + "leap and strand the bot on the +2 walk node above a water climb-out.")
        @Default("true")
        boolean navigatorParkourAscendHold,

        @Doc("Drop sprint when descending / walking ALONG a DEEP floating-water edge (≥2 deep, no floor) bordering "
                + "the foot, so sprint momentum can't drift the bot off the dry staircase into the pocket where a "
                + "buoyant bot bob-stalls on the un-climbable surface. Never fires on deliberate water entry "
                + "(planned node IS deep water) or a 1-deep splash. Drop-sprint only (no sneak-pin, so a planned "
                + "step-down still proceeds).")
        @Default("true")
        boolean navigatorDeepWaterDriftBrake,

        @Doc("DRY sibling of navigatorDeepWaterDriftBrake (task#36): latch the steepDescentNear sprint-drop across "
                + "the airborne sub-arcs of a step-down descent. The raw brake is gated onGround, so on the "
                + "airborne half of each step sprint re-arms and forward momentum walks the bot off a "
                + "survivable-but-deep (>4, <survivableFall) lip into a fatal cumulative fall (live Mountains "
                + "massif). Latched, sprint stays suppressed through the descent so momentum can't build over the "
                + "lip. Drop-sprint only (no pin); a gentle ≤4 staircase is a no-op.")
        @Default("true")
        boolean navigatorSteepDescentLatch,

        @Doc("Descent step-skip SNEAK-brake (task#36 real fix): the planner is hard-capped at pathfinderMaxDryFall "
                + "(=4) per node, so a grounded bot can never legitimately have its drive target "
                + "(wp=path.get(step)) more than that below the foot — yet the executor advances step DOWN the "
                + "staircase ahead of the bot (arc-length advance), aiming it forward+down at a far node and "
                + "launching it off the stair edge into a fatal fall (grounded foot y94 while wp=y83). Holds "
                + "vanilla sneak (edge-guard: can't step off a block edge but still steps down one at a time) + "
                + "kills sprint on the same tick, so the bot safely down-steps the routed ≤4 staircase. Cannot "
                + "deadlock (a ≤4 staircase keeps wp within maxDryFall → never fires).")
        @Default("true")
        boolean navigatorDescentStepSkipBrake,

        @Doc("Advance the step-pointer off a stepDown/fall/diagDown node that is a SHALLOW WATER-SURFACE foothold "
                + "(water at node, SOLID floor below, head not water) once the buoyant bot has grounded vertically "
                + "AT it but pinned ~0.74 b short of centre (cur2≈0.55, just over the 0.45 reach gate — buoyancy + "
                + "the water-climb jump ram it and it can't walk the last fraction in). Treats arrival at the "
                + "surface cell as reaching the node (relaxed reach), gated on a confirmed stall so a clean "
                + "approach advances normally first. Dry step-downs, deep floating-water landings, and "
                + "climb/walk/parkour edges are inert.")
        @Default("true")
        boolean navigatorWaterStepDownFloat,

        @Doc("The surface-swim turn/wall-corner FREEZE breaker (stuck against a wall or stuck in water): a flat "
                + "`walk` water-surface node sits the buoyant bot ~0.67 b out (cur2 floor ~0.455, just over the "
                + "0.45 reach gate) so `within` never closes; a straight crossing advances via `passed` (forward "
                + "momentum) but at a TURN/terminal/wall-corner node `passed` can't fire either — the node then has "
                + "NO relaxed-advance and the bot orbits/freezes (live deep-water bay corner: cur2 1.142 frozen 321 "
                + "ticks, aim swinging 403°; attack=0, NOT digging). ON: a confirmed in-water stall (noStepProgress "
                + "— horizontal-only in water so bob-immune — past 24) at a flat walk water node within reach 1.3 "
                + "advances the step (next node |Δy|<1.2 bars a climb-skip). A clean crossing advances via passed "
                + "first; dry walk and non-walk edges inert.")
        @Default("true")
        boolean navigatorWaterWalkReach,

        @Doc("Phase-1: drive the step pointer from the path PROJECTION (projSeg>step OR within) instead of the "
                + "per-tick bob-compensating gates (passed/crossedWalk/waterStepDown/waterWalkReach are "
                + "bypassed). Bob-immune by construction — unfreezes the step pointer the buoyancy bob strands "
                + "(live P0: projSeg led the frozen step by 2-5 segments at stalls). Edge-execution holds still "
                + "gate it.")
        @Default("true")
        boolean navigatorArcLengthAdvance,

        @Doc("Aim the camera AND the movement heading at the path TANGENT ahead of the projection instead of the "
                + "immediate-node bearing, which flips ~180° on node overshoot (the backward-jump / repeated "
                + "side-to-side hopping / facing-the-wall dead-corner stall; live P1: bot stalled on-path with dYaw "
                + "up to 129°). The tangent never reverses, killing that failure mode + the descent flip-rejection "
                + "bandaids. Skipped for launches + in the aim dead-zone.")
        @Default("true")
        boolean navigatorTangentAim,

        @Doc("With navigatorTangentAim: more than 0.6 blocks off the path, aim at the path's point 2.5 blocks ahead "
                + "instead of the bare tangent, so the bot rejoins the route instead of walking parallel to it (a "
                + "route chosen for its line — sight, corridor — is only kept if the bot is on it).")
        @Default("true")
        boolean navigatorTangentPursuit,

        @Doc("Phase-3: bob/jitter-IMMUNE ram-wedge recovery. When the arc-length projection makes no forward "
                + "progress (|ds|<0.05/tick) while horizontalCollision for ~1.5s, fold into the existing "
                + "fellOffPath recovery (blacklist the un-advanceable node + re-route). Structural replacement for "
                + "the descentRamStuck/ascentRamSlide zoo, which hang on noStepProgressTicks — a "
                + "3D-new-low counter the ram-jitter+bob zero every tick so it never fills (live -672,94: "
                + "descentRamStuck's exact node-1-below+hCol case, yet bobbed 34+t with no recovery).")
        @Default("true")
        boolean navigatorArcLengthWedge,

        @Doc("Bob-immune freeze-breaker trigger for a steep TALL-bank +1 stepUp/diagUp mount (live W→E -861→-632, "
                + "~50-70s jank, reproducible): the bot jumps off the diagonal corner & slides back, foot pinned "
                + "~0.78 BELOW the node, cur2 orbiting 0.64-0.88 just over the 0.45 gate (drive even flips ~180° "
                + "backward at the bank base). The existing stepUpFreeze that stops the pivot + forces a grounded "
                + "straight jump never fires — its two triggers (noStepProgressTicks 3D new-low, stepRamStuckTicks "
                + "onGround) are BOTH zeroed by the airborne bob apex. ON: a counter ticking on foot-below-node + "
                + "laterally-close IGNORING onGround ORs into stepUpFreeze so it engages on time. A clean fast "
                + "climb tops out before the bar; water/parkour/far-node inert.")
        @Default("true")
        boolean navigatorAscentRamBobBreak,

        @Doc("Release the block-less bank-DIG early when it is an UNREACHABLE OVERHANG a buoyant bot can never "
                + "break. At a concave island-bank notch (live -782/-790) the bot floats beside a +3-above-foot "
                + "riser behind an overhang, swings ~1000× with 0 breaks while NEVER grounding, and the dig's "
                + "breakingEdge flag suppresses BOTH recovery paths (reactive churn-charge + anti-stuck burst, "
                + "gated !breakingEdge) until the 1000-tick dig-commit cap finally frees it ~50 s later. ON: once "
                + "the dig holds one still-solid >=2-above-foot riser AFLOAT for ~200 ticks without grounding or "
                + "breaking, drop it (latch climbPillarGaveUp, clear breakingEdge, price the cell out, 30-tick dig "
                + "lockout) so the back-off burst frees the bot ~40 s sooner. A legit +1 grounded climb-out "
                + "staircase never trips it (its riser is +1 and it grounds + breaks).")
        @Default("true")
        boolean navigatorFutileBankDigRelease,

        @Default("true") boolean navigatorBridgeDescentPlaceAnchor,
        @Default("true") boolean navigatorBankDigSkipOverhang,
        @Default("true") boolean navigatorBuoyantSearchFromSurface,
        @Default("true") boolean navigatorBankDigForwardExit,
        @Default("true") boolean navigatorPillarReachGoalNoSnap,
        @Default("true") boolean navigatorBankDigSkipWhenCwpSwims,
        @Default("true") boolean navigatorSwimAshorePillarDespiteDeepDig,
        @Default("true") boolean navigatorFootholdBeforeBankDig,
        @Default("true") boolean navigatorClimbIntentFromSurface,
        @Default("true") boolean navigatorPillarTopsOutAtFlushExit,
        @Default("true") boolean navigatorFinalNodeDirectAim,
        @Default("true") boolean navigatorHoldLastNodeUntilStanding,
        @Default("true") boolean navigatorShallowWaterSideFoothold,
        @Default("true") boolean navigatorClimbOutResyncsAim,
        @Default("true") boolean navigatorOrbitBreaksAimLag,
        @Default("true") boolean navigatorSurfaceSprintSwim,
        @Default("true") boolean navigatorFloatingBankBobFreeze,
        @Default("false") boolean navigatorFasterChurnRepath,

        @Doc("At the segment anchor-gate, ACCEPT a continuation whose far first node is reachable by a straight "
                + "clear-LOS swim over open water (foot floating + all-water/air LOS + within ±2 Y), instead of "
                + "rejecting it as mis-anchored. Fixes the deep-water-START dead-stop (#47): a bot floating in deep "
                + "water commits a best-effort segment that collapses to swimUp+far-walk; the eager continuation "
                + "from commitEnd starts at the FAR node (tens of blocks from the foot) so the anchor-gate rejects "
                + "it (mis-anchored, d2≈2300), the foot-search returns the same best-effort, it re-rejects → repath "
                + "storm, hSpd=0, the bot never commits a forward path and never drives east (anti-spin ends "
                + "best-effort). Accepting lets the carrot bee-line toward the far node and cross. Gated so a "
                + "walled/cliff continuation or a dry foot still rejects (can't bee-line through a wall).")
        @Default("true")
        boolean navigatorDeepWaterFloatBeeline,

        @Doc("Sustain a climb on a FREE-HANGING (wall-less) vine: hold jump continuously + center-seek the column "
                + "instead of pressing toward the path node (which ejects a bot that is not buoyed by water off a "
                + "wall-less vine into the water below). Wall-backed vines keep the wall-press path.")
        @Default("true")
        boolean navigatorVineFreeHangClimb,

        @Doc("Grab a free-hanging vine at the parkour LANDING apex (the tick foot first becomes the climbable vine, "
                + "before gravity pulls it past into the water pocket below the curtain). Off → a parkour-onto-vine "
                + "undershoots to the pocket floor and must swim back up.")
        @Default("true")
        boolean navigatorVineLandGrab,

        @Doc("Release the vine CLING when the IMMEDIATE committed node is at/below the foot (wp.y<=foot.y, i.e. NOT "
                + "a climb). Stops the buoyant bot bobbing pinned on a vine curtain that HANGS over a bank/inlet "
                + "the path skims at one Y (climbUp oscillates with the y-bob, zero XZ progress — the -672 inlet "
                + "vine-bob, ~10 s); the bot drops off the vine and the normal walk/stepDown resumes. A genuine "
                + "vine ascent (wp ABOVE the foot) is untouched.")
        @Default("true")
        boolean navigatorVineDescentDrop,

        @Default("true") boolean navigatorAscendMovement,

        @Doc("Capture A* candidates + planned routes + actual trajectory for mc.debug.pathChart; (zero cost off)")
        @Default("false")
        boolean pathDebug,

        @Default("false") boolean pathArchive,

        @Doc("Per-tick compute slice, ms, of a search while the bot walks; 0 does the least work a tick can.")
        @Wire("pathfinder.sliceMs")
        @Default("6")
        @Range(min = 0, max = 50)
        long pathfinderSliceMs,

        @Doc("Per-tick compute slice, ms, while the bot stands waiting for a path; 0 does the least work a tick can.")
        @Wire("pathfinder.idleSliceMs")
        @Default("30")
        @Range(min = 0, max = 50)
        long pathfinderIdleSliceMs,

        @Default("true") boolean navigatorRecoveryHopFloorGate,
        @Default("false") boolean navigatorFromEndNoProgressDiscard,
        @Default("true") boolean navigatorTailConsumeDirectional,
        @Default("true") boolean navigatorCornerClearance,

        @Doc("Progressive quick-start stub: while a big re-plan is still slicing in the background, spend this many "
                + "nodes SYNCHRONOUSLY on a short toward-goal segment and walk it immediately instead of standing "
                + "through the search gap (fixes the pause between segments). 0=off.")
        @Default("600")
        @Range(min = 0, max = 10000)
        int pathfinderQuickNodes,

        @Default("5") int navigatorFutileSearchCap,
        @Default("40") int navigatorAscendDeadZoneCap,
        @Default("true") boolean navigatorStrideFloorGuard,
        @Default("true") boolean descentCameraDecouple,
        @Default("true") boolean navigatorTrendNeedsRoom,
        @Default("true") boolean descentDecoupleLaunches,

        @Doc("Air ticks at/below which DrownEscapeChain preempts an active process (deliberately far below "
                + "drownFloatAirThreshold so planned water crossings aren't interrupted)")
        @Default("100")
        int drownEscapeAirThreshold,

        @Default("0.5") double navigatorDescentYawAlpha,
        @Default("0.4") double navigatorCarrotBodyHalfWidth,
        @Default("0.4") double navigatorHeadingClearHalfWidth,
        @Default("true") boolean navigatorProjectCurrentLeg,
        @Default("true") boolean navigatorCarrotStopAtRise,
        @Default("true") boolean navigatorPreDigNextBreak,
        @Default("true") boolean navigatorDropAheadResync,
        @Default("true") boolean navigatorCornerFootingConsume,
        @Default("true") boolean navigatorDiagAscentSprint,
        @Default("true") boolean navigatorAvoidLoneRisers,
        @Default("48") int navigatorContinuationMaxLead,

        @Doc("Progressive pathfinding: overlap search with movement — greedily march a safe coarse-direction stub "
                + "(dry land + open water) toward the goal so the bot starts moving instantly instead of freezing "
                + "while the big sliced A* runs.")
        @Default("true")
        boolean pathfinderProgressive,

        @Default("true") boolean lethalEdgeBrake,

        @Doc("At or below this HP the navigator suppresses sprint and keeps the lethal-edge sneak pin even across "
                + "PLANNED descents (residual momentum near a survivable-planned ledge is the killer at low HP); 0 "
                + "disables.")
        @Default("6.0")
        @Range(min = 0, max = 20)
        double lowHealthCareful,

        @Default("true") boolean navigatorNoLaunchAtAdjacentBelowNode,
        @Default("1.0") double navigatorNoLaunchWithin,
        @Default("true") boolean navigatorDescentNodeHold,
        @Default("true") boolean navigatorDryReanchor,
        @Default("true") boolean navigatorWallCornerFastChurn,
        @Default("true") boolean navigatorDrowningEscape,
        @Default("true") boolean navigatorClimbGaveUpSticky,
        @Default("true") boolean navigatorStepUpBackoffRetry,
        @Default("true") boolean navigatorCarrotBodyLos,
        @Default("true") boolean navigatorDiagonalStringPull,
        @Default("true") boolean navigatorRiserLeadJump,
        @Default("0.1") double navigatorRiserLeadMargin,
        @Default("true") boolean navigatorDescentLaneKeep,
        @Default("true") boolean navigatorRiserLeadExact,
        @Default("true") boolean navigatorStepUpRawHeading,
        @Default("true") boolean navigatorSearchFromLanding,
        @Default("true") boolean navigatorCommitTailPeak,
        @Default("true") boolean navigatorRouteHysteresis,
        @Default("true") boolean navigatorDigCommitHoldRepath,
        @Default("true") boolean navigatorPillarSurfacePlace,
        @Default("true") boolean navigatorStuckStepMonotonic,
        @Default("true") boolean navigatorAboveNodeStallRecover,
        @Default("true") boolean navigatorDigAimPriority,
        @Default("true") boolean navigatorWallDigFallback,
        @Default("true") boolean navigatorRamNodeAimRelease,
        @Default("true") boolean navigatorPhysicalStallClock,
        @Default("true") boolean navigatorBridgeHoldRepath,
        @Default("true") boolean navigatorBankDigGroundBlip,
        @Default("true") boolean navigatorExpectAlarm,
        @Default("true") boolean navigatorWaterClimbLateralGate) {

    public static final ControlSettings DEFAULTS =
            ApiTypes.record(ControlSettings.class).decode(new JsonObject());
}
