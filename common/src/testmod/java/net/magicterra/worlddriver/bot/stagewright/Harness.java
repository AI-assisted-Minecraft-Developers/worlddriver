package net.magicterra.worlddriver.bot.stagewright;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import net.magicterra.stagewright.contract.SceneFailure;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.worlddriver.api.game.WorldDriver;
import net.magicterra.worlddriver.api.methods.test.TestHarnessApi;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Baseline;
import net.magicterra.worlddriver.api.methods.test.TestHarnessStubApi;
import net.magicterra.worlddriver.api.registry.ApiRegistry;

/**
 * A scene's hold on the settings and the bot ticks, through StageWright's {@code mc.test.settings} and
 * {@code mc.test.bots}. Every call answers within itself, so these return the answer and the scene goes on
 * in the same tick, as it did when it wrote the settings and ticked the bots directly.
 */
public final class Harness {
    private Harness() {}

    private static TestHarnessStubApi api() {
        ApiRegistry registry = WorldDriver.registry();
        if (registry == null) throw new SceneFailure("the driver is not up: no registry to call mc.test.* through");
        return TestHarnessApi.of(registry);
    }

    /** The answer of a call that answers within itself. */
    static <R> R now(CompletionStage<R> stage) {
        CompletableFuture<R> f = stage.toCompletableFuture();
        if (!f.isDone()) throw new SceneFailure("a harness call did not answer within the call");
        return ApiRegistry.await(f, 0);
    }

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
    public static Pin pin() {
        int id = pinId(Baseline.TEST, false);
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
    }

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
        return ((TestHarnessContracts.Ticked) now(api().bots(new TestHarnessContracts.Tick(ticks, false)))).busy();
    }

    /** Advance every ticking bot until none is busy or {@code maxTicks} ran out; the ticks it took. */
    public static int tickUntilIdle(int maxTicks) {
        return ((TestHarnessContracts.Ticked) now(api().bots(new TestHarnessContracts.Tick(maxTicks, true)))).ticks();
    }

    /** How many bots are on a task. */
    public static int busy() {
        return ((TestHarnessContracts.Counted) now(api().bots(new TestHarnessContracts.Count()))).busy();
    }

    /** How many bots are on the tick list, idle or not. */
    public static int ticking() {
        return ((TestHarnessContracts.Counted) now(api().bots(new TestHarnessContracts.Count()))).ticking();
    }

    /** Take every bot off the tick list. */
    public static void clearBots() {
        now(api().bots(new TestHarnessContracts.Clear()));
    }

    /** Take every bot off the tick list now and again when the scene ends. */
    public static void clearBots(SceneContext ctx) {
        clearBots();
        ctx.cleanup(Harness::clearBots);
    }
}
