package net.magicterra.worlddriver.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;

import java.lang.reflect.Field;
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

    /**
     * Apply the param map to {@link BotConfig} / per-bot state and return the resulting snapshot.
     *
     * <p><b>Ranges come from {@link SettingsDocs}, and only from there.</b> A row that opens with
     * {@code [lo,hi]} is shipped to clients as the key's schema description, and {@link #write}
     * rejects any number outside it before a single branch runs. A range written anywhere else is a
     * second copy free to disagree with the one the client reads, so none is: a key with a row is
     * never clamped, and a value outside the row is never reported {@code applied}.
     */
    public static Map<String, Object> apply(BotApiImpl bot, Map<String, Object> params) {
        Outcome o = write(bot, params);
        Map<String, Object> snap = SettingsSnapshot.build(bot);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("settings", snap);
        if (!o.applied().isEmpty()) {
            out.put("applied", o.applied());
            BotConfig.save();   // persist so these settings survive a client restart
        }
        if (!o.rejected().isEmpty()) out.put("rejected", o.rejected());
        if (!o.inert().isEmpty()) out.put("inert", o.inert());
        return out;
    }

    /** What one write did to each key it was given. */
    record Outcome(List<String> applied, List<String> rejected, List<String> inert) {}

    /** The write half of {@link #apply}, without the snapshot: {@code bot} is read only for
     *  {@code paused} and {@code autoBackfill}. */
    static Outcome write(BotApiImpl bot, Map<String, Object> params) {
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
        Map<String, Object> accepted = params == null ? Map.of() : inRange(params, rejected);
        if (params != null) {
            // Boolean toggle: paused absorbs the former mc.bot.pause / .resume —
            // {paused:true} stops all processes from advancing; {paused:false}
            // resumes the next tick. Released keys avoid a stuck attack.
            Object pv = accepted.get("paused");
            if (pv instanceof Boolean pb) {
                if (pb && !bot.paused) {
                    // Releasing keys requires the client thread; mirror pause().
                    onClient(() -> { releaseKeys(); return Map.of(); });
                }
                bot.paused = pb;
                applied.add("paused");
            }
            // Plain toggles and numbers, aliased or not, go through the reflective write at the
            // bottom, after inRange() has held them to their documented range. What REMAINS here
            // is what that write cannot do: a floor with no documented range, list/registry
            // validation, and the three side-effecting keys (paused, debugFly, autoBackfill).
            if (accepted.get("pathfinderWaterCellCost") instanceof Number pwc) {
                BotConfig.pathfinderWaterCellCost = Math.max(0, pwc.doubleValue());
                applied.add("pathfinderWaterCellCost");
            }
            if (accepted.get("pathfinderWaterClimbOutCost") instanceof Number pwco) {
                BotConfig.pathfinderWaterClimbOutCost = Math.max(0, pwco.doubleValue());
                applied.add("pathfinderWaterClimbOutCost");
            }
            if (accepted.get("pathfinderSubmergedWaterCost") instanceof Number pswc) {
                BotConfig.pathfinderSubmergedWaterCost = Math.max(0, pswc.doubleValue());
                applied.add("pathfinderSubmergedWaterCost");
            }
            if (accepted.get("debugFly") instanceof Boolean dfly) {
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
            if (accepted.get("autoBackfill") instanceof Boolean abf) {
                BotConfig.autoBackfill = abf;
                if (!abf) bot.backfillTracker.clear();
                applied.add("autoBackfill");
            }
            if (accepted.get("autoBackfillBlock") instanceof String abb && !abb.isBlank()) {
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
            if (accepted.get("blocksToAvoid") instanceof List<?> bl) {
                List<String> bad = new ArrayList<>();
                Set<String> nextSet = blockIds(bl, bad);
                if (bad.isEmpty()) {
                    BotConfig.extraHazardBlocks = nextSet;
                    applied.add("blocksToAvoid");
                } else {
                    rejected.add("blocksToAvoid: invalid ids " + bad);
                }
            }
            // buildBlockWhitelist is a list of block ids the bot may PLACE as build/support
            // blocks (pillar/bridge/parkour footing). Same ResourceLocation validation as
            // blocksToAvoid; whole-list replacement, pass [] to clear (→ falls back to the
            // full-cube heuristic). Invalid ids reject the entire write.
            if (accepted.get("buildBlockWhitelist") instanceof List<?> wl) {
                List<String> bad = new ArrayList<>();
                Set<String> nextSet = blockIds(wl, bad);
                if (bad.isEmpty()) {
                    BotConfig.buildBlockWhitelist = nextSet;
                    applied.add("buildBlockWhitelist");
                } else {
                    rejected.add("buildBlockWhitelist: invalid ids " + bad);
                }
            }
            // mutedEvents: event types suppressed from the live PUSH channel (the event
            // is still recorded + retrievable via mc.wait.event / replay). Free-form
            // strings — any event type. Whole-list replace; pass [] to un-mute everything.
            if (accepted.get("mutedEvents") instanceof List<?> ml) {
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
            if (accepted.get("avoidPoints") instanceof List<?> ap) {
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
        }
        // THE write path for every key the specialised setters above did not consume: a
        // `public static volatile` PRIMITIVE field on BotConfig is settable by its field name,
        // and a hand-listed alias (walker.repathEveryTicks) by its alias. A new flag needs ONLY
        // its BotConfig declaration — no branch here, no schema edit (BotTools builds the closed
        // schema from SettingsRegistry, which reads the same fields).
        for (Map.Entry<String, Object> e : accepted.entrySet()) {
            String k = e.getKey();
            if (applied.contains(k)) continue;
            Field f = SettingsRegistry.primitiveField(k);
            if (f == null) continue;
            try {
                Class<?> t = f.getType();
                Object v = e.getValue();
                if (t == boolean.class && v instanceof Boolean b) { f.setBoolean(null, b); applied.add(k); }
                else if (t == int.class && v instanceof Number n) { f.setInt(null, n.intValue()); applied.add(k); }
                else if (t == double.class && v instanceof Number n) { f.setDouble(null, n.doubleValue()); applied.add(k); }
                else if (t == float.class && v instanceof Number n) { f.setFloat(null, n.floatValue()); applied.add(k); }
                else if (t == long.class && v instanceof Number n) { f.setLong(null, n.longValue()); applied.add(k); }
            } catch (IllegalAccessException ignore) {
                // a public field; reported as inert below if it ever happens
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
            Set<String> rejKeys = rejectedKeys(rejected);
            for (String k : params.keySet()) {
                if (applied.contains(k)) continue;
                if (rejKeys.contains(k)) continue;
                inert.add(k);   // known (unknown keys threw earlier) but no branch consumed it
            }
        }
        if (!inert.isEmpty()) {
            LOG.warn("[mc.bot.setting] inert key(s) {} — known to SettingsRegistry but matched no "
                    + "apply branch (drift: a registry key lost its write path — or a direct in-JVM caller passed a wrong-typed value, which bypasses the route-layer validator)", inert);
        }
        return new Outcome(applied, rejected, inert);
    }

    /** The registered block ids in {@code list}, normalised; every entry that is not one goes to {@code bad}. */
    private static Set<String> blockIds(List<?> list, List<String> bad) {
        Set<String> ids = new LinkedHashSet<>();
        for (Object o : list) {
            if (!(o instanceof String s) || s.isBlank()) { bad.add(String.valueOf(o)); continue; }
            try {
                ResourceLocation rl = ResourceLocation.parse(s);
                if (!BuiltInRegistries.BLOCK.containsKey(rl)) { bad.add(s); continue; }
                ids.add(rl.toString());
            } catch (Exception e) { bad.add(s); }
        }
        return Set.copyOf(ids);
    }

    /** {@code params} without the numbers that fall outside their documented range, each of
     *  which is added to {@code rejected} with that range. */
    private static Map<String, Object> inRange(Map<String, Object> params, List<String> rejected) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            SettingsDocs.Range r = SettingsRegistry.documentedRange(e.getKey());
            if (r != null && e.getValue() instanceof Number n && !r.contains(n.doubleValue())) {
                rejected.add(e.getKey() + " out of range " + r.text());
                continue;
            }
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /**
     * The KEY each rejection message is about — the "has this key already been dealt with?"
     * guard must ask about keys, not about message text.
     *
     * <p>It used to be {@code rejected.stream().anyMatch(r -> r.startsWith(k))}, which conflates
     * a key with every key that has it as a PREFIX (autoBackfill/autoBackfillBlock,
     * autoEat/autoEatFoodThreshold, riskBias/riskBias.scale, …). The live case was
     * {@code {riskBias:true, "riskBias.scale":500}}: the out-of-range sibling's message matched
     * {@code riskBias}, so the valid boolean was skipped by the inert report and the caller got
     * {@code ok:true} with no signal that its flag never landed.
     *
     * <p>Rejection messages come in two shapes, {@code "key msg"} and {@code "key: msg"}, so
     * take the first whitespace-delimited token and drop a trailing colon.
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
