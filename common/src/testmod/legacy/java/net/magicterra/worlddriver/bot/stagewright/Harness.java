package net.magicterra.worlddriver.bot.stagewright;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.worlddriver.api.methods.test.TestHarnessApi;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Baseline;
import net.magicterra.worlddriver.api.methods.test.TestHarnessStubApi;
import net.magicterra.worlddriver.api.methods.test.TestInputContracts;

/**
 * A scene's hold on the settings and the bot ticks, through StageWright's {@code mc.test.settings} and
 * {@code mc.test.bots}. Every call answers within itself, so these return the answer and the scene goes on
 * in the same tick, as it did when it wrote the settings and ticked the bots directly.
 */
public final class Harness {
    private Harness() {}

    private static TestHarnessStubApi api() { return TestHarnessApi.of(Calls.api()); }

    private static <R> R now(CompletionStage<R> stage) { return Calls.now(stage); }

    // ---------------------------------------------------------------- settings

    /** Save every setting and put the test baseline in place; the scene's cleanup restores the saved ones. */
    public static void pin(SceneContext ctx) { pin(ctx, Baseline.TEST, false); }

    /** {@link #pin(SceneContext)} with the planner's wall-clock budgets lifted, so it plans the same on any machine. */
    public static void pinUntimed(SceneContext ctx) { pin(ctx, Baseline.TEST, true); }

    /** Lift the planner's wall-clock budgets until the scene ends, leaving every other setting as it is. */
    public static void untimed(SceneContext ctx) { pin(ctx, Baseline.CURRENT, true); }

    /** Put this build's defaults in place until the scene ends; for a scene about the bot as it ships. */
    public static void compiledDefaults(SceneContext ctx) { pin(ctx, Baseline.COMPILED, false); }

    /** Save every setting and put {@code baseline} in place; the scene's cleanup restores the saved ones. */
    public static void pin(SceneContext ctx, Baseline baseline, boolean untimed) {
        int id = pinId(baseline, untimed);
        ctx.cleanup(() -> now(api().settings(new TestHarnessContracts.Unpin(id))));
    }

    /** Save every setting and put the test baseline in place; closing restores the saved ones. */
    public static Pin pin() { return held(Baseline.TEST); }

    /**
     * Save every setting as it is; closing puts them back. The way to hand back a value no write
     * accepts, such as the lifted budgets {@link #untimed} leaves in place.
     */
    public static Pin save() { return held(Baseline.CURRENT); }

    private static Pin held(Baseline baseline) {
        int id = pinId(baseline, false);
        return () -> now(api().settings(new TestHarnessContracts.Unpin(id)));
    }

    /** A pin held outside a scene's cleanup. */
    public interface Pin extends AutoCloseable {
        @Override void close();
    }

    private static int pinId(Baseline baseline, boolean untimed) {
        return ((TestHarnessContracts.Pinned) now(api().settings(new TestHarnessContracts.Pin(baseline, untimed)))).pin();
    }

    /** Write one setting by its key. */
    public static void set(String key, Object value) { set(Map.of(key, value)); }

    /** Write settings by key, checked together. */
    public static void set(Map<String, ?> values) {
        now(api().settings(TestHarnessContracts.Write.of(values)));
        // The navigator under test runs where self does; written there only under a pin, which puts them back.
        if (selfPinned) Calls.waitFor(TestHarnessApi.of(ClientRegistry.self()).settings(TestHarnessContracts.Write.of(values)));
    }

    /** Whether the scene running now holds a pin on the joined client's settings ({@link #pinSelf}). */
    private static volatile boolean selfPinned;

    /** What the setting {@code key} holds. */
    public static Object get(String key) {
        return ((TestHarnessContracts.Values) now(api().settings(new TestHarnessContracts.Read(java.util.List.of(key))))).get(key);
    }

    public static boolean flag(String key) { return (Boolean) get(key); }

    public static int integer(String key) { return ((Number) get(key)).intValue(); }

    public static long int64(String key) { return ((Number) get(key)).longValue(); }

    public static double number(String key) { return ((Number) get(key)).doubleValue(); }

    public static String text(String key) { return (String) get(key); }

    @SuppressWarnings("unchecked")
    public static java.util.List<String> list(String key) { return (java.util.List<String>) get(key); }

    // ---------------------------------------------------------------- bots

    /** Advance every ticking bot once. */
    public static void tick() { tick(1); }

    /** Advance every ticking bot {@code ticks} times; the bots still busy after. */
    public static int tick(int ticks) {
        return ticked(TestHarnessContracts.Tick.of(ticks)).busy();
    }

    /** Advance every ticking bot until none is busy or {@code maxTicks} ran out; the ticks it took. */
    public static int tickUntilIdle(int maxTicks) {
        return ticked(TestHarnessContracts.Tick.untilIdle(maxTicks)).ticks();
    }

    /** Advance {@code bot} alone {@code ticks} times, parked or not; the bots still busy after. */
    public static int tick(String bot, int ticks) {
        return ticked(TestHarnessContracts.Tick.bot(bot, ticks)).busy();
    }

    /** Advance {@code bot} alone until it is idle or {@code maxTicks} ran out; the ticks it took. */
    public static int tickUntilIdle(String bot, int maxTicks) {
        return ticked(new TestHarnessContracts.Tick(maxTicks, true, java.util.Optional.of(bot),
                TestHarnessContracts.Part.ALL)).ticks();
    }

    /** Step only {@code bot}'s body {@code ticks} times: its physics, with no perceiving, reflexes or task. */
    public static void step(String bot, int ticks) {
        ticked(TestHarnessContracts.Tick.body(bot, ticks));
    }

    /** Run only {@code bot}'s brain once: it leaves its input on the body, and nothing moves until {@link #step}. */
    public static void think(String bot) {
        ticked(TestHarnessContracts.Tick.brain(bot));
    }

    private static TestHarnessContracts.Ticked ticked(TestHarnessContracts.Tick tick) {
        return (TestHarnessContracts.Ticked) now(api().bots(tick));
    }

    /** How many bots are on a task. */
    public static int busy() {
        return ((TestHarnessContracts.Counted) now(api().bots(new TestHarnessContracts.Count()))).busy();
    }

    /** How many bots are on the tick list, idle or not. */
    public static int ticking() {
        return ((TestHarnessContracts.Counted) now(api().bots(new TestHarnessContracts.Count()))).ticking();
    }

    /** Remove every bot but self. */
    public static void clearBots() {
        now(api().bots(new TestHarnessContracts.Clear()));
    }

    /** Remove every bot but self now and again when the scene ends. */
    public static void clearBots(SceneContext ctx) {
        clearBots();
        ctx.cleanup(Harness::clearBots);
    }

    /** Take {@code bot} off the tick list: only {@link #tick(String, int)} and {@link #step} move it. */
    public static void park(String bot) {
        now(api().bots(new TestHarnessContracts.Park(bot)));
    }

    /** Put a parked {@code bot} back on the tick list. */
    public static void unpark(String bot) {
        now(api().bots(new TestHarnessContracts.Unpark(bot)));
    }

    // ---------------------------------------------------------------- input

    /** Hold or let go of {@code bot}'s keys; each stays until the next press changes it. */
    public static TestInputContracts.HeldKeys press(TestHarnessContracts.Press press) {
        return now(api().press(press)).held();
    }

    /**
     * {@link #press} for self, whose keys are pressed on the client thread: the answer lands there later, and the
     * scene's server thread must not wait for it.
     */
    public static CompletableFuture<TestHarnessContracts.Pressed> pressLater(TestHarnessContracts.Press press) {
        return TestHarnessApi.of(ClientRegistry.self()).press(press).toCompletableFuture();
    }

    /**
     * {@link #pin(SceneContext)} on the joined client's driver too, when self is a client in another JVM: its
     * navigator reads the settings of the JVM it runs in. Waits for the client's answer on this thread, which on
     * loopback is a few milliseconds; the cleanup's unpin is not waited for.
     */
    public static void pinSelf(SceneContext ctx) {
        pin(ctx);
        if (!ClientRegistry.remote()) return;
        TestHarnessStubApi client = TestHarnessApi.of(ClientRegistry.self());
        int id = ((TestHarnessContracts.Pinned) Calls.waitFor(client.settings(
                new TestHarnessContracts.Pin(Baseline.TEST, false)))).pin();
        selfPinned = true;
        ctx.cleanup(() -> {
            selfPinned = false;
            client.settings(new TestHarnessContracts.Unpin(id));
        });
    }

    /** The press that starts from nothing changed, for {@code bot}. */
    public static TestHarnessContracts.Press keys(String bot) { return TestHarnessContracts.Press.of(bot); }
}
