package net.magicterra.agent.bot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the settings snapshot returned by {@code mc.bot.setting{...}} — the current value of
 * every knob, plus a reflective completion pass that surfaces any {@code public static volatile}
 * PRIMITIVE {@link BotConfig} field not hand-listed above (so newly-declared flags never lag the
 * snapshot). Extracted verbatim from {@link SettingsCommand#apply}; behaviour is identical.
 */
final class SettingsSnapshot {
    private SettingsSnapshot() {}

    static Map<String, Object> build(BotApiImpl bot) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("walker.repathEveryTicks", BotConfig.walkerRepathEveryTicks);
        snap.put("walker.totalTickBudget",  BotConfig.walkerTotalTickBudget);
        snap.put("walker.yawHysteresisDeg", BotConfig.walkerYawHysteresisDeg);
        snap.put("mine.searchVerticalRadius", BotConfig.mineSearchVerticalRadius);
        snap.put("breakTimeoutTicks", BotConfig.breakTimeoutTicks);
        snap.put("paused", bot.paused);
        snap.put("autoEat", BotConfig.autoEat);
        snap.put("autoEatFoodThreshold", BotConfig.autoEatFoodThreshold);
        snap.put("autoRespawn", BotConfig.autoRespawn);
        snap.put("autoRetreat", BotConfig.autoRetreat);
        snap.put("retreatHpThreshold", BotConfig.retreatHpThreshold);
        snap.put("autoBunker", BotConfig.autoBunker);
        snap.put("bunkerHpThreshold", BotConfig.bunkerHpThreshold);
        snap.put("bunkerTriggerRadius", BotConfig.bunkerTriggerRadius);
        snap.put("bunkerMinHostiles", BotConfig.bunkerMinHostiles);
        snap.put("bunkerDepth", BotConfig.bunkerDepth);
        snap.put("autoTotem", BotConfig.autoTotem);
        snap.put("autoShield", BotConfig.autoShield);
        snap.put("autoHeal", BotConfig.autoHeal);
        snap.put("healHpThreshold", BotConfig.healHpThreshold);
        snap.put("autoDodge", BotConfig.autoDodge);
        snap.put("creeperKeepDistance", BotConfig.creeperKeepDistance);
        snap.put("projectileDodgeRadius", BotConfig.projectileDodgeRadius);
        snap.put("autoFight", BotConfig.autoFight);
        snap.put("autoFightThreatThreshold", BotConfig.autoFightThreatThreshold);
        snap.put("combatReach", BotConfig.combatReach);
        snap.put("kiteDistance", BotConfig.kiteDistance);
        snap.put("combatCrit", BotConfig.combatCrit);
        snap.put("autoEquip", BotConfig.autoEquip);
        snap.put("equipDurabilityThreshold", BotConfig.equipDurabilityThreshold);
        snap.put("autoSwim", BotConfig.autoSwim);
        snap.put("antiSuffocate", BotConfig.antiSuffocate);
        snap.put("autoTool", BotConfig.autoTool);
        snap.put("autoBackfill", BotConfig.autoBackfill);
        snap.put("autoBackfillBlock", BotConfig.autoBackfillBlock);
        snap.put("autoBackfillRadius", BotConfig.autoBackfillRadius);
        snap.put("allowParkour4", BotConfig.allowParkour4);
        snap.put("allowBreak", BotConfig.allowBreak);
        snap.put("allowSwimEscapeBreak", BotConfig.allowSwimEscapeBreak);
        snap.put("allowSwimEscapePlace", BotConfig.allowSwimEscapePlace);
        snap.put("allowPlace", BotConfig.allowPlace);
        snap.put("allowParkourPlace", BotConfig.allowParkourPlace);
        snap.put("allowWaterBucketFall", BotConfig.allowWaterBucketFall);
        snap.put("maxWaterBucketFall", BotConfig.maxWaterBucketFall);
        snap.put("waterBucketScoop", BotConfig.waterBucketScoop);
        snap.put("avoidDanger", BotConfig.avoidDanger);
        snap.put("autoSecureAtDusk", BotConfig.autoSecureAtDusk);
        snap.put("hazardGridRadius", BotConfig.hazardGridRadius);
        snap.put("hazardGridDecimateTicks", BotConfig.hazardGridDecimateTicks);
        snap.put("deepWaterMax", BotConfig.deepWaterMax);
        snap.put("swimBankClimbMaxHeight", BotConfig.swimBankClimbMaxHeight);
        snap.put("sceneQueryMaxRadius", BotConfig.sceneQueryMaxRadius);
        snap.put("pathfinder.dangerPenalty", BotConfig.dangerPenaltyPerCell);
        snap.put("pathfinder.lavaDangerPenalty", BotConfig.lavaDangerPenalty);
        snap.put("pathfinder.contactDangerPenalty", BotConfig.contactDangerPenalty);
        snap.put("pathfinder.ledgeDangerPenalty", BotConfig.ledgeDangerPenalty);
        snap.put("pathfinder.waterDangerPenalty", BotConfig.waterDangerPenalty);
        snap.put("pathfinder.ledgeDangerMinDrop", BotConfig.ledgeDangerMinDrop);
        snap.put("avoidMobs", BotConfig.avoidMobs);
        snap.put("pathfinder.mobAvoidRadius", BotConfig.mobAvoidRadius);
        snap.put("pathfinder.mobAvoidPenalty", BotConfig.mobAvoidPenalty);
        snap.put("rangedAvoidRadius", BotConfig.rangedAvoidRadius);
        snap.put("fleeDangerBoost", BotConfig.fleeDangerBoost);
        snap.put("walkerDebug", BotConfig.walkerDebug);
        snap.put("walkerVerticalResync", BotConfig.walkerVerticalResync);
        snap.put("walkerLevelRiserJump", BotConfig.walkerLevelRiserJump);
        snap.put("walkerPadRamBreak", BotConfig.walkerPadRamBreak);
        snap.put("walkerParkourAscendHold", BotConfig.walkerParkourAscendHold);
        snap.put("walkerDeepWaterDriftBrake", BotConfig.walkerDeepWaterDriftBrake);
        snap.put("walkerSteepDescentLatch", BotConfig.walkerSteepDescentLatch);
        snap.put("craftReclaimTable", BotConfig.craftReclaimTable);
        snap.put("walkerDescentStepSkipBrake", BotConfig.walkerDescentStepSkipBrake);
        snap.put("walkerDescentFlipHold", BotConfig.walkerDescentFlipHold);
        snap.put("walkerWaterStepDownFloat", BotConfig.walkerWaterStepDownFloat);
        snap.put("walkerStepUpCrestReach", BotConfig.walkerStepUpCrestReach);
        snap.put("walkerWaterWalkReach", BotConfig.walkerWaterWalkReach);
        snap.put("walkerAscentRamJitterImmune", BotConfig.walkerAscentRamJitterImmune);
        snap.put("walkerArcLengthShadow", BotConfig.walkerArcLengthShadow);
        snap.put("walkerArcLengthAdvance", BotConfig.walkerArcLengthAdvance);
        snap.put("walkerTangentAim", BotConfig.walkerTangentAim);
        snap.put("walkerArcLengthWedge", BotConfig.walkerArcLengthWedge);
        snap.put("walkerArcProgressWedge", BotConfig.walkerArcProgressWedge);
        snap.put("walkerFellBelowAlign", BotConfig.walkerFellBelowAlign);
        snap.put("walkerAscentRamBobBreak", BotConfig.walkerAscentRamBobBreak);
        snap.put("walkerFutileBankDigRelease", BotConfig.walkerFutileBankDigRelease);
        snap.put("walkerBankDigSkipOverhang", BotConfig.walkerBankDigSkipOverhang);
        snap.put("walkerBuoyantSearchFromSurface", BotConfig.walkerBuoyantSearchFromSurface);
        snap.put("walkerBankDigForwardExit", BotConfig.walkerBankDigForwardExit);
        snap.put("walkerPillarReachGoalNoSnap", BotConfig.walkerPillarReachGoalNoSnap);
        snap.put("walkerBankDigSkipWhenCwpSwims", BotConfig.walkerBankDigSkipWhenCwpSwims);
        snap.put("walkerTraverseBreakOvershootResync", BotConfig.walkerTraverseBreakOvershootResync);
        snap.put("walkerSwimAshorePillarDespiteDeepDig", BotConfig.walkerSwimAshorePillarDespiteDeepDig);
        snap.put("walkerFloatingBankBobFreeze", BotConfig.walkerFloatingBankBobFreeze);
        snap.put("walkerFloatingBankFollow", BotConfig.walkerFloatingBankFollow);
        snap.put("walkerFasterChurnRepath", BotConfig.walkerFasterChurnRepath);
        snap.put("walkerDeepWaterFloatBeeline", BotConfig.walkerDeepWaterFloatBeeline);
        snap.put("pathfinderForbidParkourIntoDeepWater", BotConfig.pathfinderForbidParkourIntoDeepWater);
        snap.put("pathfinderForbidParkourFromFloatingWater", BotConfig.pathfinderForbidParkourFromFloatingWater);
        snap.put("pathfinderForbidParkourOverWaterGap", BotConfig.pathfinderForbidParkourOverWaterGap);
        snap.put("pathfinderParkourAscendNeedRunway", BotConfig.pathfinderParkourAscendNeedRunway);
        snap.put("pathfinderFloatingSurfaceCross", BotConfig.pathfinderFloatingSurfaceCross);
        snap.put("pathfinderVineOverWaterTax", BotConfig.pathfinderVineOverWaterTax);
        snap.put("pathfinderPadOverWaterTax", BotConfig.pathfinderPadOverWaterTax);
        snap.put("pathfinderPadClusterTax", BotConfig.pathfinderPadClusterTax);
        snap.put("walkerVineFreeHangClimb", BotConfig.walkerVineFreeHangClimb);
        snap.put("walkerVineLandGrab", BotConfig.walkerVineLandGrab);
        snap.put("walkerVineDescentDrop", BotConfig.walkerVineDescentDrop);
        snap.put("walkerAscendMovement", BotConfig.walkerAscendMovement);
        snap.put("pathDebug", BotConfig.pathDebug);
        snap.put("pathArchive", BotConfig.pathArchive);
        snap.put("pathDebugMaxNodes", BotConfig.pathDebugMaxNodes);
        snap.put("pathDebugMaxSamples", BotConfig.pathDebugMaxSamples);
        snap.put("pathChartAutoDump", BotConfig.pathChartAutoDump);
        snap.put("elytraDebug", BotConfig.elytraDebug);
        snap.put("smoothLook", BotConfig.smoothLook);
        snap.put("smoothLookDegPerTick", BotConfig.smoothLookDegPerTick);
        snap.put("pathfinder.maxNodes", BotConfig.pathfinderMaxNodes);
        snap.put("pathfinder.maxMs", BotConfig.pathfinderMaxMs);
        snap.put("pathfinder.sliceMs", BotConfig.pathfinderSliceMs);
        snap.put("pathfinder.idleSliceMs", BotConfig.pathfinderIdleSliceMs);
        snap.put("pathfinder.heuristicWeight", BotConfig.pathfinderHeuristicWeight);
        snap.put("pathfinderCacheEnabled", BotConfig.pathfinderCacheEnabled);
        snap.put("collisionAwarePathing", BotConfig.collisionAwarePathing);
        snap.put("pathfinderGoalField", BotConfig.pathfinderGoalField);
        snap.put("goalFieldCellSize", BotConfig.goalFieldCellSize);
        snap.put("goalFieldRadius", BotConfig.goalFieldRadius);
        snap.put("goalFieldVerticalRadius", BotConfig.goalFieldVerticalRadius);
        snap.put("pathfinderDepthPenalty", BotConfig.pathfinderDepthPenalty);
        snap.put("pathfinderDepthSlack", BotConfig.pathfinderDepthSlack);
        snap.put("pathfinderDescendCost", BotConfig.pathfinderDescendCost);
        snap.put("pathfinderWaterCellCost", BotConfig.pathfinderWaterCellCost);
        snap.put("pathfinderWaterClimbOutCost", BotConfig.pathfinderWaterClimbOutCost);
        snap.put("pathfinderSubmergedWaterCost", BotConfig.pathfinderSubmergedWaterCost);
        snap.put("pathfinderBridgeCost", BotConfig.pathfinderBridgeCost);
        snap.put("pathfinderThinObstacleHeight", BotConfig.pathfinderThinObstacleHeight);
        snap.put("pathfinderFrontierCommit", BotConfig.pathfinderFrontierCommit);
        snap.put("pathfinderHorizonBlocks", BotConfig.pathfinderHorizonBlocks);
        snap.put("pathfinderMaxDryFall", BotConfig.pathfinderMaxDryFall);
        snap.put("pathfinderSoftCommitNodes", BotConfig.pathfinderSoftCommitNodes);
        snap.put("pathfinderQuickNodes", BotConfig.pathfinderQuickNodes);
        snap.put("pathfinder.axisHeight", BotConfig.axisHeight);
        snap.put("blocksToAvoid", new ArrayList<>(BotConfig.extraHazardBlocks));
        snap.put("buildBlockWhitelist", new ArrayList<>(BotConfig.buildBlockWhitelist));
        snap.put("mutedEvents", new ArrayList<>(BotConfig.mutedEvents));
        snap.put("pathfinder.avoidZonePenalty", BotConfig.avoidZonePenalty);
        {
            List<Map<String, Object>> zs = new ArrayList<>();
            for (double[] z : BotConfig.avoidZones) {
                if (z.length < 4) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("x", z[0]); m.put("y", z[1]); m.put("z", z[2]); m.put("radius", z[3]);
                zs.add(m);
            }
            snap.put("avoidPoints", zs);
        }
        // Reflective completion: every public static volatile PRIMITIVE BotConfig field not
        // already in the hand-written snapshot above. Without this, newly-declared flags are
        // settable (reflective setter) but INVISIBLE to the snapshot — an observability blind
        // spot that let a preflight check read `null` for live flags (walkerCarrotBodyLos).
        // The snapshot must never lag the config surface again.
        for (java.lang.reflect.Field f : BotConfig.class.getFields()) {
            int mods = f.getModifiers();
            if (!java.lang.reflect.Modifier.isStatic(mods) || !java.lang.reflect.Modifier.isVolatile(mods)) continue;
            if (!f.getType().isPrimitive() || snap.containsKey(f.getName())) continue;
            try {
                snap.put(f.getName(), f.get(null));
            } catch (IllegalAccessException ignore) { }
        }
        return snap;
    }
}
