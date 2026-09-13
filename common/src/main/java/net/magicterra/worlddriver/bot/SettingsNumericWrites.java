package net.magicterra.worlddriver.bot;

import java.util.List;
import java.util.Map;

/**
 * Range-validated numeric setting knobs applied by exact key from {@code mc.bot.setting{...}}.
 * Extracted verbatim from {@link SettingsCommand#apply} for file-size hygiene; behaviour is
 * identical (same keys, ranges, {@code applied}/{@code rejected} bookkeeping). Runs after the
 * hand-written boolean/ranged setters and before the reflective fallback, exactly as before.
 */
final class SettingsNumericWrites {
    private SettingsNumericWrites() {}

    static void apply(Map<String, Object> params, List<String> applied, List<String> rejected) {
            for (Map.Entry<String, Object> e : params.entrySet()) {
                String k = e.getKey();
                Object v = e.getValue();
                if (!(v instanceof Number n)) continue;
                switch (k) {
                    case "walker.repathEveryTicks":
                        if (n.intValue() >= 20 && n.intValue() <= 10000) { BotConfig.walkerRepathEveryTicks = n.intValue(); applied.add(k); }
                        else rejected.add(k + " out of range [20,10000]");
                        break;
                    case "walker.totalTickBudget":
                        if (n.intValue() >= 200 && n.intValue() <= 36000) { BotConfig.walkerTotalTickBudget = n.intValue(); applied.add(k); }
                        else rejected.add(k + " out of range [200,36000]");
                        break;
                    case "walker.yawHysteresisDeg":
                        if (n.floatValue() >= 0 && n.floatValue() <= 30) { BotConfig.walkerYawHysteresisDeg = n.floatValue(); applied.add(k); }
                        else rejected.add(k + " out of range [0,30]");
                        break;
                    case "mine.searchVerticalRadius":
                        if (n.intValue() >= 1 && n.intValue() <= 32) { BotConfig.mineSearchVerticalRadius = n.intValue(); applied.add(k); }
                        else rejected.add(k + " out of range [1,32]");
                        break;
                    case "breakTimeoutTicks":
                        if (n.intValue() >= 20 && n.intValue() <= 2000) { BotConfig.breakTimeoutTicks = n.intValue(); applied.add(k); }
                        else rejected.add(k + " out of range [20,2000]");
                        break;
                    case "autoEatFoodThreshold":
                        if (n.intValue() >= 0 && n.intValue() <= 20) { BotConfig.autoEatFoodThreshold = n.intValue(); applied.add(k); }
                        else rejected.add(k + " out of range [0,20]");
                        break;
                    case "bunkerHpThreshold":
                        if (n.floatValue() >= 0f && n.floatValue() <= 20f) { BotConfig.bunkerHpThreshold = n.doubleValue(); applied.add(k); }
                        else rejected.add(k + " out of range [0,20]");
                        break;
                    case "bunkerTriggerRadius":
                        if (n.floatValue() >= 1f && n.floatValue() <= 16f) { BotConfig.bunkerTriggerRadius = n.doubleValue(); applied.add(k); }
                        else rejected.add(k + " out of range [1,16]");
                        break;
                    case "bunkerMinHostiles":
                        if (n.intValue() >= 1 && n.intValue() <= 10) { BotConfig.bunkerMinHostiles = n.intValue(); applied.add(k); }
                        else rejected.add(k + " out of range [1,10]");
                        break;
                    case "bunkerDepth":
                        if (n.intValue() >= 1 && n.intValue() <= 5) { BotConfig.bunkerDepth = n.intValue(); applied.add(k); }
                        else rejected.add(k + " out of range [1,5]");
                        break;
                    case "retreatHpThreshold":
                        if (n.floatValue() >= 0f && n.floatValue() <= 20f) { BotConfig.retreatHpThreshold = n.floatValue(); applied.add(k); }
                        else rejected.add(k + " out of range [0,20]");
                        break;
                    case "healHpThreshold":
                        if (n.floatValue() >= 0f && n.floatValue() <= 20f) { BotConfig.healHpThreshold = n.floatValue(); applied.add(k); }
                        else rejected.add(k + " out of range [0,20]");
                        break;
                    case "creeperKeepDistance":
                        if (n.doubleValue() >= 1 && n.doubleValue() <= 16) { BotConfig.creeperKeepDistance = n.doubleValue(); applied.add(k); }
                        else rejected.add(k + " out of range [1,16]");
                        break;
                    case "projectileDodgeRadius":
                        if (n.doubleValue() >= 1 && n.doubleValue() <= 32) { BotConfig.projectileDodgeRadius = n.doubleValue(); applied.add(k); }
                        else rejected.add(k + " out of range [1,32]");
                        break;
                    case "combatReach":
                        if (n.doubleValue() >= 1 && n.doubleValue() <= 6) { BotConfig.combatReach = n.doubleValue(); applied.add(k); }
                        else rejected.add(k + " out of range [1,6]");
                        break;
                    case "kiteDistance":
                        if (n.doubleValue() >= 3 && n.doubleValue() <= 32) { BotConfig.kiteDistance = n.doubleValue(); applied.add(k); }
                        else rejected.add(k + " out of range [3,32]");
                        break;
                    case "autoFightThreatThreshold":
                        if (n.doubleValue() >= 0 && n.doubleValue() <= 1) { BotConfig.autoFightThreatThreshold = n.doubleValue(); applied.add(k); }
                        else rejected.add(k + " out of range [0,1]");
                        break;
                    case "equipDurabilityThreshold":
                        if (n.doubleValue() >= 0 && n.doubleValue() <= 1) { BotConfig.equipDurabilityThreshold = n.doubleValue(); applied.add(k); }
                        else rejected.add(k + " out of range [0,1]");
                        break;
                    case "riskBias.scale":
                        if (n.intValue() >= 0 && n.intValue() <= 400) { BotConfig.riskBiasScale = n.intValue(); applied.add(k); }
                        else rejected.add(k + " out of range [0,400]");
                        break;
                    case "pathfinder.maxNodes":
                        if (n.intValue() >= 1000 && n.intValue() <= 1_000_000) { BotConfig.pathfinderMaxNodes = n.intValue(); applied.add(k); }
                        else rejected.add(k + " out of range [1000,1000000]");
                        break;
                    case "pathfinder.maxMs":
                        if (n.longValue() >= 100 && n.longValue() <= 30_000) { BotConfig.pathfinderMaxMs = n.longValue(); applied.add(k); }
                        else rejected.add(k + " out of range [100,30000]");
                        break;
                    case "pathfinder.sliceMs":
                        if (n.longValue() >= 1 && n.longValue() <= 50) { BotConfig.pathfinderSliceMs = n.longValue(); applied.add(k); }
                        else rejected.add(k + " out of range [1,50]");
                        break;
                    case "pathfinder.idleSliceMs":
                        if (n.longValue() >= 1 && n.longValue() <= 50) { BotConfig.pathfinderIdleSliceMs = n.longValue(); applied.add(k); }
                        else rejected.add(k + " out of range [1,50]");
                        break;
                    case "pathfinder.previewSliceMs":
                        if (n.longValue() >= 1 && n.longValue() <= 50) { BotConfig.pathfinderPreviewSliceMs = n.longValue(); applied.add(k); }
                        else rejected.add(k + " out of range [1,50]");
                        break;
                    case "sightRaysPerSearch":
                        if (n.intValue() >= 100 && n.intValue() <= 100_000) { BotConfig.sightRaysPerSearch = n.intValue(); applied.add(k); }
                        else rejected.add(k + " out of range [100,100000]");
                        break;
                    case "snapshotBoxMax":
                        if (n.intValue() >= 16 && n.intValue() <= 512) { BotConfig.snapshotBoxMax = n.intValue(); applied.add(k); }
                        else rejected.add(k + " out of range [16,512]");
                        break;
                    case "pathfinder.heuristicWeight":
                        if (n.doubleValue() >= 1.0 && n.doubleValue() <= 3.0) { BotConfig.pathfinderHeuristicWeight = n.doubleValue(); applied.add(k); }
                        else rejected.add(k + " out of range [1.0,3.0]");
                        break;
                    case "pathfinder.axisHeight":
                        if (n.intValue() >= -64 && n.intValue() <= 320) { BotConfig.axisHeight = n.intValue(); applied.add(k); }
                        else rejected.add(k + " out of range [-64,320]");
                        break;
                    case "smoothLookDegPerTick":
                        if (n.floatValue() >= 1f && n.floatValue() <= 180f) { BotConfig.smoothLookDegPerTick = n.floatValue(); applied.add(k); }
                        else rejected.add(k + " out of range [1,180]");
                        break;
                    case "autoBackfillRadius":
                        if (n.intValue() >= 1 && n.intValue() <= 16) { BotConfig.autoBackfillRadius = n.intValue(); applied.add(k); }
                        else rejected.add(k + " out of range [1,16]");
                        break;
                    case "maxWaterBucketFall":
                        if (n.intValue() >= 4 && n.intValue() <= 256) { BotConfig.maxWaterBucketFall = n.intValue(); applied.add(k); }
                        else rejected.add(k + " out of range [4,256]");
                        break;
                    case "hazardGridRadius":
                        if (n.intValue() >= 4 && n.intValue() <= 32) { BotConfig.hazardGridRadius = n.intValue(); applied.add(k); }
                        else rejected.add(k + " out of range [4,32]");
                        break;
                    case "hazardGridDecimateTicks":
                        if (n.intValue() >= 1 && n.intValue() <= 20) { BotConfig.hazardGridDecimateTicks = n.intValue(); applied.add(k); }
                        else rejected.add(k + " out of range [1,20]");
                        break;
                    case "deepWaterMax":
                        if (n.intValue() >= 1 && n.intValue() <= 64) { BotConfig.deepWaterMax = n.intValue(); applied.add(k); }
                        else rejected.add(k + " out of range [1,64]");
                        break;
                    case "swimBankClimbMaxHeight":
                        if (n.intValue() >= 0 && n.intValue() <= 64) { BotConfig.swimBankClimbMaxHeight = n.intValue(); applied.add(k); }
                        else rejected.add(k + " out of range [0,64]");
                        break;
                    case "sceneQueryMaxRadius":
                        if (n.intValue() >= 4 && n.intValue() <= 48) { BotConfig.sceneQueryMaxRadius = n.intValue(); applied.add(k); }
                        else rejected.add(k + " out of range [4,48]");
                        break;
                    case "pathDebugMaxNodes":
                        if (n.intValue() >= 100 && n.intValue() <= 200000) { BotConfig.pathDebugMaxNodes = n.intValue(); applied.add(k); }
                        else rejected.add(k + " out of range [100,200000]");
                        break;
                    case "pathDebugMaxSamples":
                        if (n.intValue() >= 100 && n.intValue() <= 200000) { BotConfig.pathDebugMaxSamples = n.intValue(); applied.add(k); }
                        else rejected.add(k + " out of range [100,200000]");
                        break;
                }
            }
    }
}
