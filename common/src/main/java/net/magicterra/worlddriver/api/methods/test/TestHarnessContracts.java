package net.magicterra.worlddriver.api.methods.test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.magicterra.worlddriver.api.methods.setting.SettingDefs;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.methods.setting.SettingsContracts;
import net.magicterra.worlddriver.api.schema.ApiField;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.DecodeContext;
import net.magicterra.worlddriver.api.schema.KeyedValueType.Entry;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.RecordType;
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
    /**
     * {@code untimed} also lifts the planner's wall-clock budgets ({@code pathfinder.maxMs} and
     * {@code pathfinder.sliceMs}) past what a user may set, so a search ends on its node budget alone and a
     * scene plans the same on a slow machine as on a fast one.
     */
    public record Pin(Baseline baseline, boolean untimed) implements SettingsOp {
        public static Pin of(Baseline baseline) { return new Pin(baseline, false); }
    }
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
                    ApiField.field("untimed", ApiTypes.bool()).describe("Also lift the planner's wall-clock budgets.")
                            .defaulted(false).forGetter(Pin::untimed),
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

    public sealed interface BotsOp permits Tick, Count, Clear, Park, Unpark {}

    /** Which half of a bot's tick to run. */
    public enum Part implements WireName {
        /** The brain, then the body's step. */
        ALL("all"),
        /** Only the body's physics step: no perceiving, reflexes or task. */
        BODY("body"),
        /** Only the brain: perceiving, reflexes and task leave their input on the body, and nothing moves. */
        BRAIN("brain");

        private final String wire;
        Part(String wire) { this.wire = wire; }
        @Override public String wireName() { return wire; }
    }

    /**
     * Advance the ticking bots {@code ticks} times now, stopping early once none is busy when {@code untilIdle}.
     * With {@code bot}, only that bot, parked or not, and only the {@code part} of its tick asked for.
     */
    public record Tick(int ticks, boolean untilIdle, Optional<String> bot, Part part) implements BotsOp {
        public static Tick once() { return new Tick(1, false, Optional.empty(), Part.ALL); }
        public static Tick of(int ticks) { return new Tick(ticks, false, Optional.empty(), Part.ALL); }
        public static Tick untilIdle(int max) { return new Tick(max, true, Optional.empty(), Part.ALL); }
        public static Tick bot(String bot, int ticks) { return new Tick(ticks, false, Optional.of(bot), Part.ALL); }
        public static Tick body(String bot, int ticks) { return new Tick(ticks, false, Optional.of(bot), Part.BODY); }
        public static Tick brain(String bot) { return new Tick(1, false, Optional.of(bot), Part.BRAIN); }
    }
    public record Count() implements BotsOp {}
    /** Remove every bot but {@code self}: off the tick list and out of the roster, a player one discarded. */
    public record Clear() implements BotsOp {}
    /** Take {@code bot} off the tick list; it stays addressable, and only a {@code tick} naming it moves it. */
    public record Park(String bot) implements BotsOp {}
    /** Put a parked {@code bot} back on the tick list. */
    public record Unpark(String bot) implements BotsOp {}

    public sealed interface BotsReply permits Ticked, Counted, Cleared, Parked {}
    /** {@code ticks} it advanced; {@code busy}, the bots still on a task after it. */
    public record Ticked(int ticks, int busy) implements BotsReply {}
    /** {@code ticking}: the bots on the tick list; {@code busy}: those on a task. */
    public record Counted(int ticking, int busy) implements BotsReply {}
    public record Cleared(int removed) implements BotsReply {}
    /** Whether {@code bot} is now off the tick list. */
    public record Parked(String bot, boolean parked) implements BotsReply {}

    private static final RecordType<Park> PARK = ApiTypes.record(Park.class, null,
            ApiField.field("bot", Responses.BOT_REF).required().forGetter(Park::bot),
            Park::new);
    private static final RecordType<Unpark> UNPARK = ApiTypes.record(Unpark.class, null,
            ApiField.field("bot", Responses.BOT_REF).required().forGetter(Unpark::bot),
            Unpark::new);

    public static final ApiType<BotsOp> BOTS_PARAMS = ApiTypes.<BotsOp>taggedUnion("TestBotsParams", "op",
            ApiTypes.variant("tick", Tick.class, ApiTypes.record(Tick.class, null,
                    ApiField.field("ticks", ApiTypes.integer(1, 100_000)).defaulted(1).forGetter(Tick::ticks),
                    ApiField.field("untilIdle", ApiTypes.bool()).defaulted(false).forGetter(Tick::untilIdle),
                    ApiField.field("bot", Responses.BOT_REF).describe("Only this bot, parked or not.")
                            .optional().forGetter(Tick::bot),
                    ApiField.field("part", ApiTypes.enumOf("TestTickPart", Part.class))
                            .describe("all; body: only the physics step; brain: perceiving, reflexes and task, and no "
                                    + "step, so a press can change the input before a body tick. body and brain need bot.")
                            .defaulted(Part.ALL).forGetter(Tick::part),
                    Tick::new)),
            ApiTypes.variant("count", Count.class, ApiTypes.record(Count.class, null, Count::new)),
            ApiTypes.variant("clear", Clear.class, ApiTypes.record(Clear.class, null, Clear::new)),
            ApiTypes.variant("park", Park.class, PARK),
            ApiTypes.variant("unpark", Unpark.class, UNPARK));

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
                    Cleared::new)),
            ApiTypes.variant("park", Parked.class, ApiTypes.record(Parked.class, null,
                    ApiField.field("bot", Responses.BOT_REF).required().forGetter(Parked::bot),
                    ApiField.field("parked", ApiTypes.bool()).required().forGetter(Parked::parked),
                    Parked::new)));

    public static final MethodSpec<BotsOp, BotsReply> BOTS = MethodSpec.of("mc.test.bots", BOTS_PARAMS, BOTS_REPLY,
                    "The tick list every server-side bot is advanced from. tick advances them now, within the call, as "
                            + "many times as asked, or one bot alone, or only its body or its brain; count answers how many are on it "
                            + "and how many are busy; clear removes every bot but self; park takes a bot off the list "
                            + "and unpark puts it back. Server thread only.")
            .hidden().effects(MethodSpec.Effect.BODY_TASK);

    // ------------------------------------------------------------------ input

    /**
     * The keys a bot holds, each kept until the next press changes it; an omitted one stays as it is.
     * {@code forward} and {@code left} are the movement impulses, -1 to 1. {@code attack} holds the dig on the cell
     * the bot looks at; {@code use} holds the item in hand. {@code aimAt} turns the bot to a block's centre at once;
     * {@code toolFor} puts the best tool for that block in hand. {@code hit} strikes an entity once through the
     * body's own attack, which the attack verbs build on: no reach test, no footing guard and no swing of its own.
     */
    public record Press(String bot, Optional<Double> forward, Optional<Double> left, Optional<Boolean> jump,
                        Optional<Boolean> sneak, Optional<Boolean> sprint, Optional<Boolean> attack,
                        Optional<Boolean> use, Optional<BlockPosition> aimAt, Optional<BlockPosition> toolFor,
                        Optional<Integer> hit) {
        public static Press of(String bot) {
            return new Press(bot, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }
        public Press move(double left, double forward) {
            return new Press(bot, Optional.of(forward), Optional.of(left), jump, sneak, sprint, attack, use, aimAt, toolFor, hit);
        }
        public Press jump(boolean v) {
            return new Press(bot, forward, left, Optional.of(v), sneak, sprint, attack, use, aimAt, toolFor, hit);
        }
        public Press sneak(boolean v) {
            return new Press(bot, forward, left, jump, Optional.of(v), sprint, attack, use, aimAt, toolFor, hit);
        }
        public Press sprint(boolean v) {
            return new Press(bot, forward, left, jump, sneak, Optional.of(v), attack, use, aimAt, toolFor, hit);
        }
        public Press attack(boolean v) {
            return new Press(bot, forward, left, jump, sneak, sprint, Optional.of(v), use, aimAt, toolFor, hit);
        }
        public Press use(boolean v) {
            return new Press(bot, forward, left, jump, sneak, sprint, attack, Optional.of(v), aimAt, toolFor, hit);
        }
        public Press aimAt(BlockPosition cell) {
            return new Press(bot, forward, left, jump, sneak, sprint, attack, use, Optional.of(cell), toolFor, hit);
        }
        public Press toolFor(BlockPosition cell) {
            return new Press(bot, forward, left, jump, sneak, sprint, attack, use, aimAt, Optional.of(cell), hit);
        }
        public Press hit(int entityId) {
            return new Press(bot, forward, left, jump, sneak, sprint, attack, use, aimAt, toolFor, Optional.of(entityId));
        }
        /** Every key let go. */
        public Press release() {
            return new Press(bot, Optional.of(0.0), Optional.of(0.0), Optional.of(false), Optional.of(false),
                    Optional.of(false), Optional.of(false), Optional.of(false), aimAt, toolFor, Optional.empty());
        }
    }

    /** What the bot's body reports holding after the press. */
    public record Pressed(String bot, TestInputContracts.HeldKeys held) {}

    private static final ApiType<Double> IMPULSE = ApiTypes.number(-1, 1);

    public static final RecordType<Press> PRESS = ApiTypes.record(Press.class, "TestPress",
            ApiField.field("bot", Responses.BOT_REF).required().forGetter(Press::bot),
            ApiField.field("forward", IMPULSE).optional().forGetter(Press::forward),
            ApiField.field("left", IMPULSE).optional().forGetter(Press::left),
            ApiField.field("jump", ApiTypes.bool()).optional().forGetter(Press::jump),
            ApiField.field("sneak", ApiTypes.bool()).optional().forGetter(Press::sneak),
            ApiField.field("sprint", ApiTypes.bool()).optional().forGetter(Press::sprint),
            ApiField.field("attack", ApiTypes.bool()).optional().forGetter(Press::attack),
            ApiField.field("use", ApiTypes.bool()).optional().forGetter(Press::use),
            ApiField.field("aimAt", CommonTypes.BLOCK_POSITION).optional().forGetter(Press::aimAt),
            ApiField.field("toolFor", CommonTypes.BLOCK_POSITION).optional().forGetter(Press::toolFor),
            ApiField.field("hit", ApiTypes.integer(Integer.MIN_VALUE, Integer.MAX_VALUE))
                    .describe("An entity id in the bot's level, struck once: no reach test, no footing guard, no swing.")
                    .optional().forGetter(Press::hit),
            Press::new);

    public static final RecordType<Pressed> PRESSED = ApiTypes.record(Pressed.class, "TestPressed",
            ApiField.field("bot", Responses.BOT_REF).required().forGetter(Pressed::bot),
            ApiField.field("held", TestInputContracts.HELD_KEYS).required().forGetter(Pressed::held),
            Pressed::new);

    public static final MethodSpec<Press, Pressed> PRESS_KEYS = MethodSpec.of("mc.test.input.press", PRESS, PRESSED,
                    "Hold or let go of a bot's keys, as a player at the keyboard would; each stays until the next press "
                            + "changes it. Moves nothing by itself: the body moves when it is ticked. A server-side bot "
                            + "on the server thread, answering within the call; self on the client thread, answering "
                            + "once it has run there.")
            .hidden().effects(MethodSpec.Effect.BODY_TASK).mayWait();
}
