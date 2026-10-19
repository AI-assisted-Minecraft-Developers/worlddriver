package net.magicterra.worlddriver.api.methods.interact;

import java.util.Locale;
import java.util.Optional;

import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.EntityId;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.ResourceId;
import net.magicterra.worlddriver.api.methods.shared.Responses.ActionResponse;
import net.magicterra.worlddriver.api.methods.shared.Responses.Attacked;
import net.magicterra.worlddriver.api.methods.shared.Responses.AwaitMs;
import net.magicterra.worlddriver.api.methods.shared.Responses.BotRef;
import net.magicterra.worlddriver.api.methods.shared.Responses.Equipped;
import net.magicterra.worlddriver.api.methods.shared.Responses.Held;
import net.magicterra.worlddriver.api.methods.shared.Responses.ImmediateResponse;
import net.magicterra.worlddriver.api.methods.shared.Responses.Turned;
import net.magicterra.worlddriver.api.methods.shared.Responses.Used;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.DecodeContext;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.SelfChecked;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.schema.WireName;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.schema.annotation.Tag;
import net.magicterra.worlddriver.api.schema.annotation.Variant;

/**
 * The verbs that act within the call: aim, hold, use, hit, put on armour. Each answers {@code done} with
 * what came of it, or {@code refused} with the reasons a task is refused for; only a smooth look runs
 * long enough to be an action.
 */
public final class InteractContracts {
    private InteractContracts() {}

    @Named("Hand")
    public enum Hand implements WireName {
        MAIN, OFF;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    @Named("BlockFace")
    public enum Face implements WireName {
        DOWN, UP, NORTH, SOUTH, WEST, EAST;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    /** What the game made of a click; {@code pass} means nothing took it. */
    @Named("Interaction")
    public enum Interaction implements WireName {
        SUCCESS, SUCCESS_NO_ITEM_USED, CONSUME, CONSUME_PARTIAL, PASS, FAIL;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    public static final ApiType<Hand> HAND = ApiTypes.of(Hand.class);
    public static final ApiType<Face> FACE = ApiTypes.of(Face.class);
    public static final ApiType<Interaction> INTERACTION = ApiTypes.of(Interaction.class);

    // ------------------------------------------------------------------ lookAt

    @Named("LookAtParams")
    public record LookParams(
            @Default("self") @BotRef String bot,
            @Doc("Face the middle of this block.") Optional<BlockPosition> pos,
            @Doc("With pitch, instead of pos.") Optional<@Range(min = -360, max = 360) Double> yaw,
            @Doc("With yaw; positive looks down.") Optional<@Range(min = -90, max = 90) Double> pitch,
            @Doc("A smooth look only: wait for it to settle.") @Default("0") @AwaitMs int awaitMs) implements SelfChecked {
        @Override public void check(DecodeContext at) {
            if (pos.isPresent()) {
                if (yaw.isPresent()) at.field("yaw").fail(ApiIssue.Code.INVALID_VALUE, "aim at pos or at angles, not both");
                if (pitch.isPresent()) at.field("pitch").fail(ApiIssue.Code.INVALID_VALUE, "aim at pos or at angles, not both");
            } else if (yaw.isEmpty()) {
                at.field(pitch.isPresent() ? "yaw" : "pos").fail(ApiIssue.Code.REQUIRED, "a pos, or both yaw and pitch");
            } else if (pitch.isEmpty()) {
                at.field("pitch").fail(ApiIssue.Code.REQUIRED, "yaw needs a pitch");
            }
        }
    }

    /** The aim a smooth look reached, as its action's result. */
    @Variant("lookAt")
    public record Looked(double yaw, double pitch) {}

    public static final ApiType<LookParams> LOOK_PARAMS = ApiTypes.record(LookParams.class);

    public static final UnionType<Looked> LOOK_RESULT = ApiTypes.union("LookAtResult", "kind", ApiTypes.record(Looked.class));

    public static final MethodSpec<LookParams, ActionResponse<Looked>> LOOK_AT = MethodSpec.of("mc.bot.lookAt", LOOK_PARAMS,
                    Responses.response("LookAt", LOOK_RESULT, Turned.class),
                    "Aim the view at a block or at explicit angles. Answers done, already facing that way; with the "
                            + "smoothLook setting on it instead pans over the next ticks as an action, which replaces the "
                            + "body's task like any other. On self the server's copy of the player turns a tick later, so "
                            + "mc.observe.player reads the old look until then.")
            .effects(MethodSpec.Effect.BODY_TASK)
            .mayWait();

    // ------------------------------------------------------------------ holdItem

    @Named("HoldItemParams")
    public record HoldParams(@Default("self") @BotRef String bot, @ResourceId String item) {}

    public static final ApiType<HoldParams> HOLD_PARAMS = ApiTypes.record(HoldParams.class);

    public static final MethodSpec<HoldParams, ImmediateResponse> HOLD_ITEM = MethodSpec.of("mc.bot.holdItem", HOLD_PARAMS,
                    Responses.immediate("HoldItem", ApiTypes.record(Held.class)),
                    "Put an item in the main hand: select its hotbar slot, or swap it up from anywhere in the inventory. "
                            + "minecraft:air empties the hand by selecting an empty hotbar slot. "
                            + "Refused as invalid_world_target when the body has none.")
            .effects(MethodSpec.Effect.INVENTORY_WRITE);

    // ------------------------------------------------------------------ useItem

    @Named("UseItemParams")
    public record UseParams(
            @Default("self") @BotRef String bot,
            @Doc("Use on this block's face. Omit pos and entityId to use in the air.") Optional<BlockPosition> pos,
            @Doc("Use on this entity: mount, trade, shear, feed.") Optional<@EntityId Integer> entityId,
            @Doc("With pos: the face clicked. Omitted: the one facing the body.") Optional<Face> face,
            @Default("main") Hand hand,
            @Doc("Turn toward the block or entity first.") @Default("true") boolean lookAt,
            @Doc("With entityId: hold sneak for this click.") @Default("false") boolean sneak) implements SelfChecked {
        @Override public void check(DecodeContext at) {
            if (pos.isPresent() && entityId.isPresent()) {
                at.field("entityId").fail(ApiIssue.Code.INVALID_VALUE, "use on a block or on an entity, not both");
            }
            if (face.isPresent() && pos.isEmpty()) {
                at.field("face").fail(ApiIssue.Code.INVALID_VALUE, "a face belongs to a pos");
            }
            if (sneak && entityId.isEmpty()) {
                at.field("sneak").fail(ApiIssue.Code.INVALID_VALUE, "sneak is for a use on an entity");
            }
        }
    }

    /** What the item was used on. */
    @Tag("kind") @Named("UsedOn")
    public sealed interface UsedOn permits InAir, OnBlock, OnEntity {}
    @Variant("air")
    public record InAir() implements UsedOn {}
    @Variant("block")
    public record OnBlock(BlockPosition pos, Face face) implements UsedOn {}
    /**
     * {@code riding}: the vehicle afterwards. {@code screen}: the screen open afterwards on this client;
     * {@code menu}: the menu open afterwards on another bot, which has no screen.
     */
    @Variant("entity")
    public record OnEntity(
            @EntityId int entityId,
            @ResourceId String type,
            double distance,
            Optional<@ResourceId String> riding,
            Optional<String> screen,
            Optional<String> menu) implements UsedOn {}

    public static final ApiType<UseParams> USE_PARAMS = ApiTypes.record(UseParams.class);

    public static final UnionType<UsedOn> USED_ON = ApiTypes.union(UsedOn.class);

    public static final MethodSpec<UseParams, ImmediateResponse> USE_ITEM = MethodSpec.of("mc.bot.useItem", USE_PARAMS,
                    Responses.immediate("UseItem", ApiTypes.record(Used.class)),
                    "Right-click with the held item: in the air (eat, throw, draw a bow), on a block face with pos (place, "
                            + "pour, bone-meal), or on an entity with entityId (an empty hand mounts). A named bot "
                            + "refuses a target out of reach as invalid_world_target; self clicks anyway and the server "
                            + "ignores one out of reach, so read distance in the reply.")
            .effects(MethodSpec.Effect.INVENTORY_WRITE, MethodSpec.Effect.WORLD_WRITE);

    // ------------------------------------------------------------------ attackEntity

    @Named("AttackEntityParams")
    public record AttackParams(@Default("self") @BotRef String bot, @EntityId int entityId) {}

    public static final ApiType<AttackParams> ATTACK_PARAMS = ApiTypes.record(AttackParams.class);

    public static final MethodSpec<AttackParams, ImmediateResponse> ATTACK_ENTITY = MethodSpec.of("mc.bot.attackEntity",
                    ATTACK_PARAMS,
                    Responses.immediate("AttackEntity", ApiTypes.record(Attacked.class)),
                    "One left click on an entity, facing it. Read attack on the player snapshot first: a swing before "
                            + "the weapon recharges lands for a fraction of its damage. A missing entity, or a blast that "
                            + "would take the block underfoot, is refused as invalid_world_target, and so is one out of "
                            + "reach for a named bot; self swings anyway and the server ignores a target out of reach, so "
                            + "read distance and alive in the reply.")
            .effects(MethodSpec.Effect.WORLD_WRITE);

    // ------------------------------------------------------------------ equip

    @Named("EquipProfile")
    public enum Profile implements WireName {
        /** Armour and the best weapon in hand. */
        BEST,
        /** The same as best. */
        COMBAT,
        /** Armour only. */
        ARMOR;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    @Named("ArmorSlot")
    public enum ArmorSlot implements WireName {
        HEAD, CHEST, LEGS, FEET;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    @Named("EquipParams")
    public record EquipParams(
            @Default("best") Profile profile,
            @Doc("The same as profile armor.") @Default("false") boolean armorOnly) {}

    /** What each slot holds afterwards; an empty slot is absent. */
    @Named("Loadout")
    public record Loadout(
            Optional<@ResourceId String> head,
            Optional<@ResourceId String> chest,
            Optional<@ResourceId String> legs,
            Optional<@ResourceId String> feet,
            Optional<@ResourceId String> mainHand) {}

    public static final ApiType<EquipParams> EQUIP_PARAMS = ApiTypes.record(EquipParams.class);

    public static final MethodSpec<EquipParams, ImmediateResponse> EQUIP = MethodSpec.of("mc.bot.equip", EQUIP_PARAMS,
                    Responses.immediate("Equip", ApiTypes.record(Equipped.class)),
                    "Put on the best armour in every slot and, unless armour only, the best weapon in hand (a sword "
                            + "over an axe), scoring material tier and then enchantments. lowDurability and missing are "
                            + "what is left to repair or craft. This client's own player only.")
            .effects(MethodSpec.Effect.INVENTORY_WRITE);
}
