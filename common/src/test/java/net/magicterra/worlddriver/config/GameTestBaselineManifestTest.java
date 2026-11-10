package net.magicterra.worlddriver.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Every default-ON behaviour flag must have a recorded decision about whether the arena baseline pins it off.
 *
 * <p><b>The failure this guards is not a skipped test — it is a rewritten subject.</b>
 * {@link BotConfig#applyGameTestBaseline()} runs under every scene that takes {@link BotConfig#pinnedBaseline()} and
 * writes 38 fields, the first two of which are {@code allowBreak = false} and {@code allowPlace = false}. So a scene
 * suite that is green over a behaviour proves nothing about a live client, and a live client's behaviour proves nothing
 * about the suite — the two run different bots. Nothing announces this. There is no SKIP line, no warning, no absent
 * scene; the assertions run, pass, and describe a bot that had two of its capabilities removed by the process measuring
 * it.
 *
 * <p>2026-08-22 cost of the version of this with one flag: {@code navigatorDigAimPriority} is pinned off here, so two
 * successive fixes to the dig-claim release were authored, landed and judged against a branch that never executed on
 * the ladder. The evidence was a zero — {@code dig-aim RELEASE} appearing 0 times in 8022 ticks — which reads
 * identically as "the guard never expired" and "the guard was never entered".
 *
 * <p><b>What this test does NOT do</b> is check the baseline against its own assignment list. That would be a threshold
 * computed from the thing it measures: adding a line to {@code applyGameTestBaseline} would move both sides together
 * and stay green. The universe here comes from reflection over {@link SettingsRegistry#reflectivePrimitiveFields()}
 * filtered by {@code BotConfig.COMPILED_DEFAULTS}, and what the baseline writes is measured by RUNNING it against
 * sentinel values — never by reading its source. A new flag, or a flag flipped from default-OFF to default-ON,
 * therefore lands in the universe without landing in the manifest, and this goes red until somebody makes the decision
 * and writes down why.
 *
 * <p>Default-OFF booleans are deliberately outside the universe: while a flag is off, arena and client agree, so there
 * is nothing to decide. The decision becomes due on the flip to ON — which is exactly the event that adds it here.
 */
class GameTestBaselineManifestTest {

    // ------------------------------------------------------------------ the manifest
    //
    // PINNED — the fields applyGameTestBaseline() writes. Each was ON (or a live-tuned number)
    // for real play and is forced back to the historical value the arena assertions were authored
    // against. Three are capability permissions rather than behaviour tweaks; scenes that need
    // them turn them on themselves (see JourneyPortalEntryScenes' note about exactly this).

    private static final Map<String, String> PINNED = new TreeMap<>(Map.ofEntries(
            // --- capability permissions: not "a behaviour flipped later", a whole ability removed
            entry(
                    "allowBreak",
                    "permission: the pathfinder may mine obstructing blocks. OFF here, so any "
                            + "arena assertion about digging is about a bot that cannot dig unless the scene says so"),
            entry("allowPlace", "permission: the pathfinder may place a throwaway block to bridge a gap"),
            entry(
                    "allowWaterBucketFall",
                    "permission: the planner may route a fall taller than the dry cap "
                            + "because the bot carries a water bucket"),

            // --- §78 flip wave: ON for live play after the arena assertions were written
            entry("navigatorStepUpBackoffRetry", "§78 — stepUp mount backoff-retry (#47 problem-6 grind)"),
            entry("navigatorCarrotBodyLos", "carrot LOS honest about player width (jungle-trunk friction)"),
            entry(
                    "navigatorBankDigGroundBlip",
                    "§78 — bank-dig ground-blip immunity (underground-pool climb-out grind)"),
            entry(
                    "navigatorExpectAlarm",
                    "§78 — actuator expectation alarms; changes what the log SAYS, "
                            + "which is why a scene reading log lines would see it"),
            entry("navigatorStuckStepMonotonic", "§78 — monotonic stuck-window (open-water step-jitter starvation)"),
            entry("navigatorPillarSurfacePlace", "§78 — water-surface pillar crest-place (pillarUp deadlock)"),
            entry(
                    "navigatorAboveNodeStallRecover",
                    "§78 — climbed-past-the-node stall recovery (steep-mountain churn)"),
            entry(
                    "navigatorDigAimPriority",
                    "§78 — dig aim priority, successor to navigatorStickyDig. THE 2026-08-22 "
                            + "case: pinned off here, so the ladder ran neither implementation"),
            entry("navigatorWallDigFallback", "§78 — dry wall-pin dig fallback (§71)"),
            entry(
                    "pathfinderBreakCostMultiplier",
                    "§78 — dig-aversion multiplier on planner breakCost, "
                            + "back to the neutral 1.0 the arena costs were measured with"),
            entry("navigatorDryReanchor", "§78 — dry repath-rechurn breaker"),
            entry(
                    "navigatorBuoyantSearchFromSurface",
                    "§78 — buoyant A* anchored at the water surface, not the bobbing foot"),
            entry("navigatorBankDigSkipWhenCwpSwims", "§78 — skip the bank-dig when the next committed node is water"),
            entry("navigatorBankDigSkipOverhang", "§78 — block-less bank-dig overhang rejection"),
            entry("navigatorVineDescentDrop", "§78 — release the vine cling when the next node is not a climb"),
            entry("navigatorAscentRamBobBreak", "§78 — bob-immune ascent-ram freeze-breaker"),
            entry(
                    "navigatorPillarReachGoalNoSnap",
                    "§78 — don't snap an elevated air goal down when pillaring reaches it"),
            entry("pathfinderForbidParkourFromFloatingWater", "§78 — takeoff complement of the deep-water parkour ban"),
            entry("navigatorDeepWaterFloatBeeline", "§78 — segment anchor-gate exemption for a deep-water-float start"),
            entry("navigatorWaterWalkReach", "§78 — relaxed step-advance for a water-surface walk"),
            entry(
                    "navigatorWaterStepDownFloat",
                    "§78 — step-advance for a stepDown onto a shallow water-surface foothold"),
            entry("navigatorWallCornerFastChurn", "§78 — wall-corner fast-churn recovery"),
            entry(
                    "navigatorSwimAshorePillarDespiteDeepDig",
                    "§78 — pillar-place fallback for a +2 bank the bank-dig skips"),
            entry(
                    "navigatorFootholdBeforeBankDig",
                    "foothold first — a bot holding a block pillars before it digs the bank"),
            entry(
                    "navigatorClimbIntentFromSurface",
                    "climb-out intent read against the surface cell, not the bobbing foot"),
            entry(
                    "navigatorPillarTopsOutAtFlushExit",
                    "the water pillar tops out only on a rung with a flush exit beside it"),
            entry("navigatorFinalNodeDirectAim", "the last path node is aimed at directly and approached at a walk"),
            entry(
                    "navigatorHoldLastNodeUntilStanding",
                    "a last node that is the goal is spent only once the foot stands in it"),
            entry(
                    "navigatorShallowWaterSideFoothold",
                    "afloat in one-deep water the pillar takeover places its first rung beside the bot"),
            entry("navigatorClimbOutResyncsAim", "topping out of a water climb-out resets the aim's low-pass state"),
            entry(
                    "navigatorOrbitBreaksAimLag",
                    "a sustained mid-range heading error on dry land switches the aim EMA to the cruise alpha"),
            entry("navigatorSurfaceSprintSwim", "open water is crossed in the prone sprint-swim pose"),
            entry("navigatorFutileBankDigRelease", "§78 — early-release a provably futile block-less bank dig"),
            entry("navigatorBankDigForwardExit", "§78 — forward-hemisphere guard on bank-dig riser selection"),
            entry("navigatorFloatingBankBobFreeze", "§78 — floating +1 water-bank climb-out freeze"),
            entry(
                    "navigatorDrowningEscape",
                    "§78 — navigator's own drowning-escape reflex (lethal climb-out deadlock)"),
            entry("navigatorClimbGaveUpSticky", "§78 — make the 'pillar gave up' latch survive a repath"),

            // --- §87 second flip wave (the baseline's own comment marks this boundary)
            entry("navigatorRouteHysteresis", "§87 — repath route-oscillation damper"),
            entry("navigatorDigCommitHoldRepath", "§87 — dig-commit repath hold (dig-vs-repath starvation)"),
            entry("navigatorRamNodeAimRelease", "§87 — ram-pinned node-aim release (§80)"),
            entry(
                    "navigatorPhysicalStallClock",
                    "§87 — displacement-based stall clock feeding the stuck-gated recoveries"),
            entry("navigatorBridgeHoldRepath", "§87 — hold routine repaths while a bridgePlace edge is committed"),
            entry("pathfinderFloatingBreakTax", "§87 — price a floating dig at ×25 instead of ×5"),
            entry("pathfinderLogBreakTax", "§87 — trunk-aversion multiplier on log breakCost, back to neutral 1.0")));

    // LIVE — default-ON booleans the baseline deliberately leaves alone, so the arena runs them
    // exactly as a live client does. Listed so that "not pinned" is a recorded decision rather
    // than an absence. The reason each is here is the same one unless its group says otherwise: it
    // was already ON when the arena assertions were authored, so the scenes were written against
    // it. What differs per row is what the flag does, which is what a reader needs in order to
    // judge whether a NEW flag belongs beside it or in PINNED.

    private static final Map<String, String> LIVE = new TreeMap<>(Map.ofEntries(
            // survival reflexes and safety gates — a scene that removed these would be testing
            // a bot that cannot save itself, which is not the bot that plays
            entry("antiSuffocate", "suffocation backstop"),
            entry("autoDrownEscape", "active-process drowning-escape chain"),
            entry("autoFloatWhenDrowning", "idle drowning float"),
            entry("contactDamageEscape", "step out of a damaging block"),
            entry("lavaProximityEscape", "walk away from an advancing lava front"),
            entry("avoidDanger", "A* keeps a one-block buffer from lava/fire"),
            entry("deepDarkOpenSkyNearby", "no underground penalty for a foot a few cells from a shallow surface"),
            entry("deepDarkClientSurface", "a client reads the underground penalty's surface from its own blocks"),
            entry("deepDarkSparesGoalDepth", "no underground penalty down to the depth of the search's destination"),
            entry("stuckPenaltyForgetsChangedCells", "a stuck penalty is dropped once its cell's block changes"),
            entry("lethalEdgeBrake", "navigator sneak-brake at a lethal edge"),
            entry("allowFleeBreak", "a flee may break out of a corner"),
            entry("allowSwimEscapeBreak", "a water escape may break out"),
            entry("allowSwimEscapePlace", "a water escape may place to climb out"),
            entry("waterBucketScoop", "scoop the MLG water back after landing"),
            entry("craftReclaimTable", "take back a crafting table the craft placed"),

            // client-side presentation and input coexistence — headless arenas never reach these
            entry("cameraSlew", "stream-grade camera smoothing"),
            entry("cameraFrameLerp", "draw the bot's per-tick turn across the tick's frames"),
            entry("aimOverheadKeepsYaw", "an exact aim at a block straight overhead keeps its yaw"),
            entry(
                    "digOnlyUnderCrosshair",
                    "a client dig lands only on the block under the crosshair; server bodies have no crosshair"),
            entry(
                    "placeOnlyUnderCrosshair",
                    "a client place clicks only the face under the crosshair; server bodies have no crosshair"),
            entry("placeWaitsForUseDelay", "a client place waits out vanilla's right-click delay"),
            entry("mouseYield", "human/bot mouse coexistence"),
            entry("mouseYieldHud", "the 'bot is driving' badge"),
            entry("keepTickingUnfocused", "keep ticking while the window loses focus"),
            entry("descentCameraDecouple", "decouple camera from movement on a dry descent"),
            entry("descentDecoupleLaunches", "extend that decoupling to all dry launches"),

            // pathfinder core — turning any of these off changes what a route IS, not how well
            // it is executed, so an arena running without them would be planning a different world
            entry("collisionAwarePathing", "collision-shape solidity instead of the coarse blocksMotion()"),
            entry(
                    "pathfinderSurfaceWaterNodes",
                    "only the surface water cell is a lateral node; water exits are the climbOutPlace edge"),
            entry("pathfinderSwimUpBreakStaysInWater", "a swimUpBreak never rises out of the top water cell into air"),
            entry("pathfinderSwimAshoreHeadRoom", "a swimAshore clears the cell two above the water it leaves"),
            entry(
                    "pathfinderPillarStockedLikeBridge",
                    "with a full bag a pillar rung costs no more than a bridge block"),
            entry(
                    "pathfinderDeepWaterPriced",
                    "deep water is priced by the water taxes, not charged the HazardField's lethal penalty"),
            entry("pathfinderCacheEnabled", "per-search blockstate memoisation"),
            entry("pathfinderProgressive", "progressive pathfinding"),
            entry("pathfinderFrontierCommit", "segmented planning to the loaded-chunk frontier"),
            entry("pathfinderDiveWhenNoSurfaceRoute", "dive under a wall when no surface route exists"),
            entry(
                    "pathfinderEscapeBreakOutsideBubble",
                    "with allowBreak on, a water-escape break far from the search origin is priced, not refused"),
            entry(
                    "pathfinderForbidParkourOverTheVoid",
                    "refuse to plan a leap over the void when bridging is possible"),
            entry("pathfinderForbidParkourIntoDeepWater", "refuse to plan a leap into deep water"),
            entry("pathfinderNoHangingHop", "no move from a hanging climbable cell into another"),
            entry("pathfinderParkourAscendNeedRunway", "approach-runway gate for the +1-up parkour leap"),

            // flipped ON after the arena assertions were written, and live anyway: pinning it OFF
            // leaves the arena a bot that cannot finish a walk past the planner's horizon
            entry(
                    "navigatorTailConsumeDirectional",
                    "a best-effort tail still ahead of the bot is not spent until the bot passes it or stalls"),

            // the arc-length pursuit refactor — its ON phases replaced whole families of
            // per-tick gates, so pinning them off would restore machinery that no longer exists
            entry("navigatorArcLengthAdvance", "phase 1: drive the step pointer from the path projection"),
            entry("navigatorTangentAim", "phase 2: aim at the bob-immune path tangent"),
            entry("navigatorTangentPursuit", "phase 2's cross-track term: off the path, aim at the path's point ahead"),
            entry("navigatorArcLengthWedge", "phase 3: bob-immune ram-wedge recovery"),

            // navigator execution guards that predate the §78/§87 waves
            entry("navigatorAscendMovement", "task#82 ascent machine (DrivePhase)"),
            entry("navigatorStrideFloorGuard", "stride floor-guard (gap #53)"),
            entry("navigatorRecoveryHopFloorGate", "floor-gate the unaimed recovery hops"),
            entry("navigatorCornerClearance", "corner-clearance repulsion in the walk drive"),
            entry("navigatorDeepWaterDriftBrake", "sprint brake along a deep-water edge"),
            entry("navigatorSteepDescentLatch", "dry sibling of the deep-water drift brake"),
            entry("navigatorDescentStepSkipBrake", "descent step-skip sneak-brake"),
            entry("navigatorDescentNodeHold", "refuse to consume a node below the foot while still supported"),
            entry("navigatorNoLaunchAtAdjacentBelowNode", "refuse a jump at a node both below and already close"),
            entry("navigatorParkourAscendHold", "step-advance guard for ascending parkour leaps"),
            entry("navigatorBridgeDescentPlaceAnchor", "descending-bridgePlace lip anchor"),
            entry("navigatorVineFreeHangClimb", "sustain a climb on a free-hanging vine"),
            entry("navigatorVineLandGrab", "grab a free-hanging vine at the parkour landing apex"),
            entry("navigatorWaterClimbLateralGate", "water climb-out lateral gate"),

            // the cross-country route work (2026-09): each was measured on the real client over jittered full
            // runs before it shipped ON, so the arena runs it — a scene it breaks is a finding, not noise
            entry("navigatorTrendNeedsRoom", "the dry trend centroid steers only while the body has room along it"),
            entry("navigatorCarrotStopAtRise", "carrot and trend centroid stop at a stair flight's first riser"),
            entry("navigatorProportionalLaneKeep", "a dry lane-keep strafe in proportion to the offset, not full"),
            entry("navigatorFootAboveLowFloor", "the foot cell of a body on a lower-than-full floor is the cell above"),
            entry("navigatorConsumeNodeJumpedFrom", "an airborne body above its node's row may spend it for a climb"),
            entry(
                    "navigatorRiserLeadRow",
                    "a cardinal step's riser lead also takes the riser's neighbour across the step"),
            entry("navigatorConsumeOnClimbable", "a bot on a ladder or vine may spend a node for the climb above it"),
            entry("navigatorLaunchDrivesLanding", "a leap drives at its landing node, not the camera's trend"),
            entry("navigatorArcWedgeSparesClimb", "a bot on a ladder or vine pressing its wall is not wedged"),
            entry(
                    "navigatorVineClimbToNode",
                    "a ladder or vine climb goes on while the committed node is above the feet"),
            entry("clutchSparesClimbableFall", "no water clutch for a fall a ladder or vine already makes harmless"),
            entry("navigatorCatchClimbableLanding", "a fall onto a ladder or vine cell steers into its column"),
            entry("navigatorFellOffSparesPlannedDrop", "a body inside a planned drop has not fallen off its path"),
            entry("navigatorOffPathRidesLeap", "a body in flight on a leap is measured against the leap's line"),
            entry("navigatorLeapFromLip", "a running bot jumps a straight leap at the lip of its launch cell"),
            entry("navigatorWalkUpHalfSteps", "a rise onto a bottom slab or an up-facing stair is walked, not jumped"),
            entry("navigatorVineStepOff", "a bot sliding down a ladder or vine lets go once the drop is harmless"),
            entry("navigatorVineCentreClimb", "a wall-backed ladder or vine climb steers back to its column's centre"),
            entry(
                    "navigatorRunOffShortDrop",
                    "a leap onto floor that goes on is not braked or turned back to its node"),
            entry("navigatorPadLeapUnsprinted", "a running bot takes a short diagonal leap onto a pad unsprinted"),
            entry("navigatorSplashLandingReached", "a drop's water landing is reached from under it"),
            entry("navigatorSprintIntoPool", "a drop into open water is run off at a sprint"),
            entry("navigatorSprintSwimUp", "rising under water to a surface node swims at a sprint"),
            entry("navigatorLeapIntoPool", "a sprinting bot leaps from the lip of a drop into open water"),
            entry("navigatorWadeBob", "wading one-deep water toward a level node holds the jump"),
            entry("navigatorWadeBankTiming", "a wader times its bob to go over a bank's face"),
            entry("navigatorSwimRiseReached", "a surface node is reached by a bot swimming up under it"),
            entry("navigatorHalfStepNoLead", "no riser lead jump onto a half-step flight two cells out"),
            entry("navigatorNoDipOnPlunge", "a body plunging into water from a fall does not start the cruise"),
            entry("navigatorSwimRiseLookUp", "a swimmer rising under water looks up and sprints toward a bank node"),
            entry(
                    "navigatorSprintJumpStart",
                    "a slow sprinting bot sprint-jumps up to speed down a straight level run"),
            entry("navigatorBankBreachTiming", "a swimmer rising to a bank coasts once it will top out on the face"),
            entry(
                    "navigatorTreadWhileDigging",
                    "a bank dug from the water is begun on the way in, dug from short of it"),
            entry(
                    "navigatorCruiseOverShallowFloor",
                    "an engaged sprint-swim goes on over a floor two under the surface"),
            entry(
                    "navigatorDiagonalStringPull",
                    "string-pull a flat cardinal staircase into a diagonal the body clears"),
            entry("navigatorRiserLeadJump", "jump a dry +1 riser at its face distance, keeping sprint"),
            entry("navigatorRiserLeadExact", "the riser lead only for the riser under the node itself"),
            entry("navigatorDescentLaneKeep", "lane-keep strafe on a one-block step-down"),
            entry("navigatorSearchFromLanding", "plan an airborne bot from its landing cell, not the jump apex"),
            entry("navigatorDiagAscentSprint", "keep sprint through a diagonal +1 take-off"),
            entry("navigatorCommitTailPeak", "cut a best-effort segment whose tail sinks into a dip back to its ridge"),
            entry(
                    "navigatorPreDigNextBreak",
                    "start a stairUpBreak's dig in the air so destroyDelay burns before landing"),
            entry(
                    "navigatorDropAheadResync",
                    "a body that dropped a row early on a descent skips to the node on its row"),
            entry("navigatorCornerFootingConsume", "a bot on its node's row counts as standing even on a thin corner"),
            entry(
                    "navigatorStepUpRawHeading",
                    "a step-up's heading is judged against the raw aim as well as the smoothed one"),
            entry(
                    "navigatorProjectCurrentLeg",
                    "the path projection includes the leg the bot is walking, not only those past its node"),
            entry(
                    "navigatorAvoidLoneRisers",
                    "off a climb, a heading walks round a +1 block that is not the path's own step"),
            entry(
                    "navigatorDescentAnchorOwnSupport",
                    "the descending-bridge lip anchor waits only for the support of the cell stepped onto"),
            entry("combatCrit", "jump before a melee swing for the 1.5x critical"),
            entry("combatCollectDrops", "post-kill drop sweep"),
            entry("combatShield", "melee raises the off-hand shield between swings"),
            entry("combatShieldAgainstDraw", "the combat shield goes up for a drawn bow and on the walk in"),
            entry("combatSwingBetweenShots", "melee swings only early in a bow's draw and waits out the shot"),
            entry("combatShieldLateDraw", "the walk in raises the shield for a nearby bow late in its draw"),
            entry("combatTakeCover", "melee sidesteps to put a mob in a skeleton's line of fire"),
            entry("combatDirectChase", "the walk in sprints straight at a target in sight on open floor"),
            entry("combatSprintJumpChase", "the straight chase sprint-jumps while the target is far"),
            entry("combatKeepPackInFront", "melee backs off from and faces the middle of the close mobs"),
            entry("combatCritOnTheFall", "the crit jump is timed so the swing lands while falling"),
            entry("combatNearestFirst", "an engage fight takes the nearest hostile first"),
            entry("combatCreeperEscape", "a fusing creeper is outrun or faced behind the shield"),
            entry("combatCreeperLast", "an engage fight leaves a creeper for last"),
            entry("combatCreeperBait", "melee footwork lets an unlit creeper come close"),
            entry("combatCreeperCircle", "clear of a fusing creeper's blast, the bot circles it"),
            entry("combatPressIn", "melee walks in on a target knocked to the edge of reach"),
            entry("combatOrbitLoneTarget", "melee sidesteps round a lone target, clear of walls"),
            entry("combatWalkInPastArrival", "a fight walks the last block its route stopped short of"),
            entry("combatKeepSpacing", "melee steps back from a mob inside its strike distance"),
            entry("mineCollectAsYouGo", "mine picks up each drop before going for the next block"),
            entry("mineCollectPickupReach", "mine walks to a drop by pickup reach, breaking into its hole"),
            entry("mineNextBlockInPlace", "mine starts the next block at once when the drop lands in reach"),
            entry("minePeelInReach", "mine breaks the one block cutting its view of a target in reach"),
            entry("mineHoldDigOnTheWay", "mine counts the post-break dig cooldown down while it walks"),
            entry("mineNearestByWalk", "mine takes the target with the shortest walk to its stand"),
            entry("mineRouteByVeins", "mine starts the shortest walk through whole veins that fills the quota"),
            entry("mineNoIdleTicks", "mine chains choose, walk and dig hand-overs within one tick"),
            entry("clearAreaDigsOnlyTheBox", "clearArea's walks break nothing outside the box"),
            entry("clearAreaPlansStands", "clearArea breaks all it can from the stand with the most cells in reach"),
            entry("clearAreaHoldDigOnTheWay", "clearArea counts the post-break dig cooldown down while it walks"),
            entry("buildPlansStands", "build places all it can from the reachable stand that reaches the most"),
            entry("farmPlansStands", "farm harvests and replants all it can from the stand that reaches the most"),
            entry("elytraViaLinesUp", "an elytra flight crosses each via point along the line halving its turn"),
            entry("elytraViaKeepsBoost", "an elytra flight keeps a rocket burning while via points remain"),
            entry("elytraLandsOnSurface", "an elytra landing touches down on pos's surface without a hurting drop"),
            entry("elytraLandingStops", "an elytra landing holds sneak until the body stands still"),
            entry("elytraFlareBoosts", "an elytra landing lights a rocket when slow and low"),
            entry("elytraKeepsBurningRocket", "a new elytra order counts the last order's rocket as burning"),
            entry(
                    "navigatorFailWhenProvenUnreachable",
                    "a walk whose owner asks fails once a search proves the goal out of reach"),
            entry(
                    "navigatorNoPathWaitsOnlyForNearPenalties",
                    "a no-path search waits only for stuck penalties near the cells it reached"),
            entry("duskUrgent", "urgent dusk securing")));

    // ------------------------------------------------------------------ the assertions

    @Test
    void theBaselineWritesExactlyTheManifest() {
        Set<String> written = fieldsWrittenByBaseline();
        assertEquals(
                new TreeSet<>(PINNED.keySet()),
                written,
                "applyGameTestBaseline() and this manifest disagree about which fields it pins. "
                        + "If you added a line there, add a row here saying why the arena must not run "
                        + "that behaviour; if you removed one, remove the row. The manifest is the only "
                        + "place a reader can find out why the suite's bot differs from the shipped one.");
    }

    @Test
    void everyDefaultOnBooleanIsClassified() {
        Set<String> universe = defaultOnBooleans();
        Set<String> classified = new TreeSet<>(LIVE.keySet());
        for (String name : PINNED.keySet()) {
            if (isBoolean(name)) {
                classified.add(name);
            }
        }
        assertEquals(
                classified,
                universe,
                "the set of default-ON booleans and the manifest disagree.\n"
                        + "  In the universe but not the manifest: a flag that ships ON has no recorded "
                        + "decision. Unless applyGameTestBaseline() pins it, the arena bot now runs a "
                        + "behaviour the scenes were not authored against — add it to PINNED (and to the "
                        + "baseline) if they were written without it, or to LIVE if the suite should run "
                        + "it. Flipping an existing flag from default-OFF to default-ON lands it here "
                        + "too, and that flip is the moment the decision comes due.\n"
                        + "  In the manifest but not the universe: the flag no longer ships ON (or was "
                        + "deleted), so its row is stale — drop it, and if it is in PINNED drop the "
                        + "baseline line too, because pinning a flag that is already OFF is a no-op that "
                        + "reads like a decision.");
    }

    /**
     * The two enumerators must agree about which fields exist.
     *
     * <p>They reach the config classes by different reflection: {@code reflectivePrimitiveFields()} uses
     * {@code getFields()}, which walks the superclass chain, while {@code COMPILED_DEFAULTS} is built from
     * {@code persistableFields()} → {@code getDeclaredFields()}, which does not. Both javadocs call themselves "the ONE
     * enumeration"; each is, within itself. Both walk {@code BotConfig.CLASSES}, the one class per layer the settings
     * were split into, and every field is declared in one of those classes itself, so the difference is invisible.
     *
     * <p>It would stop being invisible if a config class came to extend another: a field moved to the parent stays
     * visible to the settings schema and snapshot (agents see it, so nothing looks wrong) but drops out of save/load
     * and out of {@code snapshotAll}/{@code restoreAll}, which is precisely the leak snapshotAll's javadoc exists to
     * prevent — {@code applyGameTestBaseline()}'s OFF values would escape into the live bot for exactly the moved
     * flags.
     *
     * <p>This assertion covered only {@code boolean} until now, which left the 86 int/double/float/ long fields — and
     * the String/Set ones, which are persistable but not primitive — with no guard at all. It is green today for a
     * reason that can be read off the source rather than measured: {@code persistable} accepts
     * boolean/int/long/double/float/String/Set, every {@code public static volatile} primitive is static and non-final
     * (volatile implies non-final) and of one of those five primitive types, and {@code serialize} returns null only
     * for a null value, which a boxed primitive never is.
     */
    @Test
    void everySurfaceFieldHasCompiledDefault() {
        Map<String, String> defaults = compiledDefaults();
        Set<String> unrecorded = new TreeSet<>();
        for (Field f : SettingsRegistry.reflectivePrimitiveFields()) {
            if (!defaults.containsKey(f.getName())) {
                unrecorded.add(f.getName());
            }
        }
        assertEquals(
                Set.of(),
                unrecorded,
                "a field on the settings surface has no compiled default, so it is invisible to "
                        + "the universe above and could ship ON without ever being classified. Either it "
                        + "is per-tick runtime state (which belongs with the bot that rewrites it, not in a "
                        + "config class), or it is configuration and should be persistable, or "
                        + "it was moved out of its config class's own declaration — see this test's javadoc: "
                        + "getFields() follows a superclass, getDeclaredFields() does not, so a split that "
                        + "moves fields to a parent keeps them in the settings schema while dropping them "
                        + "from persistence and from snapshotAll/restoreAll.");
    }

    @Test
    void theProbeLeavesTheConfigAsItFoundIt() {
        // The probe writes a sentinel into every primitive on the surface. These tests share a
        // JVM with the rest of common's suite, so a leak here would fail somebody else's test in
        // a way that points nowhere near this file.
        Map<Field, Object> before = saveAll();
        fieldsWrittenByBaseline();
        Map<Field, Object> after = saveAll();
        Set<String> drifted = new TreeSet<>();
        before.forEach((f, v) -> {
            if (!v.equals(after.get(f))) {
                drifted.add(f.getName());
            }
        });
        assertTrue(drifted.isEmpty(), "the baseline probe leaked into live config: " + drifted);
    }

    /**
     * The probe against a stand-in that writes one field of each primitive type, so its blind spots do not hinge on
     * which types the real baseline happens to pin today: a type it cannot see would let a later baseline line of that
     * type slip past the manifest check.
     */
    @Test
    void theProbeSeesOneWriteOfEachTypeAndNothingElse() {
        Map<Class<?>, Field> oneOfEach = new LinkedHashMap<>();
        for (Field f : SettingsRegistry.reflectivePrimitiveFields()) {
            oneOfEach.putIfAbsent(f.getType(), f);
        }
        assertTrue(
                oneOfEach.containsKey(boolean.class) && oneOfEach.containsKey(double.class),
                "the settings surface came back without booleans or doubles: " + oneOfEach.keySet());
        for (Field f : oneOfEach.values()) {
            assertEquals(
                    Set.of(f.getName()),
                    fieldsWrittenBy(() -> assign(f)),
                    "the probe misread a lone write to the " + f.getType() + " field " + f.getName());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Map.Entry<String, String> entry(String k, String v) {
        return Map.entry(k, v);
    }

    /**
     * The fields {@link BotConfig#applyGameTestBaseline()} writes, measured by running it.
     *
     * <p>Two passes with different sentinels, because one pass can only prove a field changed AWAY from that sentinel:
     * a baseline line assigning the very value the pass had installed would look untouched. A field the baseline writes
     * differs from at least one of two distinct sentinels, so the union is exact.
     */
    private static Set<String> fieldsWrittenByBaseline() {
        return fieldsWrittenBy(BotConfig::applyGameTestBaseline);
    }

    private static Set<String> fieldsWrittenBy(Runnable writer) {
        Map<Field, Object> saved = saveAll();
        try {
            Set<String> changed = new TreeSet<>(probe(writer, true, -9991));
            changed.addAll(probe(writer, false, -9992));
            return changed;
        } finally {
            restoreAll(saved);
        }
    }

    private static Set<String> probe(Runnable writer, boolean flag, long number) {
        for (Field f : SettingsRegistry.reflectivePrimitiveFields()) {
            writeSentinel(f, flag, number);
        }
        writer.run();
        Set<String> changed = new TreeSet<>();
        for (Field f : SettingsRegistry.reflectivePrimitiveFields()) {
            if (!isSentinel(f, flag, number)) {
                changed.add(f.getName());
            }
        }
        return changed;
    }

    private static void writeSentinel(Field f, boolean flag, long number) {
        Class<?> t = f.getType();
        try {
            if (t == boolean.class) {
                f.setBoolean(null, flag);
            } else if (t == int.class) {
                f.setInt(null, (int) number);
            } else if (t == long.class) {
                f.setLong(null, number);
            } else if (t == float.class) {
                f.setFloat(null, number);
            } else if (t == double.class) {
                f.setDouble(null, number);
            } else {
                throw new AssertionError("the settings surface grew a primitive this probe "
                        + "cannot write (" + t + " " + f.getName() + "). Teach it that type — "
                        + "skipping the field would make the baseline's writes to it invisible, "
                        + "which is the exact blindness this test exists to remove.");
            }
        } catch (IllegalAccessException ex) {
            throw new AssertionError("cannot write " + f.getName(), ex);
        }
    }

    private static boolean isSentinel(Field f, boolean flag, long number) {
        Class<?> t = f.getType();
        try {
            if (t == boolean.class) {
                return f.getBoolean(null) == flag;
            }
            if (t == int.class) {
                return f.getInt(null) == (int) number;
            }
            if (t == long.class) {
                return f.getLong(null) == number;
            }
            if (t == float.class) {
                return f.getFloat(null) == (float) number;
            }
            if (t == double.class) {
                return f.getDouble(null) == (double) number;
            }
            throw new AssertionError("unreachable: writeSentinel already rejected " + t);
        } catch (IllegalAccessException ex) {
            throw new AssertionError("cannot read " + f.getName(), ex);
        }
    }

    /** A value distinct from both sentinels, as one baseline line would assign it. */
    private static void assign(Field f) {
        Class<?> t = f.getType();
        try {
            if (t == boolean.class) {
                f.setBoolean(null, true);
            } else if (t == int.class) {
                f.setInt(null, 7);
            } else if (t == long.class) {
                f.setLong(null, 7L);
            } else if (t == float.class) {
                f.setFloat(null, 7.5f);
            } else if (t == double.class) {
                f.setDouble(null, 7.5);
            } else {
                throw new AssertionError("no stand-in value for " + t + " " + f.getName());
            }
        } catch (IllegalAccessException ex) {
            throw new AssertionError("cannot write " + f.getName(), ex);
        }
    }

    private static boolean isBoolean(String fieldName) {
        for (Field f : SettingsRegistry.reflectivePrimitiveFields()) {
            if (f.getName().equals(fieldName)) {
                return f.getType() == boolean.class;
            }
        }
        return false;
    }

    private static Set<String> defaultOnBooleans() {
        Map<String, String> defaults = compiledDefaults();
        Set<String> out = new TreeSet<>();
        for (Field f : SettingsRegistry.reflectivePrimitiveFields()) {
            if (f.getType() == boolean.class && "true".equals(defaults.get(f.getName()))) {
                out.add(f.getName());
            }
        }
        return out;
    }

    /**
     * The compiled-in defaults, captured at BotConfig class init before any {@code load()}.
     *
     * <p>Read reflectively rather than by sampling the live fields on purpose: these tests share a JVM, so a live read
     * would make the universe "whatever the last test left set". If the field is ever renamed this throws instead of
     * degrading — an empty map would make the whole test vacuously green, which is the failure mode it was written to
     * prevent.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, String> compiledDefaults() {
        try {
            Field f = BotConfig.class.getDeclaredField("COMPILED_DEFAULTS");
            f.setAccessible(true);
            Map<String, String> m = (Map<String, String>) f.get(null);
            if (m == null || m.isEmpty()) {
                throw new AssertionError("BotConfig.COMPILED_DEFAULTS is empty; this test cannot "
                        + "tell a default-ON flag from a default-OFF one and would pass over "
                        + "anything");
            }
            return m;
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(
                    "BotConfig.COMPILED_DEFAULTS is how this test knows a flag's "
                            + "shipped default. Point this at the new name if it moved — do NOT fall back "
                            + "to reading the live field values.",
                    ex);
        }
    }

    private static Map<Field, Object> saveAll() {
        Map<Field, Object> m = new LinkedHashMap<>();
        for (Field f : SettingsRegistry.reflectivePrimitiveFields()) {
            try {
                m.put(f, f.get(null));
            } catch (IllegalAccessException ex) {
                throw new AssertionError("cannot read " + f.getName(), ex);
            }
        }
        return m;
    }

    private static void restoreAll(Map<Field, Object> saved) {
        saved.forEach((f, v) -> {
            try {
                f.set(null, v);
            } catch (IllegalAccessException ex) {
                throw new AssertionError("cannot restore " + f.getName(), ex);
            }
        });
    }
}
