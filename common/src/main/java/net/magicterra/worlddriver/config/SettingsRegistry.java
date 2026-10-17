package net.magicterra.worlddriver.config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.magicterra.worlddriver.config.BotConfig;

/**
 * Single source of truth for the {@code mc.bot.setting} key surface — #280 root fix.
 *
 * <p>Before this class the settable-key knowledge was split across three places that drifted: the
 * read snapshot, the write if-chain, and a hand-written schema. A newly-declared {@link BotConfig}
 * flag whose schema or branch lagged was <em>silently dropped</em> by the write path — the #280
 * disease. This registry owns the KEY + TYPE enumeration ONCE; the contract
 * ({@code SettingsContracts}) builds every key's wire type from it, and {@code SettingsCommand} reads
 * and writes through {@link #readOf} and {@link #backingField}.
 *
 * <p><b>Pure.</b> Nothing here touches the game: enumeration reads {@link BotConfig}'s field
 * declarations without initializing it and exposes a plain {@link Type} enum, so the API artifact
 * generator loads it with no Minecraft on the classpath.
 *
 * <p><b>Anti-drift.</b> The reflective completion pass — the exact vector by which a new flag
 * enters the surface — is a SINGLE method ({@link #reflectivePrimitiveFields()}). The hand-listed
 * section ({@link #HAND}) fixes the order of the keys a read without {@code keys} answers. A
 * class-load self-check resolves every {@code CONFIG_FIELD} name against {@link BotConfig} and
 * throws on a typo.
 *
 * <p><b>The fourth side: consumers.</b> The checks above keep the key, the snapshot, the
 * schema and the docs agreeing about which knobs EXIST. None of them asks whether a knob
 * does anything. A field added here surfaces automatically, so a flag whose read site never
 * landed — or whose behavior was later refactored away, leaving the knob — is accepted by
 * {@code mc.bot.setting}, echoed back as set, and changes nothing; the caller, often an LLM,
 * gets every signal that it worked. {@code SettingsConsumerTest} (common/src/test) closes
 * that side: it takes the key set from {@link #reflectivePrimitiveFields()} — this method,
 * not a re-parse — and fails if any key is unread outside the settings plumbing. Every key
 * is consumed today. (Deliberately not stated as a count: the number here read 218 while the
 * surface had grown to 224, and a stale total is exactly the kind of claim that test exists
 * to make unnecessary — it enumerates, so nobody has to trust a written-down figure.)
 */
public final class SettingsRegistry {
    private SettingsRegistry() {}

    /** Schema-facing type of a key, derived from the BotConfig field / snapshot value type. */
    public enum Type { BOOLEAN, INTEGER, NUMBER, STRING, STRING_LIST, POINT_LIST }

    /**
     * Fail class load if {@link SettingsDocs} documents a key this registry does not know.
     *
     * <p>The same anti-drift contract the {@code CONFIG_FIELD} self-check already gives the
     * read path, now extended to the prose. Before the docs moved next to the keys they
     * describe, they were an ASCII table inside the {@code mc.bot.setting} tool description
     * with nothing tying the two together — which is how it ended up documenting 125 of 241
     * keys with two rows whose "key" had swallowed its own range spec.
     *
     * <p>Deliberately one-directional: an UNDOCUMENTED key is allowed (demanding a row would
     * block adding a flag). How many lack one is deliberately not written here, for the reason
     * the class javadoc gives above: a total in prose outlives its own accuracy — count them
     * from {@link #knownKeys()} and {@link SettingsDocs#documentedKeys()} instead. An ORPHANED
     * row, on the other hand, is not allowed, because that
     * means a key was renamed or deleted and its prose was left pointing at nothing.
     */
    static void assertDocsResolve() {
        List<String> orphans = new ArrayList<>();
        Set<String> known = knownKeys();
        for (String k : SettingsDocs.documentedKeys()) {
            if (!known.contains(k)) orphans.add(k);
        }
        if (!orphans.isEmpty()) {
            throw new IllegalStateException(
                    "SettingsDocs documents key(s) unknown to SettingsRegistry: " + orphans
                    + " — rename or delete the row alongside the setting it described");
        }
    }

    /** How {@code SettingsCommand} reads and writes a hand-listed key's live value. */
    public enum Read { CONFIG_FIELD, BOT_PAUSED, HAZARD_LIST, WHITELIST_LIST, MUTED_LIST, UNDRIVABLE_LIST, AVOID_POINTS }

    /** One hand-listed key: its wire name, how to read its value, and the backing field (if any). */
    record Hand(String key, Read read, String field) {}

    private static Hand field(String key)               { return new Hand(key, Read.CONFIG_FIELD, key); }
    private static Hand field(String key, String field) { return new Hand(key, Read.CONFIG_FIELD, field); }
    private static Hand hand(String key, Read read, String field) { return new Hand(key, read, field); }

    /**
     * Hand-listed keys, in the order a read without {@code keys} answers them: the keys whose wire
     * name is not their field's, or whose value is not a field at all.
     */
    static final List<Hand> HAND = List.of(
        field("walker.repathEveryTicks", "walkerRepathEveryTicks"),
        field("walker.totalTickBudget", "walkerTotalTickBudget"),
        field("walker.yawHysteresisDeg", "walkerYawHysteresisDeg"),
        field("mine.searchVerticalRadius", "mineSearchVerticalRadius"),
        field("breakTimeoutTicks"),
        hand("paused", Read.BOT_PAUSED, null),
        field("autoEat"),
        field("autoEatFoodThreshold"),
        field("autoRespawn"),
        field("autoRetreat"),
        field("retreatHpThreshold"),
        field("autoBunker"),
        field("bunkerHpThreshold"),
        field("bunkerTriggerRadius"),
        field("bunkerMinHostiles"),
        field("bunkerDepth"),
        field("autoTotem"),
        field("autoShield"),
        field("autoHeal"),
        field("healHpThreshold"),
        field("autoDodge"),
        field("creeperKeepDistance"),
        field("projectileDodgeRadius"),
        field("autoFight"),
        field("autoFightThreatThreshold"),
        field("combatReach"),
        field("kiteDistance"),
        field("combatCrit"),
        field("combatCollectDrops"),
        field("autoEquip"),
        field("equipDurabilityThreshold"),
        field("autoSwim"),
        field("antiSuffocate"),
        field("contactDamageEscape"),
        field("lavaProximityEscape"),
        field("autoTool"),
        field("autoBackfill"),
        field("autoBackfillBlock"),
        field("tunnelWidthBias"),
        field("autoBackfillRadius"),
        field("allowParkour4"),
        field("allowBreak"),
        field("allowSwimEscapeBreak"),
        field("allowSwimEscapePlace"),
        field("allowPlace"),
        field("allowParkourPlace"),
        field("allowWaterBucketFall"),
        field("maxWaterBucketFall"),
        field("waterBucketScoop"),
        field("avoidDanger"),
        field("autoSecureAtDusk"),
        field("hazardGridRadius"),
        field("hazardGridDecimateTicks"),
        field("deepWaterMax"),
        field("swimBankClimbMaxHeight"),
        field("sceneQueryMaxRadius"),
        field("pathfinder.dangerPenalty", "dangerPenaltyPerCell"),
        field("pathfinder.lavaDangerPenalty", "lavaDangerPenalty"),
        field("pathfinder.contactDangerPenalty", "contactDangerPenalty"),
        field("pathfinder.ledgeDangerPenalty", "ledgeDangerPenalty"),
        field("pathfinder.waterDangerPenalty", "waterDangerPenalty"),
        field("pathfinder.ledgeDangerMinDrop", "ledgeDangerMinDrop"),
        field("avoidMobs"),
        field("pathfinder.mobAvoidRadius", "mobAvoidRadius"),
        field("pathfinder.mobAvoidPenalty", "mobAvoidPenalty"),
        field("rangedAvoidRadius"),
        field("fleeDangerBoost"),
        field("walkerDebug"),
        field("walkerVerticalResync"),
        field("walkerLevelRiserJump"),
        field("walkerPadRamBreak"),
        field("walkerParkourAscendHold"),
        field("walkerDeepWaterDriftBrake"),
        field("walkerSteepDescentLatch"),
        field("craftReclaimTable"),
        field("walkerDescentStepSkipBrake"),
        field("walkerDescentFlipHold"),
        field("walkerWaterStepDownFloat"),
        field("walkerStepUpCrestReach"),
        field("walkerWaterWalkReach"),
        field("walkerAscentRamJitterImmune"),
        field("walkerArcLengthShadow"),
        field("walkerArcLengthAdvance"),
        field("walkerTangentAim"),
        field("walkerTangentPursuit"),
        field("walkerArcLengthWedge"),
        field("walkerArcProgressWedge"),
        field("walkerFellBelowAlign"),
        field("walkerAscentRamBobBreak"),
        field("walkerFutileBankDigRelease"),
        field("walkerBridgeDescentPlaceAnchor"),
        field("walkerBankDigSkipOverhang"),
        field("walkerBuoyantSearchFromSurface"),
        field("walkerBankDigForwardExit"),
        field("walkerPillarReachGoalNoSnap"),
        field("walkerBankDigSkipWhenCwpSwims"),
        field("walkerTraverseBreakOvershootResync"),
        field("walkerSwimAshorePillarDespiteDeepDig"),
        field("walkerFootholdBeforeBankDig"),
        field("walkerClimbIntentFromSurface"),
        field("walkerPillarTopsOutAtFlushExit"),
        field("walkerFinalNodeDirectAim"),
        field("walkerHoldLastNodeUntilStanding"),
        field("walkerShallowWaterSideFoothold"),
        field("walkerClimbOutResyncsAim"),
        field("walkerOrbitBreaksAimLag"),
        field("walkerSurfaceSprintSwim"),
        field("walkerFloatingBankBobFreeze"),
        field("walkerFloatingBankFollow"),
        field("walkerFasterChurnRepath"),
        field("walkerDeepWaterFloatBeeline"),
        field("pathfinderForbidParkourIntoDeepWater"),
        field("pathfinderForbidParkourFromFloatingWater"),
        field("pathfinderForbidParkourOverWaterGap"),
        field("pathfinderParkourAscendNeedRunway"),
        field("pathfinderFloatingSurfaceCross"),
        field("pathfinderVineOverWaterTax"),
        field("pathfinderPadOverWaterTax"),
        field("pathfinderPadClusterTax"),
        field("walkerVineFreeHangClimb"),
        field("walkerVineLandGrab"),
        field("walkerVineDescentDrop"),
        field("walkerAscendMovement"),
        field("pathDebug"),
        field("pathArchive"),
        field("pathDebugMaxNodes"),
        field("pathDebugMaxSamples"),
        field("pathChartAutoDump"),
        field("elytraDebug"),
        field("smoothLook"),
        field("smoothLookDegPerTick"),
        field("riskBias"),
        field("riskBias.scale", "riskBiasScale"),
        field("pathfinder.maxNodes", "pathfinderMaxNodes"),
        field("pathfinder.maxMs", "pathfinderMaxMs"),
        field("pathfinder.sliceMs", "pathfinderSliceMs"),
        field("pathfinder.idleSliceMs", "pathfinderIdleSliceMs"),
        field("pathfinder.previewSliceMs", "pathfinderPreviewSliceMs"),
        field("sightRaysPerSearch"),
        field("snapshotBoxMax"),
        field("detourAlarmRatio"),
        field("routeEventCooldownTicks"),
        field("pathfinder.heuristicWeight", "pathfinderHeuristicWeight"),
        field("pathfinderCacheEnabled"),
        field("collisionAwarePathing"),
        field("pathfinderSurfaceWaterNodes"),
        field("pathfinderDeepWaterPriced"),
        field("pathfinderGoalField"),
        field("goalFieldCellSize"),
        field("goalFieldRadius"),
        field("goalFieldVerticalRadius"),
        field("pathfinderDepthPenalty"),
        field("pathfinderDepthSlack"),
        field("pathfinderDescendCost"),
        field("pathfinderWaterCellCost"),
        field("pathfinderWaterClimbOutCost"),
        field("pathfinderSubmergedWaterCost"),
        field("pathfinderBridgeCost"),
        field("pathfinderPillarCost"),
        field("pathfinderPillarCostStocked"),
        field("pathfinderPillarStockBlocks"),
        field("walkerRecoveryHopFloorGate"),
        field("walkerFromEndNoProgressDiscard"),
        field("walkerTailConsumeDirectional"),
        field("walkerCornerClearance"),
        field("pathfinderThinObstacleHeight"),
        field("pathfinderFrontierCommit"),
        field("pathfinderHorizonBlocks"),
        field("pathfinderMaxDryFall"),
        field("pathfinderSoftCommitNodes"),
        field("pathfinderQuickNodes"),
        field("pathfinder.axisHeight", "axisHeight"),
        hand("blocksToAvoid", Read.HAZARD_LIST, "extraHazardBlocks"),
        hand("buildBlockWhitelist", Read.WHITELIST_LIST, "buildBlockWhitelist"),
        hand("mutedEvents", Read.MUTED_LIST, "mutedEvents"),
        hand("undrivableMobs", Read.UNDRIVABLE_LIST, "undrivableMobs"),
        field("pathfinder.avoidZonePenalty", "avoidZonePenalty"),
        hand("avoidPoints", Read.AVOID_POINTS, null)
    );

    /**
     * Write-only keys with NO {@link BotConfig} field, so the reflective completion never surfaces
     * them — they must be declared here to be KNOWN. {@code debugFly} is a creative-flight test
     * affordance, applied through {@code BotApi.debugFly}.
     */
    private static final Map<String, Type> APPLY_ONLY = new LinkedHashMap<>();
    static { APPLY_ONLY.put("debugFly", Type.BOOLEAN); }

    // ---- resolved caches (built once at class load) ----
    private static final Map<String, Field> CONFIG_FIELDS = new HashMap<>();
    private static final LinkedHashSet<String> HAND_KEYS = new LinkedHashSet<>();
    private static final LinkedHashMap<String, Type> SCHEMA_PROPS = new LinkedHashMap<>();
    private static final Map<String, Hand> HAND_BY_KEY = new HashMap<>();
    private static final Map<String, String> ALIAS_OF_FIELD = new HashMap<>();
    private static final Map<String, Field> PRIMITIVE_FIELDS = new HashMap<>();

    static {
        for (Hand h : HAND) {
            if (!HAND_KEYS.add(h.key())) {
                throw new IllegalStateException("SettingsRegistry: duplicate hand key '" + h.key() + "'");
            }
            HAND_BY_KEY.put(h.key(), h);
            if (h.field() != null && !h.field().equals(h.key())) ALIAS_OF_FIELD.put(h.field(), h.key());
            if (h.read() == Read.CONFIG_FIELD) {
                try {
                    CONFIG_FIELDS.put(h.field(), BotConfig.field(h.field()));
                } catch (NoSuchFieldException e) {
                    throw new IllegalStateException(
                        "SettingsRegistry: no config field '" + h.field() + "' for key '" + h.key() + "'", e);
                }
            }
            SCHEMA_PROPS.put(h.key(), handType(h));
        }
        // Reflective completion — shared enumerator, so the schema/registry can't lag the snapshot.
        for (Field f : reflectivePrimitiveFields()) {
            if (HAND_KEYS.contains(f.getName())) continue;   // shadowed by a hand key (matches build)
            SCHEMA_PROPS.putIfAbsent(f.getName(), typeOf(f.getType()));
            PRIMITIVE_FIELDS.put(f.getName(), f);
        }
        SCHEMA_PROPS.putAll(APPLY_ONLY);
        assertDocsResolve();   // LAST: needs the finished key set
    }

    private static Type handType(Hand h) {
        return switch (h.read()) {
            case BOT_PAUSED -> Type.BOOLEAN;
            case HAZARD_LIST, WHITELIST_LIST, MUTED_LIST, UNDRIVABLE_LIST -> Type.STRING_LIST;
            case AVOID_POINTS -> Type.POINT_LIST;
            case CONFIG_FIELD -> typeOf(CONFIG_FIELDS.get(h.field()).getType());
        };
    }

    private static Type typeOf(Class<?> t) {
        if (t == boolean.class) return Type.BOOLEAN;
        if (t == double.class || t == float.class) return Type.NUMBER;
        if (t == String.class) return Type.STRING;
        return Type.INTEGER;   // int / long (and any other integral primitive)
    }

    /**
     * Public static volatile PRIMITIVE config fields in {@link BotConfig#publicFields()} order.
     * The ONE enumerator of the reflective keys, so a new flag
     * is readable, writable and in the contract at once, or not at all.
     */
    static List<Field> reflectivePrimitiveFields() {
        List<Field> out = new ArrayList<>();
        for (Field f : BotConfig.publicFields()) {
            int mods = f.getModifiers();
            if (!Modifier.isStatic(mods) || !Modifier.isVolatile(mods)) continue;
            if (!f.getType().isPrimitive()) continue;
            out.add(f);
        }
        return out;
    }

    /** Cached BotConfig field for a {@code CONFIG_FIELD} hand key's backing field name (or null). */
    static Field configField(String fieldName) { return CONFIG_FIELDS.get(fieldName); }

    /**
     * The primitive field a write to {@code key} lands in, or null: the backing field of a hand key
     * ({@code walker.repathEveryTicks} → {@code walkerRepathEveryTicks}), else the field of that name.
     */
    static Field primitiveField(String key) {
        Hand h = HAND_BY_KEY.get(key);
        Field f = h == null ? PRIMITIVE_FIELDS.get(key)
                : h.read() == Read.CONFIG_FIELD ? CONFIG_FIELDS.get(h.field()) : null;
        return f != null && f.getType().isPrimitive() ? f : null;
    }

    /**
     * The documented range of {@code key}. An aliased field answers to its own name as well as the
     * alias, so the field name is held to the range written on the alias.
     */
    public static SettingsDocs.Range documentedRange(String key) {
        SettingsDocs.Range r = SettingsDocs.range(key);
        if (r != null) return r;
        String alias = ALIAS_OF_FIELD.get(key);
        return alias == null ? null : SettingsDocs.range(alias);
    }

    /** The hand-listed keys, in snapshot order. */
    static List<Hand> handEntries() { return HAND; }

    /** How {@code key}'s value is read: a field for every key but the hand-read lists and {@code paused}. */
    public static Read readOf(String key) {
        Hand h = HAND_BY_KEY.get(key);
        return h == null ? Read.CONFIG_FIELD : h.read();
    }

    /** The field behind {@code key}, primitive or not, or null for a key {@link #readOf} reads another way. */
    public static Field backingField(String key) {
        Hand h = HAND_BY_KEY.get(key);
        if (h == null) return PRIMITIVE_FIELDS.get(key);
        return h.read() == Read.CONFIG_FIELD ? CONFIG_FIELDS.get(h.field()) : null;
    }

    // ---- public surface ----

    /** Every key {@code mc.bot.setting} accepts, ordered: hand keys, reflective fields, apply-only. */
    public static Set<String> knownKeys() { return Collections.unmodifiableSet(SCHEMA_PROPS.keySet()); }

    /** True if {@code key} is a settable knob (drives the closed schema + apply's unknown-key gate). */
    public static boolean isKnown(String key) { return SCHEMA_PROPS.containsKey(key); }

    /** key → schema {@link Type}, same order as {@link #knownKeys()}. */
    public static Map<String, Type> schemaProps() { return Collections.unmodifiableMap(SCHEMA_PROPS); }

    /** The primitive a field-backed key is stored as ({@code int}, {@code long}, {@code float}, …), or null. */
    public static Class<?> storedType(String key) {
        Field f = primitiveField(key);
        return f == null ? null : f.getType();
    }
}
