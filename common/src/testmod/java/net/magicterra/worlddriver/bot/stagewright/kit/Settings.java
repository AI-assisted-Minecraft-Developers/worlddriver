package net.magicterra.worlddriver.bot.stagewright.kit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.worlddriver.api.methods.test.TestHarnessBlockingApi;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Baseline;

/**
 * A test's hold on worlddriver's settings, through StageWright's {@code mc.test.settings}. The only way to write a
 * setting from a test: a {@link #pin} saves every setting, puts a baseline in place and hands back the handle whose
 * {@link #set} writes; the saved settings come back when the test ends, passed or not ({@link Game#afterTest}).
 *
 * <pre>{@code
 * Settings settings = Settings.pin(game);          // the test baseline the scenes were written against
 * settings.set("allowBreak", true).set("allowPlace", true);
 * }</pre>
 *
 * <p><b>Pin before driving a bot.</b> The old scene gates applied the test baseline once at server start, so every
 * scene ran under it; nothing applies it now. A test that drives worlddriver's bot ({@link SceneBot},
 * {@link ClientRig}) pins first — {@code ConfigPinDisciplineTest} checks it.
 *
 * <p><b>One pinned test at a time.</b> The settings are one set per JVM, and tests in a game run in parallel, so the
 * first pin of a test waits until no other test holds one, and lets go when the test ends. Every pinned test of a
 * scene therefore runs alone with respect to the others, as every scene did under the old gates. A test may pin as
 * often as it likes; only its first pin waits. Pin from the test's own thread, not inside {@code game.runOnServer}.
 *
 * <p>{@link #set} and {@link #get} may be called from the test thread or inline inside {@code game.runOnServer}: each
 * runs on the server thread, between two ticks.
 *
 * <p><b>The real client.</b> A navigator reads the settings of the JVM it runs in, so a test driving the client's
 * player ({@code self}) on a dedicated server pins the client too: {@link #mirrorTo} pins the same baseline there and
 * repeats every write. {@link ClientRig#adopt} does that for you.
 */
public final class Settings {

    /** Held by the test that pinned first, until it ends. */
    private static final Semaphore HELD = new Semaphore(1, true);

    /** The thread of the test holding {@link #HELD}, so its later pins do not wait for itself. */
    private static volatile Thread holder;

    private final Game game;
    private final Baseline baseline;
    private final boolean untimed;
    private final int pin;
    /** Every write through this handle, in order, so a mirror taken later starts from the same settings. */
    private final Map<String, Object> written = new LinkedHashMap<>();
    private final List<Mirror> mirrors = new ArrayList<>();
    private volatile boolean restored;

    private Settings(Game game, Baseline baseline, boolean untimed, int pin) {
        this.game = game;
        this.baseline = baseline;
        this.untimed = untimed;
        this.pin = pin;
    }

    // ---------------------------------------------------------------- pins

    /** Save every setting and put the test baseline in place: the settings the old scenes were written against. */
    public static Settings pin(Game game) {
        return pin(game, Baseline.TEST, false);
    }

    /** {@link #pin(Game)} with the planner's wall-clock budgets lifted, so it plans the same on any machine. */
    public static Settings pinUntimed(Game game) {
        return pin(game, Baseline.TEST, true);
    }

    /** Lift the planner's wall-clock budgets for the rest of the test, leaving every other setting as it is. */
    public static Settings untimed(Game game) {
        return pin(game, Baseline.CURRENT, true);
    }

    /** Put this build's defaults in place for the rest of the test; for a test about the bot as it ships. */
    public static Settings compiledDefaults(Game game) {
        return pin(game, Baseline.COMPILED, false);
    }

    /**
     * Save every setting as it is now, changing nothing; {@link #restore} puts them back before the test ends. The
     * way to hand back a value no write accepts, such as the lifted budgets {@link #untimed} leaves in place.
     */
    public static Settings save(Game game) {
        return pin(game, Baseline.CURRENT, false);
    }

    /** Save every setting and put {@code baseline} in place, the planner's budgets lifted when {@code untimed}. */
    public static Settings pin(Game game, Baseline baseline, boolean untimed) {
        hold(game);
        int id = game.onServer(server -> ((TestHarnessContracts.Pinned) harness(game)
                .settings(new TestHarnessContracts.Pin(baseline, untimed))).pin());
        Settings settings = new Settings(game, baseline, untimed, id);
        game.afterTest(server -> settings.restore());
        return settings;
    }

    /** Take {@link #HELD} for the calling test, once, and give it back when the test ends. */
    private static void hold(Game game) {
        Thread me = Thread.currentThread();
        if (holder == me) return;
        HELD.acquireUninterruptibly();
        holder = me;
        // Registered before the first unpin, so it runs after every unpin of the test (last registered runs first).
        game.afterTest(server -> {
            holder = null;
            HELD.release();
        });
    }

    /**
     * Put back what this pin saved, now rather than when the test ends; a second call does nothing. The mirrored
     * client's pin comes back too.
     */
    public void restore() {
        if (restored) return;
        restored = true;
        // A client's pin was taken after this one, so it goes back first. Not waited for on the server thread
        // beyond the call itself: the client answers from its own RPC thread, which never waits for this one.
        for (Mirror m : mirrors.reversed()) m.api().settings(new TestHarnessContracts.Unpin(m.pin()));
        game.runOnServer(server -> harness(game).settings(new TestHarnessContracts.Unpin(pin)));
    }

    /** A client this pin is mirrored to, and the pin it holds there. */
    private record Mirror(TestHarnessBlockingApi api, int pin) {}

    /**
     * Pin the same baseline in the real client's JVM, repeat every write made so far there, and repeat every later
     * one: the client's navigator reads the client's settings. On an integrated server the client shares this JVM,
     * and the second pin is a harmless nested one.
     */
    public Settings mirrorTo(Client client) {
        TestHarnessBlockingApi api = TestHarnessBlockingApi.of(ClientTestMethods.of(game, client));
        int id = ((TestHarnessContracts.Pinned) api.settings(new TestHarnessContracts.Pin(baseline, untimed))).pin();
        mirrors.add(new Mirror(api, id));
        if (!written.isEmpty()) api.settings(TestHarnessContracts.Write.of(written));
        return this;
    }

    // ---------------------------------------------------------------- writes and reads

    /** Write one setting by its key, checked as {@code mc.bot.setting} checks it. */
    public Settings set(String key, Object value) {
        return set(Map.of(key, value));
    }

    /** Write settings by key, checked together, in the map's order. */
    public Settings set(Map<String, ?> values) {
        if (restored) throw new IllegalStateException("this pin was restored; pin again to write settings");
        game.runOnServer(server -> harness(game).settings(TestHarnessContracts.Write.of(values)));
        written.putAll(values);
        for (Mirror m : mirrors) m.api().settings(TestHarnessContracts.Write.of(values));
        return this;
    }

    /** What the setting {@code key} holds in this JVM. */
    public Object get(String key) {
        return read(game, key);
    }

    public boolean flag(String key) { return (Boolean) get(key); }

    public int integer(String key) { return ((Number) get(key)).intValue(); }

    public long int64(String key) { return ((Number) get(key)).longValue(); }

    public double number(String key) { return ((Number) get(key)).doubleValue(); }

    public String text(String key) { return (String) get(key); }

    @SuppressWarnings("unchecked")
    public List<String> list(String key) { return (List<String>) get(key); }

    /** What the setting {@code key} holds; reading needs no pin. */
    public static Object read(Game game, String key) {
        return game.onServer(server -> ((TestHarnessContracts.Values) harness(game)
                .settings(new TestHarnessContracts.Read(List.of(key)))).get(key));
    }

    private static TestHarnessBlockingApi harness(Game game) {
        return game.api(TestHarnessBlockingApi::of);
    }
}
