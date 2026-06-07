package net.magicterra.agent.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.magicterra.agent.bot.util.BotInteract.*;
import static net.magicterra.agent.bot.util.BotUtil.*;
import static net.magicterra.agent.AgentDriverCommon.LOG;
import net.minecraft.resources.ResourceLocation;
import java.util.Set;
import java.util.LinkedHashSet;

/**
 * Applies {@code mc.bot.setting{...}} param mutations to {@link BotConfig} and
 * per-bot state, then returns the resulting settings snapshot. Extracted from
 * BotApiImpl; the impl's {@code setting(params)} delegates here.
 */
public final class SettingsCommand {

    private SettingsCommand() {}

    public static Map<String, Object> apply(BotApiImpl bot, Map<String, Object> params) {
        // Write path: any params keys that match a known setting + are in range
        // are applied to BotConfig immediately. Unknown keys ignored with a
        // diagnostic; out-of-range keys rejected.
        List<String> applied = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        if (params != null) {
            // Boolean toggle: paused absorbs the former mc.bot.pause / .resume —
            // {paused:true} stops all processes from advancing; {paused:false}
            // resumes the next tick. Released keys avoid a stuck attack.
            Object pv = params.get("paused");
            if (pv instanceof Boolean pb) {
                if (pb && !bot.paused) {
                    // Releasing keys requires the client thread; mirror pause().
                    onClient(() -> { releaseKeys(); return Map.of(); });
                }
                bot.paused = pb;
                applied.add("paused");
            }
            // Baritone-style survival toggles. Off→on flips just write the
            // flag; the live state is enforced on the next clientTick so we
            // don't need to hop to the client thread here.
            if (params.get("autoEat") instanceof Boolean ae) {
                BotConfig.autoEat = ae;
                applied.add("autoEat");
            }
            if (params.get("autoRespawn") instanceof Boolean ar) {
                BotConfig.autoRespawn = ar;
                applied.add("autoRespawn");
            }
            if (params.get("autoRetreat") instanceof Boolean art) {
                BotConfig.autoRetreat = art;
                applied.add("autoRetreat");
            }
            if (params.get("autoBunker") instanceof Boolean abk) {
                BotConfig.autoBunker = abk;
                applied.add("autoBunker");
            }
            if (params.get("autoTotem") instanceof Boolean ato) {
                BotConfig.autoTotem = ato;
                applied.add("autoTotem");
            }
            if (params.get("autoShield") instanceof Boolean ash) {
                BotConfig.autoShield = ash;
                applied.add("autoShield");
            }
            if (params.get("autoHeal") instanceof Boolean ah) {
                BotConfig.autoHeal = ah;
                applied.add("autoHeal");
            }
            if (params.get("autoDodge") instanceof Boolean ado) {
                BotConfig.autoDodge = ado;
                applied.add("autoDodge");
            }
            if (params.get("autoFight") instanceof Boolean af) {
                BotConfig.autoFight = af;
                applied.add("autoFight");
            }
            if (params.get("combatCrit") instanceof Boolean cc) {
                BotConfig.combatCrit = cc;
                applied.add("combatCrit");
            }
            if (params.get("autoEquip") instanceof Boolean ae2) {
                BotConfig.autoEquip = ae2;
                applied.add("autoEquip");
            }
            if (params.get("autoSwim") instanceof Boolean as) {
                BotConfig.autoSwim = as;
                applied.add("autoSwim");
            }
            if (params.get("antiSuffocate") instanceof Boolean asf) {
                BotConfig.antiSuffocate = asf;
                applied.add("antiSuffocate");
            }
            if (params.get("allowParkour4") instanceof Boolean ap4) {
                BotConfig.allowParkour4 = ap4;
                applied.add("allowParkour4");
            }
            if (params.get("autoTool") instanceof Boolean at) {
                BotConfig.autoTool = at;
                applied.add("autoTool");
            }
            if (params.get("allowBreak") instanceof Boolean ab) {
                BotConfig.allowBreak = ab;
                applied.add("allowBreak");
            }
            if (params.get("allowSwimEscapeBreak") instanceof Boolean ase) {
                BotConfig.allowSwimEscapeBreak = ase;
                applied.add("allowSwimEscapeBreak");
            }
            if (params.get("allowSwimEscapePlace") instanceof Boolean asp) {
                BotConfig.allowSwimEscapePlace = asp;
                applied.add("allowSwimEscapePlace");
            }
            if (params.get("allowPlace") instanceof Boolean apl) {
                BotConfig.allowPlace = apl;
                applied.add("allowPlace");
            }
            if (params.get("allowParkourPlace") instanceof Boolean app) {
                BotConfig.allowParkourPlace = app;
                applied.add("allowParkourPlace");
            }
            if (params.get("allowWaterBucketFall") instanceof Boolean awb) {
                BotConfig.allowWaterBucketFall = awb;
                applied.add("allowWaterBucketFall");
            }
            if (params.get("waterBucketScoop") instanceof Boolean wbs) {
                BotConfig.waterBucketScoop = wbs;
                applied.add("waterBucketScoop");
            }
            if (params.get("avoidDanger") instanceof Boolean avd) {
                BotConfig.avoidDanger = avd;
                applied.add("avoidDanger");
            }
            if (params.get("lethalEdgeBrake") instanceof Boolean leb) {
                BotConfig.lethalEdgeBrake = leb;
                applied.add("lethalEdgeBrake");
            }
            if (params.get("autoSecureAtDusk") instanceof Boolean asad) {
                BotConfig.autoSecureAtDusk = asad;
                applied.add("autoSecureAtDusk");
            }
            if (params.get("pathfinder.dangerPenalty") instanceof Number dp) {
                double v = dp.doubleValue();
                if (v < 0 || v > 1000) {
                    rejected.add("pathfinder.dangerPenalty: out of range [0,1000]");
                } else {
                    BotConfig.dangerPenaltyPerCell = v;
                    applied.add("pathfinder.dangerPenalty");
                }
            }
            if (params.get("pathfinder.lavaDangerPenalty") instanceof Number lp) {
                double v = lp.doubleValue();
                if (v < 0 || v > 5000) {
                    rejected.add("pathfinder.lavaDangerPenalty: out of range [0,5000]");
                } else {
                    BotConfig.lavaDangerPenalty = v;
                    applied.add("pathfinder.lavaDangerPenalty");
                }
            }
            if (params.get("pathfinder.contactDangerPenalty") instanceof Number cp) {
                double v = cp.doubleValue();
                if (v < 0 || v > 1000) {
                    rejected.add("pathfinder.contactDangerPenalty: out of range [0,1000]");
                } else {
                    BotConfig.contactDangerPenalty = v;
                    applied.add("pathfinder.contactDangerPenalty");
                }
            }
            if (params.get("pathfinder.ledgeDangerPenalty") instanceof Number lgp) {
                double v = lgp.doubleValue();
                if (v < 0 || v > 1000) {
                    rejected.add("pathfinder.ledgeDangerPenalty: out of range [0,1000]");
                } else {
                    BotConfig.ledgeDangerPenalty = v;
                    applied.add("pathfinder.ledgeDangerPenalty");
                }
            }
            if (params.get("pathfinder.ledgeDangerMinDrop") instanceof Number lmd) {
                int v = lmd.intValue();
                if (v < 1 || v > 64) {
                    rejected.add("pathfinder.ledgeDangerMinDrop: out of range [1,64]");
                } else {
                    BotConfig.ledgeDangerMinDrop = v;
                    applied.add("pathfinder.ledgeDangerMinDrop");
                }
            }
            if (params.get("pathfinder.waterDangerPenalty") instanceof Number wdp) {
                double v = wdp.doubleValue();
                if (v < 0 || v > 1000) {
                    rejected.add("pathfinder.waterDangerPenalty: out of range [0,1000]");
                } else {
                    BotConfig.waterDangerPenalty = v;
                    applied.add("pathfinder.waterDangerPenalty");
                }
            }
            if (params.get("avoidMobs") instanceof Boolean avm) {
                BotConfig.avoidMobs = avm;
                applied.add("avoidMobs");
            }
            if (params.get("walkerDebug") instanceof Boolean wd) {
                BotConfig.walkerDebug = wd;
                applied.add("walkerDebug");
            }
            if (params.get("pathDebug") instanceof Boolean pd) {
                BotConfig.pathDebug = pd;
                applied.add("pathDebug");
            }
            if (params.get("pathfinderCacheEnabled") instanceof Boolean pce) {
                BotConfig.pathfinderCacheEnabled = pce;
                applied.add("pathfinderCacheEnabled");
            }
            if (params.get("collisionAwarePathing") instanceof Boolean cap) {
                BotConfig.collisionAwarePathing = cap;
                applied.add("collisionAwarePathing");
            }
            if (params.get("pathfinderGoalField") instanceof Boolean gf) {
                BotConfig.pathfinderGoalField = gf;
                applied.add("pathfinderGoalField");
            }
            if (params.get("goalFieldCellSize") instanceof Number gfc) {
                BotConfig.goalFieldCellSize = Math.max(1, gfc.intValue());
                applied.add("goalFieldCellSize");
            }
            if (params.get("goalFieldRadius") instanceof Number gfr) {
                BotConfig.goalFieldRadius = Math.max(8, gfr.intValue());
                applied.add("goalFieldRadius");
            }
            if (params.get("goalFieldVerticalRadius") instanceof Number gfvr) {
                BotConfig.goalFieldVerticalRadius = Math.max(4, gfvr.intValue());
                applied.add("goalFieldVerticalRadius");
            }
            if (params.get("pathfinderDepthPenalty") instanceof Number dpp) {
                BotConfig.pathfinderDepthPenalty = Math.max(0, dpp.doubleValue());
                applied.add("pathfinderDepthPenalty");
            }
            if (params.get("pathfinderDepthSlack") instanceof Number dps) {
                BotConfig.pathfinderDepthSlack = Math.max(0, dps.intValue());
                applied.add("pathfinderDepthSlack");
            }
            if (params.get("pathfinderDescendCost") instanceof Number pdc) {
                BotConfig.pathfinderDescendCost = Math.max(0, pdc.doubleValue());
                applied.add("pathfinderDescendCost");
            }
            if (params.get("pathfinderFrontierCommit") instanceof Boolean fc) {
                BotConfig.pathfinderFrontierCommit = fc;
                applied.add("pathfinderFrontierCommit");
            }
            if (params.get("pathChartAutoDump") instanceof Boolean pcad) {
                BotConfig.pathChartAutoDump = pcad;
                applied.add("pathChartAutoDump");
            }
            if (params.get("elytraDebug") instanceof Boolean ed) {
                BotConfig.elytraDebug = ed;
                applied.add("elytraDebug");
            }
            if (params.get("debugFly") instanceof Boolean dfly) {
                // Test affordance: toggle creative flight on the CLIENT thread
                // (a real Java Runnable — the Rhino sandbox blocks this from
                // eval). Lets the flight-handling path be exercised live.
                Minecraft mcf = Minecraft.getInstance();
                mcf.execute(() -> {
                    LocalPlayer pf = mcf.player;
                    if (pf == null) {
                        LOG.info("[debugFly] player null");
                        return;
                    }
                    pf.getAbilities().mayfly = true;
                    pf.getAbilities().flying = dfly;
                    pf.onUpdateAbilities();
                    LOG.info(
                            "[debugFly] requested={} readback mayfly={} flying={} onGround={} y={}",
                            dfly, pf.getAbilities().mayfly, pf.getAbilities().flying, pf.onGround(), pf.getY());
                });
                applied.add("debugFly");
            }
            if (params.get("pathfinder.mobAvoidRadius") instanceof Number mr) {
                double v = mr.doubleValue();
                if (v < 0 || v > 64) {
                    rejected.add("pathfinder.mobAvoidRadius: out of range [0,64]");
                } else {
                    BotConfig.mobAvoidRadius = v;
                    applied.add("pathfinder.mobAvoidRadius");
                }
            }
            if (params.get("pathfinder.avoidZonePenalty") instanceof Number azp) {
                double v = azp.doubleValue();
                if (v < 0 || v > 5000) {
                    rejected.add("pathfinder.avoidZonePenalty: out of range [0,5000]");
                } else {
                    BotConfig.avoidZonePenalty = v;
                    applied.add("pathfinder.avoidZonePenalty");
                }
            }
            if (params.get("pathfinder.mobAvoidPenalty") instanceof Number mp) {
                double v = mp.doubleValue();
                if (v < 0 || v > 1000) {
                    rejected.add("pathfinder.mobAvoidPenalty: out of range [0,1000]");
                } else {
                    BotConfig.mobAvoidPenalty = v;
                    applied.add("pathfinder.mobAvoidPenalty");
                }
            }
            if (params.get("rangedAvoidRadius") instanceof Number rar) {
                int v = rar.intValue();
                if (v < 4 || v > 48) {
                    rejected.add("rangedAvoidRadius: out of range [4,48]");
                } else {
                    BotConfig.rangedAvoidRadius = v;
                    applied.add("rangedAvoidRadius");
                }
            }
            if (params.get("fleeDangerBoost") instanceof Number fdb) {
                double v = fdb.doubleValue();
                if (v < 1 || v > 20) {
                    rejected.add("fleeDangerBoost: out of range [1,20]");
                } else {
                    BotConfig.fleeDangerBoost = v;
                    applied.add("fleeDangerBoost");
                }
            }
            if (params.get("smoothLook") instanceof Boolean sl) {
                BotConfig.smoothLook = sl;
                applied.add("smoothLook");
            }
            if (params.get("autoBackfill") instanceof Boolean abf) {
                BotConfig.autoBackfill = abf;
                if (!abf) bot.backfillTracker.clear();
                applied.add("autoBackfill");
            }
            if (params.get("autoBackfillBlock") instanceof String abb && !abb.isBlank()) {
                try {
                    var rl = ResourceLocation.parse(abb);
                    if (!BuiltInRegistries.BLOCK.containsKey(rl)) {
                        rejected.add("autoBackfillBlock: unknown block id " + abb);
                    } else {
                        BotConfig.autoBackfillBlock = rl.toString();
                        applied.add("autoBackfillBlock");
                    }
                } catch (Exception ex) {
                    rejected.add("autoBackfillBlock: invalid id " + abb);
                }
            }
            // blocksToAvoid is a list of block ids; each must parse as a
            // ResourceLocation (e.g. "minecraft:powder_snow"). Whole-list
            // replacement — pass [] to clear. Invalid ids cause the entire
            // write to be rejected so the caller knows nothing was applied.
            if (params.get("blocksToAvoid") instanceof List<?> bl) {
                List<String> bad = new ArrayList<>();
                Set<String> nextSet = new LinkedHashSet<>();
                for (Object o : bl) {
                    if (!(o instanceof String s) || s.isBlank()) { bad.add(String.valueOf(o)); continue; }
                    try {
                        ResourceLocation rl = ResourceLocation.parse(s);
                        if (!BuiltInRegistries.BLOCK.containsKey(rl)) { bad.add(s); continue; }
                        nextSet.add(rl.toString());
                    } catch (Exception e) { bad.add(s); }
                }
                if (bad.isEmpty()) {
                    BotConfig.extraHazardBlocks = Set.copyOf(nextSet);
                    applied.add("blocksToAvoid");
                } else {
                    rejected.add("blocksToAvoid: invalid ids " + bad);
                }
            }
            // mutedEvents: event types suppressed from the live PUSH channel (the event
            // is still recorded + retrievable via mc.wait.event / replay). Free-form
            // strings — any event type. Whole-list replace; pass [] to un-mute everything.
            if (params.get("mutedEvents") instanceof List<?> ml) {
                Set<String> nextSet = new LinkedHashSet<>();
                for (Object o : ml) {
                    if (o instanceof String s && !s.isBlank()) nextSet.add(s);
                }
                BotConfig.mutedEvents = Set.copyOf(nextSet);
                applied.add("mutedEvents");
            }
            // avoidPoints: agent-marked danger zones to route around. List of points,
            // each {x,y,z,radius?} (radius default 8). Whole-list replacement — pass []
            // to clear. e.g. [{"x":50,"y":58,"z":350,"radius":10}].
            if (params.get("avoidPoints") instanceof List<?> ap) {
                List<double[]> zones = new ArrayList<>();
                boolean ok = true;
                for (Object o : ap) {
                    if (!(o instanceof Map<?, ?> pm)) { ok = false; break; }
                    Object x = pm.get("x"), y = pm.get("y"), z = pm.get("z"), r = pm.get("radius");
                    if (!(x instanceof Number nx) || !(y instanceof Number ny) || !(z instanceof Number nz)) { ok = false; break; }
                    double rad = (r instanceof Number nr) ? nr.doubleValue() : 8.0;
                    if (rad < 1) rad = 1; if (rad > 64) rad = 64;
                    zones.add(new double[]{ nx.doubleValue(), ny.doubleValue(), nz.doubleValue(), rad });
                }
                if (ok) {
                    BotConfig.avoidZones = zones.toArray(new double[0][]);
                    applied.add("avoidPoints");
                } else {
                    rejected.add("avoidPoints: each item must be {x,y,z,radius?}");
                }
            }
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
        snap.put("pathDebug", BotConfig.pathDebug);
        snap.put("pathDebugMaxNodes", BotConfig.pathDebugMaxNodes);
        snap.put("pathDebugMaxSamples", BotConfig.pathDebugMaxSamples);
        snap.put("pathChartAutoDump", BotConfig.pathChartAutoDump);
        snap.put("elytraDebug", BotConfig.elytraDebug);
        snap.put("smoothLook", BotConfig.smoothLook);
        snap.put("smoothLookDegPerTick", BotConfig.smoothLookDegPerTick);
        snap.put("pathfinder.maxNodes", BotConfig.pathfinderMaxNodes);
        snap.put("pathfinder.maxMs", BotConfig.pathfinderMaxMs);
        snap.put("pathfinder.sliceMs", BotConfig.pathfinderSliceMs);
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
        snap.put("pathfinderFrontierCommit", BotConfig.pathfinderFrontierCommit);
        snap.put("pathfinder.axisHeight", BotConfig.axisHeight);
        snap.put("blocksToAvoid", new ArrayList<>(BotConfig.extraHazardBlocks));
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
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("settings", snap);
        if (!applied.isEmpty()) {
            out.put("applied", applied);
            BotConfig.save();   // persist so these settings survive a client restart
        }
        if (!rejected.isEmpty()) out.put("rejected", rejected);
        return out;
    }
}
