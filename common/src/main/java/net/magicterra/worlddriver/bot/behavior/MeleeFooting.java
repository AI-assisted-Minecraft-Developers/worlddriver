package net.magicterra.worlddriver.bot.behavior;

import net.magicterra.worlddriver.bot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

/**
 * The melee footwork's step against the terrain it steps into. The footwork (walking in on the cooldown, the strafe
 * round a target, the step back from a close mob) decides a camera-frame impulse from the mobs alone; in a cave that
 * impulse pressed the body into a one-block rise until the next crit jump lifted it over, or into a wall for as long as
 * the fight lasted, and each press was a bump (cave combat courses: on the ground against a face for a quarter of a
 * second to a second and a quarter, while a mob was in reach). A person jumps the rise as they reach it and does not
 * walk into a wall. So a rise ahead is jumped before the body meets it, and stepped onto once the feet are over its
 * top, and the part of the impulse that heads into a wall, a face two blocks high, is dropped, leaving the part along
 * it.
 */
final class MeleeFooting {

    /** How far past the body's side the cell ahead is looked for: a little over a tick of a walk. */
    private static final double PROBE = 0.3;

    /** Below this, a world-frame impulse is no step at all. */
    private static final double STILL = 1e-3;

    private MeleeFooting() {}

    /**
     * The footing for a {@code left}, {@code forward} impulse in the body's own frame: the impulse with whatever heads
     * into a wall taken out, and whether to jump for a rise one block up ahead. The result is {@code {left, forward,
     * jump}}, the jump 1 or 0.
     */
    static float[] step(Player p, WorldView w, float left, float forward) {
        double yaw = Math.toRadians(p.getYRot());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        // The camera frame to the world's: forward is (-sin, cos), left is (cos, sin).
        double dx = -sin * forward + cos * left;
        double dz = cos * forward + sin * left;
        if (Math.hypot(dx, dz) < STILL) {
            return new float[] {left, forward, 0f};
        }
        BlockPos feet = BlockPos.containing(p.getX(), p.getY() + STILL, p.getZ());
        double reach = p.getBbWidth() / 2 + PROBE;
        int fx = Math.abs(dx) < STILL ? 0 : (int) Math.signum(dx);
        int fz = Math.abs(dz) < STILL ? 0 : (int) Math.signum(dz);
        Face x = fx == 0 ? Face.OPEN : face(w, feet, BlockPos.containing(p.getX() + fx * reach, feet.getY(), p.getZ()));
        Face z = fz == 0 ? Face.OPEN : face(w, feet, BlockPos.containing(p.getX(), feet.getY(), p.getZ() + fz * reach));
        // In the air with a rise still beside the feet, the step waits until the feet are over the rise's top: pressed
        // on, the body met the face on the way up (or, off a crit jump that was not for the rise, fell back from it).
        boolean airborne = !p.onGround();
        if (x == Face.WALL || x == Face.RISE && airborne) {
            dx = 0;
        }
        if (z == Face.WALL || z == Face.RISE && airborne) {
            dz = 0;
        }
        boolean jump = x == Face.RISE || z == Face.RISE;
        // Back to the camera frame.
        float outForward = (float) (-sin * dx + cos * dz);
        float outLeft = (float) (cos * dx + sin * dz);
        return new float[] {outLeft, outForward, jump && p.onGround() ? 1f : 0f};
    }

    /** What the body meets in the cell {@code ahead} of its {@code feet}. */
    private enum Face {
        OPEN,
        RISE,
        WALL
    }

    private static Face face(WorldView w, BlockPos feet, BlockPos ahead) {
        if (ahead.equals(feet) || w.isPassable(ahead)) {
            return Face.OPEN;
        }
        // A rise is a block the body can stand on top of: the two cells above it free, and room overhead to jump.
        boolean rise = w.isSolid(ahead)
                && w.isPassable(ahead.above())
                && w.isPassable(ahead.above(2))
                && w.isPassable(feet.above(2));
        return rise ? Face.RISE : Face.WALL;
    }
}
