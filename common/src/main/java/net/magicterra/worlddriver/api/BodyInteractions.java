package net.magicterra.worlddriver.api;

import java.util.LinkedHashMap;
import java.util.Map;

import net.magicterra.worlddriver.bot.BodyReady;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.body.BodyHost;
import net.magicterra.worlddriver.bot.body.Hands;
import net.magicterra.worlddriver.bot.process.LookProcess;
import net.magicterra.worlddriver.bot.util.BotUtil;
import net.magicterra.worlddriver.model.Params;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * {@code mc.bot.lookAt}, {@code holdItem}, {@code useItem} and {@code attackEntity} on a registered bot,
 * run on the server thread by {@link BodyRoutes#onHost}.
 *
 * <p>The client's versions send what a click sends and leave the rest to the server's packet handlers.
 * A server-side bot has no connection to send through, so these do what those handlers do with the
 * packet, reach check included, and answer with what came of it. Two replies differ for that reason: a
 * use or an attack out of reach is refused here where the client's is ignored, and a use on an entity
 * names the open {@code menu} where the client names its {@code screen}.
 *
 * <p>A bot without hands, an NPC, refuses all but {@code lookAt} with {@code no_hands}. Nothing here
 * may name a client class.
 */
final class BodyInteractions {

    private BodyInteractions() {}

    /** The slack the server's use and attack handlers add to the interaction range. */
    private static final double REACH_SLACK = 1.0;

    static Map<String, Object> lookAt(BodyHost host, Params p) {
        LivingEntity self = host.entity();
        BlockPos at = p.getPos("pos");
        float[] aim;
        if (at != null) {
            aim = anglesTo(self.getEyePosition(), at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5);
        } else if (p.get("yaw") instanceof Number yaw && p.get("pitch") instanceof Number pitch) {
            aim = new float[]{yaw.floatValue(), pitch.floatValue()};
        } else {
            return error("provide pos or both yaw+pitch");
        }
        if (BotConfig.smoothLook) {
            host.start(new LookProcess(at, aim[0], aim[1]));
            return result(host, "ok", true, "started", true, "smooth", true, "yaw", aim[0], "pitch", aim[1]);
        }
        turn(self, aim);
        return result(host, "ok", true, "yaw", aim[0], "pitch", aim[1]);
    }

    /** The item into the main hand through {@link Hands#holdItem}, the reach every process trusts. */
    static Map<String, Object> holdItem(BodyHost host, Params p) {
        String idIn = p.getNonBlank("item");
        if (idIn == null) return error("item required");
        String id = idIn.contains(":") ? idIn : "minecraft:" + idIn;
        Hands hands = host.body().hands().orElse(null);
        if (hands == null) return noHands(host);
        ResourceLocation key = ResourceLocation.tryParse(id);
        Item item = key == null ? Items.AIR : BuiltInRegistries.ITEM.get(key);
        boolean held = item != Items.AIR && hands.holdItem(item);
        String heldId = BuiltInRegistries.ITEM.getKey(host.entity().getMainHandItem().getItem()).toString();
        return held ? result(host, "ok", true, "held", heldId)
                    : result(host, "ok", false, "error", "not in inventory: " + id, "held", heldId);
    }

    /** In the air, on a block with {@code pos}, or on an entity with {@code entityId}, which wins over {@code pos}. */
    static Map<String, Object> useItem(BodyHost host, Params p) {
        ServerPlayer sp = host.body().hands().isPresent() && host.entity() instanceof ServerPlayer s ? s : null;
        if (sp == null) return noHands(host);
        if (p.get("entityId") != null) return useItemOnEntity(host, sp, p);
        if (p.get("pos") != null) return useItemOn(host, sp, p);
        InteractionHand hand = Params.toHand(p.get("hand"));
        sp.setShiftKeyDown(false);
        InteractionResult result = sp.gameMode.useItem(sp, sp.serverLevel(), sp.getItemInHand(hand), hand);
        if (result.consumesAction()) sp.swing(hand, true);
        return result(host, "ok", true, "hand", handName(hand), "result", result.name(), "consumed", result.consumesAction());
    }

    private static Map<String, Object> useItemOn(BodyHost host, ServerPlayer sp, Params p) {
        BlockPos pos = p.getPos("pos");
        if (pos == null) return error("pos required");
        Direction given = Params.toFace(p.get("face"));
        Direction face = given != null ? given : BotUtil.faceTowardEye(pos, sp);
        InteractionHand hand = Params.toHand(p.get("hand"));
        Vec3 hit = new Vec3(pos.getX() + 0.5 + face.getStepX() * 0.5,
                pos.getY() + 0.5 + face.getStepY() * 0.5,
                pos.getZ() + 0.5 + face.getStepZ() * 0.5);
        if (p.getBool("lookAt", true)) turn(sp, anglesTo(sp.getEyePosition(), hit.x, hit.y, hit.z));
        sp.setShiftKeyDown(false);
        if (!sp.canInteractWithBlock(pos, REACH_SLACK)) return error("pos " + pos.toShortString() + " is out of reach");
        InteractionResult result = sp.gameMode.useItemOn(sp, sp.serverLevel(), sp.getItemInHand(hand), hand,
                new BlockHitResult(hit, face, pos, false));
        if (result.consumesAction()) sp.swing(hand, true);
        return result(host, "ok", true, "hand", handName(hand), "pos", posMap(pos), "face", face.getName(),
                "result", result.name(), "consumed", result.consumesAction());
    }

    private static Map<String, Object> useItemOnEntity(BodyHost host, ServerPlayer sp, Params p) {
        if (!(p.get("entityId") instanceof Number n)) return error("entityId required (integer)");
        int entityId = n.intValue();
        Entity target = sp.serverLevel().getEntity(entityId);
        if (target == null) return error("no entity with id " + entityId);
        if (target == sp) return error("cannot interact with self");
        InteractionHand hand = Params.toHand(p.get("hand"));
        if (p.getBool("lookAt", true)) turn(sp, anglesAt(sp, target));
        double distance = Math.sqrt(sp.distanceToSqr(target));
        if (!sp.canInteractWithEntity(target, REACH_SLACK)) {
            return error("entity " + entityId + " is out of reach at " + distance);
        }
        // Sneak-gated interactions read the shift flag during this call only.
        sp.setShiftKeyDown(p.getBool("sneak", false));
        try {
            // The client's pair of packets: interactAt first, interact when that did not consume. Its hit
            // is the entity's position, so the location relative to the entity is zero.
            InteractionResult result = target.interactAt(sp, Vec3.ZERO, hand);
            if (!result.consumesAction()) result = sp.interactOn(target, hand);
            if (result.consumesAction()) sp.swing(hand, true);
            Entity vehicle = sp.getVehicle();
            return result(host, "ok", true, "entityId", entityId, "type", typeOf(target), "hand", handName(hand),
                    "result", result.name(), "consumed", result.consumesAction(), "distance", distance,
                    "riding", vehicle == null ? "none" : typeOf(vehicle),
                    "menu", sp.containerMenu == sp.inventoryMenu ? "none" : sp.containerMenu.getClass().getSimpleName());
        } finally {
            sp.setShiftKeyDown(false);
        }
    }

    /** Through {@link Hands#attackEntity}, as the client's verb goes: the footing guard lives on that one path. */
    static Map<String, Object> attackEntity(BodyHost host, Params p) {
        if (!(p.get("entityId") instanceof Number n)) return error("entityId required (integer)");
        int entityId = n.intValue();
        Hands hands = host.body().hands().orElse(null);
        if (hands == null) return noHands(host);
        LivingEntity self = host.entity();
        Entity target = self.level().getEntity(entityId);
        if (target == null) return error("no entity with id " + entityId);
        if (target == self) return error("cannot attack self");
        double distance = Math.sqrt(self.distanceToSqr(target));
        String refusal = self instanceof Player player && !player.canInteractWithEntity(target, REACH_SLACK)
                ? "entity " + entityId + " is out of reach" : null;
        if (refusal == null) {
            // Swung before the aim, so a refusal leaves the bot not yet turned toward what it declined to hit.
            hands.attackEntity(target);
            refusal = hands.lastAttackRefusal();
        }
        if (refusal == null) {
            self.setShiftKeyDown(false);
            float[] aim = anglesAt(self, target);
            self.setYRot(aim[0]);
            self.setXRot(aim[1]);
            self.swing(InteractionHand.MAIN_HAND, true);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", refusal == null);
        if (refusal != null) out.put("error", refusal);
        out.put("entityId", entityId);
        out.put("type", typeOf(target));
        out.put("alive", target.isAlive());
        out.put("distance", distance);
        out.put("body", host.id());
        return out;
    }

    /** Yaw and pitch from {@code eye} to a point. */
    private static float[] anglesTo(Vec3 eye, double x, double y, double z) {
        double dx = x - eye.x, dy = y - eye.y, dz = z - eye.z;
        return new float[]{(float) Math.toDegrees(Math.atan2(-dx, dz)),
                (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)))};
    }

    /** Yaw and pitch at the middle of {@code target}'s box, the aim the client's hand verbs take. */
    private static float[] anglesAt(LivingEntity self, Entity target) {
        Vec3 ep = target.position();
        double dx = ep.x - self.getX(), dz = ep.z - self.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(ep.y + target.getBbHeight() * 0.5 - self.getEyeY(),
                Math.sqrt(dx * dx + dz * dz)));
        return new float[]{yaw, pitch};
    }

    private static void turn(LivingEntity e, float[] aim) {
        e.setYRot(aim[0]);
        e.yHeadRot = aim[0];
        e.yBodyRot = aim[0];
        e.setXRot(aim[1]);
    }

    private static String handName(InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND ? "main" : "off";
    }

    private static String typeOf(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
    }

    private static Map<String, Object> posMap(BlockPos p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", p.getX());
        m.put("y", p.getY());
        m.put("z", p.getZ());
        return m;
    }

    private static Map<String, Object> noHands(BodyHost host) {
        return BodyHost.refuse(BodyReady.Reason.NO_HANDS, "bot " + host.id() + " has no hands");
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", message);
        return m;
    }

    /** The pairs in order, then {@code body}. */
    private static Map<String, Object> result(BodyHost host, Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        m.put("body", host.id());
        return m;
    }
}
