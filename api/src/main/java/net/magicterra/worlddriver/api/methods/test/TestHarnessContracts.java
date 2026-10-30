package net.magicterra.worlddriver.api.methods.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.magicterra.worlddriver.api.methods.setting.SettingsContracts;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.Responses.BotRef;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.DecodeContext;
import net.magicterra.worlddriver.api.schema.KeyedValueType;
import net.magicterra.worlddriver.api.schema.KeyedValueType.Entry;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.RecordType;
import net.magicterra.worlddriver.api.schema.SelfChecked;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.schema.WireName;
import net.magicterra.worlddriver.api.schema.annotation.As;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.schema.annotation.Tag;
import net.magicterra.worlddriver.api.schema.annotation.Variant;

/**
 * The test harness's hold on the settings and the bots: {@code mc.test.settings} and {@code mc.test.bots}. StageWright
 * publishes them; the shapes live here so a scene calls them through {@link TestHarnessApi}. Each runs on the thread
 * that calls it and answers within the call, so a scene on the server thread reads the answer at once and goes on in
 * the same tick.
 */
public final class TestHarnessContracts {

    private static final KeyedValueType SETTING_VALUES = SettingsContracts.SETTING_VALUES;

    private static final KeyedValueType WRITE_VALUES = SettingsContracts.SETTING_VALUES.atLeast(1);

    private static final ApiType<List<String>> KEY_LIST =
            ApiTypes.list(ApiTypes.of(String.class, Key.class), SettingsContracts.TYPES.size());

    public static final UnionType<SettingsOp> SETTINGS_PARAMS = ApiTypes.union(SettingsOp.class);

    public static final UnionType<SettingsReply> SETTINGS_REPLY = ApiTypes.union(SettingsReply.class);

    public static final MethodSpec<SettingsOp, SettingsReply> SETTINGS = MethodSpec.of(
                    "mc.test.settings",
                    SETTINGS_PARAMS,
                    SETTINGS_REPLY,
                    "The settings for a scene, on the calling thread and never saved. pin saves every setting and puts "
                            + "baseline in place, answering a pin that unpin restores the saved settings with; write sets "
                            + "keys, checked as mc.bot.setting checks them; read answers keys.")
            .hidden()
            .effects(MethodSpec.Effect.CONFIG_WRITE);

    public static final UnionType<BotsOp> BOTS_PARAMS = ApiTypes.union(BotsOp.class);

    public static final UnionType<BotsReply> BOTS_REPLY = ApiTypes.union(BotsReply.class);

    public static final MethodSpec<BotsOp, BotsReply> BOTS = MethodSpec.of(
                    "mc.test.bots",
                    BOTS_PARAMS,
                    BOTS_REPLY,
                    "The tick list every server-side bot is advanced from. tick advances them now, within the call, as "
                            + "many times as asked, or one bot alone, or only its body or its brain; count answers how many are on it "
                            + "and how many are busy; clear removes every bot but self; park takes a bot off the list "
                            + "and unpark puts it back. Server thread only.")
            .hidden()
            .effects(MethodSpec.Effect.BODY_TASK);

    public static final RecordType<Press> PRESS = ApiTypes.record(Press.class);

    public static final RecordType<Pressed> PRESSED = ApiTypes.record(Pressed.class);

    public static final MethodSpec<Press, Pressed> PRESS_KEYS = MethodSpec.of(
                    "mc.test.input.press",
                    PRESS,
                    PRESSED,
                    "Hold or let go of a bot's keys, as a player at the keyboard would; each stays until the next press "
                            + "changes it. Moves nothing by itself: the body moves when it is ticked. A server-side bot "
                            + "on the server thread, answering within the call; self on the client thread, answering "
                            + "once it has run there.")
            .hidden()
            .effects(MethodSpec.Effect.BODY_TASK)
            .mayWait();

    private TestHarnessContracts() {}

    // ------------------------------------------------------------------ settings

    /** What a pin puts in place once it has saved the settings. */
    @Named("TestBaseline")
    public enum Baseline implements WireName {
        /** The settings the scenes were written against: the newer behaviours off, breaking and placing off. */
        TEST("test"),
        /** The defaults this build ships with. */
        COMPILED("compiled"),
        /** Nothing: the settings stay as they are. */
        CURRENT("current");

        private final String wire;

        Baseline(String wire) {
            this.wire = wire;
        }

        @Override
        public String wireName() {
            return wire;
        }
    }

    /** A setting's key, checked against the key table by the request that names it. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    @Length(min = 1, max = 64)
    public @interface Key {}

    /** What {@code mc.test.settings} does, chosen by {@code op}: pin, unpin, write or read. */
    @Tag("op")
    @Named("TestSettingsParams")
    public sealed interface SettingsOp permits Pin, Unpin, Write, Read {}

    /**
     * {@code untimed} also lifts the planner's wall-clock budgets ({@code pathfinder.maxMs} and
     * {@code pathfinder.sliceMs}) past what a user may set, so a search ends on its node budget alone and a scene plans
     * the same on a slow machine as on a fast one.
     */
    @Variant("pin")
    public record Pin(
            @Default("test") Baseline baseline,

            @Doc("Also lift the planner's wall-clock budgets.") @Default("false")
            boolean untimed)
            implements SettingsOp {
        /** A pin that puts {@code baseline} in place and keeps the planner's wall-clock budgets. */
        public static Pin of(Baseline baseline) {
            return new Pin(baseline, false);
        }
    }

    /** Restore the settings saved by the pin numbered {@code pin}. */
    @Variant("unpin")
    public record Unpin(@Range(min = 1) int pin) implements SettingsOp {}

    /** Set keys, each checked as {@code mc.bot.setting} checks it; a control key is refused. */
    @Variant("write")
    public record Write(
            @As(value = TestHarnessContracts.class, field = "WRITE_VALUES")
            List<Entry> values) implements SettingsOp, SelfChecked {
        /** Write {@code values}, in the map's iteration order. */
        public static Write of(Map<String, ?> values) {
            List<Entry> entries = new java.util.ArrayList<>();
            values.forEach((k, v) -> entries.add(new Entry(k, v)));
            return new Write(entries);
        }

        @Override
        public void check(DecodeContext at) {
            for (Entry e : values) {
                if (SettingsContracts.CONTROLS.contains(e.key())) {
                    at.field("values")
                            .field(e.key())
                            .fail(ApiIssue.Code.INVALID_VALUE, e.key() + " is a control, not a setting");
                }
            }
        }
    }

    /** Answer what each of {@code keys} holds; an unknown key is refused. */
    @Variant("read")
    public record Read(
            @As(value = TestHarnessContracts.class, field = "KEY_LIST")
            List<String> keys) implements SettingsOp, SelfChecked {
        @Override
        public void check(DecodeContext at) {
            for (int i = 0; i < keys.size(); i++) {
                String k = keys.get(i);
                if (!SettingsContracts.isKnown(k)) {
                    at.field("keys").index(i).fail(ApiIssue.Code.INVALID_VALUE, "unknown key \"" + k + "\"");
                }
            }
        }
    }

    /** The answer of {@code mc.test.settings}, tagged by {@code op} as the request was. */
    @Tag("op")
    @Named("TestSettingsReply")
    public sealed interface SettingsReply permits Pinned, Unpinned, Written, Values {}

    /** The pin's number, to pass to {@link Unpin} to restore the saved settings. */
    @Variant("pin")
    public record Pinned(@Range(min = 1) int pin) implements SettingsReply {}

    /** The pin whose saved settings were restored. */
    @Variant("unpin")
    public record Unpinned(@Range(min = 1) int pin) implements SettingsReply {}

    /** What each written key holds after the write. */
    @Variant("write")
    public record Written(
            @Doc("What each key holds after the write.")
            @As(value = TestHarnessContracts.class, field = "SETTING_VALUES")
            List<Entry> values)
            implements SettingsReply {}

    /** What each read key holds. */
    @Variant("read")
    public record Values(
            @As(value = TestHarnessContracts.class, field = "SETTING_VALUES")
            List<Entry> values) implements SettingsReply {
        /** What {@code key} holds. */
        public Object get(String key) {
            for (Entry e : values) {
                if (e.key().equals(key)) {
                    return e.value();
                }
            }
            throw new IllegalArgumentException(key + " was not read");
        }
    }

    // ------------------------------------------------------------------ bots

    /** What {@code mc.test.bots} does, chosen by {@code op}: tick, count, clear, park or unpark. */
    @Tag("op")
    @Named("TestBotsParams")
    public sealed interface BotsOp permits Tick, Count, Clear, Park, Unpark {}

    /** Which half of a bot's tick to run. */
    @Named("TestTickPart")
    public enum Part implements WireName {
        /** The brain, then the body's step. */
        ALL("all"),
        /** Only the body's physics step: no perceiving, reflexes or task. */
        BODY("body"),
        /** Only the brain: perceiving, reflexes and task leave their input on the body, and nothing moves. */
        BRAIN("brain");

        private final String wire;

        Part(String wire) {
            this.wire = wire;
        }

        @Override
        public String wireName() {
            return wire;
        }
    }

    /**
     * Advance the ticking bots {@code ticks} times now, stopping early once none is busy when {@code untilIdle}. With
     * {@code bot}, only that bot, parked or not, and only the {@code part} of its tick asked for.
     */
    @Variant("tick")
    public record Tick(
            @Default("1") @Range(min = 1, max = 100_000) int ticks,
            @Default("false") boolean untilIdle,
            @Doc("Only this bot, parked or not.") Optional<@BotRef String> bot,

            @Doc("all; body: only the physics step; brain: perceiving, reflexes and task, and no step, so a press can "
                    + "change the input before a body tick. body and brain need bot.")
            @Default("all")
            Part part)
            implements BotsOp {
        /** Advance every ticking bot once. */
        public static Tick once() {
            return new Tick(1, false, Optional.empty(), Part.ALL);
        }

        /** Advance every ticking bot {@code ticks} times. */
        public static Tick of(int ticks) {
            return new Tick(ticks, false, Optional.empty(), Part.ALL);
        }

        /** Advance every ticking bot up to {@code max} times, stopping early once none is busy. */
        public static Tick untilIdle(int max) {
            return new Tick(max, true, Optional.empty(), Part.ALL);
        }

        /** Advance only {@code bot}, parked or not, {@code ticks} times. */
        public static Tick bot(String bot, int ticks) {
            return new Tick(ticks, false, Optional.of(bot), Part.ALL);
        }

        /** Run only {@code bot}'s body physics step {@code ticks} times. */
        public static Tick body(String bot, int ticks) {
            return new Tick(ticks, false, Optional.of(bot), Part.BODY);
        }

        /** Run {@code bot}'s brain once, leaving its input on the body without moving it. */
        public static Tick brain(String bot) {
            return new Tick(1, false, Optional.of(bot), Part.BRAIN);
        }
    }

    /** Answer how many bots are on the tick list and how many are busy. */
    @Variant("count")
    public record Count() implements BotsOp {}

    /** Remove every bot but {@code self}: off the tick list and out of the roster, a player one discarded. */
    @Variant("clear")
    public record Clear() implements BotsOp {}

    /** Take {@code bot} off the tick list; it stays addressable, and only a {@code tick} naming it moves it. */
    @Variant("park")
    public record Park(@BotRef String bot) implements BotsOp {}

    /** Put a parked {@code bot} back on the tick list. */
    @Variant("unpark")
    public record Unpark(@BotRef String bot) implements BotsOp {}

    /** The answer of {@code mc.test.bots}, tagged by {@code op}; park and unpark both answer {@link Parked}. */
    @Tag("op")
    @Named("TestBotsReply")
    public sealed interface BotsReply permits Ticked, Counted, Cleared, Parked {}

    /** {@code ticks} it advanced; {@code busy}, the bots still on a task after it. */
    @Variant("tick")
    public record Ticked(
            @Range(min = 0) int ticks, @Range(min = 0) int busy) implements BotsReply {}

    /** {@code ticking}: the bots on the tick list; {@code busy}: those on a task. */
    @Variant("count")
    public record Counted(
            @Range(min = 0) int ticking, @Range(min = 0) int busy) implements BotsReply {}

    /** How many bots the clear removed. */
    @Variant("clear")
    public record Cleared(@Range(min = 0) int removed) implements BotsReply {}

    /** Whether {@code bot} is now off the tick list. */
    @Variant("park")
    public record Parked(@BotRef String bot, boolean parked) implements BotsReply {}

    // ------------------------------------------------------------------ input

    /**
     * The keys a bot holds, each kept until the next press changes it; an omitted one stays as it is. Except
     * {@code jump} out of water: like the navigator's, it is a press for one step, which a step clears. {@code forward}
     * and {@code left} are the movement impulses, -1 to 1. {@code attack} holds the dig on the cell the bot looks at;
     * {@code use} holds the item in hand. {@code aimAt} turns the bot to a block's centre at once; {@code toolFor} puts
     * the best tool for that block in hand. {@code hit} strikes an entity once through the body's own attack, which the
     * attack verbs build on: no reach test, no footing guard and no swing of its own.
     */
    @Named("TestPress")
    public record Press(
            @BotRef String bot,
            Optional<@Range(min = -1, max = 1) Double> forward,
            Optional<@Range(min = -1, max = 1) Double> left,
            Optional<Boolean> jump,
            Optional<Boolean> sneak,
            Optional<Boolean> sprint,
            Optional<Boolean> attack,
            Optional<Boolean> use,
            Optional<BlockPosition> aimAt,
            Optional<BlockPosition> toolFor,

            @Doc("An entity id in the bot's level, struck once: no reach test, no footing guard, no swing.")
            Optional<Integer> hit) {
        /** A press for {@code bot} that changes no key; chain the builders below to set some. */
        public static Press of(String bot) {
            return new Press(
                    bot,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
        }

        /** A copy that sets the movement impulses, each -1 to 1; note {@code left} comes first. */
        public Press move(double left, double forward) {
            return new Press(
                    bot,
                    Optional.of(forward),
                    Optional.of(left),
                    jump,
                    sneak,
                    sprint,
                    attack,
                    use,
                    aimAt,
                    toolFor,
                    hit);
        }

        /** A copy that holds or lets go of jump. */
        public Press jump(boolean v) {
            return new Press(bot, forward, left, Optional.of(v), sneak, sprint, attack, use, aimAt, toolFor, hit);
        }

        /** A copy that holds or lets go of sneak. */
        public Press sneak(boolean v) {
            return new Press(bot, forward, left, jump, Optional.of(v), sprint, attack, use, aimAt, toolFor, hit);
        }

        /** A copy that holds or lets go of sprint. */
        public Press sprint(boolean v) {
            return new Press(bot, forward, left, jump, sneak, Optional.of(v), attack, use, aimAt, toolFor, hit);
        }

        /** A copy that holds or lets go of the dig on the cell the bot looks at. */
        public Press attack(boolean v) {
            return new Press(bot, forward, left, jump, sneak, sprint, Optional.of(v), use, aimAt, toolFor, hit);
        }

        /** A copy that holds or lets go of using the item in hand. */
        public Press use(boolean v) {
            return new Press(bot, forward, left, jump, sneak, sprint, attack, Optional.of(v), aimAt, toolFor, hit);
        }

        /** A copy that turns the bot to {@code cell}'s centre at once. */
        public Press aimAt(BlockPosition cell) {
            return new Press(bot, forward, left, jump, sneak, sprint, attack, use, Optional.of(cell), toolFor, hit);
        }

        /** A copy that puts the best tool for the block at {@code cell} in hand. */
        public Press toolFor(BlockPosition cell) {
            return new Press(bot, forward, left, jump, sneak, sprint, attack, use, aimAt, Optional.of(cell), hit);
        }

        /** A copy that strikes the entity {@code entityId} once, with no reach test, footing guard or swing. */
        public Press hit(int entityId) {
            return new Press(
                    bot, forward, left, jump, sneak, sprint, attack, use, aimAt, toolFor, Optional.of(entityId));
        }

        /** Every key let go. */
        public Press release() {
            return new Press(
                    bot,
                    Optional.of(0.0),
                    Optional.of(0.0),
                    Optional.of(false),
                    Optional.of(false),
                    Optional.of(false),
                    Optional.of(false),
                    Optional.of(false),
                    aimAt,
                    toolFor,
                    Optional.empty());
        }
    }

    /** What the bot's body reports holding after the press. */
    @Named("TestPressed")
    public record Pressed(@BotRef String bot, TestInputContracts.HeldKeys held) {}
}
