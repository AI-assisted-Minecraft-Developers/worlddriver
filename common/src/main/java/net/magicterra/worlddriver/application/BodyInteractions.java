package net.magicterra.worlddriver.application;

import java.util.Optional;
import net.magicterra.worlddriver.api.game.Positions;
import net.magicterra.worlddriver.api.contract.ApiException;
import net.magicterra.worlddriver.api.contract.ApiIssue;
import net.magicterra.worlddriver.api.methods.interact.InteractContracts;
import net.magicterra.worlddriver.api.methods.interact.InteractContracts.Face;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.methods.shared.Responses.ImmediateResponse;
import net.magicterra.worlddriver.api.methods.shared.Responses.RefusalReason;
import net.magicterra.worlddriver.api.methods.shared.Responses.Refused;
import net.magicterra.worlddriver.bot.body.BodyHost;
import net.magicterra.worlddriver.bot.body.Hands;
import net.magicterra.worlddriver.bot.util.BotUtil;
import net.magicterra.worlddriver.bot.util.HandVerbs;
import net.magicterra.worlddriver.bot.util.RegistryIds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * {@code mc.bot.lookAt}, {@code holdItem}, {@code useItem} and {@code attackEntity} on a registered bot, run on
 * the server thread.
 *
 * <p>The client's versions send what a click sends and leave the rest to the server's packet handlers.
 * A server-side bot has no connection to send through, so these do what those handlers do with the
 * packet, reach check included, and answer with what came of it. Two answers differ for that reason: a
 * use or an attack out of reach is refused here where the client's is ignored, and a use on an entity
 * names the open {@code menu} where the client names its {@code screen}.
 *
 * <p>A bot without hands, an NPC, refuses all but {@code lookAt} as {@code unsupported_capability}. Nothing
 * here may name a client class.
 */
final class BodyInteractions {

    private BodyInteractions() {}

    /** The slack the server's use and attack handlers add to the interaction range. */
    private static final double REACH_SLACK = 1.0;

    /** Yaw and pitch from {@code self}'s eyes to {@code p}'s block or angles. */
    static float[] aim(LivingEntity self, InteractContracts.LookParams p) {
        if (p.pos().isEmpty()) return new float[]{p.yaw().orElseThrow().floatValue(), p.pitch().orElseThrow().floatValue()};
        BlockPosition at = p.pos().get();
        return HandVerbs.anglesTo(self.getEyePosition(), at.x() + 0.5, at.y() + 0.5, at.z() + 0.5);
    }

    /** An instant look, on whichever body's thread {@code self} belongs to. */
    static Responses.Turned turn(LivingEntity self, InteractContracts.LookParams p) {
        float[] aim = aim(self, p);
        HandVerbs.turn(self, aim);
        return new Responses.Turned(aim[0], aim[1]);
    }

    /** The item into the main hand through {@link Hands#holdItem}, the reach every process trusts. */
    static ImmediateResponse holdItem(BodyHost host, InteractContracts.HoldParams p) {
        Hands hands = host.body().hands().orElse(null);
        if (hands == null) return noHands(host);
        Item item = RegistryIds.lookup(BuiltInRegistries.ITEM, p.item()).orElseThrow(() -> ApiException.invalidParam(
                "/item", ApiIssue.Code.INVALID_VALUE, "no item " + p.item() + " in this game"));
        return hands.holdItemOrEmptyHand(item) ? new Responses.Held(RegistryIds.itemId(host.entity().getMainHandItem()))
                : new Refused<>(RefusalReason.INVALID_WORLD_TARGET, Hands.cannotHold(item, host.id()));
    }

    static ImmediateResponse useItem(BodyHost host, InteractContracts.UseParams p) {
        ServerPlayer sp = host.body().hands().isPresent() && host.entity() instanceof ServerPlayer s ? s : null;
        if (sp == null) return noHands(host);
        if (p.entityId().isPresent()) return useOnEntity(sp, p, p.entityId().get());
        if (p.pos().isPresent()) return useOnBlock(sp, p, p.pos().get());
        InteractionHand hand = HandVerbs.hand(p.hand());
        sp.setShiftKeyDown(false);
        InteractionResult result = sp.gameMode.useItem(sp, sp.serverLevel(), sp.getItemInHand(hand), hand);
        if (result.consumesAction()) sp.swing(hand, true);
        return new Responses.Used(p.hand(), HandVerbs.interaction(result), result.consumesAction(), new InteractContracts.InAir());
    }

    private static ImmediateResponse useOnBlock(ServerPlayer sp, InteractContracts.UseParams p, BlockPosition at) {
        BlockPos pos = Positions.blockPos(at);
        Direction face = p.face().map(f -> Direction.byName(f.wireName())).orElseGet(() -> BotUtil.faceTowardEye(pos, sp));
        InteractionHand hand = HandVerbs.hand(p.hand());
        Vec3 hit = HandVerbs.faceCentre(pos, face);
        if (p.lookAt()) HandVerbs.turn(sp, HandVerbs.anglesTo(sp.getEyePosition(), hit.x, hit.y, hit.z));
        sp.setShiftKeyDown(false);
        if (!sp.canInteractWithBlock(pos, REACH_SLACK)) {
            return new Refused<>(RefusalReason.INVALID_WORLD_TARGET, "pos " + pos.toShortString() + " is out of reach");
        }
        InteractionResult result = sp.gameMode.useItemOn(sp, sp.serverLevel(), sp.getItemInHand(hand), hand,
                new BlockHitResult(hit, face, pos, false));
        if (result.consumesAction()) sp.swing(hand, true);
        return new Responses.Used(p.hand(), HandVerbs.interaction(result), result.consumesAction(),
                new InteractContracts.OnBlock(at, Face.valueOf(face.name())));
    }

    private static ImmediateResponse useOnEntity(ServerPlayer sp, InteractContracts.UseParams p, int entityId) {
        Entity target = sp.serverLevel().getEntity(entityId);
        if (target == null) return new Refused<>(RefusalReason.INVALID_WORLD_TARGET, "no entity with id " + entityId);
        if (target == sp) return new Refused<>(RefusalReason.INVALID_WORLD_TARGET, "cannot interact with self");
        InteractionHand hand = HandVerbs.hand(p.hand());
        if (p.lookAt()) HandVerbs.turn(sp, HandVerbs.anglesAt(sp, target));
        double distance = Math.sqrt(sp.distanceToSqr(target));
        if (!sp.canInteractWithEntity(target, REACH_SLACK)) {
            return new Refused<>(RefusalReason.INVALID_WORLD_TARGET, "entity " + entityId + " is out of reach at " + distance);
        }
        // Sneak-gated interactions read the shift flag during this call only.
        sp.setShiftKeyDown(p.sneak());
        try {
            // The client's pair of packets: interactAt first, interact when that did not consume. Its hit is
            // the entity's position, so the location relative to the entity is zero.
            InteractionResult result = target.interactAt(sp, Vec3.ZERO, hand);
            if (!result.consumesAction()) result = sp.interactOn(target, hand);
            if (result.consumesAction()) sp.swing(hand, true);
            Entity vehicle = sp.getVehicle();
            return new Responses.Used(p.hand(), HandVerbs.interaction(result), result.consumesAction(),
                    new InteractContracts.OnEntity(entityId, RegistryIds.typeId(target), distance,
                            vehicle == null ? Optional.empty() : Optional.of(RegistryIds.typeId(vehicle)), Optional.empty(),
                            sp.containerMenu == sp.inventoryMenu ? Optional.empty()
                                    : Optional.of(sp.containerMenu.getClass().getSimpleName())));
        } finally {
            sp.setShiftKeyDown(false);
        }
    }

    /** Through {@link Hands#attackEntity}, as the client's verb goes: the footing guard lives on that one path. */
    static ImmediateResponse attackEntity(BodyHost host, InteractContracts.AttackParams p) {
        Hands hands = host.body().hands().orElse(null);
        if (hands == null) return noHands(host);
        LivingEntity self = host.entity();
        Entity target = self.level().getEntity(p.entityId());
        if (target == null) return new Refused<>(RefusalReason.INVALID_WORLD_TARGET, "no entity with id " + p.entityId());
        if (target == self) return new Refused<>(RefusalReason.INVALID_WORLD_TARGET, "cannot attack self");
        double distance = Math.sqrt(self.distanceToSqr(target));
        if (self instanceof Player player && !player.canInteractWithEntity(target, REACH_SLACK)) {
            return new Refused<>(RefusalReason.INVALID_WORLD_TARGET, "entity " + p.entityId() + " is out of reach at " + distance);
        }
        // Swung before the aim, so a refusal leaves the bot not yet turned toward what it declined to hit.
        hands.attackEntity(target);
        String refusal = hands.lastAttackRefusal();
        if (refusal != null) return new Refused<>(RefusalReason.INVALID_WORLD_TARGET, refusal);
        self.setShiftKeyDown(false);
        float[] aim = HandVerbs.anglesAt(self, target);
        self.setYRot(aim[0]);
        self.setXRot(aim[1]);
        self.swing(InteractionHand.MAIN_HAND, true);
        return new Responses.Attacked(p.entityId(), RegistryIds.typeId(target), target.isAlive(), distance);
    }

    private static ImmediateResponse noHands(BodyHost host) {
        return new Refused<>(RefusalReason.UNSUPPORTED_CAPABILITY, host.id() + " has no hands");
    }
}
