package net.magicterra.worlddriver.api.methods.observe;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.magicterra.worlddriver.api.schema.ApiField;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.RecordType;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.schema.WireName;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts.Route;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.Look;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.Point;

/**
 * {@code mc.observe.player}, {@code mc.observe.threats}, {@code mc.observe.boss}, {@code mc.observe.scene} and
 * {@code mc.observe.map}.
 */
public final class ObserveContracts {
    private ObserveContracts() {}

    private static final ApiType<Integer> COUNT = ApiTypes.integer(0, Integer.MAX_VALUE);
    private static final ApiType<Double> DISTANCE = ApiTypes.number(0, Double.MAX_VALUE);

    // ------------------------------------------------------------------ player
    //
    // One player's state. The server reads a player in its player list; a client with no server attached
    // reads its own player. Both build the same record, and the client adds what only it has ({@code view}:
    // the crosshair and the frame rate).

    public record PlayerParams(Optional<String> name) {}

    /** {@code player} is absent when nobody matches. */
    public record PlayerReading(Optional<PlayerState> player) {}

    public record PlayerState(String name, String uuid, String dimension, String gameMode, Point pos,
                              BlockPosition blockPos, Look look, BodyState body, Vitals vitals, List<Effect> effects,
                              TimeOfDay time, Attack attack, Equipment equipment, List<InventorySlot> inventory,
                              Map<String, Integer> items, Optional<ClientView> view) {}


    public record BodyState(boolean onGround, Point eyePos, String pose, boolean inWall, boolean inWater,
                            boolean underWater, boolean crouching, String eyeBlock, String feetBlock) {}

    public record Vitals(double health, double maxHealth, int air, int maxAir, int food, double saturation, int xpLevel) {}

    public record Effect(String id, int amplifier, int durationTicks) {}

    public enum DayPhase implements WireName {
        DAY("day"), SUNSET("sunset"), NIGHT("night"), SUNRISE("sunrise");

        private final String wire;

        DayPhase(String wire) { this.wire = wire; }

        @Override public String wireName() { return wire; }
    }

    public record TimeOfDay(long dayTime, long dayOfWorld, long timeOfDay, DayPhase phase) {}

    public record Attack(double strengthScale, boolean ready, int cooldownTicks, int fullCooldownTicks) {}

    /** Wear is present on damageable items only: its presence itself says the item wears out. */
    public record Item(String id, int count, Optional<Integer> maxDamage, Optional<Integer> damage,
                       Optional<Integer> durability) {}

    public record Armor(Optional<Item> head, Optional<Item> chest, Optional<Item> legs, Optional<Item> feet) {}

    public record Equipment(int selectedSlot, Optional<Item> mainHand, Optional<Item> offHand, Armor armor) {}

    public record InventorySlot(int slot, String id, int count, Optional<Integer> maxDamage, Optional<Integer> damage,
                                Optional<Integer> durability) {}

    public sealed interface Crosshair permits Miss, BlockHit, EntityHit {}
    public record Miss() implements Crosshair {}
    public record BlockHit(BlockPosition blockPos, String face, String block, double distance) implements Crosshair {}
    public record EntityHit(int entityId, String entityType, Point pos, double distance) implements Crosshair {}

    public record ClientView(Crosshair hit, int fps) {}

    public static final ApiType<Effect> EFFECT = ApiTypes.record("MobEffect",
            ApiField.field("id", ApiTypes.string()).required().forGetter(Effect::id),
            ApiField.field("amplifier", ApiTypes.integer()).required().forGetter(Effect::amplifier),
            ApiField.field("durationTicks", ApiTypes.integer()).describe("-1: infinite.")
                    .required().forGetter(Effect::durationTicks),
            Effect::new);

    public static final ApiType<Item> ITEM = ApiTypes.record("PlayerItem",
            ApiField.field("id", CommonTypes.RESOURCE_ID).required().forGetter(Item::id),
            ApiField.field("count", COUNT).required().forGetter(Item::count),
            ApiField.field("maxDamage", COUNT).optional().forGetter(Item::maxDamage),
            ApiField.field("damage", COUNT).optional().forGetter(Item::damage),
            ApiField.field("durability", ApiTypes.integer())
                    .describe("Points left, maxDamage − damage; a floor on remaining uses, as Unbreaking stretches a point.")
                    .optional().forGetter(Item::durability),
            Item::new);

    private static final ApiType<InventorySlot> INVENTORY_SLOT = ApiTypes.record("PlayerInventorySlot",
            ApiField.field("slot", COUNT).describe("Vanilla index: 0–8 hotbar, 9–35 main, 36–39 armor, 40 offhand.")
                    .required().forGetter(InventorySlot::slot),
            ApiField.field("id", CommonTypes.RESOURCE_ID).required().forGetter(InventorySlot::id),
            ApiField.field("count", COUNT).required().forGetter(InventorySlot::count),
            ApiField.field("maxDamage", COUNT).optional().forGetter(InventorySlot::maxDamage),
            ApiField.field("damage", COUNT).optional().forGetter(InventorySlot::damage),
            ApiField.field("durability", ApiTypes.integer()).optional().forGetter(InventorySlot::durability),
            InventorySlot::new);

    private static final RecordType<Look> LOOK = ApiTypes.record("Look",
            ApiField.field("yaw", ApiTypes.number()).required().forGetter(Look::yaw),
            ApiField.field("pitch", ApiTypes.number()).required().forGetter(Look::pitch),
            Look::new);

    private static final ApiType<BodyState> BODY_STATE = ApiTypes.record("PlayerBodyState",
            ApiField.field("onGround", ApiTypes.bool()).required().forGetter(BodyState::onGround),
            ApiField.field("eyePos", CommonTypes.POINT).required().forGetter(BodyState::eyePos),
            ApiField.field("pose", ApiTypes.string()).describe("STANDING, CROUCHING, SWIMMING, FALL_FLYING, SLEEPING, …")
                    .required().forGetter(BodyState::pose),
            ApiField.field("inWall", ApiTypes.bool()).describe("Suffocating in a block.").required().forGetter(BodyState::inWall),
            ApiField.field("inWater", ApiTypes.bool()).required().forGetter(BodyState::inWater),
            ApiField.field("underWater", ApiTypes.bool()).required().forGetter(BodyState::underWater),
            ApiField.field("crouching", ApiTypes.bool()).required().forGetter(BodyState::crouching),
            ApiField.field("eyeBlock", CommonTypes.RESOURCE_ID).required().forGetter(BodyState::eyeBlock),
            ApiField.field("feetBlock", CommonTypes.RESOURCE_ID).required().forGetter(BodyState::feetBlock),
            BodyState::new);

    private static final ApiType<Vitals> VITALS = ApiTypes.record("Vitals",
            ApiField.field("health", ApiTypes.number()).required().forGetter(Vitals::health),
            ApiField.field("maxHealth", ApiTypes.number()).required().forGetter(Vitals::maxHealth),
            ApiField.field("air", ApiTypes.integer()).describe("Ticks of breath left; drowning starts below 0.")
                    .required().forGetter(Vitals::air),
            ApiField.field("maxAir", ApiTypes.integer()).required().forGetter(Vitals::maxAir),
            ApiField.field("food", ApiTypes.integer(0, 20)).required().forGetter(Vitals::food),
            ApiField.field("saturation", ApiTypes.number()).required().forGetter(Vitals::saturation),
            ApiField.field("xpLevel", ApiTypes.integer()).required().forGetter(Vitals::xpLevel),
            Vitals::new);

    private static final ApiType<TimeOfDay> TIME = ApiTypes.record("TimeOfDay",
            ApiField.field("dayTime", ApiTypes.int64(Long.MIN_VALUE, Long.MAX_VALUE)).describe("The world's raw day time.")
                    .required().forGetter(TimeOfDay::dayTime),
            ApiField.field("dayOfWorld", ApiTypes.int64(Long.MIN_VALUE, Long.MAX_VALUE)).required().forGetter(TimeOfDay::dayOfWorld),
            ApiField.field("timeOfDay", ApiTypes.int64(0, 23_999)).required().forGetter(TimeOfDay::timeOfDay),
            ApiField.field("phase", ApiTypes.enumOf("DayPhase", DayPhase.class))
                    .describe("Hostiles spawn from sunset's end (13000) and burn from sunrise (23000).")
                    .required().forGetter(TimeOfDay::phase),
            TimeOfDay::new);

    private static final ApiType<Attack> ATTACK = ApiTypes.record("AttackReadiness",
            ApiField.field("strengthScale", ApiTypes.number(0, 1))
                    .describe("The share of the held weapon's damage a swing would land now.")
                    .required().forGetter(Attack::strengthScale),
            ApiField.field("ready", ApiTypes.bool()).describe("strengthScale is 1: a full hit, and a crit when falling.")
                    .required().forGetter(Attack::ready),
            ApiField.field("cooldownTicks", COUNT).describe("Ticks until ready; wait these before mc.bot.attackEntity.")
                    .required().forGetter(Attack::cooldownTicks),
            ApiField.field("fullCooldownTicks", COUNT).describe("The held weapon's whole recharge, to compare weapons by.")
                    .required().forGetter(Attack::fullCooldownTicks),
            Attack::new);

    private static final ApiType<Armor> ARMOR = ApiTypes.record("Armor",
            ApiField.field("head", ITEM).optional().forGetter(Armor::head),
            ApiField.field("chest", ITEM).optional().forGetter(Armor::chest),
            ApiField.field("legs", ITEM).optional().forGetter(Armor::legs),
            ApiField.field("feet", ITEM).optional().forGetter(Armor::feet),
            Armor::new);

    private static final ApiType<Equipment> EQUIPMENT = ApiTypes.record("Equipment",
            ApiField.field("selectedSlot", ApiTypes.integer(0, 8)).required().forGetter(Equipment::selectedSlot),
            ApiField.field("mainHand", ITEM).describe("Absent: empty hand.").optional().forGetter(Equipment::mainHand),
            ApiField.field("offHand", ITEM).optional().forGetter(Equipment::offHand),
            ApiField.field("armor", ARMOR).required().forGetter(Equipment::armor),
            Equipment::new);

    public static final UnionType<Crosshair> CROSSHAIR = ApiTypes.taggedUnion("Crosshair", "type",
            ApiTypes.variant("miss", Miss.class, ApiTypes.record(null, Miss::new)),
            ApiTypes.variant("block", BlockHit.class, ApiTypes.record(null,
                    ApiField.field("blockPos", CommonTypes.BLOCK_POSITION).required().forGetter(BlockHit::blockPos),
                    ApiField.field("face", ApiTypes.string()).required().forGetter(BlockHit::face),
                    ApiField.field("block", CommonTypes.RESOURCE_ID).required().forGetter(BlockHit::block),
                    ApiField.field("distance", ApiTypes.number(0, Double.MAX_VALUE)).required().forGetter(BlockHit::distance),
                    BlockHit::new)),
            ApiTypes.variant("entity", EntityHit.class, ApiTypes.record(null,
                    ApiField.field("entityId", CommonTypes.ENTITY_ID).required().forGetter(EntityHit::entityId),
                    ApiField.field("entityType", CommonTypes.RESOURCE_ID).required().forGetter(EntityHit::entityType),
                    ApiField.field("pos", CommonTypes.POINT).required().forGetter(EntityHit::pos),
                    ApiField.field("distance", ApiTypes.number(0, Double.MAX_VALUE)).required().forGetter(EntityHit::distance),
                    EntityHit::new)));

    private static final ApiType<ClientView> CLIENT_VIEW = ApiTypes.record("ClientView",
            ApiField.field("hit", CROSSHAIR).describe("What the camera aims at.").required().forGetter(ClientView::hit),
            ApiField.field("fps", COUNT).required().forGetter(ClientView::fps),
            ClientView::new);

    public static final ApiType<PlayerState> PLAYER_STATE = ApiTypes.record("PlayerState",
            ApiField.field("name", ApiTypes.string()).required().forGetter(PlayerState::name),
            ApiField.field("uuid", ApiTypes.string()).required().forGetter(PlayerState::uuid),
            ApiField.field("dimension", CommonTypes.RESOURCE_ID).required().forGetter(PlayerState::dimension),
            ApiField.field("gameMode", ApiTypes.stringEnum("GameMode", "survival", "creative", "adventure", "spectator"))
                    .required().forGetter(PlayerState::gameMode),
            ApiField.field("pos", CommonTypes.POINT).describe("The feet.").required().forGetter(PlayerState::pos),
            ApiField.field("blockPos", CommonTypes.BLOCK_POSITION).describe("The cell the feet are in.")
                    .required().forGetter(PlayerState::blockPos),
            ApiField.field("look", LOOK).describe("Degrees. A turn shows here one tick later.").required().forGetter(PlayerState::look),
            ApiField.field("body", BODY_STATE).required().forGetter(PlayerState::body),
            ApiField.field("vitals", VITALS).required().forGetter(PlayerState::vitals),
            ApiField.field("effects", ApiTypes.list(EFFECT, 256)).required().forGetter(PlayerState::effects),
            ApiField.field("time", TIME).describe("The world's clock, to plan around the night.").required().forGetter(PlayerState::time),
            ApiField.field("attack", ATTACK).describe("The held weapon's melee recharge.").required().forGetter(PlayerState::attack),
            ApiField.field("equipment", EQUIPMENT).required().forGetter(PlayerState::equipment),
            ApiField.field("inventory", ApiTypes.list(INVENTORY_SLOT, 256))
                    .describe("Every non-empty slot of the whole bag, hotbar, armor and offhand included.")
                    .required().forGetter(PlayerState::inventory),
            ApiField.field("items", ApiTypes.map(COUNT, 4096))
                    .describe("Item id → count over main inventory and offhand: the have that mc.recipe.resolve and "
                            + "mc.plan.acquire take, and the bag mc.bot.craft consumes from.")
                    .required().forGetter(PlayerState::items),
            ApiField.field("view", CLIENT_VIEW).describe("Client fallback only.").optional().forGetter(PlayerState::view),
            PlayerState::new);

    public static final ApiType<PlayerParams> PLAYER_PARAMS = ApiTypes.record("ObservePlayerParams",
            ApiField.field("name", ApiTypes.string(1, 64))
                    .describe("A player's profile name. Omitted: the first player on the server, or this client's own.")
                    .optional().forGetter(PlayerParams::name),
            PlayerParams::new);

    public static final ApiType<PlayerReading> PLAYER_REPLY = ApiTypes.record("PlayerReading",
            ApiField.field("player", PLAYER_STATE).describe("Absent when nobody matches.")
                    .optional().forGetter(PlayerReading::player),
            PlayerReading::new);

    // Pollable: one player's fields, copied on the game thread in one task; it never waits for a tick.
    public static final MethodSpec<PlayerParams, PlayerReading> PLAYER = MethodSpec.of("mc.observe.player", PLAYER_PARAMS,
                    PLAYER_REPLY,
                    "One player's state: position, look, body, vitals and effects, the world's time, melee readiness, "
                            + "equipment, the whole inventory and an item-count map. On a client with no server attached it "
                            + "reads this client's own player, adds view (crosshair hit, fps), and a name that is not that "
                            + "player's reads as nobody.")
            .asPollable();

    // ------------------------------------------------------------------ threats

    public record ThreatsParams(int radius) {}

    public record Threat(int id, String type, Point pos, double distance, boolean canSeeMe, boolean facingMe,
                         boolean charging, double creeperSwell, boolean attackedMe, double threat) {}

    public record Projectile(int id, String type, Point pos, Point vel, boolean willHit, int ticksToImpact) {}

    /** Both lists are empty where there is no client: the scan reads what only a client renders. */
    public record Threats(List<Threat> threats, List<Projectile> incomingProjectiles) {}

    public static final ApiType<ThreatsParams> THREATS_PARAMS = ApiTypes.record("ThreatsParams",
            ApiField.field("radius", ApiTypes.integer(1, 64)).describe("Blocks around the player.")
                    .defaulted(24).forGetter(ThreatsParams::radius),
            ThreatsParams::new);

    private static final ApiType<Threat> THREAT = ApiTypes.record("Threat",
            ApiField.field("id", CommonTypes.ENTITY_ID).required().forGetter(Threat::id),
            ApiField.field("type", CommonTypes.RESOURCE_ID).required().forGetter(Threat::type),
            ApiField.field("pos", CommonTypes.POINT).required().forGetter(Threat::pos),
            ApiField.field("distance", DISTANCE).required().forGetter(Threat::distance),
            ApiField.field("canSeeMe", ApiTypes.bool()).describe("A line-of-sight raycast to the player.")
                    .required().forGetter(Threat::canSeeMe),
            ApiField.field("facingMe", ApiTypes.bool()).required().forGetter(Threat::facingMe),
            ApiField.field("charging", ApiTypes.bool()).describe("Drawing a bow.").required().forGetter(Threat::charging),
            ApiField.field("creeperSwell", ApiTypes.number(0, 1)).describe("Rises 0→1 as a creeper detonates.")
                    .required().forGetter(Threat::creeperSwell),
            ApiField.field("attackedMe", ApiTypes.bool()).describe("The player's last damager, within about 2 s.")
                    .required().forGetter(Threat::attackedMe),
            ApiField.field("threat", ApiTypes.number(0, 1)).describe("Danger score; deal with the highest first.")
                    .required().forGetter(Threat::threat),
            Threat::new);

    private static final ApiType<Projectile> PROJECTILE = ApiTypes.record("IncomingProjectile",
            ApiField.field("id", CommonTypes.ENTITY_ID).required().forGetter(Projectile::id),
            ApiField.field("type", CommonTypes.RESOURCE_ID).required().forGetter(Projectile::type),
            ApiField.field("pos", CommonTypes.POINT).required().forGetter(Projectile::pos),
            ApiField.field("vel", CommonTypes.POINT).describe("Blocks per tick.").required().forGetter(Projectile::vel),
            ApiField.field("willHit", ApiTypes.bool()).required().forGetter(Projectile::willHit),
            ApiField.field("ticksToImpact", ApiTypes.integer()).required().forGetter(Projectile::ticksToImpact),
            Projectile::new);

    public static final ApiType<Threats> THREATS_REPLY = ApiTypes.record("Threats",
            ApiField.field("threats", ApiTypes.list(THREAT, 1024)).describe("Most dangerous first.")
                    .required().forGetter(Threats::threats),
            ApiField.field("incomingProjectiles", ApiTypes.list(PROJECTILE, 1024)).required()
                    .forGetter(Threats::incomingProjectiles),
            Threats::new);

    // Pollable: one bounded scan of the client's own entity set, on the client thread.
    public static final MethodSpec<ThreatsParams, Threats> THREATS = MethodSpec.of("mc.observe.threats", THREATS_PARAMS,
                    THREATS_REPLY,
                    "Scored hostiles and incoming projectiles around this client's player, as the bot's own reflexes "
                            + "see them. Client only: empty lists without a client.")
            .asPollable();

    // ------------------------------------------------------------------ boss

    public record BossParams(int radius) {}

    public record Crystal(int id, Point pos, double distance, boolean caged) {}

    public enum DragonPhase implements WireName {
        PERCH("perch"), CIRCLING("circling"), STRAFING("strafing"), APPROACH("approach"), LANDING("landing"),
        TAKEOFF("takeoff"), CHARGING("charging"), DYING("dying"), HOVERING("hovering"), UNKNOWN("unknown");

        private final String wire;

        DragonPhase(String wire) { this.wire = wire; }

        @Override public String wireName() { return wire; }
    }

    public enum WitherPhase implements WireName {
        SPAWNING("spawning"), FIRST("first"), SECOND("second");

        private final String wire;

        WitherPhase(String wire) { this.wire = wire; }

        @Override public String wireName() { return wire; }
    }

    public record DragonHead(int id, Point pos) {}

    public sealed interface Boss permits Dragon, Wither {}

    public record Dragon(int id, double health, double maxHealth, double healthPct, Point pos, double distance,
                         DragonPhase phase, boolean perched, DragonHead head, int crystalsAlive) implements Boss {}

    public record Wither(int id, double health, double maxHealth, double healthPct, Point pos, double distance,
                         WitherPhase phase, int invulTicks, boolean powered) implements Boss {}

    /** {@code boss}: the nearest dragon or wither in range, absent when none is. */
    public record BossReading(List<Crystal> crystals, Optional<Boss> boss) {}

    public static final ApiType<BossParams> BOSS_PARAMS = ApiTypes.record("BossParams",
            ApiField.field("radius", ApiTypes.integer(1, 256)).describe("Blocks around the player; 64 covers the End pillars.")
                    .defaulted(64).forGetter(BossParams::radius),
            BossParams::new);

    private static final ApiType<Crystal> CRYSTAL = ApiTypes.record("EndCrystal",
            ApiField.field("id", CommonTypes.ENTITY_ID).required().forGetter(Crystal::id),
            ApiField.field("pos", CommonTypes.POINT).required().forGetter(Crystal::pos),
            ApiField.field("distance", DISTANCE).required().forGetter(Crystal::distance),
            ApiField.field("caged", ApiTypes.bool()).describe("Iron bars around it: climb in, it cannot be shot from below.")
                    .required().forGetter(Crystal::caged),
            Crystal::new);

    private static final UnionType<Boss> BOSS = ApiTypes.taggedUnion("Boss", "type",
            ApiTypes.variant("ender_dragon", Dragon.class, ApiTypes.record(null,
                    ApiField.field("id", CommonTypes.ENTITY_ID).required().forGetter(Dragon::id),
                    ApiField.field("health", ApiTypes.number()).required().forGetter(Dragon::health),
                    ApiField.field("maxHealth", ApiTypes.number()).required().forGetter(Dragon::maxHealth),
                    ApiField.field("healthPct", ApiTypes.number(0, 1)).required().forGetter(Dragon::healthPct),
                    ApiField.field("pos", CommonTypes.POINT).required().forGetter(Dragon::pos),
                    ApiField.field("distance", DISTANCE).required().forGetter(Dragon::distance),
                    ApiField.field("phase", ApiTypes.enumOf("DragonPhase", DragonPhase.class)).required().forGetter(Dragon::phase),
                    ApiField.field("perched", ApiTypes.bool()).describe("Sitting on the portal: the melee window.")
                            .required().forGetter(Dragon::perched),
                    ApiField.field("head", ApiTypes.record("DragonHead",
                            ApiField.field("id", CommonTypes.ENTITY_ID).required().forGetter(DragonHead::id),
                            ApiField.field("pos", CommonTypes.POINT).required().forGetter(DragonHead::pos),
                            DragonHead::new)).describe("The part that takes full damage.").required().forGetter(Dragon::head),
                    ApiField.field("crystalsAlive", COUNT).describe("The dragon heals while any remain.")
                            .required().forGetter(Dragon::crystalsAlive),
                    Dragon::new)),
            ApiTypes.variant("wither", Wither.class, ApiTypes.record(null,
                    ApiField.field("id", CommonTypes.ENTITY_ID).required().forGetter(Wither::id),
                    ApiField.field("health", ApiTypes.number()).required().forGetter(Wither::health),
                    ApiField.field("maxHealth", ApiTypes.number()).required().forGetter(Wither::maxHealth),
                    ApiField.field("healthPct", ApiTypes.number(0, 1)).required().forGetter(Wither::healthPct),
                    ApiField.field("pos", CommonTypes.POINT).required().forGetter(Wither::pos),
                    ApiField.field("distance", DISTANCE).required().forGetter(Wither::distance),
                    ApiField.field("phase", ApiTypes.enumOf("WitherPhase", WitherPhase.class))
                            .describe("spawning: invulnerable and about to explode; second: powered, immune to arrows.")
                            .required().forGetter(Wither::phase),
                    ApiField.field("invulTicks", COUNT).required().forGetter(Wither::invulTicks),
                    ApiField.field("powered", ApiTypes.bool()).describe("At or below half health.").required().forGetter(Wither::powered),
                    Wither::new)));

    public static final ApiType<BossReading> BOSS_REPLY = ApiTypes.record("BossReading",
            ApiField.field("crystals", ApiTypes.list(CRYSTAL, 1024)).describe("Every End crystal in range, boss or not.")
                    .required().forGetter(BossReading::crystals),
            ApiField.field("boss", BOSS).optional().forGetter(BossReading::boss),
            BossReading::new);

    // Pollable: one bounded scan of the client's own entity set, on the client thread.
    public static final MethodSpec<BossParams, BossReading> BOSS_SPEC = MethodSpec.of("mc.observe.boss", BOSS_PARAMS, BOSS_REPLY,
                    "The nearest ender dragon or wither in range, with its phase and health, and the End crystals. "
                            + "Client only: no boss and no crystals without a client.")
            .asPollable();

    // ------------------------------------------------------------------ scene

    public enum Render implements WireName {
        SUMMARY("summary"), MAP("map");

        private final String wire;

        Render(String wire) { this.wire = wire; }

        @Override public String wireName() { return wire; }
    }

    public enum Overlay implements WireName {
        HEIGHT("height"), SIGHT("sight"), MOB_DENSITY("mobDensity");

        private final String wire;

        Overlay(String wire) { this.wire = wire; }

        @Override public String wireName() { return wire; }
    }

    public record SceneParams(Optional<BlockPosition> center, int radius, Render render, List<Overlay> overlays,
                              Optional<Route> route) {}

    public record FleeStep(int dx, int dz) {}

    public record HazardSummary(int lethalCount, boolean cornered, Optional<FleeStep> safeFleeStep) {}

    public record HazardMap(List<String> rows, Map<String, String> legend) {}

    public record HeightStats(int centerY, Optional<Integer> minY, Optional<Integer> maxY) {}

    public record Observer(int id, String type, Optional<String> name) {}

    public record SightOverlay(List<Observer> observers, List<String> rows, int exposedCells,
                               Optional<Boolean> budgetExhausted, Optional<Boolean> snapshotTruncated) {}

    public record DensityOverlay(int mobs, double radius, int count, List<String> rows, int maxDensity, int clusteredCells,
                                 Optional<Boolean> snapshotTruncated) {}

    /** {@code requestedRadius}: present when the asked radius was over the limit and {@code radius} is the limit. */
    public record Scene(BlockPosition center, int radius, Optional<Integer> requestedRadius, HazardSummary hazardSummary,
                        Optional<HazardMap> map, Optional<HeightStats> height, Optional<SightOverlay> sight,
                        Optional<DensityOverlay> mobDensity) {}

    public static final ApiType<SceneParams> SCENE_PARAMS = ApiTypes.record("SceneParams",
            ApiField.field("center", CommonTypes.BLOCK_POSITION)
                    .describe("Omitted: the first player, or mc.system.testOrigin with nobody on.")
                    .optional().forGetter(SceneParams::center),
            ApiField.field("radius", ApiTypes.integer(1, Integer.MAX_VALUE))
                    .describe("Chebyshev radius; over the sceneQueryMaxRadius setting it is cut to that.")
                    .defaulted(12).forGetter(SceneParams::radius),
            ApiField.field("render", ApiTypes.enumOf("SceneRender", Render.class))
                    .describe("map: also the ASCII hazard grid and its legend.")
                    .defaulted(Render.SUMMARY).forGetter(SceneParams::render),
            ApiField.field("overlays", ApiTypes.list(ApiTypes.enumOf("SceneOverlay", Overlay.class), 3))
                    .describe("height: surface-height range. sight: per standable cell, how many observers see it. "
                            + "mobDensity: per cell, hostiles within the cluster radius. Rows align with the map's.")
                    .defaulted(List.of()).forGetter(SceneParams::overlays),
            ApiField.field("route", RouteContracts.ROUTE)
                    .describe("Which observers and mobs the overlays count: mc.bot.goto's route, of which sight and mobs "
                            + "are read. Omitted: ranged hostiles, cluster radius 6.")
                    .optional().forGetter(SceneParams::route),
            SceneParams::new);

    private static final ApiType<HazardSummary> HAZARD_SUMMARY = ApiTypes.record("HazardSummary",
            ApiField.field("lethalCount", COUNT).describe("Cells that would kill a 20-HP body.")
                    .required().forGetter(HazardSummary::lethalCount),
            ApiField.field("cornered", ApiTypes.bool()).describe("No safe step out.").required().forGetter(HazardSummary::cornered),
            ApiField.field("safeFleeStep", ApiTypes.record("FleeStep",
                    ApiField.field("dx", ApiTypes.integer(-1, 1)).required().forGetter(FleeStep::dx),
                    ApiField.field("dz", ApiTypes.integer(-1, 1)).required().forGetter(FleeStep::dz),
                    FleeStep::new)).describe("The safest step away from a threat to the south.")
                    .optional().forGetter(HazardSummary::safeFleeStep),
            HazardSummary::new);

    private static final ApiType<HazardMap> HAZARD_MAP = ApiTypes.record("HazardMap",
            ApiField.field("rows", ApiTypes.list(ApiTypes.string(), 1024))
                    .describe("North first; glyphs space-separated, @ the center.").required().forGetter(HazardMap::rows),
            ApiField.field("legend", ApiTypes.map(ApiTypes.string(), 64)).describe("Glyph → meaning.")
                    .required().forGetter(HazardMap::legend),
            HazardMap::new);

    private static final ApiType<HeightStats> HEIGHT_STATS = ApiTypes.record("HeightStats",
            ApiField.field("centerY", ApiTypes.integer()).required().forGetter(HeightStats::centerY),
            ApiField.field("minY", ApiTypes.integer()).describe("Absent: no standable cell.").optional().forGetter(HeightStats::minY),
            ApiField.field("maxY", ApiTypes.integer()).optional().forGetter(HeightStats::maxY),
            HeightStats::new);

    private static final ApiType<Observer> OBSERVER = ApiTypes.record("SightObserver",
            ApiField.field("id", CommonTypes.ENTITY_ID).required().forGetter(Observer::id),
            ApiField.field("type", ApiTypes.string()).required().forGetter(Observer::type),
            ApiField.field("name", ApiTypes.string()).describe("A custom or player name.").optional().forGetter(Observer::name),
            Observer::new);

    private static final ApiType<List<String>> OVERLAY_ROWS = ApiTypes.list(ApiTypes.string(), 1024)
            .describe("Like the map's rows: a digit per cell, + for ten or more, . no footing, ? beyond the ray budget.");

    private static final ApiType<SightOverlay> SIGHT_OVERLAY = ApiTypes.record("SightOverlay",
            ApiField.field("observers", ApiTypes.list(OBSERVER, 4096)).required().forGetter(SightOverlay::observers),
            ApiField.field("rows", OVERLAY_ROWS).required().forGetter(SightOverlay::rows),
            ApiField.field("exposedCells", COUNT).required().forGetter(SightOverlay::exposedCells),
            ApiField.field("budgetExhausted", ApiTypes.trueOnly()).optional().forGetter(SightOverlay::budgetExhausted),
            ApiField.field("snapshotTruncated", ApiTypes.trueOnly()).optional().forGetter(SightOverlay::snapshotTruncated),
            SightOverlay::new);

    private static final ApiType<DensityOverlay> DENSITY_OVERLAY = ApiTypes.record("DensityOverlay",
            ApiField.field("mobs", COUNT).describe("Counted mobs near enough to matter.").required().forGetter(DensityOverlay::mobs),
            ApiField.field("radius", ApiTypes.number(0, Double.MAX_VALUE)).describe("The cluster radius.")
                    .required().forGetter(DensityOverlay::radius),
            ApiField.field("count", COUNT).describe("How many within radius make a cluster.").required().forGetter(DensityOverlay::count),
            ApiField.field("rows", OVERLAY_ROWS).required().forGetter(DensityOverlay::rows),
            ApiField.field("maxDensity", COUNT).required().forGetter(DensityOverlay::maxDensity),
            ApiField.field("clusteredCells", COUNT).required().forGetter(DensityOverlay::clusteredCells),
            ApiField.field("snapshotTruncated", ApiTypes.trueOnly()).optional().forGetter(DensityOverlay::snapshotTruncated),
            DensityOverlay::new);

    public static final ApiType<Scene> SCENE_REPLY = ApiTypes.record("Scene",
            ApiField.field("center", CommonTypes.BLOCK_POSITION).required().forGetter(Scene::center),
            ApiField.field("radius", ApiTypes.integer(1, Integer.MAX_VALUE)).required().forGetter(Scene::radius),
            ApiField.field("requestedRadius", ApiTypes.integer(1, Integer.MAX_VALUE)).optional().forGetter(Scene::requestedRadius),
            ApiField.field("hazardSummary", HAZARD_SUMMARY).required().forGetter(Scene::hazardSummary),
            ApiField.field("map", HAZARD_MAP).describe("render: map only.").optional().forGetter(Scene::map),
            ApiField.field("height", HEIGHT_STATS).describe("The height overlay.").optional().forGetter(Scene::height),
            ApiField.field("sight", SIGHT_OVERLAY).optional().forGetter(Scene::sight),
            ApiField.field("mobDensity", DENSITY_OVERLAY).optional().forGetter(Scene::mobDensity),
            Scene::new);

    // Bulk: a hazard field up to sceneQueryMaxRadius plus a ray per cell for sight, in one server task.
    public static final MethodSpec<SceneParams, Scene> SCENE = MethodSpec.of("mc.observe.scene", SCENE_PARAMS, SCENE_REPLY,
                    "The server's hazard grid around a center: lethal cells, whether the center is cornered, the safest "
                            + "step away; optionally an ASCII hazard map and the height, sight and mobDensity overlays the "
                            + "goto route planner judges by. Works with no client.")
            .execution(MethodSpec.ExecutionClass.BULK);

    // ------------------------------------------------------------------ map

    public enum Plane implements WireName {
        XZ("xz"), XY("xy"), ZY("zy");

        private final String wire;

        Plane(String wire) { this.wire = wire; }

        @Override public String wireName() { return wire; }
    }

    private static final ApiType<Plane> PLANE = ApiTypes.enumOf("MapPlane", Plane.class);

    public record MapParams(Optional<BlockPosition> center, int radius, int height, Plane plane) {}

    public record AsciiMap(Plane plane, BlockPosition center, int radius, int width, int height, int threats, String legend,
                           String map) {}

    public static final ApiType<MapParams> MAP_PARAMS = ApiTypes.record("MapParams",
            ApiField.field("center", CommonTypes.BLOCK_POSITION).describe("Omitted: the first player's cell.")
                    .optional().forGetter(MapParams::center),
            ApiField.field("radius", ApiTypes.integer(1, 24)).describe("Horizontal half-extent.")
                    .defaulted(12).forGetter(MapParams::radius),
            ApiField.field("height", ApiTypes.integer(1, 24)).describe("Vertical half-extent of a cross-section.")
                    .defaulted(7).forGetter(MapParams::height),
            ApiField.field("plane", PLANE)
                    .describe("xz: top-down surface heightmap. xy, zy: a vertical slice through the center.")
                    .defaulted(Plane.XZ).forGetter(MapParams::plane),
            MapParams::new);

    public static final ApiType<AsciiMap> MAP_REPLY = ApiTypes.record("AsciiMap",
            ApiField.field("plane", PLANE).required().forGetter(AsciiMap::plane),
            ApiField.field("center", CommonTypes.BLOCK_POSITION).required().forGetter(AsciiMap::center),
            ApiField.field("radius", ApiTypes.integer(1, 24)).required().forGetter(AsciiMap::radius),
            ApiField.field("width", COUNT).describe("Characters per line.").required().forGetter(AsciiMap::width),
            ApiField.field("height", COUNT).describe("Lines.").required().forGetter(AsciiMap::height),
            ApiField.field("threats", COUNT).describe("Hostiles drawn by initial.").required().forGetter(AsciiMap::threats),
            ApiField.field("legend", ApiTypes.string()).required().forGetter(AsciiMap::legend),
            ApiField.field("map", ApiTypes.string()).describe("The grid, lines joined by newlines; north or +y on top.")
                    .required().forGetter(AsciiMap::map),
            AsciiMap::new);

    // Bulk: up to 49 × 49 columns walked top-down in one server task.
    public static final MethodSpec<MapParams, AsciiMap> MAP = MethodSpec.of("mc.observe.map", MAP_PARAMS, MAP_REPLY,
                    "A compact ASCII map for fast tactical reads: a top-down surface heightmap (# wall, v drop, . walk, "
                            + "~ water, ! lava) or a vertical slice, with hostiles drawn by initial and @ at the center. "
                            + "Works with no client; with no player a center is required.")
            .execution(MethodSpec.ExecutionClass.BULK);
}
