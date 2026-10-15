package net.magicterra.worlddriver.bot;

import net.magicterra.worlddriver.api.game.Positions;
import net.magicterra.worlddriver.api.methods.interact.InteractContracts;
import net.magicterra.worlddriver.api.methods.interact.InteractContracts.Face;
import net.magicterra.worlddriver.api.methods.interact.InteractContracts.OnEntity;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.methods.shared.Responses.ImmediateResponse;
import net.magicterra.worlddriver.api.methods.shared.Responses.RefusalReason;
import net.magicterra.worlddriver.api.methods.shared.Responses.Refused;
import net.magicterra.worlddriver.game.body.ClientPlayerBody;
import net.magicterra.worlddriver.game.body.Hands;
import net.magicterra.worlddriver.game.body.HandVerbs;
import net.magicterra.worlddriver.game.observe.RegistryIds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

import static net.magicterra.worlddriver.game.body.BotInteract.*;
import static net.magicterra.worlddriver.game.body.BotUtil.*;

/**
 * {@code mc.bot.holdItem}, {@code useItem} and {@code attackEntity} on this client's player: what the
 * mouse buttons send. Called on the client thread with a player present.
 */
final class InteractionCommands {

    private InteractionCommands() {}

    /** The same rule as any other body's hands; the method has already refused an unregistered id. */
    static ImmediateResponse holdItem(InteractContracts.HoldParams params) {
        Minecraft mc = Minecraft.getInstance();
        Item item = RegistryIds.lookup(BuiltInRegistries.ITEM, params.item()).orElseThrow();
        return new ClientPlayerBody(mc).holdItemOrEmptyHand(item)
                ? new Responses.Held(RegistryIds.itemId(mc.player.getMainHandItem()))
                : new Refused<>(RefusalReason.INVALID_WORLD_TARGET, Hands.cannotHold(item, params.body()));
    }

    static ImmediateResponse useItem(InteractContracts.UseParams p) {
        if (p.entityId().isPresent()) return useOnEntity(p, p.entityId().get());
        if (p.pos().isPresent()) return useOnBlock(p, p.pos().get());
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        InteractionHand hand = HandVerbs.hand(p.hand());
        // A process may have left keyShift down; vanilla's useItem does not read it, but the next click would.
        player.setShiftKeyDown(false);
        InteractionResult result = mc.gameMode.useItem(player, hand);
        if (result.consumesAction()) player.swing(hand);
        return new Responses.Used(p.hand(), HandVerbs.interaction(result), result.consumesAction(), new InteractContracts.InAir());
    }

    private static ImmediateResponse useOnBlock(InteractContracts.UseParams p, BlockPosition at) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        BlockPos blockPos = Positions.blockPos(at);
        InteractionHand hand = HandVerbs.hand(p.hand());
        // Omitted, the face is the one toward the player, so the synthetic hit looks like a real ray from the eye.
        Direction face = p.face().map(f -> Direction.byName(f.wireName())).orElseGet(() -> faceTowardEye(blockPos, player));
        Vec3 hit = HandVerbs.faceCentre(blockPos, face);
        // So the server-side rotation matches the synthetic ray, which a modded server's checks may read.
        if (p.lookAt()) HandVerbs.turn(player, HandVerbs.anglesTo(player.getEyePosition(), hit.x, hit.y, hit.z));
        player.setShiftKeyDown(false);
        InteractionResult result = mc.gameMode.useItemOn(player, hand, new BlockHitResult(hit, face, blockPos, false));
        if (result.consumesAction()) player.swing(hand);
        return new Responses.Used(p.hand(), HandVerbs.interaction(result), result.consumesAction(),
                new InteractContracts.OnBlock(at, Face.valueOf(face.name())));
    }

    private static ImmediateResponse useOnEntity(InteractContracts.UseParams p, int entityId) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Entity target = mc.level.getEntity(entityId);
        if (target == null) return new Refused<>(RefusalReason.INVALID_WORLD_TARGET, "no entity with id " + entityId);
        if (target == player) return new Refused<>(RefusalReason.INVALID_WORLD_TARGET, "cannot interact with self");
        InteractionHand hand = HandVerbs.hand(p.hand());
        if (p.lookAt()) HandVerbs.turn(player, HandVerbs.anglesAt(player, target));
        // The interact packets snapshot isShiftKeyDown, and sneak-gated interactions (a tamed horse's
        // inventory, an armour stand) need it held for exactly this call.
        player.setShiftKeyDown(p.sneak());
        try {
            // Taken before the click: the server mounts or opens the menu on a later tick.
            OnEntity before = afterUse(new OnEntity(entityId, RegistryIds.typeId(target), Math.sqrt(player.distanceToSqr(target)),
                    Optional.empty(), Optional.empty(), Optional.empty()));
            // Vanilla's startUseItem entity branch: interactAt, then interact when that did not consume.
            InteractionResult result = mc.gameMode.interactAt(player, target, new EntityHitResult(target), hand);
            if (!result.consumesAction()) result = mc.gameMode.interact(player, target, hand);
            if (result.consumesAction()) player.swing(hand);
            return new Responses.Used(p.hand(), HandVerbs.interaction(result), result.consumesAction(), before);
        } finally {
            player.setShiftKeyDown(false);
        }
    }

    /** {@code on} with the vehicle and the screen the player has now. */
    static OnEntity afterUse(OnEntity on) {
        Minecraft mc = Minecraft.getInstance();
        Entity vehicle = mc.player == null ? null : mc.player.getVehicle();
        return new OnEntity(on.entityId(), on.type(), on.distance(),
                vehicle == null ? Optional.empty() : Optional.of(RegistryIds.typeId(vehicle)),
                mc.screen == null ? Optional.empty() : Optional.of(mc.screen.getClass().getSimpleName()),
                Optional.empty());
    }

    static ImmediateResponse attackEntity(InteractContracts.AttackParams p) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Entity target = mc.level.getEntity(p.entityId());
        if (target == null) return new Refused<>(RefusalReason.INVALID_WORLD_TARGET, "no entity with id " + p.entityId());
        if (target == player) return new Refused<>(RefusalReason.INVALID_WORLD_TARGET, "cannot attack self");
        // The same swing path the bot processes take — Hands.attackEntity, whose default method carries the
        // BlastFooting rule — rather than a second copy of the guard in front of a direct mc.gameMode.attack:
        // an invariant enforced on two paths comes back through whichever forgot it. Swung BEFORE the aim, so
        // a refusal leaves the player not yet turned toward what it declined to hit; the server's attack
        // handling reads the target and the distance, never the rotation.
        ClientPlayerBody hands = new ClientPlayerBody(mc);
        hands.attackEntity(target);
        String refusal = hands.lastAttackRefusal();
        if (refusal != null) return new Refused<>(RefusalReason.INVALID_WORLD_TARGET, refusal);
        // Then what the MouseHandler does around a left click on an entity: face it and swing the main arm.
        player.setShiftKeyDown(false);
        float[] aim = HandVerbs.anglesAt(player, target);
        player.setYRot(aim[0]); player.setXRot(aim[1]);
        player.swing(InteractionHand.MAIN_HAND);
        return new Responses.Attacked(p.entityId(), RegistryIds.typeId(target), target.isAlive(), Math.sqrt(player.distanceToSqr(target)));
    }


}
