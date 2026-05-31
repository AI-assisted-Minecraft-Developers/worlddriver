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
            if (params.get("autoSwim") instanceof Boolean as) {
                BotConfig.autoSwim = as;
                applied.add("autoSwim");
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
            if (params.get("avoidMobs") instanceof Boolean avm) {
                BotConfig.avoidMobs = avm;
                applied.add("avoidMobs");
            }
            if (params.get("walkerDebug") instanceof Boolean wd) {
                BotConfig.walkerDebug = wd;
                applied.add("walkerDebug");
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
            if (params.get("pathfinder.mobAvoidPenalty") instanceof Number mp) {
                double v = mp.doubleValue();
                if (v < 0 || v > 1000) {
                    rejected.add("pathfinder.mobAvoidPenalty: out of range [0,1000]");
                } else {
                    BotConfig.mobAvoidPenalty = v;
                    applied.add("pathfinder.mobAvoidPenalty");
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
                Set<String> nextSet = new java.util.LinkedHashSet<>();
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
        snap.put("autoSwim", BotConfig.autoSwim);
        snap.put("autoTool", BotConfig.autoTool);
        snap.put("autoBackfill", BotConfig.autoBackfill);
        snap.put("autoBackfillBlock", BotConfig.autoBackfillBlock);
        snap.put("autoBackfillRadius", BotConfig.autoBackfillRadius);
        snap.put("allowParkour4", BotConfig.allowParkour4);
        snap.put("allowBreak", BotConfig.allowBreak);
        snap.put("allowPlace", BotConfig.allowPlace);
        snap.put("allowParkourPlace", BotConfig.allowParkourPlace);
        snap.put("allowWaterBucketFall", BotConfig.allowWaterBucketFall);
        snap.put("maxWaterBucketFall", BotConfig.maxWaterBucketFall);
        snap.put("waterBucketScoop", BotConfig.waterBucketScoop);
        snap.put("avoidDanger", BotConfig.avoidDanger);
        snap.put("pathfinder.dangerPenalty", BotConfig.dangerPenaltyPerCell);
        snap.put("pathfinder.lavaDangerPenalty", BotConfig.lavaDangerPenalty);
        snap.put("pathfinder.contactDangerPenalty", BotConfig.contactDangerPenalty);
        snap.put("pathfinder.ledgeDangerPenalty", BotConfig.ledgeDangerPenalty);
        snap.put("pathfinder.ledgeDangerMinDrop", BotConfig.ledgeDangerMinDrop);
        snap.put("avoidMobs", BotConfig.avoidMobs);
        snap.put("pathfinder.mobAvoidRadius", BotConfig.mobAvoidRadius);
        snap.put("pathfinder.mobAvoidPenalty", BotConfig.mobAvoidPenalty);
        snap.put("walkerDebug", BotConfig.walkerDebug);
        snap.put("elytraDebug", BotConfig.elytraDebug);
        snap.put("smoothLook", BotConfig.smoothLook);
        snap.put("smoothLookDegPerTick", BotConfig.smoothLookDegPerTick);
        snap.put("pathfinder.maxNodes", BotConfig.pathfinderMaxNodes);
        snap.put("pathfinder.maxMs", BotConfig.pathfinderMaxMs);
        snap.put("pathfinder.sliceMs", BotConfig.pathfinderSliceMs);
        snap.put("pathfinder.axisHeight", BotConfig.axisHeight);
        snap.put("blocksToAvoid", new ArrayList<>(BotConfig.extraHazardBlocks));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("settings", snap);
        if (!applied.isEmpty())  out.put("applied", applied);
        if (!rejected.isEmpty()) out.put("rejected", rejected);
        return out;
    }
}
