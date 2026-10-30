package net.magicterra.worlddriver.api.methods.setting;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Collections;
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
import net.magicterra.worlddriver.api.schema.SelfChecked;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.schema.annotation.As;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.OneOf;
import net.magicterra.worlddriver.api.schema.annotation.Pattern;
import net.magicterra.worlddriver.api.schema.annotation.Tag;
import net.magicterra.worlddriver.api.schema.annotation.TsAlias;
import net.magicterra.worlddriver.api.schema.annotation.Variant;

/**
 * {@code mc.bot.setting}: read the tuning surface, patch it, or apply one of its controls.
 *
 * <p>The keys are the components of the per-layer settings records, {@link BotSettings} and the ones in
 * {@link #LAYERS}: a key's type, prose and default are read off its component. A key is bound to the field that holds
 * it on the config side, by the component's name; {@code SettingsBindingTest} holds every key to a field and every
 * setting field to a key.
 *
 * <p>Two keys are controls rather than configuration: {@code paused} stops this client's bot and releases its keys, and
 * {@code debugFly} toggles creative flight. Their effects cannot be taken back by restoring a value, so they are
 * written one at a time with {@code op: 'apply'} and never mixed into a patch.
 */
public final class SettingsContracts {

    /** Every layer's settings, the facade's first and then each layer down: the order a read without keys answers. */
    public static final List<Class<? extends Record>> LAYERS = List.of(
            BotSettings.class,
            ArbitrationSettings.class,
            BehaviorSettings.class,
            ControlSettings.class,
            PlanningSettings.class,
            RulesSettings.class,
            WorldSettings.class,
            GameSettings.class);

    /** Each key's value type, in {@link #LAYERS} order. */
    public static final Map<String, ApiType<?>> TYPES;

    /** Each key's description, for the keys that have one. */
    public static final Map<String, String> DOCS;

    /** Each key's default, as its type decodes it; a key with no field of its own has none. */
    public static final Map<String, Object> DEFAULTS;

    static {
        Map<String, ApiType<?>> types = new LinkedHashMap<>();
        Map<String, String> docs = new LinkedHashMap<>();
        Map<String, Object> defaults = new LinkedHashMap<>();
        for (Class<? extends Record> layer : LAYERS) {
            for (ApiField<?, ?> f : ApiTypes.record(layer).fields()) {
                if (types.put(f.name(), f.type()) != null) {
                    throw new IllegalStateException("setting " + f.name() + " declared twice");
                }
                if (f.description() != null) {
                    docs.put(f.name(), f.description());
                }
                if (f.defaultValue() != null) {
                    defaults.put(f.name(), f.defaultValue());
                }
            }
        }
        TYPES = Collections.unmodifiableMap(types);
        DOCS = Collections.unmodifiableMap(docs);
        DEFAULTS = Collections.unmodifiableMap(defaults);
    }

    /** Written with {@code apply} only. */
    public static final Set<String> CONTROLS = Set.of("paused", "debugFly");

    /** Controls with nothing to read back. */
    public static final Set<String> WRITE_ONLY = Set.of("debugFly");

    public static final KeyedValueType SETTING_VALUES =
            ApiTypes.keyedValues("SettingValues", TYPES, DOCS, DEFAULTS::get);

    private static final KeyedValueType PATCH_VALUES = SETTING_VALUES.atLeast(1);

    private static final ApiType<List<String>> KEY_LIST =
            ApiTypes.list(ApiTypes.of(String.class, SettingKey.class), TYPES.size());

    public static final UnionType<SettingsOp> PARAMS = ApiTypes.union(SettingsOp.class);

    public static final UnionType<SettingsReply> REPLY = ApiTypes.union(SettingsReply.class);

    public static final MethodSpec<SettingsOp, SettingsReply> SPEC = MethodSpec.of(
                    "mc.bot.setting",
                    PARAMS,
                    REPLY,
                    "The bot's tuning surface. op 'read' answers {key: value} for the keys asked for, or every readable one. "
                            + "op 'patch' takes values {key: value}, validates them all, then commits them together and saves "
                            + "them; a failed save answers persisted: false with the values still committed. op 'apply' "
                            + "runs one control, {control, value}: paused "
                            + "stops this client's bot and releases its keys, debugFly toggles creative flight. Values "
                            + "are shared by every bot in this game; paused and debugFly act on this client's player.")
            .effects(MethodSpec.Effect.CONFIG_WRITE);

    private SettingsContracts() {}

    /** A block id in a setting. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    @Pattern(value = "[a-z0-9_.-]+:[a-z0-9/._-]+", expected = "a block id such as minecraft:cobblestone")
    public @interface BlockId {}

    /** An entity type id in a setting. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    @Pattern(value = "[a-z0-9_.-]+:[a-z0-9/._-]+", expected = "an entity type id such as minecraft:squid")
    public @interface EntityTypeId {}

    /** A key of {@code mc.bot.setting}. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    @Length(min = 1, max = 64)
    @TsAlias(name = "SettingKey", type = "keyof SettingValues")
    public @interface SettingKey {}

    /** Whether {@code key} is a setting key, control or not. */
    public static boolean isKnown(String key) {
        return TYPES.containsKey(key);
    }

    /** Whether {@code key} is a setting key that op {@code read} can answer, which excludes {@link #WRITE_ONLY}. */
    public static boolean readable(String key) {
        return isKnown(key) && !WRITE_ONLY.contains(key);
    }

    /** Whether {@code key} is a setting key that op {@code patch} can write, which excludes {@link #CONTROLS}. */
    public static boolean patchable(String key) {
        return isKnown(key) && !CONTROLS.contains(key);
    }

    /** The parameters of {@code mc.bot.setting}: a read, a patch or an apply, told apart by {@code op}. */
    @Tag("op")
    @Named("SettingsParams")
    public sealed interface SettingsOp permits Read, Patch, Apply {}

    /** Op {@code read}: the values of the named keys, or of every readable key; answered with {@link Values}. */
    @Variant("read")
    public record Read(
            @Doc("Omitted: every readable key. Empty: none.")
            Optional<@As(value = SettingsContracts.class, field = "KEY_LIST") List<String>> keys)
            implements SettingsOp, SelfChecked {
        /** Read {@code keys}, in that order. */
        public static Read of(String... keys) {
            return new Read(Optional.of(List.of(keys)));
        }

        /** Read every readable key. */
        public static Read all() {
            return new Read(Optional.empty());
        }

        @Override
        public void check(DecodeContext at) {
            List<String> named = keys.orElse(List.of());
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < named.size(); i++) {
                String k = named.get(i);
                DecodeContext here = at.field("keys").index(i);
                if (readable(k)) {
                    if (!seen.add(k)) {
                        here.fail(ApiIssue.Code.INVALID_VALUE, k + " is named twice");
                    }
                } else if (isKnown(k)) {
                    here.fail(ApiIssue.Code.INVALID_VALUE, k + " is a control with nothing to read");
                } else {
                    here.fail(ApiIssue.Code.INVALID_VALUE, "unknown key \"" + k + "\"");
                }
            }
        }
    }

    /** Op {@code patch}: new values for one or more keys, committed together; controls are refused. */
    @Variant("patch")
    public record Patch(
            @Doc("The keys to write and their new values, e.g. {autoEat: true}; validated together and committed "
                    + "together, so nothing is written unless every value is valid. Keys not named keep their values.")
            @As(value = SettingsContracts.class, field = "PATCH_VALUES")
            List<Entry> values)
            implements SettingsOp, SelfChecked {
        /** Write {@code values}, in the map's iteration order. */
        public static Patch of(Map<String, ?> values) {
            List<Entry> entries = new java.util.ArrayList<>();
            values.forEach((k, v) -> entries.add(new Entry(k, v)));
            return new Patch(entries);
        }

        @Override
        public void check(DecodeContext at) {
            for (Entry e : values) {
                if (CONTROLS.contains(e.key())) {
                    at.field("values")
                            .field(e.key())
                            .fail(ApiIssue.Code.INVALID_VALUE, e.key() + " is a control; write it with op: 'apply'");
                }
            }
        }
    }

    /** Op {@code apply}: runs one control, {@code paused} or {@code debugFly}, with the given value. */
    @Variant("apply")
    public record Apply(
            @OneOf(
                    name = "SettingsControl",
                    value = {"paused", "debugFly"})
            String control,

            boolean value)
            implements SettingsOp {}

    /** The reply of {@code mc.bot.setting}, tagged with the same {@code op} as the request. */
    @Tag("op")
    @Named("SettingsReply")
    public sealed interface SettingsReply permits Values, Patched, Applied {}

    /** The answer to a read: each key asked for and its value. */
    @Variant("read")
    public record Values(
            @Doc("In the order asked for.") @As(value = SettingsContracts.class, field = "SETTING_VALUES")
            List<Entry> values)
            implements SettingsReply {
        /** What {@code key} holds, when it was read. */
        public Optional<Object> get(String key) {
            return valueOf(values, key);
        }
    }

    /** {@code values} are what the keys hold after the commit; a failed save leaves them committed. */
    @Variant("patch")
    public record Patched(
            @Doc("What each written key holds after the commit, in the order sent.")
            @As(value = SettingsContracts.class, field = "SETTING_VALUES")
            List<Entry> values,

            @Doc("Whether the settings file was written; false leaves the values committed in memory.")
            boolean persisted,

            Optional<String> persistError)
            implements SettingsReply {
        /** What {@code key} holds after the commit, when it was written. */
        public Optional<Object> get(String key) {
            return valueOf(values, key);
        }
    }

    private static Optional<Object> valueOf(List<Entry> values, String key) {
        for (Entry e : values) {
            if (e.key().equals(key)) {
                return Optional.of(e.value());
            }
        }
        return Optional.empty();
    }

    /** The answer to an apply: the control that ran and the value it was given. */
    @Variant("apply")
    public record Applied(
            @OneOf(
                    name = "SettingsControl",
                    value = {"paused", "debugFly"})
            String control,

            boolean value)
            implements SettingsReply {}
}
