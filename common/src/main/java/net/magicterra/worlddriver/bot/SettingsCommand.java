package net.magicterra.worlddriver.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.magicterra.worlddriver.bot.util.BotInteract.*;
import static net.magicterra.worlddriver.bot.util.BotUtil.*;
import static net.magicterra.worlddriver.WorldDriverCommon.LOG;
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
        // Write path: any params keys that match a known setting + are in range are applied to
        // BotConfig immediately. #280 fix: an UNKNOWN key (one not in the single-source
        // SettingsRegistry) is REJECTED, not silently ignored. All-or-nothing — a call carrying
        // ANY unknown key throws BEFORE mutating anything, so nothing is half-applied. This is the
        // apply-side guard that backstops the closed mc.bot.setting schema (SchemaValidator already
        // rejects unknown keys at route() for every transport); direct callers hit this instead.
        // Out-of-range keys are still soft-rejected into rejected[] with ok:true.
        List<String> applied = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        if (params != null && !params.isEmpty()) {
            List<String> unknown = new ArrayList<>();
            for (String k : params.keySet()) {
                if (!SettingsRegistry.isKnown(k)) unknown.add(k);
            }
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException(
                        "mc.bot.setting: unknown key(s) " + unknown
                        + "; known keys: " + SettingsRegistry.knownKeys().size()
                        + ", see mc.bot.setting schema (all-or-nothing: nothing was applied)");
            }
        }
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
            // The ~100 plain boolean toggles that used to be spelled out here (autoEat,
            // autoRespawn, allowBreak, every walker*/pathfinder* flag, …) are gone. Each was
            // literally `if (params.get("K") instanceof Boolean v) { BotConfig.K = v;
            // applied.add("K"); }` — byte-equivalent to the generic reflective write at the
            // bottom of this method, which has handled them all along. They were kept, per
            // that block's own comment, "for their side effects / legacy key aliases"; none
            // of the deleted hundred had either. Off→on flips just write the flag; the live
            // state is enforced on the next clientTick, so no client-thread hop is needed —
            // which is exactly why they were mechanizable. What REMAINS below is only what
            // the reflective path genuinely cannot do: range clamps, aliased keys whose wire
            // name differs from the field name, list/registry validation, and the three
            // side-effecting keys (paused, debugFly, autoBackfill).
            if (params.get("lowHealthCareful") instanceof Number lhc) {
                BotConfig.lowHealthCareful = lhc.doubleValue();
                applied.add("lowHealthCareful");
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
            // Pathfinder budget setters (sliceMs / idleSliceMs / maxMs / maxNodes) are NOT
            // handled here. They used to be, WITHOUT a range check, while SettingsNumericWrites
            // also handles all four WITH one — and its loop has no `applied.contains(k)` guard,
            // so both ran. An out-of-range value was therefore committed by this block, counted
            // in `applied`, and only THEN range-rejected: the response carried the key in BOTH
            // `applied` and `rejected` with ok:true while the illegal value stayed live. An
            // in-range value was merely double-counted in `applied`. SettingsNumericWrites is
            // the single write path now; its ranges are the ones methods.md documents
            // (maxNodes[1000,1000000] maxMs[100,30000] sliceMs[1,50] idleSliceMs[1,50]).
            // Scenes that want the determinism knob (a huge sliceMs so each A* search runs
            // atomically) set the BotConfig field directly in Java and are unaffected.
            if (params.get("pathfinderDiagAscendPenalty") instanceof Number dap) {
                BotConfig.pathfinderDiagAscendPenalty = dap.doubleValue();
                applied.add("pathfinderDiagAscendPenalty");
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
            if (params.get("pathfinderWaterCellCost") instanceof Number pwc) {
                BotConfig.pathfinderWaterCellCost = Math.max(0, pwc.doubleValue());
                applied.add("pathfinderWaterCellCost");
            }
            if (params.get("pathfinderWaterClimbOutCost") instanceof Number pwco) {
                BotConfig.pathfinderWaterClimbOutCost = Math.max(0, pwco.doubleValue());
                applied.add("pathfinderWaterClimbOutCost");
            }
            if (params.get("pathfinderSubmergedWaterCost") instanceof Number pswc) {
                BotConfig.pathfinderSubmergedWaterCost = Math.max(0, pswc.doubleValue());
                applied.add("pathfinderSubmergedWaterCost");
            }
            if (params.get("pathfinderBridgeCost") instanceof Number pbc) {
                BotConfig.pathfinderBridgeCost = Math.max(0, pbc.doubleValue());
                applied.add("pathfinderBridgeCost");
            }
            if (params.get("pathfinderThinObstacleHeight") instanceof Number ptoh) {
                BotConfig.pathfinderThinObstacleHeight = Math.max(0, ptoh.doubleValue());
                applied.add("pathfinderThinObstacleHeight");
            }
            if (params.get("pathfinderHorizonBlocks") instanceof Number phb) {
                int v = phb.intValue();
                if (v >= 0 && v <= 512) { BotConfig.pathfinderHorizonBlocks = v; applied.add("pathfinderHorizonBlocks"); }
                else rejected.add("pathfinderHorizonBlocks out of range [0,512]");
            }
            if (params.get("pathfinderMaxDryFall") instanceof Number pmdf) {
                int v = pmdf.intValue();
                if (v >= 3 && v <= 5) { BotConfig.pathfinderMaxDryFall = v; applied.add("pathfinderMaxDryFall"); }
                else rejected.add("pathfinderMaxDryFall out of range [3,5]");
            }
            if (params.get("pathfinderSoftCommitNodes") instanceof Number pscn) {
                int v = pscn.intValue();
                if (v >= 0 && v <= 1_000_000) { BotConfig.pathfinderSoftCommitNodes = v; applied.add("pathfinderSoftCommitNodes"); }
                else rejected.add("pathfinderSoftCommitNodes out of range [0,1000000]");
            }
            if (params.get("pathfinderQuickNodes") instanceof Number pqn) {
                int v = pqn.intValue();
                if (v >= 0 && v <= 10_000) { BotConfig.pathfinderQuickNodes = v; applied.add("pathfinderQuickNodes"); }
                else rejected.add("pathfinderQuickNodes out of range [0,10000]");
            }
            if (params.get("debugFly") instanceof Boolean dfly) {
                // Test affordance: toggle creative flight on the CLIENT thread, exercising the
                // flight-handling path live. ⚠️ Its old reason「the Rhino sandbox blocks this from
                // eval」was false and is gone — see ScriptTools' javadoc; the affordance stays.
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
            // buildBlockWhitelist is a list of block ids the bot may PLACE as build/support
            // blocks (pillar/bridge/parkour footing). Same ResourceLocation validation as
            // blocksToAvoid; whole-list replacement, pass [] to clear (→ falls back to the
            // full-cube heuristic). Invalid ids reject the entire write.
            if (params.get("buildBlockWhitelist") instanceof List<?> wl) {
                List<String> bad = new ArrayList<>();
                Set<String> nextSet = new LinkedHashSet<>();
                for (Object o : wl) {
                    if (!(o instanceof String s) || s.isBlank()) { bad.add(String.valueOf(o)); continue; }
                    try {
                        ResourceLocation rl = ResourceLocation.parse(s);
                        if (!BuiltInRegistries.BLOCK.containsKey(rl)) { bad.add(s); continue; }
                        nextSet.add(rl.toString());
                    } catch (Exception e) { bad.add(s); }
                }
                if (bad.isEmpty()) {
                    BotConfig.buildBlockWhitelist = Set.copyOf(nextSet);
                    applied.add("buildBlockWhitelist");
                } else {
                    rejected.add("buildBlockWhitelist: invalid ids " + bad);
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
            SettingsNumericWrites.apply(params, applied, rejected);
        }
        // THE write path for every key the specialised setters above did not consume: any
        // `public static volatile` PRIMITIVE field on BotConfig is settable by its exact
        // field name. A new flag needs ONLY its BotConfig declaration — no branch here, no
        // schema edit (BotTools builds the closed schema from SettingsRegistry, which reads
        // the same fields). volatile is required, as the marker that a field is designed for
        // runtime flips. Keys that DO need a specialised setter (clamp, alias, list, side
        // effect) keep it above and win by running first and adding the key to `applied`.
        //
        // params may be null (a direct in-JVM caller with no arguments); the loop below used
        // to dereference it unguarded while the inert report further down was guarded, so a
        // null-params call NPE'd here.
        Set<String> rejKeys = rejectedKeys(rejected);
        for (Map.Entry<String, Object> e : (params == null
                ? java.util.Collections.<String, Object>emptyMap()
                : params).entrySet()) {
            String k = e.getKey();
            if (applied.contains(k) || rejKeys.contains(k)) continue;
            try {
                java.lang.reflect.Field f = BotConfig.class.getField(k);
                int mods = f.getModifiers();
                if (!java.lang.reflect.Modifier.isStatic(mods) || !java.lang.reflect.Modifier.isVolatile(mods)) continue;
                Class<?> t = f.getType();
                Object v = e.getValue();
                if (t == boolean.class && v instanceof Boolean b) { f.setBoolean(null, b); applied.add(k); }
                else if (t == int.class && v instanceof Number n) { f.setInt(null, n.intValue()); applied.add(k); }
                else if (t == double.class && v instanceof Number n) { f.setDouble(null, n.doubleValue()); applied.add(k); }
                else if (t == float.class && v instanceof Number n) { f.setFloat(null, n.floatValue()); applied.add(k); }
                else if (t == long.class && v instanceof Number n) { f.setLong(null, n.longValue()); applied.add(k); }
            } catch (NoSuchFieldException | IllegalAccessException ignore) {
                // unknown key → ignored, same contract as before
            }
        }
        // Runtime apply-keys ⊆ registry self-check: a param key that is KNOWN to the registry yet
        // was matched by NO branch above (not applied, not rejected) is INERT — a registry key with
        // no live write path (e.g. a hand-written setter that expects a different value type than
        // the schema advertised, or a reflected key whose reflective write silently no-op'd). It is
        // NOT an unknown key (those already threw), so we surface it honestly rather than drop it.
        // In a correct build this list is empty; it fires only on real drift and is logged loudly.
        List<String> inert = new ArrayList<>();
        if (params != null) {
            Set<String> rejKeys2 = rejectedKeys(rejected);
            for (String k : params.keySet()) {
                if (applied.contains(k)) continue;
                if (rejKeys2.contains(k)) continue;
                inert.add(k);   // known (unknown keys threw earlier) but no branch consumed it
            }
        }
        if (!inert.isEmpty()) {
            LOG.warn("[mc.bot.setting] inert key(s) {} — known to SettingsRegistry but matched no "
                    + "apply branch (drift: a registry key lost its write path — or a direct in-JVM caller passed a wrong-typed value, which bypasses the route-layer validator)", inert);
        }
        Map<String, Object> snap = SettingsSnapshot.build(bot);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("settings", snap);
        if (!applied.isEmpty()) {
            out.put("applied", applied);
            BotConfig.save();   // persist so these settings survive a client restart
        }
        if (!rejected.isEmpty()) out.put("rejected", rejected);
        if (!inert.isEmpty()) out.put("inert", inert);
        return out;
    }

    /**
     * The KEY each rejection message is about — the two "has this key already been dealt
     * with?" guards must ask about keys, not about message text.
     *
     * <p>Both guards used to be {@code rejected.stream().anyMatch(r -> r.startsWith(k))},
     * which conflates a key with every key that has it as a PREFIX. Nine such prefix pairs
     * exist on the key surface — the nine are named, the surface's size is not, because that
     * number grows with every added flag (autoBackfill/autoBackfillBlock, autoEat/autoEatFoodThreshold,
     * cameraSlew, duskUrgent, mouseYield, pathDebug, riskBias, smoothLook, autoFight). The
     * live case was {@code {riskBias:true, "riskBias.scale":500}}: the out-of-range sibling
     * produced {@code "riskBias.scale out of range [0,400]"}, whose prefix matched
     * {@code riskBias} — so the perfectly valid boolean was skipped by the reflective write
     * AND skipped by the inert report, leaving the caller an {@code ok:true} with no signal
     * whatsoever that its flag never landed.
     *
     * <p>Rejection messages come in two shapes, {@code "key msg"} and {@code "key: msg"}, so
     * take the first whitespace-delimited token and drop a trailing colon. Deriving the set
     * here keeps every one of the ~30 {@code rejected.add(...)} sites (including those in
     * {@link SettingsNumericWrites}) untouched.
     */
    private static Set<String> rejectedKeys(List<String> rejected) {
        Set<String> keys = new LinkedHashSet<>();
        for (String r : rejected) {
            if (r == null || r.isEmpty()) continue;
            int sp = r.indexOf(' ');
            String k = (sp < 0) ? r : r.substring(0, sp);
            if (k.endsWith(":")) k = k.substring(0, k.length() - 1);
            if (!k.isEmpty()) keys.add(k);
        }
        return keys;
    }
}
