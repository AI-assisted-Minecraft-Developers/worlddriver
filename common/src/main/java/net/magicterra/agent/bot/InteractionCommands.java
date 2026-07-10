package net.magicterra.agent.bot;

import net.magicterra.agent.bot.process.LookProcess;
import net.magicterra.agent.model.Params;
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

import java.util.Map;

import static net.magicterra.agent.bot.util.BotInteract.*;
import static net.magicterra.agent.bot.util.BotUtil.*;

/**
 * Direct hand/interaction verbs ({@code mc.bot.useItem/useItemOn/attackEntity/
 * lookAt}). Extracted verbatim from BotApiImpl; the impl's {@code @Override}
 * methods delegate here. {@code lookAt} takes the {@link BotApiImpl} to start a
 * smooth-look process via its package-private {@code startProcess}.
 */
final class InteractionCommands {

    private InteractionCommands() {}

    static Map<String, Object> lookAt(BotApiImpl bot, Map<String, Object> params) {
        if (params == null) return Map.of("ok", false, "error", "missing pos or yaw/pitch");
        Params q = Params.of(params);
        BlockPos at = q.getPos("pos");
        Object yawO = q.get("yaw"), pitchO = q.get("pitch");
        return onClient(() -> {
            LocalPlayer p = Minecraft.getInstance().player;
            if (p == null) return Map.of("ok", false, "error", "no player");
            float yaw, pitch;
            if (at != null) {
                Vec3 eye = p.getEyePosition();
                double dx = at.getX() + 0.5 - eye.x, dy = at.getY() + 0.5 - eye.y, dz = at.getZ() + 0.5 - eye.z;
                yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            } else if (yawO instanceof Number yn && pitchO instanceof Number pn) {
                yaw = yn.floatValue();
                pitch = pn.floatValue();
            } else {
                return Map.of("ok", false, "error", "provide pos or both yaw+pitch");
            }
            // smoothLook → hand off to a per-tick LookProcess that pans the
            // camera toward the target; otherwise snap (unchanged contract).
            if (BotConfig.smoothLook) {
                bot.startProcess(new LookProcess(at, yaw, pitch));
                return Map.of("ok", true, "started", true, "smooth", true, "yaw", yaw, "pitch", pitch);
            }
            p.setYRot(yaw); p.yHeadRot = yaw; p.yBodyRot = yaw; p.setXRot(pitch);
            return Map.of("ok", true, "yaw", yaw, "pitch", pitch);
        });
    }

    static Map<String, Object> useItem(Map<String, Object> params) {
        InteractionHand hand = parseHand(Params.of(params).get("hand"));
        return onClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p == null || mc.gameMode == null) return Map.of("ok", false, "error", "no player");
            // Prevent the secondary-use shortcut from short-circuiting an item's
            // primary behavior — vanilla useItem itself doesn't read it, but a
            // prior process may have left keyShift down.
            p.setShiftKeyDown(false);
            InteractionResult result = mc.gameMode.useItem(p, hand);
            if (result.consumesAction()) p.swing(hand);
            return Map.of(
                "ok", true,
                "hand", hand == InteractionHand.MAIN_HAND ? "main" : "off",
                "result", result.name(),
                "consumed", result.consumesAction()
            );
        });
    }

    static Map<String, Object> attackEntity(Map<String, Object> params) {
        if (params == null) return Map.of("ok", false, "error", "missing entityId");
        Params q = Params.of(params);
        Object idObj = q.get("entityId");
        if (!(idObj instanceof Number)) return Map.of("ok", false, "error", "entityId required (integer)");
        final int entityId = ((Number) idObj).intValue();
        return onClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p == null || mc.gameMode == null || mc.level == null) {
                return Map.of("ok", false, "error", "no player");
            }
            Entity target = mc.level.getEntity(entityId);
            if (target == null) {
                return Map.of("ok", false, "error", "no entity with id " + entityId);
            }
            if (target == p) return Map.of("ok", false, "error", "cannot attack self");
            // Same path the vanilla MouseHandler takes on left-click of an entity:
            // turn to face, swing main arm, dispatch attack through MPGameMode so
            // the server applies weapon damage + cooldown + crit/sweep rules.
            p.setShiftKeyDown(false);
            Vec3 ep = target.position();
            double dx = ep.x - p.getX();
            double dz = ep.z - p.getZ();
            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            float pitch = (float) -Math.toDegrees(Math.atan2(
                    (ep.y + target.getBbHeight() * 0.5) - p.getEyeY(),
                    Math.sqrt(dx * dx + dz * dz)));
            p.setYRot(yaw); p.setXRot(pitch);
            mc.gameMode.attack(p, target);
            p.swing(InteractionHand.MAIN_HAND);
            return Map.of(
                "ok", true,
                "entityId", entityId,
                "type", BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString(),
                "alive", target.isAlive(),
                "distance", Math.sqrt(p.distanceToSqr(target))
            );
        });
    }

    static Map<String, Object> useItemOnEntity(Map<String, Object> params) {
        if (params == null) return Map.of("ok", false, "error", "missing entityId");
        Params q = Params.of(params);
        Object idObj = q.get("entityId");
        if (!(idObj instanceof Number)) return Map.of("ok", false, "error", "entityId required (integer)");
        final int entityId = ((Number) idObj).intValue();
        InteractionHand hand = parseHand(q.get("hand"));
        boolean wantLookAt = q.getBool("lookAt", true);
        boolean sneak = q.getBool("sneak", false);
        return onClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p == null || mc.gameMode == null || mc.level == null) {
                return Map.of("ok", false, "error", "no player");
            }
            Entity target = mc.level.getEntity(entityId);
            if (target == null) {
                return Map.of("ok", false, "error", "no entity with id " + entityId);
            }
            if (target == p) return Map.of("ok", false, "error", "cannot interact with self");
            if (wantLookAt) {
                Vec3 ep = target.position();
                double dx = ep.x - p.getX();
                double dz = ep.z - p.getZ();
                float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
                float pitch = (float) -Math.toDegrees(Math.atan2(
                        (ep.y + target.getBbHeight() * 0.5) - p.getEyeY(),
                        Math.sqrt(dx * dx + dz * dz)));
                p.setYRot(yaw); p.yHeadRot = yaw; p.yBodyRot = yaw; p.setXRot(pitch);
            }
            // The interact packets snapshot isShiftKeyDown — sneak-gated
            // interactions (open tamed horse inventory, armor-stand pickup)
            // need it held for exactly this call.
            p.setShiftKeyDown(sneak);
            try {
                // Vanilla Minecraft.startUseItem ENTITY branch: interactAt
                // first, fall through to interact when not consumed.
                EntityHitResult hit = new EntityHitResult(target);
                InteractionResult result = mc.gameMode.interactAt(p, target, hit, hand);
                if (!result.consumesAction()) result = mc.gameMode.interact(p, target, hand);
                if (result.consumesAction()) p.swing(hand);
                Entity vehicle = p.getVehicle();
                return Map.of(
                    "ok", true,
                    "entityId", entityId,
                    "type", BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString(),
                    "hand", hand == InteractionHand.MAIN_HAND ? "main" : "off",
                    "result", result.name(),
                    "consumed", result.consumesAction(),
                    "distance", Math.sqrt(p.distanceToSqr(target)),
                    "riding", vehicle == null ? "none"
                            : BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()).toString(),
                    "screen", mc.screen == null ? "none" : mc.screen.getClass().getSimpleName()
                );
            } finally {
                p.setShiftKeyDown(false);
            }
        });
    }

    static Map<String, Object> useItemOn(Map<String, Object> params) {
        if (params == null) return Map.of("ok", false, "error", "missing pos");
        Params q = Params.of(params);
        BlockPos blockPos = q.getPos("pos");
        if (blockPos == null) return Map.of("ok", false, "error", "pos required");
        Direction face = parseFace(q.get("face"));
        InteractionHand hand = parseHand(q.get("hand"));
        boolean wantLookAt = q.getBool("lookAt", true);

        return onClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p == null || mc.gameMode == null || mc.level == null) return Map.of("ok", false, "error", "no player");

            // The face parameter is "the face of `pos` we're clicking" — i.e. the
            // outward-facing face of the click target. If omitted, pick the face
            // closest to the player (so the synthetic hit looks like a real
            // ray-cast from the player's eye).
            Direction effFace = face != null ? face : pickFaceTowardsPlayer(blockPos, p);

            // Click location = center of that face. The integrated server uses
            // it for ranged interaction checks AND for items that key off the
            // exact hit Vec3 (e.g. slab top/bottom selection).
            double cx = blockPos.getX() + 0.5 + effFace.getStepX() * 0.5;
            double cy = blockPos.getY() + 0.5 + effFace.getStepY() * 0.5;
            double cz = blockPos.getZ() + 0.5 + effFace.getStepZ() * 0.5;
            Vec3 hitLoc = new Vec3(cx, cy, cz);
            BlockHitResult hit = new BlockHitResult(hitLoc, effFace, blockPos, false);

            if (wantLookAt) {
                // Aim at the hit location so the server-side rotation matches
                // the synthetic ray. Without this, server-side checks that look
                // at view direction (e.g. anti-cheat in modded servers) may
                // reject; in single-player it's harmless cosmetic.
                Vec3 eye = p.getEyePosition();
                double dx = cx - eye.x, dy = cy - eye.y, dz = cz - eye.z;
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                p.setYRot(yaw); p.yHeadRot = yaw; p.yBodyRot = yaw; p.setXRot(pitch);
            }
            p.setShiftKeyDown(false);

            InteractionResult result = mc.gameMode.useItemOn(p, hand, hit);
            if (result.consumesAction()) p.swing(hand);

            return Map.of(
                "ok", true,
                "hand", hand == InteractionHand.MAIN_HAND ? "main" : "off",
                "pos", posMap(blockPos),
                "face", effFace.getName(),
                "result", result.name(),
                "consumed", result.consumesAction()
            );
        });
    }
}
