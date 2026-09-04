package net.magicterra.worlddriver.bot;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every default-ON behaviour flag must have a recorded decision about whether the arena
 * baseline pins it off.
 *
 * <p><b>The failure this guards is not a skipped test — it is a rewritten subject.</b>
 * {@link BotConfig#applyGameTestBaseline()} runs once at server start under
 * {@code -Dstagewright.autorun} (WorldDriverFabric / WorldDriverNeoForge) and writes 38 fields,
 * the first two of which are {@code allowBreak = false} and {@code allowPlace = false}. So a
 * scene suite that is green over a behaviour proves nothing about a live client, and a live
 * client's behaviour proves nothing about the suite — the two run different bots. Nothing
 * announces this. There is no SKIP line, no warning, no absent scene; the assertions run, pass,
 * and describe a body that had two of its capabilities removed by the process measuring it.
 *
 * <p>2026-08-22 cost of the version of this with one flag: {@code walkerDigAimPriority} is pinned
 * off here, so two successive fixes to the dig-claim release were authored, landed and judged
 * against a branch that never executed on the ladder. The evidence was a zero —
 * {@code dig-aim RELEASE} appearing 0 times in 8022 ticks — which reads identically as "the guard
 * never expired" and "the guard was never entered".
 *
 * <p><b>What this test does NOT do</b> is check the baseline against its own assignment list.
 * That would be a threshold computed from the thing it measures: adding a line to
 * {@code applyGameTestBaseline} would move both sides together and stay green. The universe here
 * comes from reflection over {@link SettingsRegistry#reflectivePrimitiveFields()} filtered by
 * {@code BotConfig.COMPILED_DEFAULTS}, and what the baseline writes is measured by RUNNING it
 * against sentinel values — never by reading its source. A new flag, or a flag flipped from
 * default-OFF to default-ON, therefore lands in the universe without landing in the manifest,
 * and this goes red until somebody makes the decision and writes down why.
 *
 * <p>Default-OFF booleans are deliberately outside the universe: while a flag is off, arena and
 * client agree, so there is nothing to decide. The decision becomes due on the flip to ON — which
 * is exactly the event that adds it here.
 */
class GameTestBaselineManifestTest {

    // ------------------------------------------------------------------ the manifest
    //
    // PINNED — the fields applyGameTestBaseline() writes. Each was ON (or a live-tuned number)
    // for real play and is forced back to the historical value the arena assertions were authored
    // against. Three are capability permissions rather than behaviour tweaks; scenes that need
    // them turn them on themselves (see JourneyPortalEntryScenes' note about exactly this).

    private static final Map<String, String> PINNED = new TreeMap<>(Map.ofEntries(
            // --- capability permissions: not "a behaviour flipped later", a whole ability removed
            e("allowBreak", "permission: the pathfinder may mine obstructing blocks. OFF here, so any "
                    + "arena assertion about digging is about a body that cannot dig unless the scene says so"),
            e("allowPlace", "permission: the pathfinder may place a throwaway block to bridge a gap"),
            e("allowWaterBucketFall", "permission: the planner may route a fall taller than the dry cap "
                    + "because the body carries a water bucket"),

            // --- §78 flip wave: ON for live play after the arena assertions were written
            e("walkerStepUpBackoffRetry", "§78 — stepUp mount backoff-retry (#47 problem-6 grind)"),
            e("walkerCarrotBodyLos", "§78 — carrot LOS honest about body width (jungle-trunk friction)"),
            e("walkerBankDigGroundBlip", "§78 — bank-dig ground-blip immunity (underground-pool climb-out grind)"),
            e("walkerExpectAlarm", "§78 — actuator expectation alarms; changes what the log SAYS, "
                    + "which is why a scene reading log lines would see it"),
            e("walkerStuckStepMonotonic", "§78 — monotonic stuck-window (open-water step-jitter starvation)"),
            e("walkerPillarSurfacePlace", "§78 — water-surface pillar crest-place (pillarUp deadlock)"),
            e("walkerAboveNodeStallRecover", "§78 — climbed-past-the-node stall recovery (steep-mountain churn)"),
            e("walkerDigAimPriority", "§78 — dig aim priority, successor to walkerStickyDig. THE 2026-08-22 "
                    + "case: pinned off here, so the ladder ran neither implementation"),
            e("walkerWallDigFallback", "§78 — dry wall-pin dig fallback (§71)"),
            e("pathfinderBreakCostMultiplier", "§78 — dig-aversion multiplier on planner breakCost, "
                    + "back to the neutral 1.0 the arena costs were measured with"),
            e("walkerDryReanchor", "§78 — dry repath-rechurn breaker"),
            e("walkerBuoyantSearchFromSurface", "§78 — buoyant A* anchored at the water surface, not the bobbing foot"),
            e("walkerBankDigSkipWhenCwpSwims", "§78 — skip the bank-dig when the next committed node is water"),
            e("walkerBankDigSkipOverhang", "§78 — block-less bank-dig overhang rejection"),
            e("walkerVineDescentDrop", "§78 — release the vine cling when the next node is not a climb"),
            e("walkerAscentRamBobBreak", "§78 — bob-immune ascent-ram freeze-breaker"),
            e("walkerPillarReachGoalNoSnap", "§78 — don't snap an elevated air goal down when pillaring reaches it"),
            e("pathfinderForbidParkourFromFloatingWater", "§78 — takeoff complement of the deep-water parkour ban"),
            e("walkerDeepWaterFloatBeeline", "§78 — segment anchor-gate exemption for a deep-water-float start"),
            e("walkerWaterWalkReach", "§78 — relaxed step-advance for a water-surface walk"),
            e("walkerWaterStepDownFloat", "§78 — step-advance for a stepDown onto a shallow water-surface foothold"),
            e("walkerWallCornerFastChurn", "§78 — wall-corner fast-churn recovery"),
            e("walkerSwimAshorePillarDespiteDeepDig", "§78 — pillar-place fallback for a +2 bank the bank-dig skips"),
            e("walkerFootholdBeforeBankDig", "foothold first — a body holding a block pillars before it digs the bank"),
            e("walkerClimbIntentFromSurface", "climb-out intent read against the surface cell, not the bobbing foot"),
            e("walkerPillarTopsOutAtFlushExit", "the water pillar tops out only on a rung with a flush exit beside it"),
            e("walkerFinalNodeDirectAim", "the last path node is aimed at directly and approached at a walk"),
            e("walkerHoldLastNodeUntilStanding", "a last node that is the goal is spent only once the foot stands in it"),
            e("walkerShallowWaterSideFoothold", "afloat in one-deep water the pillar takeover places its first rung beside the body"),
            e("walkerClimbOutResyncsAim", "topping out of a water climb-out resets the aim's low-pass state"),
            e("walkerOrbitBreaksAimLag", "a sustained mid-range heading error on dry land switches the aim EMA to the cruise alpha"),
            e("walkerSurfaceSprintSwim", "open water is crossed in the prone sprint-swim pose"),
            e("walkerFutileBankDigRelease", "§78 — early-release a provably futile block-less bank dig"),
            e("walkerBankDigForwardExit", "§78 — forward-hemisphere guard on bank-dig riser selection"),
            e("walkerFloatingBankBobFreeze", "§78 — floating +1 water-bank climb-out freeze"),
            e("walkerDrowningEscape", "§78 — walker's own drowning-escape reflex (lethal climb-out deadlock)"),
            e("walkerClimbGaveUpSticky", "§78 — make the 'pillar gave up' latch survive a repath"),

            // --- §87 second flip wave (the baseline's own comment marks this boundary)
            e("walkerRouteHysteresis", "§87 — repath route-oscillation damper"),
            e("walkerDigCommitHoldRepath", "§87 — dig-commit repath hold (dig-vs-repath starvation)"),
            e("walkerRamNodeAimRelease", "§87 — ram-pinned node-aim release (§80)"),
            e("walkerPhysicalStallClock", "§87 — displacement-based stall clock feeding the stuck-gated recoveries"),
            e("walkerBridgeHoldRepath", "§87 — hold routine repaths while a bridgePlace edge is committed"),
            e("pathfinderFloatingBreakTax", "§87 — price a floating dig at ×25 instead of ×5"),
            e("pathfinderLogBreakTax", "§87 — trunk-aversion multiplier on log breakCost, back to neutral 1.0")));

    // LIVE — default-ON booleans the baseline deliberately leaves alone, so the arena runs them
    // exactly as a live client does. Listed so that "not pinned" is a recorded decision rather
    // than an absence. The reason each is here is the same one: it was already ON when the arena
    // assertions were authored, so the scenes were written against it. What differs per row is
    // what the flag does, which is what a reader needs in order to judge whether a NEW flag
    // belongs beside it or in PINNED.

    private static final Map<String, String> LIVE = new TreeMap<>(Map.ofEntries(
            // survival reflexes and safety gates — a scene that removed these would be testing
            // a body that cannot save itself, which is not the body that plays
            e("antiSuffocate", "suffocation backstop"),
            e("autoDrownEscape", "active-process drowning-escape chain"),
            e("autoFloatWhenDrowning", "idle drowning float"),
            e("contactDamageEscape", "step out of a damaging block"),
            e("lavaProximityEscape", "walk away from an advancing lava front"),
            e("avoidDanger", "A* keeps a one-block buffer from lava/fire"),
            e("lethalEdgeBrake", "walker sneak-brake at a lethal edge"),
            e("allowFleeBreak", "a flee may break out of a corner"),
            e("allowSwimEscapeBreak", "a water escape may break out"),
            e("allowSwimEscapePlace", "a water escape may place to climb out"),
            e("waterBucketScoop", "scoop the MLG water back after landing"),
            e("craftReclaimTable", "take back a crafting table the craft placed"),

            // client-side presentation and input coexistence — headless arenas never reach these
            e("cameraSlew", "stream-grade camera smoothing"),
            e("mouseYield", "human/bot mouse coexistence"),
            e("mouseYieldHud", "the 'bot is driving' badge"),
            e("keepTickingUnfocused", "keep ticking while the window loses focus"),
            e("descentCameraDecouple", "decouple camera from movement on a dry descent"),
            e("descentDecoupleLaunches", "extend that decoupling to all dry launches"),

            // pathfinder core — turning any of these off changes what a route IS, not how well
            // it is executed, so an arena running without them would be planning a different world
            e("collisionAwarePathing", "collision-shape solidity instead of the coarse blocksMotion()"),
            e("pathfinderSurfaceWaterNodes", "only the surface water cell is a lateral node; water exits are the climbOutPlace edge"),
            e("pathfinderDeepWaterPriced", "deep water is priced by the water taxes, not charged the HazardField's lethal penalty"),
            e("pathfinderCacheEnabled", "per-search blockstate memoisation"),
            e("pathfinderProgressive", "progressive pathfinding"),
            e("pathfinderFrontierCommit", "segmented planning to the loaded-chunk frontier"),
            e("pathfinderForbidParkourOverTheVoid", "refuse to plan a leap over the void when bridging is possible"),
            e("pathfinderForbidParkourIntoDeepWater", "refuse to plan a leap into deep water"),
            e("pathfinderParkourAscendNeedRunway", "approach-runway gate for the +1-up parkour leap"),

            // the arc-length pursuit refactor — its ON phases replaced whole families of
            // per-tick gates, so pinning them off would restore machinery that no longer exists
            e("walkerArcLengthAdvance", "phase 1: drive the step pointer from the path projection"),
            e("walkerTangentAim", "phase 2: aim at the bob-immune path tangent"),
            e("walkerArcLengthWedge", "phase 3: bob-immune ram-wedge recovery"),

            // walker execution guards that predate the §78/§87 waves
            e("walkerAscendMovement", "task#82 ascent machine (WalkerTickDrive)"),
            e("walkerStrideFloorGuard", "stride floor-guard (gap #53)"),
            e("walkerRecoveryHopFloorGate", "floor-gate the unaimed recovery hops"),
            e("walkerCornerClearance", "corner-clearance repulsion in the walk drive"),
            e("walkerDeepWaterDriftBrake", "sprint brake along a deep-water edge"),
            e("walkerSteepDescentLatch", "dry sibling of the deep-water drift brake"),
            e("walkerDescentStepSkipBrake", "descent step-skip sneak-brake"),
            e("walkerDescentNodeHold", "refuse to consume a node below the foot while still supported"),
            e("walkerNoLaunchAtAdjacentBelowNode", "refuse a jump at a node both below and already close"),
            e("walkerParkourAscendHold", "step-advance guard for ascending parkour leaps"),
            e("walkerBridgeDescentPlaceAnchor", "descending-bridgePlace lip anchor"),
            e("walkerVineFreeHangClimb", "sustain a climb on a free-hanging vine"),
            e("walkerVineLandGrab", "grab a free-hanging vine at the parkour landing apex"),
            e("walkerWaterClimbLateralGate", "water climb-out lateral gate"),
            e("combatCrit", "jump before a melee swing for the 1.5x critical"),
            e("combatCollectDrops", "post-kill drop sweep"),
            e("duskUrgent", "urgent dusk securing")));

    /**
     * Fields that exist on the settings surface but have no compiled default recorded, because
     * {@code BotConfig.NON_PERSISTED} excludes them: per-frame runtime context, not configuration.
     * Named here so that "missing from the universe" is a decision too — a new flag that lands in
     * NON_PERSISTED would otherwise slip past this test entirely.
     *
     * <p>All four happen to be booleans; the assertion below does not assume that.
     */
    private static final Set<String> RUNTIME_ONLY = Set.of(
            "fleeActive", "walkerDigActive", "walkerCruiseActive", "pathfinderBoxedEscalate");

    // ------------------------------------------------------------------ the assertions

    @Test
    void theBaselineWritesExactlyTheManifest() {
        Set<String> written = fieldsWrittenByBaseline();
        assertEquals(new TreeSet<>(PINNED.keySet()), written,
                "applyGameTestBaseline() and this manifest disagree about which fields it pins. "
                + "If you added a line there, add a row here saying why the arena must not run "
                + "that behaviour; if you removed one, remove the row. The manifest is the only "
                + "place a reader can find out why the suite's bot differs from the shipped one.");
    }

    @Test
    void everyDefaultOnBooleanIsClassified() {
        Set<String> universe = defaultOnBooleans();
        Set<String> classified = new TreeSet<>(LIVE.keySet());
        for (String name : PINNED.keySet()) {
            if (isBoolean(name)) classified.add(name);
        }
        assertEquals(classified, universe,
                "the set of default-ON booleans and the manifest disagree.\n"
                + "  In the universe but not the manifest: a flag that ships ON has no recorded "
                + "decision. Unless applyGameTestBaseline() pins it, the arena bot now runs a "
                + "behaviour the scenes were not authored against — add it to PINNED (and to the "
                + "baseline) if they were written without it, or to LIVE if the suite should run "
                + "it. Flipping an existing flag from default-OFF to default-ON lands it here "
                + "too, and that flip is the moment the decision comes due.\n"
                + "  In the manifest but not the universe: the flag no longer ships ON (or was "
                + "deleted), so its row is stale — drop it, and if it is in PINNED drop the "
                + "baseline line too, because pinning a flag that is already OFF is a no-op that "
                + "reads like a decision.");
    }

    @Test
    void theProbeCanTellAPinnedFieldFromAnUnpinnedOne() {
        // Without this, a probe that quietly stopped detecting anything would turn the test above
        // into "the empty set equals the empty set" on the day someone empties the baseline.
        Set<String> written = fieldsWrittenByBaseline();
        assertTrue(written.contains("allowBreak"),
                "the probe cannot see a boolean the baseline demonstrably writes — it is not "
                + "measuring applyGameTestBaseline() at all: " + written);
        assertTrue(written.contains("pathfinderBreakCostMultiplier"),
                "the probe sees booleans but not NUMERIC pins; a baseline line assigning a double "
                + "would be invisible to it: " + written);
        assertFalse(written.contains("walkerDebug"),
                "the probe reports a field the baseline does not touch — the sentinel comparison "
                + "is wrong and every result from it is noise");
    }

    @Test
    void theUniverseIsThereAtAll() {
        Set<String> universe = defaultOnBooleans();
        assertTrue(universe.size() >= 50,
                "expected the full default-ON boolean surface (81 at last count), got "
                + universe.size() + " — COMPILED_DEFAULTS or reflectivePrimitiveFields changed "
                + "shape, and an empty universe would make this whole test vacuously green");
        assertTrue(universe.contains("allowBreak"), "sanity: allowBreak ships ON");
        assertFalse(universe.contains("walkerStickyDig"), "sanity: walkerStickyDig ships OFF");
    }

    /**
     * The two enumerators must agree about which fields exist.
     *
     * <p>They reach BotConfig by different reflection: {@code reflectivePrimitiveFields()} uses
     * {@code getFields()}, which walks the superclass chain, while {@code COMPILED_DEFAULTS} is
     * built from {@code persistableFields()} → {@code getDeclaredFields()}, which does not. Both
     * javadocs call themselves "the ONE enumeration"; each is, within itself. Today the difference
     * is invisible because every field is declared in BotConfig itself.
     *
     * <p>It stops being invisible the day BotConfig is split. The file sits at 2993 of the 3000-line
     * budget, so a split is coming, and the natural shape — {@code BotConfig extends …} — is the one
     * that breaks silently: a moved field stays visible to the settings schema and snapshot (agents
     * see it, so nothing looks wrong) but drops out of save/load and out of
     * {@code snapshotAll}/{@code restoreAll}, which is precisely the leak snapshotAll's javadoc
     * exists to prevent — {@code applyGameTestBaseline()}'s OFF values would escape into the live
     * bot for exactly the moved flags.
     *
     * <p>This assertion covered only {@code boolean} until now, which left the 86 int/double/float/
     * long fields — and the String/Set ones, which are persistable but not primitive — with no
     * guard at all. It is green today for a reason that can be read off the source rather than
     * measured: {@code persistable} accepts boolean/int/long/double/float/String/Set, every
     * {@code public static volatile} primitive is static and non-final (volatile implies non-final)
     * and of one of those five primitive types, and {@code serialize} returns null only for a null
     * value, which a boxed primitive never is.
     */
    @Test
    void everySurfaceFieldHasACompiledDefaultOrIsDeclaredRuntimeOnly() {
        Map<String, String> defaults = compiledDefaults();
        Set<String> unrecorded = new TreeSet<>();
        for (Field f : SettingsRegistry.reflectivePrimitiveFields()) {
            if (!defaults.containsKey(f.getName())) unrecorded.add(f.getName());
        }
        assertEquals(new TreeSet<>(RUNTIME_ONLY), unrecorded,
                "a field on the settings surface has no compiled default, so it is invisible to "
                + "the universe above and could ship ON without ever being classified. Either it "
                + "is genuine per-frame runtime state (add it to RUNTIME_ONLY here and to "
                + "BotConfig.NON_PERSISTED), or it is configuration and should be persistable, or "
                + "it was moved out of BotConfig's own declaration — see this test's javadoc: "
                + "getFields() follows a superclass, getDeclaredFields() does not, so a split that "
                + "moves fields to a parent keeps them in the settings schema while dropping them "
                + "from persistence and from snapshotAll/restoreAll.");
    }

    @Test
    void theProbeLeavesTheConfigAsItFoundIt() {
        // The probe writes a sentinel into every primitive on the surface. These tests share a
        // JVM with the rest of common's suite, so a leak here would fail somebody else's test in
        // a way that points nowhere near this file.
        Map<Field, Object> before = saveAll();
        fieldsWrittenByBaseline();
        Map<Field, Object> after = saveAll();
        Set<String> drifted = new TreeSet<>();
        before.forEach((f, v) -> {
            if (!v.equals(after.get(f))) drifted.add(f.getName());
        });
        assertTrue(drifted.isEmpty(), "the baseline probe leaked into live config: " + drifted);
    }

    // ------------------------------------------------------------------ helpers

    private static Map.Entry<String, String> e(String k, String v) { return Map.entry(k, v); }

    /**
     * The fields {@link BotConfig#applyGameTestBaseline()} writes, measured by running it.
     *
     * <p>Two passes with different sentinels, because one pass can only prove a field changed
     * AWAY from that sentinel: a baseline line assigning the very value the pass had installed
     * would look untouched. A field the baseline writes differs from at least one of two distinct
     * sentinels, so the union is exact.
     */
    private static Set<String> fieldsWrittenByBaseline() {
        Map<Field, Object> saved = saveAll();
        try {
            Set<String> changed = new TreeSet<>(probe(true, -9991));
            changed.addAll(probe(false, -9992));
            return changed;
        } finally {
            restoreAll(saved);
        }
    }

    private static Set<String> probe(boolean flag, long number) {
        for (Field f : SettingsRegistry.reflectivePrimitiveFields()) writeSentinel(f, flag, number);
        BotConfig.applyGameTestBaseline();
        Set<String> changed = new TreeSet<>();
        for (Field f : SettingsRegistry.reflectivePrimitiveFields()) {
            if (!isSentinel(f, flag, number)) changed.add(f.getName());
        }
        return changed;
    }

    private static void writeSentinel(Field f, boolean flag, long number) {
        Class<?> t = f.getType();
        try {
            if (t == boolean.class) f.setBoolean(null, flag);
            else if (t == int.class) f.setInt(null, (int) number);
            else if (t == long.class) f.setLong(null, number);
            else if (t == float.class) f.setFloat(null, number);
            else if (t == double.class) f.setDouble(null, number);
            else throw new AssertionError("the settings surface grew a primitive this probe "
                        + "cannot write (" + t + " " + f.getName() + "). Teach it that type — "
                        + "skipping the field would make the baseline's writes to it invisible, "
                        + "which is the exact blindness this test exists to remove.");
        } catch (IllegalAccessException ex) {
            throw new AssertionError("cannot write " + f.getName(), ex);
        }
    }

    private static boolean isSentinel(Field f, boolean flag, long number) {
        Class<?> t = f.getType();
        try {
            if (t == boolean.class) return f.getBoolean(null) == flag;
            if (t == int.class) return f.getInt(null) == (int) number;
            if (t == long.class) return f.getLong(null) == number;
            if (t == float.class) return f.getFloat(null) == (float) number;
            if (t == double.class) return f.getDouble(null) == (double) number;
            throw new AssertionError("unreachable: writeSentinel already rejected " + t);
        } catch (IllegalAccessException ex) {
            throw new AssertionError("cannot read " + f.getName(), ex);
        }
    }

    private static boolean isBoolean(String fieldName) {
        for (Field f : SettingsRegistry.reflectivePrimitiveFields()) {
            if (f.getName().equals(fieldName)) return f.getType() == boolean.class;
        }
        return false;
    }

    private static Set<String> defaultOnBooleans() {
        Map<String, String> defaults = compiledDefaults();
        Set<String> out = new TreeSet<>();
        for (Field f : SettingsRegistry.reflectivePrimitiveFields()) {
            if (f.getType() == boolean.class && "true".equals(defaults.get(f.getName()))) {
                out.add(f.getName());
            }
        }
        return out;
    }

    /**
     * The compiled-in defaults, captured at BotConfig class init before any {@code load()}.
     *
     * <p>Read reflectively rather than by sampling the live fields on purpose: these tests share a
     * JVM, so a live read would make the universe "whatever the last test left set". If the field
     * is ever renamed this throws instead of degrading — an empty map would make the whole test
     * vacuously green, which is the failure mode it was written to prevent.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, String> compiledDefaults() {
        try {
            Field f = BotConfig.class.getDeclaredField("COMPILED_DEFAULTS");
            f.setAccessible(true);
            Map<String, String> m = (Map<String, String>) f.get(null);
            if (m == null || m.isEmpty()) {
                throw new AssertionError("BotConfig.COMPILED_DEFAULTS is empty; this test cannot "
                        + "tell a default-ON flag from a default-OFF one and would pass over "
                        + "anything");
            }
            return m;
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("BotConfig.COMPILED_DEFAULTS is how this test knows a flag's "
                    + "shipped default. Point this at the new name if it moved — do NOT fall back "
                    + "to reading the live field values.", ex);
        }
    }

    private static Map<Field, Object> saveAll() {
        Map<Field, Object> m = new LinkedHashMap<>();
        for (Field f : SettingsRegistry.reflectivePrimitiveFields()) {
            try {
                m.put(f, f.get(null));
            } catch (IllegalAccessException ex) {
                throw new AssertionError("cannot read " + f.getName(), ex);
            }
        }
        return m;
    }

    private static void restoreAll(Map<Field, Object> saved) {
        saved.forEach((f, v) -> {
            try {
                f.set(null, v);
            } catch (IllegalAccessException ex) {
                throw new AssertionError("cannot restore " + f.getName(), ex);
            }
        });
    }
}
