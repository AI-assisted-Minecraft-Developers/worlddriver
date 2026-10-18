package net.magicterra.worlddriver.api.methods.test;

import java.util.List;
import java.util.Map;

import net.magicterra.worlddriver.api.methods.setting.SettingDefs;
import net.magicterra.worlddriver.api.methods.setting.SettingsContracts;
import net.magicterra.worlddriver.api.schema.ApiField;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.DecodeContext;
import net.magicterra.worlddriver.api.schema.KeyedValueType.Entry;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.WireName;

/**
 * The test harness's hold on the settings and the bots: {@code mc.test.settings} and {@code mc.test.bots}.
 * StageWright publishes them; the shapes live here so a scene calls them through {@link TestHarnessApi}.
 * Each runs on the thread that calls it and answers within the call, so a scene on the server thread
 * reads the answer at once and goes on in the same tick.
 */
public final class TestHarnessContracts {
    private TestHarnessContracts() {}

    // ------------------------------------------------------------------ settings

    /** What a pin puts in place once it has saved the settings. */
    public enum Baseline implements WireName {
        /** The settings the scenes were written against: the newer behaviours off, breaking and placing off. */
        TEST("test"),
        /** The defaults this build ships with. */
        COMPILED("compiled"),
        /** Nothing: the settings stay as they are. */
        CURRENT("current");

        private final String wire;
        Baseline(String wire) { this.wire = wire; }
        @Override public String wireName() { return wire; }
    }

    public sealed interface SettingsOp permits Pin, Unpin, Write, Read {}
    public record Pin(Baseline baseline) implements SettingsOp {}
    public record Unpin(int pin) implements SettingsOp {}
    public record Write(List<Entry> values) implements SettingsOp {
        /** Write {@code values}, in the map's iteration order. */
        public static Write of(Map<String, ?> values) {
            List<Entry> entries = new java.util.ArrayList<>();
            values.forEach((k, v) -> entries.add(new Entry(k, v)));
            return new Write(entries);
        }
    }
    public record Read(List<String> keys) implements SettingsOp {}

    public sealed interface SettingsReply permits Pinned, Unpinned, Written, Values {}
    public record Pinned(int pin) implements SettingsReply {}
    public record Unpinned(int pin) implements SettingsReply {}
    public record Written(List<Entry> values) implements SettingsReply {}
    public record Values(List<Entry> values) implements SettingsReply {
        /** What {@code key} holds. */
        public Object get(String key) {
            for (Entry e : values) if (e.key().equals(key)) return e.value();
            throw new IllegalArgumentException(key + " was not read");
        }
    }

    private static final ApiType<String> KEY = ApiTypes.string(1, 64).withCheck((k, at) -> {
        if (!SettingDefs.isKnown(k)) at.fail(ApiIssue.Code.INVALID_VALUE, "unknown key \"" + k + "\"");
    });

    public static final ApiType<SettingsOp> SETTINGS_PARAMS = ApiTypes.<SettingsOp>taggedUnion("TestSettingsParams", "op",
            ApiTypes.variant("pin", Pin.class, ApiTypes.record(Pin.class, null,
                    ApiField.field("baseline", ApiTypes.enumOf("TestBaseline", Baseline.class)).defaulted(Baseline.TEST)
                            .forGetter(Pin::baseline),
                    Pin::new)),
            ApiTypes.variant("unpin", Unpin.class, ApiTypes.record(Unpin.class, null,
                    ApiField.field("pin", ApiTypes.integer(1, Integer.MAX_VALUE)).required().forGetter(Unpin::pin),
                    Unpin::new)),
            ApiTypes.variant("write", Write.class, ApiTypes.record(Write.class, null,
                    ApiField.field("values", SettingsContracts.SETTING_VALUES.atLeast(1)).required().forGetter(Write::values),
                    Write::new)),
            ApiTypes.variant("read", Read.class, ApiTypes.record(Read.class, null,
                    ApiField.field("keys", ApiTypes.list(KEY, SettingDefs.all().size())).required().forGetter(Read::keys),
                    Read::new)))
            .withCheck(TestHarnessContracts::checkSettings);

    private static void checkSettings(SettingsOp op, DecodeContext at) {
        if (op instanceof Write w) {
            for (Entry e : w.values()) {
                if (SettingsContracts.CONTROLS.contains(e.key())) {
                    at.field("values").field(e.key()).fail(ApiIssue.Code.INVALID_VALUE, e.key() + " is a control, not a setting");
                }
            }
        }
    }

    public static final ApiType<SettingsReply> SETTINGS_REPLY = ApiTypes.<SettingsReply>taggedUnion("TestSettingsReply", "op",
            ApiTypes.variant("pin", Pinned.class, ApiTypes.record(Pinned.class, null,
                    ApiField.field("pin", ApiTypes.integer(1, Integer.MAX_VALUE)).required().forGetter(Pinned::pin),
                    Pinned::new)),
            ApiTypes.variant("unpin", Unpinned.class, ApiTypes.record(Unpinned.class, null,
                    ApiField.field("pin", ApiTypes.integer(1, Integer.MAX_VALUE)).required().forGetter(Unpinned::pin),
                    Unpinned::new)),
            ApiTypes.variant("write", Written.class, ApiTypes.record(Written.class, null,
                    ApiField.field("values", SettingsContracts.SETTING_VALUES).describe("What each key holds after the write.")
                            .required().forGetter(Written::values),
                    Written::new)),
            ApiTypes.variant("read", Values.class, ApiTypes.record(Values.class, null,
                    ApiField.field("values", SettingsContracts.SETTING_VALUES).required().forGetter(Values::values),
                    Values::new)));

    public static final MethodSpec<SettingsOp, SettingsReply> SETTINGS = MethodSpec.of("mc.test.settings",
                    SETTINGS_PARAMS, SETTINGS_REPLY,
                    "The settings for a scene, on the calling thread and never saved. pin saves every setting and puts "
                            + "baseline in place, answering a pin that unpin restores the saved settings with; write sets "
                            + "keys, checked as mc.bot.setting checks them; read answers keys.")
            .hidden().effects(MethodSpec.Effect.CONFIG_WRITE);

    // ------------------------------------------------------------------ bots

    public sealed interface BotsOp permits Tick, Count, Clear {}
    /** Advance every ticking bot {@code ticks} times now, stopping early once none is busy when {@code untilIdle}. */
    public record Tick(int ticks, boolean untilIdle) implements BotsOp {
        public static Tick once() { return new Tick(1, false); }
    }
    public record Count() implements BotsOp {}
    public record Clear() implements BotsOp {}

    public sealed interface BotsReply permits Ticked, Counted, Cleared {}
    /** {@code ticks} it advanced; {@code busy}, the bots still on a task after it. */
    public record Ticked(int ticks, int busy) implements BotsReply {}
    /** {@code ticking}: the bots on the tick list; {@code busy}: those on a task. */
    public record Counted(int ticking, int busy) implements BotsReply {}
    public record Cleared(int removed) implements BotsReply {}

    public static final ApiType<BotsOp> BOTS_PARAMS = ApiTypes.<BotsOp>taggedUnion("TestBotsParams", "op",
            ApiTypes.variant("tick", Tick.class, ApiTypes.record(Tick.class, null,
                    ApiField.field("ticks", ApiTypes.integer(1, 100_000)).defaulted(1).forGetter(Tick::ticks),
                    ApiField.field("untilIdle", ApiTypes.bool()).defaulted(false).forGetter(Tick::untilIdle),
                    Tick::new)),
            ApiTypes.variant("count", Count.class, ApiTypes.record(Count.class, null, Count::new)),
            ApiTypes.variant("clear", Clear.class, ApiTypes.record(Clear.class, null, Clear::new)));

    private static final ApiType<Integer> COUNT = ApiTypes.integer(0, Integer.MAX_VALUE);

    public static final ApiType<BotsReply> BOTS_REPLY = ApiTypes.<BotsReply>taggedUnion("TestBotsReply", "op",
            ApiTypes.variant("tick", Ticked.class, ApiTypes.record(Ticked.class, null,
                    ApiField.field("ticks", COUNT).required().forGetter(Ticked::ticks),
                    ApiField.field("busy", COUNT).required().forGetter(Ticked::busy),
                    Ticked::new)),
            ApiTypes.variant("count", Counted.class, ApiTypes.record(Counted.class, null,
                    ApiField.field("ticking", COUNT).required().forGetter(Counted::ticking),
                    ApiField.field("busy", COUNT).required().forGetter(Counted::busy),
                    Counted::new)),
            ApiTypes.variant("clear", Cleared.class, ApiTypes.record(Cleared.class, null,
                    ApiField.field("removed", COUNT).required().forGetter(Cleared::removed),
                    Cleared::new)));

    public static final MethodSpec<BotsOp, BotsReply> BOTS = MethodSpec.of("mc.test.bots", BOTS_PARAMS, BOTS_REPLY,
                    "The tick list every server-side bot is advanced from. tick advances each now, within the call, as "
                            + "many times as asked; count answers how many are on it and how many are busy; clear takes "
                            + "every bot off it. Server thread only.")
            .hidden().effects(MethodSpec.Effect.BODY_TASK);
}
