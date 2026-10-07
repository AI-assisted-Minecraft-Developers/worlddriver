package net.magicterra.worlddriver.bot;

import net.magicterra.worlddriver.api.Positions;
import net.magicterra.worlddriver.api.methods.ActionContract.ImmediateResponse;
import net.magicterra.worlddriver.api.methods.ActionContract.RefusalReason;
import net.magicterra.worlddriver.api.methods.ActionContract.Refused;
import net.magicterra.worlddriver.api.methods.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.HandContracts;
import net.magicterra.worlddriver.api.methods.HandContracts.Face;
import net.magicterra.worlddriver.api.methods.HandContracts.Hand;
import net.magicterra.worlddriver.api.methods.HandContracts.Interaction;
import net.magicterra.worlddriver.api.methods.HandContracts.OnEntity;
import net.magicterra.worlddriver.bot.body.ClientPlayerBody;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Optional;

import static net.magicterra.worlddriver.bot.util.BotInteract.*;
import static net.magicterra.worlddriver.bot.util.BotUtil.*;

/**
 * {@code mc.bot.holdItem}, {@code useItem} and {@code attackEntity} on this client's player: what the
 * mouse buttons send. Called on the client thread with a player present.
 */
final class InteractionCommands {

    private InteractionCommands() {}

    /** Hotbar select, else a swap up from the main inventory: the reach every process already trusts. */
    static ImmediateResponse holdItem(HandContracts.HoldParams params) {
        Minecraft mc = Minecraft.getInstance();
        boolean ok = ensureHolding(mc, stk -> !stk.isEmpty()
                && BuiltInRegistries.ITEM.getKey(stk.getItem()).toString().equals(params.item()));
        var held = mc.player.getInventory().getSelected();
        return ok ? new HandContracts.Held(BuiltInRegistries.ITEM.getKey(held.getItem()).toString())
                : new Refused<>(RefusalReason.INVALID_WORLD_TARGET, "no " + params.item() + " in the inventory");
    }

    static ImmediateResponse useItem(HandContracts.UseParams p) {
        if (p.entityId().isPresent()) return useOnEntity(p, p.entityId().get());
        if (p.pos().isPresent()) return useOnBlock(p, p.pos().get());
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        InteractionHand hand = hand(p.hand());
        // A process may have left keyShift down; vanilla's useItem does not read it, but the next click would.
        player.setShiftKeyDown(false);
        InteractionResult result = mc.gameMode.useItem(player, hand);
        if (result.consumesAction()) player.swing(hand);
        return new HandContracts.Used(p.hand(), interaction(result), result.consumesAction(), new HandContracts.InAir());
    }

    private static ImmediateResponse useOnBlock(HandContracts.UseParams p, BlockPosition at) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        BlockPos blockPos = Positions.blockPos(at);
        InteractionHand hand = hand(p.hand());
        // Omitted, the face is the one toward the player, so the synthetic hit looks like a real ray from the eye.
        Direction face = p.face().map(f -> Direction.byName(f.wireName())).orElseGet(() -> pickFaceTowardsPlayer(blockPos, player));
        // The centre of that face: the server's reach check reads it, and so do items keyed off the hit
        // (a slab's top or bottom half).
        double cx = blockPos.getX() + 0.5 + face.getStepX() * 0.5;
        double cy = blockPos.getY() + 0.5 + face.getStepY() * 0.5;
        double cz = blockPos.getZ() + 0.5 + face.getStepZ() * 0.5;
        if (p.lookAt()) {
            // So the server-side rotation matches the synthetic ray, which a modded server's checks may read.
            Vec3 eye = player.getEyePosition();
            double dx = cx - eye.x, dy = cy - eye.y, dz = cz - eye.z;
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            player.setYRot(yaw); player.yHeadRot = yaw; player.yBodyRot = yaw; player.setXRot(pitch);
        }
        player.setShiftKeyDown(false);
        InteractionResult result = mc.gameMode.useItemOn(player, hand, new BlockHitResult(new Vec3(cx, cy, cz), face, blockPos, false));
        if (result.consumesAction()) player.swing(hand);
        return new HandContracts.Used(p.hand(), interaction(result), result.consumesAction(),
                new HandContracts.OnBlock(at, Face.valueOf(face.name())));
    }

    private static ImmediateResponse useOnEntity(HandContracts.UseParams p, int entityId) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Entity target = mc.level.getEntity(entityId);
        if (target == null) return new Refused<>(RefusalReason.INVALID_WORLD_TARGET, "no entity with id " + entityId);
        if (target == player) return new Refused<>(RefusalReason.INVALID_WORLD_TARGET, "cannot interact with self");
        InteractionHand hand = hand(p.hand());
        if (p.lookAt()) {
            float[] aim = aimAnglesAt(player, target);
            player.setYRot(aim[0]); player.yHeadRot = aim[0]; player.yBodyRot = aim[0]; player.setXRot(aim[1]);
        }
        // The interact packets snapshot isShiftKeyDown, and sneak-gated interactions (a tamed horse's
        // inventory, an armour stand) need it held for exactly this call.
        player.setShiftKeyDown(p.sneak());
        try {
            // Taken before the click: the server mounts or opens the menu on a later tick.
            OnEntity before = afterUse(new OnEntity(entityId, typeOf(target), Math.sqrt(player.distanceToSqr(target)),
                    Optional.empty(), Optional.empty(), Optional.empty()));
            // Vanilla's startUseItem entity branch: interactAt, then interact when that did not consume.
            InteractionResult result = mc.gameMode.interactAt(player, target, new EntityHitResult(target), hand);
            if (!result.consumesAction()) result = mc.gameMode.interact(player, target, hand);
            if (result.consumesAction()) player.swing(hand);
            return new HandContracts.Used(p.hand(), interaction(result), result.consumesAction(), before);
        } finally {
            player.setShiftKeyDown(false);
        }
    }

    /** {@code on} with the vehicle and the screen the player has now. */
    static OnEntity afterUse(OnEntity on) {
        Minecraft mc = Minecraft.getInstance();
        Entity vehicle = mc.player == null ? null : mc.player.getVehicle();
        return new OnEntity(on.entityId(), on.type(), on.distance(),
                vehicle == null ? Optional.empty() : Optional.of(typeOf(vehicle)),
                mc.screen == null ? Optional.empty() : Optional.of(mc.screen.getClass().getSimpleName()),
                Optional.empty());
    }

    static ImmediateResponse attackEntity(HandContracts.AttackParams p) {
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
        float[] aim = aimAnglesAt(player, target);
        player.setYRot(aim[0]); player.setXRot(aim[1]);
        player.swing(InteractionHand.MAIN_HAND);
        return new HandContracts.Attacked(p.entityId(), typeOf(target), target.isAlive(), Math.sqrt(player.distanceToSqr(target)));
    }

    private static InteractionHand hand(Hand h) {
        return h == Hand.OFF ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    static Interaction interaction(InteractionResult r) {
        return Interaction.valueOf(r.name().toUpperCase(Locale.ROOT));
    }

    private static String typeOf(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
    }
}
