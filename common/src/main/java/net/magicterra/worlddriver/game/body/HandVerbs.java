package net.magicterra.worlddriver.game.body;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * What the hand verbs share between the client's own player and a server body: where a synthetic click lands, and the
 * aim at an entity. No client type is named here, so a dedicated server links it.
 */
public final class HandVerbs {
    private HandVerbs() {}

    /**
     * The centre of {@code face} of {@code pos}: the server's reach check reads it, and so do items keyed off the hit
     * (a slab's top or bottom half).
     */
    public static Vec3 faceCentre(BlockPos pos, Direction face) {
        return new Vec3(
                pos.getX() + 0.5 + face.getStepX() * 0.5,
                pos.getY() + 0.5 + face.getStepY() * 0.5,
                pos.getZ() + 0.5 + face.getStepZ() * 0.5);
    }

    /** Yaw and pitch at the middle of {@code target}'s box, the aim a hand verb on an entity takes. */
    public static float[] anglesAt(LivingEntity self, Entity target) {
        Vec3 ep = target.position();
        double dx = ep.x - self.getX(), dz = ep.z - self.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(
                Math.atan2(ep.y + target.getBbHeight() * 0.5 - self.getEyeY(), Math.sqrt(dx * dx + dz * dz)));
        return new float[] {yaw, pitch};
    }

    /** Yaw and pitch from {@code eye} to a point. */
    public static float[] anglesTo(Vec3 eye, double x, double y, double z) {
        double dx = x - eye.x, dy = y - eye.y, dz = z - eye.z;
        return new float[] {
            (float) Math.toDegrees(Math.atan2(-dx, dz)),
            (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)))
        };
    }

    /** Turn the head, body and view to {@code aim}. */
    public static void turn(LivingEntity e, float[] aim) {
        e.setYRot(aim[0]);
        e.yHeadRot = aim[0];
        e.yBodyRot = aim[0];
        e.setXRot(aim[1]);
    }
}
