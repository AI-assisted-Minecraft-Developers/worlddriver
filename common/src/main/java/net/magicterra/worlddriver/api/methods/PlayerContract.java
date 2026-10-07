package net.magicterra.worlddriver.api.methods;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.magicterra.worlddriver.api.contract.ApiField;
import net.magicterra.worlddriver.api.contract.ApiType;
import net.magicterra.worlddriver.api.contract.ApiTypes;
import net.magicterra.worlddriver.api.contract.MethodSpec;
import net.magicterra.worlddriver.api.contract.RecordType;
import net.magicterra.worlddriver.api.contract.UnionType;
import net.magicterra.worlddriver.api.contract.WireName;
import net.magicterra.worlddriver.api.methods.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.CommonTypes.Look;
import net.magicterra.worlddriver.api.methods.CommonTypes.Point;

/**
 * {@code mc.observe.player}: one player's state. The server reads a player in its player list; a client
 * with no server attached reads its own player. Both build the same record, and the client adds what only
 * it has ({@code view}: the crosshair and the frame rate).
 */
public final class PlayerContract {
    private PlayerContract() {}

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

    // ------------------------------------------------------------------ types

    private static final ApiType<Integer> COUNT = ApiTypes.integer(0, Integer.MAX_VALUE);

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

    public static final ApiType<PlayerParams> PARAMS = ApiTypes.record("ObservePlayerParams",
            ApiField.field("name", ApiTypes.string(1, 64))
                    .describe("A player's profile name. Omitted: the first player on the server, or this client's own.")
                    .optional().forGetter(PlayerParams::name),
            PlayerParams::new);

    public static final ApiType<PlayerReading> READING = ApiTypes.record("PlayerReading",
            ApiField.field("player", PLAYER_STATE).describe("Absent when nobody matches.")
                    .optional().forGetter(PlayerReading::player),
            PlayerReading::new);

    // Pollable: one player's fields, copied on the game thread in one task; it never waits for a tick.
    public static final MethodSpec<PlayerParams, PlayerReading> SPEC = MethodSpec.of("mc.observe.player", PARAMS, READING,
                    "One player's state: position, look, body, vitals and effects, the world's time, melee readiness, "
                            + "equipment, the whole inventory and an item-count map. On a client with no server attached it "
                            + "reads this client's own player, adds view (crosshair hit, fps), and a name that is not that "
                            + "player's reads as nobody.")
            .asPollable();
}
