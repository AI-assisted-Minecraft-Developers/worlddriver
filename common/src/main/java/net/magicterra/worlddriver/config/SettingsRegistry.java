package net.magicterra.worlddriver.config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Binds each {@code mc.bot.setting} key to where its value lives. The keys themselves, with their types
 * and prose, are declared by the contract; this side answers, for a key by its wire name, which field
 * holds it or which other way it is read, and what that field held when this build started.
 *
 * <p>A key names the field of the same name unless {@link #HAND} says otherwise: a wire name that is not
 * its field's, or a value that is not one field. {@code SettingsBindingTest} holds every key to a binding
 * and every setting field to exactly one key, so a field added here and not declared there, or the
 * reverse, fails the build instead of answering as unknown.
 *
 * <p><b>Pure.</b> Nothing here touches the game, so the API artifact generator can load it to read the
 * defaults without Minecraft on the classpath.
 *
 * <p>Whether a key does anything is a further question: {@code SettingsConsumerTest} fails if a setting
 * field is unread outside the settings plumbing.
 */
public final class SettingsRegistry {
    private SettingsRegistry() {}

    /** How {@code SettingsCommand} reads and writes a key's live value. */
    public enum Read { CONFIG_FIELD, BOT_PAUSED, HAZARD_LIST, WHITELIST_LIST, MUTED_LIST, UNDRIVABLE_LIST, AVOID_POINTS }

    /** A key bound some other way than to the field of its own name; {@code field} is null for no field. */
    record Hand(String key, Read read, String field) {}

    private static Hand field(String key, String field) { return new Hand(key, Read.CONFIG_FIELD, field); }
    private static Hand hand(String key, Read read, String field) { return new Hand(key, read, field); }

    static final List<Hand> HAND = List.of(
        field("navigator.repathEveryTicks", "navigatorRepathEveryTicks"),
        field("navigator.totalTickBudget", "navigatorTotalTickBudget"),
        field("navigator.yawHysteresisDeg", "navigatorYawHysteresisDeg"),
        field("mine.searchVerticalRadius", "mineSearchVerticalRadius"),
        hand("paused", Read.BOT_PAUSED, null),
        field("pathfinder.dangerPenalty", "dangerPenaltyPerCell"),
        field("pathfinder.lavaDangerPenalty", "lavaDangerPenalty"),
        field("pathfinder.contactDangerPenalty", "contactDangerPenalty"),
        field("pathfinder.ledgeDangerPenalty", "ledgeDangerPenalty"),
        field("pathfinder.waterDangerPenalty", "waterDangerPenalty"),
        field("pathfinder.ledgeDangerMinDrop", "ledgeDangerMinDrop"),
        field("pathfinder.mobAvoidRadius", "mobAvoidRadius"),
        field("pathfinder.mobAvoidPenalty", "mobAvoidPenalty"),
        field("riskBias.scale", "riskBiasScale"),
        field("pathfinder.maxNodes", "pathfinderMaxNodes"),
        field("pathfinder.maxMs", "pathfinderMaxMs"),
        field("pathfinder.sliceMs", "pathfinderSliceMs"),
        field("pathfinder.idleSliceMs", "pathfinderIdleSliceMs"),
        field("pathfinder.previewSliceMs", "pathfinderPreviewSliceMs"),
        field("pathfinder.heuristicWeight", "pathfinderHeuristicWeight"),
        field("pathfinder.axisHeight", "axisHeight"),
        hand("blocksToAvoid", Read.HAZARD_LIST, "extraHazardBlocks"),
        hand("buildBlockWhitelist", Read.WHITELIST_LIST, "buildBlockWhitelist"),
        hand("mutedEvents", Read.MUTED_LIST, "mutedEvents"),
        hand("undrivableMobs", Read.UNDRIVABLE_LIST, "undrivableMobs"),
        field("pathfinder.avoidZonePenalty", "avoidZonePenalty"),
        hand("avoidPoints", Read.AVOID_POINTS, "avoidZones")
    );

    private static final Map<String, Hand> HAND_BY_KEY = new HashMap<>();

    static {
        for (Hand h : HAND) {
            if (HAND_BY_KEY.put(h.key(), h) != null) {
                throw new IllegalStateException("SettingsRegistry: key '" + h.key() + "' bound twice");
            }
            if (h.field() != null) {
                try {
                    BotConfig.field(h.field());
                } catch (NoSuchFieldException e) {
                    throw new IllegalStateException(
                        "SettingsRegistry: no config field '" + h.field() + "' for key '" + h.key() + "'", e);
                }
            }
        }
    }

    /**
     * Public static volatile PRIMITIVE config fields in {@link BotConfig#publicFields()} order: every
     * setting but the strings and the lists.
     */
    static List<Field> reflectivePrimitiveFields() {
        List<Field> out = new ArrayList<>();
        for (Field f : BotConfig.publicFields()) {
            if (isSetting(f) && f.getType().isPrimitive()) out.add(f);
        }
        return out;
    }

    /** A public static volatile field: what a key may be bound to. */
    static boolean isSetting(Field f) {
        int mods = f.getModifiers();
        return Modifier.isStatic(mods) && Modifier.isVolatile(mods);
    }

    /** How {@code key}'s value is read: a field for every key but the hand-read lists and {@code paused}. */
    public static Read readOf(String key) {
        Hand h = HAND_BY_KEY.get(key);
        return h == null ? Read.CONFIG_FIELD : h.read();
    }

    /** The field {@code key} is bound to, whichever way it is read, or null for none. */
    static Field boundField(String key) {
        Hand h = HAND_BY_KEY.get(key);
        String name = h == null ? key : h.field();
        if (name == null) return null;
        try {
            Field f = BotConfig.field(name);
            return isSetting(f) ? f : null;
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    /** The field behind {@code key}, or null for a key {@link #readOf} reads another way. */
    public static Field backingField(String key) {
        return readOf(key) == Read.CONFIG_FIELD ? boundField(key) : null;
    }

    /**
     * What {@code key}'s field held when this build started, as a JSON value: a boolean, a number, a
     * string, or a sorted list of strings for a set. Null for a key with no field behind it.
     */
    public static Object compiledDefault(String key) {
        Field f = boundField(key);
        if (f == null) return null;
        String s = BotConfig.COMPILED_DEFAULTS.get(f.getName());
        if (s == null) return null;
        Class<?> t = f.getType();
        if (t == boolean.class) return Boolean.parseBoolean(s);
        if (t == int.class) return Integer.parseInt(s);
        if (t == long.class) return Long.parseLong(s);
        if (t == double.class || t == float.class) return Double.parseDouble(s);
        if (t == String.class) return s;
        if (s.isEmpty()) return List.of();
        String[] items = s.split(",");
        Arrays.sort(items);
        return List.of(items);
    }
}
