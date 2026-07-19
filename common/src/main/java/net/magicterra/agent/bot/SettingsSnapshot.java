package net.magicterra.agent.bot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the settings snapshot returned by {@code mc.bot.setting{...}} — the current value of
 * every knob, plus a reflective completion pass that surfaces any {@code public static volatile}
 * PRIMITIVE {@link BotConfig} field not hand-listed (so newly-declared flags never lag the
 * snapshot). Behaviour is byte-identical to the old inlined version; the difference is that the
 * key list now lives ONCE in {@link SettingsRegistry} (hand section + the shared reflective
 * enumerator) instead of being duplicated across the snapshot, the apply if-chain, and the schema.
 * This class keeps its instance-reading role — it reads live VALUES; the registry owns the
 * KEY/TYPE enumeration (#280 single source).
 */
final class SettingsSnapshot {
    private SettingsSnapshot() {}

    static Map<String, Object> build(BotApiImpl bot) {
        Map<String, Object> snap = new LinkedHashMap<>();
        // Hand-listed knobs, in the registry's (== the historical snapshot's) order.
        for (SettingsRegistry.Hand h : SettingsRegistry.handEntries()) {
            snap.put(h.key(), readHand(bot, h));
        }
        // Reflective completion: every public static volatile PRIMITIVE BotConfig field not already
        // emitted above. Shares the SAME enumerator the registry uses, so a newly-declared flag is
        // settable, visible in the snapshot, AND advertised in the closed schema — never one without
        // the others (the #280 blind spot that read `null` for live flags like walkerCarrotBodyLos).
        for (java.lang.reflect.Field f : SettingsRegistry.reflectivePrimitiveFields()) {
            if (snap.containsKey(f.getName())) continue;
            try {
                snap.put(f.getName(), f.get(null));
            } catch (IllegalAccessException ignore) { }
        }
        return snap;
    }

    /** Reads a hand-listed key's live value — the value production the registry deliberately does NOT own. */
    private static Object readHand(BotApiImpl bot, SettingsRegistry.Hand h) {
        switch (h.read()) {
            case BOT_PAUSED:
                return bot.paused;
            case HAZARD_LIST:
                return new ArrayList<>(BotConfig.extraHazardBlocks);
            case WHITELIST_LIST:
                return new ArrayList<>(BotConfig.buildBlockWhitelist);
            case MUTED_LIST:
                return new ArrayList<>(BotConfig.mutedEvents);
            case AVOID_POINTS: {
                List<Map<String, Object>> zs = new ArrayList<>();
                for (double[] z : BotConfig.avoidZones) {
                    if (z.length < 4) continue;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("x", z[0]); m.put("y", z[1]); m.put("z", z[2]); m.put("radius", z[3]);
                    zs.add(m);
                }
                return zs;
            }
            case CONFIG_FIELD:
            default:
                try {
                    return SettingsRegistry.configField(h.field()).get(null);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("SettingsSnapshot: cannot read BotConfig." + h.field(), e);
                }
        }
    }
}
