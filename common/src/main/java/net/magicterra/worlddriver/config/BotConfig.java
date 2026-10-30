package net.magicterra.worlddriver.config;

import static net.magicterra.worlddriver.config.Mod.LOG;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.magicterra.worlddriver.api.methods.setting.BotSettings;

/**
 * Mutable tuning values exposed via {@code mc.bot.setting}: the facade's own here, every other layer's in its own class
 * ({@link #CLASSES}), all enumerated, persisted and pinned from here. Read every tick — no caching of the previous
 * value elsewhere, so a write applies immediately to the next tick.
 *
 * <p>Volatile to make cross-thread writes (RPC handler thread → client tick thread) visible without synchronization.
 * Range bounds are enforced in {@link SettingsRegistry} so this class doesn't need its own validation.
 */
public final class BotConfig {
    private BotConfig() {}

    /**
     * Baritone-style survival toggles — each tick the bot driver checks these and may inject a single client-side
     * action (hold the use intent for autoEat, call player.respawn() for autoRespawn). Off by default so a quiet bot
     * stays quiet.
     */
    public static volatile boolean autoEat = BotSettings.DEFAULTS.autoEat();

    /**
     * Click the Respawn button (via player.respawn()) the moment a DeathScreen is shown. Without this, a dead bot sits
     * on the death overlay until a human intervenes.
     */
    public static volatile boolean autoRespawn = BotSettings.DEFAULTS.autoRespawn();

    /**
     * Phase B hand/equipment reflexes (ambient, run concurrently with movement, arbitrating the use key shield > heal >
     * eat). All off by default so a quiet bot stays quiet.
     */
    public static volatile boolean autoTotem = BotSettings.DEFAULTS.autoTotem(); // keep a totem in the offhand

    public static volatile boolean autoShield = BotSettings.DEFAULTS.autoShield(); // raise shield vs incoming
    public static volatile boolean autoHeal = BotSettings.DEFAULTS.autoHeal(); // drink/eat to heal when low

    /**
     * Phase F T0 reflex: while the combat chain is engaged, ensure the best armor is worn and the best weapon is in
     * hand (sibling of {@link #autoTool}). Off by default; the explicit {@code mc.bot.equip} verb works regardless.
     */
    public static volatile boolean autoEquip = BotSettings.DEFAULTS.autoEquip();

    /**
     * Equipped items below this remaining-durability fraction (0–1) are flagged in {@code mc.bot.equip}'s
     * {@code lowDurability} report so the agent can go repair / swap before they break mid-fight. Default 0.1 (10%).
     */
    public static volatile double equipDurabilityThreshold = BotSettings.DEFAULTS.equipDurabilityThreshold();

    /**
     * Hold jump while fully submerged so the bot floats toward the surface instead of drowning. Released the moment
     * head is in air. Yields to active processes that own keyJump (mine/build BREAKING/PLACING).
     */
    public static volatile boolean autoSwim = BotSettings.DEFAULTS.autoSwim();
    /**
     * Per-tick slice (ms) of a route PREVIEW ({@code PreviewSearch}): it runs beside a walk and must not steal its
     * frames.
     */
    public static volatile long pathfinderPreviewSliceMs = BotSettings.DEFAULTS.pathfinderPreviewSliceMs();
    /**
     * Most sight rays one search may fire for {@code route.sight}; past it the search reruns without sight rather than
     * pricing the rest as exposed — {@code SightExposure} says why.
     */
    public static volatile int sightRaysPerSearch = BotSettings.DEFAULTS.sightRaysPerSearch();

    /**
     * Y plane targeted by {@code mc.bot.goto{axis:true}} — Baritone's {@code axisHeight} setting (default 120, the
     * classic "highway" Y). Read when an Axis goal is constructed.
     */
    public static volatile int axisHeight = BotSettings.DEFAULTS.axisHeight();

    /**
     * Event types muted from the live PUSH channel via {@code mc.bot.setting{mutedEvents:[type,...]}}. By default EVERY
     * driver event pushes to the MCP/WS channel; listing a type here suppresses ONLY its push — the event is still
     * recorded and retrievable via mc.wait.event / replay. Empty = push everything. Whole-list replace; pass [] to
     * clear. Persisted across relaunch.
     */
    public static volatile Set<String> mutedEvents = Set.copyOf(BotSettings.DEFAULTS.mutedEvents());

    /**
     * Entity type ids the bot stack never takes over: an order or {@code /worlddriver mob} naming one is refused. The
     * way out for a modded mob that misbehaves when driven. Whole-list replace.
     */
    public static volatile Set<String> undrivableMobs = Set.copyOf(BotSettings.DEFAULTS.undrivableMobs());

    /**
     * Baritone {@code autoTool} analogue — when on, the bot swaps to the best hotbar tool whenever the player crosshair
     * points at a breakable block (and no process or chain drives the bot). Default off so scripted hotbar layouts
     * aren't fighting the bot for the selected slot. Read every clientTick via maybeAutoTool.
     */
    public static volatile boolean autoTool = BotSettings.DEFAULTS.autoTool();

    /**
     * Baritone {@code BackfillProcess} analogue — when on, the bot tracks the cells its own digs broke (never air it
     * only walked through) and refills them with {@link BehaviorConfig#autoBackfillBlock} whenever no other process
     * owns the keys. Seals mine tunnels behind a {@code mc.bot.mine} pass. Off by default.
     */
    public static volatile boolean autoBackfill = BotSettings.DEFAULTS.autoBackfill();
    /** Side of the travel direction an even-width tunnel puts its extra column on: left or right. */
    public static volatile String tunnelWidthBias = BotSettings.DEFAULTS.tunnelWidthBias();

    /**
     * gap#68-⑧: ticks after a respawn during which autoFight does NOT re-engage and autoBackfill does NOT auto-start —
     * a freshly-respawned naked bot must not resume lethal intents (combat kept hunting / orphan digging executed
     * post-respawn).
     */
    public static volatile int respawnGraceTicks = BotSettings.DEFAULTS.respawnGraceTicks();

    /**
     * Baritone mob-avoidance analogue. When on, a goto/follow whose {@code route} names neither {@code mobs} nor
     * {@code risk} gets a default {@code MobCluster} berth from the three settings below ({@code RouteParams.parse}
     * expands it, so both bots price mobs once, the same way). Off by default: it changes pathing noticeably.
     */
    public static volatile boolean avoidMobs = BotSettings.DEFAULTS.avoidMobs();
    /**
     * Default {@code route.mobs.radius}: a melee mob's berth; cost ramps from {@link #mobAvoidPenalty} at the mob to 0
     * at the edge.
     */
    public static volatile double mobAvoidRadius = BotSettings.DEFAULTS.mobAvoidRadius();
    /** Default {@code route.mobs.penalty}: one mob's peak cost, at zero distance. */
    public static volatile double mobAvoidPenalty = BotSettings.DEFAULTS.mobAvoidPenalty();
    /**
     * Default {@code route.mobs.rangedRadius}: the wider berth of a mob that shoots (skeleton/witch), AltoClef's ranged
     * split.
     */
    public static volatile int rangedAvoidRadius = BotSettings.DEFAULTS.rangedAvoidRadius();
    /** Cap on stored A* candidate nodes per search (reservoir-downsampled above this). */
    public static volatile int pathDebugMaxNodes = BotSettings.DEFAULTS.pathDebugMaxNodes();
    /** Cap on stored per-tick trajectory samples (ring buffer; oldest dropped). */
    public static volatile int pathDebugMaxSamples = BotSettings.DEFAULTS.pathDebugMaxSamples();
    /** When true, auto-write a chart on every goto terminal outcome (success and failure). */
    public static volatile boolean pathChartAutoDump = BotSettings.DEFAULTS.pathChartAutoDump();

    /**
     * {@code mc.observe.scene} radius clamp (blocks). Requests larger than this are truncated with a truncated:true
     * report. Default 32.
     */
    public static volatile int sceneQueryMaxRadius = BotSettings.DEFAULTS.sceneQueryMaxRadius();

    // ===================== reflection over the persistable set =====================
    // The file itself is BotConfigFile; what lives here is shared with snapshotAll/restoreAll.

    /**
     * Every class that declares settings, the facade's first and then each layer down: the order every enumeration
     * below walks. A setting sits in the lowest layer that reads it, so a layer reads only its own class and the ones
     * below.
     */
    public static final List<Class<?>> CLASSES = List.of(
            BotConfig.class,
            ArbitrationConfig.class,
            BehaviorConfig.class,
            ControlConfig.class,
            PlanningConfig.class,
            RulesConfig.class,
            WorldConfig.class,
            GameConfig.class);

    /**
     * Initializes this class, which captures {@link #COMPILED_DEFAULTS}. Every other config class calls it from a
     * static block after its own fields, so the defaults are captured before any setting anywhere can be written,
     * whichever class is touched first.
     */
    static void initialize() {}

    /** The public field {@code name} of whichever class declares it. */
    public static Field field(String name) throws NoSuchFieldException {
        for (Class<?> c : CLASSES) {
            try {
                return c.getField(name);
            } catch (NoSuchFieldException ignored) {
                // the next layer down
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** The public fields of every config class, class by class in {@link #CLASSES} order. */
    public static List<Field> publicFields() {
        List<Field> out = new ArrayList<>();
        for (Class<?> c : CLASSES) {
            out.addAll(List.of(c.getFields()));
        }
        return out;
    }

    /**
     * Serialize a field's current value to the persisted string form (the Set is comma-joined; scalars via
     * {@code String.valueOf}). Returns {@code null} for a null value so save can skip it. The ONE encoder shared by
     * {@link BotConfigFile#save()} and {@link #COMPILED_DEFAULTS} capture, so a value and its shadow default are
     * byte-for-byte comparable.
     */
    static String serialize(Field f) throws IllegalAccessException {
        Object v = f.get(null);
        if (v == null) {
            return null;
        }
        if (v instanceof Set<?> set) {
            // ADVISORY (task#93 review): Set.of(...) iteration order is SALTED per JVM run,
            // so a NON-EMPTY compiled Set default would serialize differently each boot and
            // false-flag the value-vs-current-default comparisons (stale-snapshot check +
            // legacy drift WARN) into churn. All persistable Set defaults are empty today
            // (SALT-immune, "" either way). If a Set default ever becomes non-empty, sort
            // the elements here (or compare as parsed sets) before relying on text equality.
            StringBuilder sb = new StringBuilder();
            for (Object o : set) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(o);
            }
            return sb.toString();
        }
        return String.valueOf(v);
    }

    /**
     * The compiled-in default of every persistable field, captured at class initialization — which completes BEFORE
     * {@link BotConfigFile#load()} (a method call) can apply any persisted value, so these are the true defaults baked
     * into THIS build. The shadow-default scheme leans on this map twice: {@link BotConfigFile#save()} writes it as the
     * {@code <key>.default} line, and {@link BotConfigFile#load()} re-saves a file whose stored snapshot no longer
     * matches the current compiled default. Assigned in a static block at the very END of the class so every field it
     * reads (all the volatiles above) is already class-initialized.
     */
    static final Map<String, String> COMPILED_DEFAULTS;

    private static Map<String, String> captureCompiledDefaults() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Field f : persistableFields()) {
            try {
                String s = serialize(f);
                if (s != null) {
                    m.put(f.getName(), s);
                }
            } catch (Exception ignored) {
                /* own field — cannot happen */
            }
        }
        return m;
    }

    /**
     * Reset every persistable field to THIS build's compiled default — the live truth stack. StageWright scenes whose
     * contract is live behaviour (not the §78 legacy arena baseline) call this right after {@link #pinnedBaseline()}:
     * the pin still restores the pre-scene state on close; this only changes what the scene body runs. Scenes must
     * re-assert any flag they need OFF (allowBreak / allowPlace default ON live).
     */
    public static synchronized void applyCompiledDefaults() {
        for (Field f : persistableFields()) {
            String s = COMPILED_DEFAULTS.get(f.getName());
            if (s == null) {
                continue;
            }
            try {
                assign(f, s);
            } catch (Exception ex) {
                LOG.warn("[config] default reset skip {}: {}", f.getName(), ex.toString());
            }
        }
    }

    /**
     * A static, non-final field of a scalar type (or the hazard-block Set) — the set we round-trip. Arrays (avoidZones)
     * and anything else are excluded.
     */
    private static boolean persistable(Field f) {
        int m = f.getModifiers();
        if (!Modifier.isStatic(m) || Modifier.isFinal(m)) {
            return false;
        }
        Class<?> t = f.getType();
        return t == boolean.class
                || t == int.class
                || t == long.class
                || t == double.class
                || t == float.class
                || t == String.class
                || t == Set.class;
    }

    /**
     * The {@link #persistable} fields, class by class in {@link #CLASSES} order and each in declaration order — the ONE
     * enumeration save/load/snapshotAll/restoreAll all iterate, so the persisted set and the snapshot set can't drift
     * apart.
     */
    static List<Field> persistableFields() {
        List<Field> out = new ArrayList<>();
        for (Class<?> c : CLASSES) {
            for (Field f : c.getDeclaredFields()) {
                if (persistable(f)) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    static void assign(Field f, String s) throws IllegalAccessException {
        Class<?> t = f.getType();
        if (t == boolean.class) {
            f.setBoolean(null, Boolean.parseBoolean(s.trim()));
        } else if (t == int.class) {
            f.setInt(null, Integer.parseInt(s.trim()));
        } else if (t == long.class) {
            f.setLong(null, Long.parseLong(s.trim()));
        } else if (t == double.class) {
            f.setDouble(null, Double.parseDouble(s.trim()));
        } else if (t == float.class) {
            f.setFloat(null, Float.parseFloat(s.trim()));
        } else if (t == String.class) {
            f.set(null, s);
        } else if (t == Set.class) {
            Set<String> set = new LinkedHashSet<>();
            for (String part : s.split(",")) {
                part = part.trim();
                if (!part.isEmpty()) {
                    set.add(part);
                }
            }
            f.set(null, Set.copyOf(set));
        }
    }

    /**
     * Snapshot every mutable config key (the {@link #persistable} set — all navigator/pathfinder flags included). Rig
     * guard for tests that flip config beyond a handful of named keys — especially anything calling
     * {@link #applyGameTestBaseline} mid-suite: pair with {@link #restoreAll} in a finally so a {@code /test} run on an
     * integrated server can't leak the legacy-OFF baseline into the live bot.
     */
    public static synchronized Map<String, Object> snapshotAll() {
        Map<String, Object> snap = new LinkedHashMap<>();
        try {
            for (Field f : persistableFields()) {
                snap.put(f.getName(), f.get(null));
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e); // own accessible fields — cannot happen
        }
        return snap;
    }

    /** Restore a {@link #snapshotAll} snapshot verbatim. */
    public static synchronized void restoreAll(Map<String, Object> snap) {
        try {
            for (Field f : persistableFields()) {
                if (snap.containsKey(f.getName())) {
                    f.set(null, snap.get(f.getName()));
                }
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Closeable snapshot+restore pair — {@code close()} is {@link #restoreAll}. */
    public interface ConfigPin extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * The gametest rig template in one unlosable piece: snapshot EVERY mutable config key, re-pin
     * {@link #applyGameTestBaseline}, and hand back the restore as an {@link AutoCloseable} —
     *
     * <pre>try (var pin = BotConfig.pinnedBaseline()) { … arena body … }</pre>
     *
     * Arenas whose metrics are config-sensitive must use this instead of hand-rolling the three steps: restoring only a
     * few named keys after flipping the whole baseline is exactly the leak that made descentYawArena "flaky" for sixty
     * days (a {@code /test} run on an integrated server left the live bot on the legacy-OFF baseline).
     */
    public static ConfigPin pinnedBaseline() {
        Map<String, Object> snap = snapshotAll();
        applyGameTestBaseline();
        return () -> restoreAll(snap);
    }

    /**
     * GameTest legacy baseline (§78): the arena suite's assertions were authored against the historical default-OFF
     * flag set; when the #47-validated combo was flipped to default-ON for live play, 14 required arenas broke because
     * flags the tests never touch (allowBreak/allowPlace/DrowningEscape/...) changed the bot's behavior mid-arena.
     * Nothing calls this at server start any more: in-game tests pin it per test through StageWright's
     * {@code mc.test.settings} (the testmod kit's {@code Settings.pin}), unit tests through {@link #pinnedBaseline()},
     * and a test that WANTS a flag still sets it explicitly. Live clients never call this.
     */
    public static void applyGameTestBaseline() {
        WorldConfig.allowBreak = false;
        WorldConfig.allowPlace = false;
        WorldConfig.allowWaterBucketFall = false;
        ControlConfig.navigatorStepUpBackoffRetry = false;
        ControlConfig.navigatorCarrotBodyLos = false;
        ControlConfig.navigatorBankDigGroundBlip = false;
        ControlConfig.navigatorExpectAlarm = false;
        ControlConfig.navigatorStuckStepMonotonic = false;
        ControlConfig.navigatorPillarSurfacePlace = false;
        ControlConfig.navigatorAboveNodeStallRecover = false;
        ControlConfig.navigatorDigAimPriority = false;
        ControlConfig.navigatorWallDigFallback = false;
        WorldConfig.pathfinderBreakCostMultiplier = 1.0;
        ControlConfig.navigatorDryReanchor = false;
        ControlConfig.navigatorBuoyantSearchFromSurface = false;
        ControlConfig.navigatorBankDigSkipWhenCwpSwims = false;
        ControlConfig.navigatorBankDigSkipOverhang = false;
        ControlConfig.navigatorVineDescentDrop = false;
        ControlConfig.navigatorAscentRamBobBreak = false;
        ControlConfig.navigatorPillarReachGoalNoSnap = false;
        RulesConfig.pathfinderForbidParkourFromFloatingWater = false;
        ControlConfig.navigatorDeepWaterFloatBeeline = false;
        ControlConfig.navigatorWaterWalkReach = false;
        ControlConfig.navigatorWaterStepDownFloat = false;
        ControlConfig.navigatorWallCornerFastChurn = false;
        ControlConfig.navigatorSwimAshorePillarDespiteDeepDig = false;
        ControlConfig.navigatorFootholdBeforeBankDig = false;
        ControlConfig.navigatorClimbIntentFromSurface = false;
        ControlConfig.navigatorPillarTopsOutAtFlushExit = false;
        ControlConfig.navigatorFinalNodeDirectAim = false;
        ControlConfig.navigatorHoldLastNodeUntilStanding = false;
        ControlConfig.navigatorShallowWaterSideFoothold = false;
        ControlConfig.navigatorClimbOutResyncsAim = false;
        ControlConfig.navigatorOrbitBreaksAimLag = false;
        ControlConfig.navigatorSurfaceSprintSwim = false;
        ControlConfig.navigatorFutileBankDigRelease = false;
        ControlConfig.navigatorBankDigForwardExit = false;
        ControlConfig.navigatorFloatingBankBobFreeze = false;
        ControlConfig.navigatorDrowningEscape = false;
        ControlConfig.navigatorClimbGaveUpSticky = false;
        // §87 second flip wave (C100+C101 double-green endorsement)
        ControlConfig.navigatorRouteHysteresis = false;
        ControlConfig.navigatorDigCommitHoldRepath = false;
        ControlConfig.navigatorRamNodeAimRelease = false;
        ControlConfig.navigatorPhysicalStallClock = false;
        ControlConfig.navigatorBridgeHoldRepath = false;
        WorldConfig.pathfinderFloatingBreakTax = false;
        WorldConfig.pathfinderLogBreakTax = 1.0;
    }

    // Capture the compiled-in defaults LAST — after every persistable volatile is
    // initialized, and (being class init) before load() runs.
    static {
        COMPILED_DEFAULTS = captureCompiledDefaults();
    }
}
