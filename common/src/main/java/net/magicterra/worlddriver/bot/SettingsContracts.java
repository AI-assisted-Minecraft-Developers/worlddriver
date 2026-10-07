package net.magicterra.worlddriver.bot;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.magicterra.worlddriver.api.schema.ApiField;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.DecodeContext;
import net.magicterra.worlddriver.api.schema.KeyedValueType;
import net.magicterra.worlddriver.api.schema.KeyedValueType.Entry;
import net.magicterra.worlddriver.api.schema.MethodSpec;

/**
 * {@code mc.bot.setting}: read the tuning surface, patch it, or apply one of its controls.
 *
 * <p>The key table is {@link SettingsRegistry}'s, with each key's prose and range from {@link SettingsDocs}:
 * the one place the keys are enumerated, read reflectively from the settings class's field declarations.
 * Neither class touches the game, so the artifact generator still loads this without Minecraft. It lives
 * beside them rather than in {@code api/methods} because the contract layer may not depend on the bot.
 *
 * <p>Two keys are controls rather than configuration: {@code paused} stops this client's bot and releases
 * its keys, and {@code debugFly} toggles creative flight. Their effects cannot be taken back by restoring a
 * value, so they are written one at a time with {@code op: 'apply'} and never mixed into a patch.
 */
public final class SettingsContracts {
    private SettingsContracts() {}

    /** A point the pathfinder keeps away from, by {@code radius} blocks. */
    public record AvoidPoint(double x, double y, double z, double radius) {}

    public sealed interface SettingsOp permits Read, Patch, Apply {}
    public record Read(Optional<List<String>> keys) implements SettingsOp {
        /** Read {@code keys}, in that order. */
        public static Read of(String... keys) { return new Read(Optional.of(List.of(keys))); }

        /** Read every readable key. */
        public static Read all() { return new Read(Optional.empty()); }
    }

    public record Patch(List<Entry> values) implements SettingsOp {
        /** Write {@code values}, in the map's iteration order. */
        public static Patch of(Map<String, ?> values) {
            List<Entry> entries = new java.util.ArrayList<>();
            values.forEach((k, v) -> entries.add(new Entry(k, v)));
            return new Patch(entries);
        }
    }

    public record Apply(String control, boolean value) implements SettingsOp {}

    public sealed interface SettingsReply permits Values, Patched, Applied {}

    public record Values(List<Entry> values) implements SettingsReply {
        /** What {@code key} holds, when it was read. */
        public Optional<Object> get(String key) { return valueOf(values, key); }
    }

    /** {@code values} are what the keys hold after the commit; a failed save leaves them committed. */
    public record Patched(List<Entry> values, boolean persisted, Optional<String> persistError) implements SettingsReply {
        /** What {@code key} holds after the commit, when it was written. */
        public Optional<Object> get(String key) { return valueOf(values, key); }
    }

    private static Optional<Object> valueOf(List<Entry> values, String key) {
        for (Entry e : values) if (e.key().equals(key)) return Optional.of(e.value());
        return Optional.empty();
    }

    public record Applied(String control, boolean value) implements SettingsReply {}

    /** Written with {@code apply} only. */
    public static final Set<String> CONTROLS = Set.of("paused", "debugFly");
    /** Controls with nothing to read back. */
    public static final Set<String> WRITE_ONLY = Set.of("debugFly");

    public static boolean readable(String key) { return SettingsRegistry.isKnown(key) && !WRITE_ONLY.contains(key); }

    public static boolean patchable(String key) { return SettingsRegistry.isKnown(key) && !CONTROLS.contains(key); }

    /** Keys whose numbers are floored at zero with no documented ceiling. */
    private static final Set<String> NON_NEGATIVE = Set.of(
            "pathfinderWaterCellCost", "pathfinderWaterClimbOutCost", "pathfinderSubmergedWaterCost");

    private static final ApiType<AvoidPoint> AVOID_POINT = ApiTypes.record("AvoidPoint",
            ApiField.field("x", ApiTypes.number()).required().forGetter(AvoidPoint::x),
            ApiField.field("y", ApiTypes.number()).required().forGetter(AvoidPoint::y),
            ApiField.field("z", ApiTypes.number()).required().forGetter(AvoidPoint::z),
            ApiField.field("radius", ApiTypes.number(1, 64)).defaulted(8.0).forGetter(AvoidPoint::radius),
            AvoidPoint::new);

    /** Each key's value type, in the registry's order. */
    public static final Map<String, ApiType<?>> TYPES = types();

    private static Map<String, ApiType<?>> types() {
        Map<String, ApiType<?>> out = new LinkedHashMap<>();
        SettingsRegistry.schemaProps().forEach((key, kind) -> out.put(key, typeOf(key, kind)));
        return out;
    }

    private static Map<String, String> docs() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : TYPES.keySet()) {
            String doc = SettingsDocs.of(key);
            if (doc != null) out.put(key, doc);
        }
        return out;
    }

    private static ApiType<?> typeOf(String key, SettingsRegistry.Type kind) {
        SettingsDocs.Range r = SettingsRegistry.documentedRange(key);
        return switch (key) {
            case "tunnelWidthBias" -> ApiTypes.stringEnum("TunnelWidthBias", "left", "right");
            case "autoBackfillBlock" -> ApiTypes.pattern("[a-z0-9_.-]+:[a-z0-9/._-]+", "a block id such as minecraft:cobblestone");
            case "blocksToAvoid", "buildBlockWhitelist" -> ApiTypes.list(
                    ApiTypes.pattern("[a-z0-9_.-]+:[a-z0-9/._-]+", "a block id such as minecraft:powder_snow"), 256);
            case "mutedEvents" -> ApiTypes.list(ApiTypes.string(1, 64), 64);
            case "avoidPoints" -> ApiTypes.list(AVOID_POINT, 64);
            default -> switch (kind) {
                case BOOLEAN -> ApiTypes.bool();
                case STRING -> ApiTypes.string(0, 256);
                case INTEGER -> SettingsRegistry.storedType(key) == long.class
                        ? ApiTypes.int64(r == null ? Long.MIN_VALUE : (long) r.lo(), r == null ? Long.MAX_VALUE : (long) r.hi())
                        : ApiTypes.integer(r == null ? Integer.MIN_VALUE : (int) r.lo(), r == null ? Integer.MAX_VALUE : (int) r.hi());
                case NUMBER -> r != null ? ApiTypes.number(r.lo(), r.hi())
                        : NON_NEGATIVE.contains(key) ? ApiTypes.number(0, Double.MAX_VALUE) : ApiTypes.number();
                case STRING_LIST -> ApiTypes.list(ApiTypes.string(1, 256), 256);
                case POINT_LIST -> ApiTypes.list(AVOID_POINT, 64);
            };
        };
    }

    public static final KeyedValueType SETTING_VALUES = ApiTypes.keyedValues("SettingValues", TYPES, docs());

    private static final ApiType<String> SETTING_KEY = ApiTypes.string(1, 64).tsAlias("SettingKey", "keyof SettingValues");

    private static final ApiType<String> CONTROL = ApiTypes.stringEnum("SettingsControl", "paused", "debugFly");

    private static final int MAX_KEYS = TYPES.size();

    public static final ApiType<SettingsOp> PARAMS = ApiTypes.<SettingsOp>taggedUnion("SettingsParams", "op",
            ApiTypes.variant("read", Read.class, ApiTypes.record(null,
                    ApiField.field("keys", ApiTypes.list(SETTING_KEY, MAX_KEYS))
                            .describe("Omitted: every readable key. Empty: none.").optional().forGetter(Read::keys),
                    Read::new)),
            ApiTypes.variant("patch", Patch.class, ApiTypes.record(null,
                    ApiField.field("values", SETTING_VALUES.atLeast(1))
                            .describe("The keys to write and their new values, e.g. {autoEat: true}; validated together "
                                    + "and committed together, so nothing is written unless every value is valid. Keys not "
                                    + "named keep their values.")
                            .required().forGetter(Patch::values),
                    Patch::new)),
            ApiTypes.variant("apply", Apply.class, ApiTypes.record(null,
                    ApiField.field("control", CONTROL).required().forGetter(Apply::control),
                    ApiField.field("value", ApiTypes.bool()).required().forGetter(Apply::value),
                    Apply::new)))
            .withCheck(SettingsContracts::check);

    private static void check(SettingsOp op, DecodeContext at) {
        switch (op) {
            case Read r -> {
                List<String> keys = r.keys().orElse(List.of());
                Set<String> seen = new HashSet<>();
                for (int i = 0; i < keys.size(); i++) {
                    String k = keys.get(i);
                    DecodeContext here = at.field("keys").index(i);
                    if (!SettingsRegistry.isKnown(k)) here.fail(ApiIssue.Code.INVALID_VALUE, "unknown key \"" + k + "\"");
                    else if (!readable(k)) here.fail(ApiIssue.Code.INVALID_VALUE, k + " is a control with nothing to read");
                    else if (!seen.add(k)) here.fail(ApiIssue.Code.INVALID_VALUE, k + " is named twice");
                }
            }
            case Patch p -> {
                for (Entry e : p.values()) {
                    if (CONTROLS.contains(e.key())) {
                        at.field("values").field(e.key()).fail(ApiIssue.Code.INVALID_VALUE,
                                e.key() + " is a control; write it with op: 'apply'");
                    }
                }
            }
            case Apply a -> { }
        }
    }

    public static final ApiType<SettingsReply> REPLY = ApiTypes.<SettingsReply>taggedUnion("SettingsReply", "op",
            ApiTypes.variant("read", Values.class, ApiTypes.record(null,
                    ApiField.field("values", SETTING_VALUES).describe("In the order asked for.")
                            .required().forGetter(Values::values),
                    Values::new)),
            ApiTypes.variant("patch", Patched.class, ApiTypes.record(null,
                    ApiField.field("values", SETTING_VALUES)
                            .describe("What each written key holds after the commit, in the order sent.")
                            .required().forGetter(Patched::values),
                    ApiField.field("persisted", ApiTypes.bool())
                            .describe("Whether the settings file was written; false leaves the values committed in memory.")
                            .required().forGetter(Patched::persisted),
                    ApiField.field("persistError", ApiTypes.string()).optional().forGetter(Patched::persistError),
                    Patched::new)),
            ApiTypes.variant("apply", Applied.class, ApiTypes.record(null,
                    ApiField.field("control", CONTROL).required().forGetter(Applied::control),
                    ApiField.field("value", ApiTypes.bool()).required().forGetter(Applied::value),
                    Applied::new)));

    public static final MethodSpec<SettingsOp, SettingsReply> SPEC = MethodSpec.of("mc.bot.setting", PARAMS, REPLY,
                    "The bot's tuning surface. op 'read' answers {key: value} for the keys asked for, or every readable one. "
                            + "op 'patch' takes values {key: value}, validates them all, then commits them together and saves "
                            + "them; a failed save answers persisted: false with the values still committed. op 'apply' "
                            + "runs one control, {control, value}: paused "
                            + "stops this client's bot and releases its keys, debugFly toggles creative flight. Values "
                            + "are shared by every bot in this game; paused and debugFly act on this client's player.")
            .effects(MethodSpec.Effect.CONFIG_WRITE);
}
