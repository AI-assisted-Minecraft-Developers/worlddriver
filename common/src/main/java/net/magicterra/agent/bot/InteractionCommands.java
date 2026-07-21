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

import java.util.HashMap;
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

    /** {@code mc.bot.holdItem} — put a specific inventory item into the main hand:
     *  select its hotbar slot, else swap it up from the main inventory
     *  (BotInteract.ensureHolding — the reach every process already trusts, #27/#56
     *  family). First-class survival prelude to useItem: bucket scoops, flint &
     *  steel, eating a chosen food. Matched by registry-key string so no Item
     *  object resolution is needed. */
    static Map<String, Object> holdItem(Map<String, Object> params) {
        Object idRaw = params == null ? null : params.get("item");
        String idIn = idRaw instanceof String s ? s : null;
        if (idIn == null || idIn.isBlank()) return Map.of("ok", false, "error", "item required");
        String id = idIn.contains(":") ? idIn : "minecraft:" + idIn;
        return onClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p == null) return Map.of("ok", false, "error", "no player");
            boolean ok = ensureHolding(mc, stk -> !stk.isEmpty()
                    && BuiltInRegistries.ITEM.getKey(stk.getItem()).toString().equals(id));
            var held = p.getInventory().getSelected();
            String heldId = held.isEmpty() ? "minecraft:air"
                    : BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
            return ok ? Map.of("ok", true, "held", heldId)
                      : Map.of("ok", false, "error", "not in inventory: " + id, "held", heldId);
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
            float[] aim = aimAnglesAt(p, target);
            p.setYRot(aim[0]); p.setXRot(aim[1]);
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
        Map<String, Object> partial = onClient(() -> {
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
                float[] aim = aimAnglesAt(p, target);
                p.setYRot(aim[0]); p.yHeadRot = aim[0]; p.yBodyRot = aim[0]; p.setXRot(aim[1]);
            }
            // The interact packets snapshot isShiftKeyDown — sneak-gated
            // interactions (open tamed horse inventory, armor-stand pickup)
            // need it held for exactly this call.
            p.setShiftKeyDown(sneak);
            try {
                // Snapshot pre-interact state; the server applies the mount /
                // opens the menu and syncs back on a later tick, so this call's
                // post-interact p.getVehicle()/mc.screen still show the old state.
                String vehicleBefore = vehicleTypeOf(p);
                String screenBefore = screenNameOf(mc);
                // Vanilla Minecraft.startUseItem ENTITY branch: interactAt
                // first, fall through to interact when not consumed.
                EntityHitResult hit = new EntityHitResult(target);
                InteractionResult result = mc.gameMode.interactAt(p, target, hit, hand);
                if (!result.consumesAction()) result = mc.gameMode.interact(p, target, hand);
                if (result.consumesAction()) p.swing(hand);
                Map<String, Object> m = new HashMap<>();
                m.put("ok", true);
                m.put("entityId", entityId);
                m.put("type", BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString());
                m.put("hand", hand == InteractionHand.MAIN_HAND ? "main" : "off");
                m.put("result", result.name());
                m.put("consumed", result.consumesAction());
                m.put("distance", Math.sqrt(p.distanceToSqr(target)));
                m.put("vehicleBefore", vehicleBefore);
                m.put("screenBefore", screenBefore);
                return m;
            } finally {
                p.setShiftKeyDown(false);
            }
        });
        if (!Boolean.TRUE.equals(partial.get("ok"))) return partial;

        String vehicleBefore = (String) partial.remove("vehicleBefore");
        String screenBefore = (String) partial.remove("screenBefore");
        // Can't sleep on the client thread — packet processing (the very mount/
        // menu-open we're waiting on) runs in the client tick, so blocking it
        // deadlocks. Poll from this (RPC) thread instead, each attempt a short
        // onClient() sample, until the server-applied state actually changes.
        String riding = vehicleBefore;
        String screen = screenBefore;
        // If we're already ON the client thread (BotUtil.onClient runs its lambda
        // inline rather than dispatching when isSameThread() is true — e.g. a
        // process step calling useItemOnEntity directly during a client tick),
        // skip the poll entirely and keep the pre-interact snapshot: the
        // Thread.sleep(50) below would freeze the client for up to 250ms AND
        // deadlock the very packet processing (the mount/menu sync this loop
        // waits on) it's blocking, since that processing runs on this same thread.
        if (!Minecraft.getInstance().isSameThread()) {
            for (int attempt = 0; attempt < 6; attempt++) {
                Map<String, String> sample = onClient(() -> {
                    Minecraft mc = Minecraft.getInstance();
                    LocalPlayer p = mc.player;
                    return Map.of("riding", p == null ? "none" : vehicleTypeOf(p), "screen", screenNameOf(mc));
                });
                riding = sample.get("riding");
                screen = sample.get("screen");
                if (!riding.equals(vehicleBefore) || !screen.equals(screenBefore)) break;
                if (attempt < 5) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        partial.put("riding", riding);
        partial.put("screen", screen);
        return partial;
    }

    private static String vehicleTypeOf(LocalPlayer p) {
        Entity vehicle = p.getVehicle();
        return vehicle == null ? "none" : BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()).toString();
    }

    private static String screenNameOf(Minecraft mc) {
        return mc.screen == null ? "none" : mc.screen.getClass().getSimpleName();
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
