package net.magicterra.agent.bot;

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

/**
 * Single source of truth for the {@code mc.bot.setting} key surface — #280 root fix.
 *
 * <p>Before this class the settable-key knowledge was split across three places that drifted:
 * {@link SettingsSnapshot#build} (the read snapshot), {@link SettingsCommand#apply} (the write
 * if-chain), and the hand-written {@code BotTools} schema (the advertisement). A newly-declared
 * {@link BotConfig} flag whose schema/branch lagged was <em>silently dropped</em> by the apply
 * path — the #280 disease. This registry owns the KEY + TYPE enumeration ONCE; the snapshot
 * consumes it for its hand section, the schema is built from it (closed), and apply rejects any
 * param key it doesn't recognise.
 *
 * <p><b>Pure + server-loadable.</b> No Minecraft-client runtime is touched: enumeration needs no
 * {@link BotApiImpl} instance and imports nothing from the client or the mcp schema DSL (it exposes
 * a plain {@link Type} enum; {@code BotTools} maps that to a {@code Schema}). {@link BotConfig} is a
 * common/server-safe class (already loaded server-side via {@code pinnedBaseline}), so a T0
 * dedicated-server scene can call {@link #knownKeys()} / {@link #schemaProps()} directly.
 *
 * <p><b>Anti-drift.</b> The reflective completion pass — the exact vector by which a new flag
 * enters the surface — is a SINGLE method ({@link #reflectivePrimitiveFields()}) consumed by BOTH
 * this registry and {@link SettingsSnapshot#build}, so they cannot diverge. The hand-listed section
 * ({@link #HAND}) is the ordered mirror of the snapshot's {@code snap.put(...)} sequence and is what
 * {@code build} now iterates for its hand values, so the snapshot's output stays byte-identical
 * (key order included) while the keys live in one place. A class-load self-check resolves every
 * {@code CONFIG_FIELD} name against {@link BotConfig} and throws on a typo.
 */
public final class SettingsRegistry {
    private SettingsRegistry() {}

    /** Schema-facing type of a key, derived from the BotConfig field / snapshot value type. */
    public enum Type { BOOLEAN, INTEGER, NUMBER, STRING, STRING_LIST, POINT_LIST }

    /** How {@link SettingsSnapshot#build} reads a hand-listed key's live value. */
    enum Read { CONFIG_FIELD, BOT_PAUSED, HAZARD_LIST, WHITELIST_LIST, MUTED_LIST, AVOID_POINTS }

    /** One hand-listed key: its wire name, how to read its value, and the backing field (if any). */
    record Hand(String key, Read read, String field) {}

    private static Hand field(String key)               { return new Hand(key, Read.CONFIG_FIELD, key); }
    private static Hand field(String key, String field) { return new Hand(key, Read.CONFIG_FIELD, field); }
    private static Hand hand(String key, Read read, String field) { return new Hand(key, read, field); }

    /**
     * Hand-listed keys in the EXACT order {@link SettingsSnapshot#build} emits them. This list is
     * the single source for the hand section — {@code build} iterates it, and drift is impossible
     * because there is no second copy. Generated from the snapshot's {@code snap.put(...)} sequence.
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
        field("pathfinder.maxNodes", "pathfinderMaxNodes"),
        field("pathfinder.maxMs", "pathfinderMaxMs"),
        field("pathfinder.sliceMs", "pathfinderSliceMs"),
        field("pathfinder.idleSliceMs", "pathfinderIdleSliceMs"),
        field("pathfinder.heuristicWeight", "pathfinderHeuristicWeight"),
        field("pathfinderCacheEnabled"),
        field("collisionAwarePathing"),
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
        field("pathfinder.avoidZonePenalty", "avoidZonePenalty"),
        hand("avoidPoints", Read.AVOID_POINTS, null)
    );

    /**
     * Apply-only keys with NO {@link BotConfig} field and not in the snapshot, so the reflective
     * completion never surfaces them — they must be declared here to be KNOWN. {@code debugFly} is
     * a client-thread creative-flight test affordance (see {@link SettingsCommand#apply}).
     */
    private static final Map<String, Type> APPLY_ONLY = new LinkedHashMap<>();
    static { APPLY_ONLY.put("debugFly", Type.BOOLEAN); }

    // ---- resolved caches (built once at class load) ----
    private static final Map<String, Field> CONFIG_FIELDS = new HashMap<>();
    private static final LinkedHashSet<String> HAND_KEYS = new LinkedHashSet<>();
    private static final LinkedHashMap<String, Type> SCHEMA_PROPS = new LinkedHashMap<>();

    static {
        for (Hand h : HAND) {
            if (!HAND_KEYS.add(h.key())) {
                throw new IllegalStateException("SettingsRegistry: duplicate hand key '" + h.key() + "'");
            }
            if (h.read() == Read.CONFIG_FIELD) {
                try {
                    CONFIG_FIELDS.put(h.field(), BotConfig.class.getField(h.field()));
                } catch (NoSuchFieldException e) {
                    throw new IllegalStateException(
                        "SettingsRegistry: no BotConfig field '" + h.field() + "' for key '" + h.key() + "'", e);
                }
            }
            SCHEMA_PROPS.put(h.key(), handType(h));
        }
        // Reflective completion — shared enumerator, so the schema/registry can't lag the snapshot.
        for (Field f : reflectivePrimitiveFields()) {
            if (HAND_KEYS.contains(f.getName())) continue;   // shadowed by a hand key (matches build)
            SCHEMA_PROPS.putIfAbsent(f.getName(), typeOf(f.getType()));
        }
        SCHEMA_PROPS.putAll(APPLY_ONLY);
    }

    private static Type handType(Hand h) {
        return switch (h.read()) {
            case BOT_PAUSED -> Type.BOOLEAN;
            case HAZARD_LIST, WHITELIST_LIST, MUTED_LIST -> Type.STRING_LIST;
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
     * Public static volatile PRIMITIVE {@link BotConfig} fields in {@code Class.getFields()} order.
     * The ONE enumerator shared by this registry and {@link SettingsSnapshot#build}'s completion
     * pass — a new flag surfaces in both or neither.
     */
    static List<Field> reflectivePrimitiveFields() {
        List<Field> out = new ArrayList<>();
        for (Field f : BotConfig.class.getFields()) {
            int mods = f.getModifiers();
            if (!Modifier.isStatic(mods) || !Modifier.isVolatile(mods)) continue;
            if (!f.getType().isPrimitive()) continue;
            out.add(f);
        }
        return out;
    }

    /** Cached BotConfig field for a {@code CONFIG_FIELD} hand key's backing field name (or null). */
    static Field configField(String fieldName) { return CONFIG_FIELDS.get(fieldName); }

    /** The hand-listed keys, in snapshot order. */
    static List<Hand> handEntries() { return HAND; }

    // ---- public surface ----

    /** Every key {@code mc.bot.setting} accepts, ordered: hand keys, reflective fields, apply-only. */
    public static Set<String> knownKeys() { return Collections.unmodifiableSet(SCHEMA_PROPS.keySet()); }

    /** True if {@code key} is a settable knob (drives the closed schema + apply's unknown-key gate). */
    public static boolean isKnown(String key) { return SCHEMA_PROPS.containsKey(key); }

    /** key → schema {@link Type}, same order as {@link #knownKeys()}. {@code BotTools} maps to a Schema. */
    public static Map<String, Type> schemaProps() { return Collections.unmodifiableMap(SCHEMA_PROPS); }
}
