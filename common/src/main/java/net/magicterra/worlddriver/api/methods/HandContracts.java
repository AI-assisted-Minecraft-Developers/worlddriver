package net.magicterra.worlddriver.api.methods;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.magicterra.worlddriver.api.contract.ApiField;
import net.magicterra.worlddriver.api.contract.ApiIssue;
import net.magicterra.worlddriver.api.contract.ApiType;
import net.magicterra.worlddriver.api.contract.ApiTypes;
import net.magicterra.worlddriver.api.contract.MethodSpec;
import net.magicterra.worlddriver.api.contract.UnionType;
import net.magicterra.worlddriver.api.contract.WireName;
import net.magicterra.worlddriver.api.methods.ActionContract.ActionResponse;
import net.magicterra.worlddriver.api.methods.ActionContract.ImmediateResponse;
import net.magicterra.worlddriver.api.methods.CommonTypes.BlockPosition;

/**
 * The verbs that act within the call: aim, hold, use, hit, put on armour. Each answers {@code done} with
 * what came of it, or {@code refused} with the reasons a task is refused for; only a smooth look runs
 * long enough to be an action.
 */
public final class HandContracts {
    private HandContracts() {}

    public enum Hand implements WireName {
        MAIN, OFF;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    public enum Face implements WireName {
        DOWN, UP, NORTH, SOUTH, WEST, EAST;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    /** What the game made of a click; {@code pass} means nothing took it. */
    public enum Interaction implements WireName {
        SUCCESS, SUCCESS_NO_ITEM_USED, CONSUME, CONSUME_PARTIAL, PASS, FAIL;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    public static final ApiType<Hand> HAND = ApiTypes.enumOf("Hand", Hand.class);
    public static final ApiType<Face> FACE = ApiTypes.enumOf("BlockFace", Face.class);
    public static final ApiType<Interaction> INTERACTION = ApiTypes.enumOf("Interaction", Interaction.class);

    // ------------------------------------------------------------------ lookAt

    public record LookParams(String body, Optional<BlockPosition> pos, Optional<Double> yaw, Optional<Double> pitch,
                             int awaitMs) {}

    /** The aim a smooth look reached, as its action's result. */
    public record Looked(double yaw, double pitch) {}

    /** An instant look: the body already faces this way. */
    public record Turned(double yaw, double pitch) implements ActionResponse<Looked> {}

    public static final ApiType<LookParams> LOOK_PARAMS = ApiTypes.record("LookAtParams",
            ApiField.field("body", ActionContract.BODY_REF).defaulted("self").forGetter(LookParams::body),
            ApiField.field("pos", CommonTypes.BLOCK_POSITION).describe("Face the middle of this block.")
                    .optional().forGetter(LookParams::pos),
            ApiField.field("yaw", ApiTypes.number(-360, 360)).describe("With pitch, instead of pos.")
                    .optional().forGetter(LookParams::yaw),
            ApiField.field("pitch", ApiTypes.number(-90, 90)).describe("With yaw; positive looks down.")
                    .optional().forGetter(LookParams::pitch),
            ApiField.field("awaitMs", ActionContract.AWAIT_MS).describe("A smooth look only: wait for it to settle.")
                    .defaulted(0).forGetter(LookParams::awaitMs),
            LookParams::new)
            .checked((p, at) -> {
                if (p.pos().isPresent()) {
                    if (p.yaw().isPresent()) at.field("yaw").fail(ApiIssue.Code.INVALID_VALUE, "aim at pos or at angles, not both");
                    if (p.pitch().isPresent()) at.field("pitch").fail(ApiIssue.Code.INVALID_VALUE, "aim at pos or at angles, not both");
                } else if (p.yaw().isEmpty()) {
                    at.field(p.pitch().isPresent() ? "yaw" : "pos").fail(ApiIssue.Code.REQUIRED, "a pos, or both yaw and pitch");
                } else if (p.pitch().isEmpty()) {
                    at.field("pitch").fail(ApiIssue.Code.REQUIRED, "yaw needs a pitch");
                }
            });

    public static final UnionType<Looked> LOOK_RESULT = ApiTypes.taggedUnion("LookAtResult", "kind",
            ApiTypes.variant("lookAt", Looked.class, ApiTypes.record(null,
                    ApiField.field("yaw", ApiTypes.number()).required().forGetter(Looked::yaw),
                    ApiField.field("pitch", ApiTypes.number()).required().forGetter(Looked::pitch),
                    Looked::new)));

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static final MethodSpec<LookParams, ActionResponse<Looked>> LOOK_AT = MethodSpec.of("mc.bot.lookAt", LOOK_PARAMS,
                    ActionContract.response("LookAt", LOOK_RESULT,
                            (UnionType.Variant) ApiTypes.variant("done", Turned.class, ApiTypes.record(null,
                                    ApiField.field("yaw", ApiTypes.number()).required().forGetter(Turned::yaw),
                                    ApiField.field("pitch", ApiTypes.number()).required().forGetter(Turned::pitch),
                                    Turned::new))),
                    "Aim the view at a block or at explicit angles. Answers done, already facing that way; with the "
                            + "smoothLook setting on it instead pans over the next ticks as an action, which replaces the "
                            + "body's task like any other. On self the server's copy of the player turns a tick later, so "
                            + "mc.observe.player reads the old look until then.")
            .effects(MethodSpec.Effect.BODY_TASK)
            .mayWait();

    // ------------------------------------------------------------------ holdItem

    public record HoldParams(String body, String item) {}

    public record Held(String held) implements ImmediateResponse {}

    public static final ApiType<HoldParams> HOLD_PARAMS = ApiTypes.record("HoldItemParams",
            ApiField.field("body", ActionContract.BODY_REF).defaulted("self").forGetter(HoldParams::body),
            ApiField.field("item", CommonTypes.RESOURCE_ID).required().forGetter(HoldParams::item),
            HoldParams::new);

    public static final MethodSpec<HoldParams, ImmediateResponse> HOLD_ITEM = MethodSpec.of("mc.bot.holdItem", HOLD_PARAMS,
                    ActionContract.immediate("HoldItem", Held.class, ApiTypes.record(null,
                            ApiField.field("held", CommonTypes.RESOURCE_ID).describe("The item now in the main hand.")
                                    .required().forGetter(Held::held),
                            Held::new)),
                    "Put an item in the main hand: select its hotbar slot, or swap it up from anywhere in the inventory. "
                            + "minecraft:air empties the hand by selecting an empty hotbar slot. "
                            + "Refused as invalid_world_target when the body has none.")
            .effects(MethodSpec.Effect.INVENTORY_WRITE);

    // ------------------------------------------------------------------ useItem

    public record UseParams(String body, Optional<BlockPosition> pos, Optional<Integer> entityId, Optional<Face> face,
                            Hand hand, boolean lookAt, boolean sneak) {}

    /** What the item was used on. */
    public sealed interface UsedOn permits InAir, OnBlock, OnEntity {}
    public record InAir() implements UsedOn {}
    public record OnBlock(BlockPosition pos, Face face) implements UsedOn {}
    /**
     * {@code riding}: the vehicle afterwards. {@code screen}: the screen open afterwards on this client;
     * {@code menu}: the menu open afterwards on another bot, which has no screen.
     */
    public record OnEntity(int entityId, String type, double distance, Optional<String> riding, Optional<String> screen,
                           Optional<String> menu) implements UsedOn {}

    public record Used(Hand hand, Interaction result, boolean consumed, UsedOn on) implements ImmediateResponse {}

    public static final ApiType<UseParams> USE_PARAMS = ApiTypes.record("UseItemParams",
            ApiField.field("body", ActionContract.BODY_REF).defaulted("self").forGetter(UseParams::body),
            ApiField.field("pos", CommonTypes.BLOCK_POSITION).describe("Use on this block's face. Omit pos and entityId to use in the air.")
                    .optional().forGetter(UseParams::pos),
            ApiField.field("entityId", CommonTypes.ENTITY_ID).describe("Use on this entity: mount, trade, shear, feed.")
                    .optional().forGetter(UseParams::entityId),
            ApiField.field("face", FACE).describe("With pos: the face clicked. Omitted: the one facing the body.")
                    .optional().forGetter(UseParams::face),
            ApiField.field("hand", HAND).defaulted(Hand.MAIN).forGetter(UseParams::hand),
            ApiField.field("lookAt", ApiTypes.bool()).describe("Turn toward the block or entity first.")
                    .defaulted(true).forGetter(UseParams::lookAt),
            ApiField.field("sneak", ApiTypes.bool()).describe("With entityId: hold sneak for this click.")
                    .defaulted(false).forGetter(UseParams::sneak),
            UseParams::new)
            .checked((p, at) -> {
                if (p.pos().isPresent() && p.entityId().isPresent()) {
                    at.field("entityId").fail(ApiIssue.Code.INVALID_VALUE, "use on a block or on an entity, not both");
                }
                if (p.face().isPresent() && p.pos().isEmpty()) {
                    at.field("face").fail(ApiIssue.Code.INVALID_VALUE, "a face belongs to a pos");
                }
                if (p.sneak() && p.entityId().isEmpty()) {
                    at.field("sneak").fail(ApiIssue.Code.INVALID_VALUE, "sneak is for a use on an entity");
                }
            });

    public static final UnionType<UsedOn> USED_ON = ApiTypes.taggedUnion("UsedOn", "kind",
            ApiTypes.variant("air", InAir.class, ApiTypes.record(null, InAir::new)),
            ApiTypes.variant("block", OnBlock.class, ApiTypes.record(null,
                    ApiField.field("pos", CommonTypes.BLOCK_POSITION).required().forGetter(OnBlock::pos),
                    ApiField.field("face", FACE).required().forGetter(OnBlock::face),
                    OnBlock::new)),
            ApiTypes.variant("entity", OnEntity.class, ApiTypes.record(null,
                    ApiField.field("entityId", CommonTypes.ENTITY_ID).required().forGetter(OnEntity::entityId),
                    ApiField.field("type", CommonTypes.RESOURCE_ID).required().forGetter(OnEntity::type),
                    ApiField.field("distance", ApiTypes.number()).required().forGetter(OnEntity::distance),
                    ApiField.field("riding", CommonTypes.RESOURCE_ID).optional().forGetter(OnEntity::riding),
                    ApiField.field("screen", ApiTypes.string()).optional().forGetter(OnEntity::screen),
                    ApiField.field("menu", ApiTypes.string()).optional().forGetter(OnEntity::menu),
                    OnEntity::new)));

    public static final MethodSpec<UseParams, ImmediateResponse> USE_ITEM = MethodSpec.of("mc.bot.useItem", USE_PARAMS,
                    ActionContract.immediate("UseItem", Used.class, ApiTypes.record(null,
                            ApiField.field("hand", HAND).required().forGetter(Used::hand),
                            ApiField.field("result", INTERACTION).required().forGetter(Used::result),
                            ApiField.field("consumed", ApiTypes.bool()).describe("The click did something.")
                                    .required().forGetter(Used::consumed),
                            ApiField.field("on", USED_ON).required().forGetter(Used::on),
                            Used::new)),
                    "Right-click with the held item: in the air (eat, throw, draw a bow), on a block face with pos (place, "
                            + "pour, bone-meal), or on an entity with entityId (an empty hand mounts). Out of reach is "
                            + "refused as invalid_world_target.")
            .effects(MethodSpec.Effect.INVENTORY_WRITE, MethodSpec.Effect.WORLD_WRITE);

    // ------------------------------------------------------------------ attackEntity

    public record AttackParams(String body, int entityId) {}

    public record Attacked(int entityId, String type, boolean alive, double distance) implements ImmediateResponse {}

    public static final ApiType<AttackParams> ATTACK_PARAMS = ApiTypes.record("AttackEntityParams",
            ApiField.field("body", ActionContract.BODY_REF).defaulted("self").forGetter(AttackParams::body),
            ApiField.field("entityId", CommonTypes.ENTITY_ID).required().forGetter(AttackParams::entityId),
            AttackParams::new);

    public static final MethodSpec<AttackParams, ImmediateResponse> ATTACK_ENTITY = MethodSpec.of("mc.bot.attackEntity",
                    ATTACK_PARAMS,
                    ActionContract.immediate("AttackEntity", Attacked.class, ApiTypes.record(null,
                            ApiField.field("entityId", CommonTypes.ENTITY_ID).required().forGetter(Attacked::entityId),
                            ApiField.field("type", CommonTypes.RESOURCE_ID).required().forGetter(Attacked::type),
                            ApiField.field("alive", ApiTypes.bool()).required().forGetter(Attacked::alive),
                            ApiField.field("distance", ApiTypes.number()).required().forGetter(Attacked::distance),
                            Attacked::new)),
                    "One left click on an entity, facing it. Read attack on the player snapshot first: a swing before "
                            + "the weapon recharges lands for a fraction of its damage. A missing entity, one out of "
                            + "reach, or a blast that would take the block underfoot is refused as invalid_world_target.")
            .effects(MethodSpec.Effect.WORLD_WRITE);

    // ------------------------------------------------------------------ equip

    public enum Profile implements WireName {
        /** Armour and the best weapon in hand. */
        BEST,
        /** The same as best. */
        COMBAT,
        /** Armour only. */
        ARMOR;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    public enum ArmorSlot implements WireName {
        HEAD, CHEST, LEGS, FEET;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    public record EquipParams(Profile profile, boolean armorOnly) {}

    /** What each slot holds afterwards; an empty slot is absent. */
    public record Loadout(Optional<String> head, Optional<String> chest, Optional<String> legs, Optional<String> feet,
                          Optional<String> mainHand) {}

    public record Equipped(Profile profile, List<String> equipped, Loadout loadout, List<String> lowDurability,
                           List<ArmorSlot> missing) implements ImmediateResponse {}

    private static final ApiType<Profile> PROFILE = ApiTypes.enumOf("EquipProfile", Profile.class);

    public static final ApiType<EquipParams> EQUIP_PARAMS = ApiTypes.record("EquipParams",
            ApiField.field("profile", PROFILE).defaulted(Profile.BEST)
                    .forGetter(EquipParams::profile),
            ApiField.field("armorOnly", ApiTypes.bool()).describe("The same as profile armor.")
                    .defaulted(false).forGetter(EquipParams::armorOnly),
            EquipParams::new);

    private static final ApiType<Loadout> LOADOUT = ApiTypes.record("Loadout",
            ApiField.field("head", CommonTypes.RESOURCE_ID).optional().forGetter(Loadout::head),
            ApiField.field("chest", CommonTypes.RESOURCE_ID).optional().forGetter(Loadout::chest),
            ApiField.field("legs", CommonTypes.RESOURCE_ID).optional().forGetter(Loadout::legs),
            ApiField.field("feet", CommonTypes.RESOURCE_ID).optional().forGetter(Loadout::feet),
            ApiField.field("mainHand", CommonTypes.RESOURCE_ID).optional().forGetter(Loadout::mainHand),
            Loadout::new);

    public static final MethodSpec<EquipParams, ImmediateResponse> EQUIP = MethodSpec.of("mc.bot.equip", EQUIP_PARAMS,
                    ActionContract.immediate("Equip", Equipped.class, ApiTypes.record(null,
                            ApiField.field("profile", PROFILE).required().forGetter(Equipped::profile),
                            ApiField.field("equipped", ApiTypes.list(CommonTypes.RESOURCE_ID, 16))
                                    .describe("What it put on or took in hand.").required().forGetter(Equipped::equipped),
                            ApiField.field("loadout", LOADOUT).required().forGetter(Equipped::loadout),
                            ApiField.field("lowDurability", ApiTypes.list(CommonTypes.RESOURCE_ID, 16))
                                    .describe("Worn or held items below the equipDurabilityThreshold setting.")
                                    .required().forGetter(Equipped::lowDurability),
                            ApiField.field("missing", ApiTypes.list(ApiTypes.enumOf("ArmorSlot", ArmorSlot.class), 4))
                                    .describe("Armour slots still empty.").required().forGetter(Equipped::missing),
                            Equipped::new)),
                    "Put on the best armour in every slot and, unless armour only, the best weapon in hand (a sword "
                            + "over an axe), scoring material tier and then enchantments. lowDurability and missing are "
                            + "what is left to repair or craft. This client's own player only.")
            .effects(MethodSpec.Effect.INVENTORY_WRITE);
}
