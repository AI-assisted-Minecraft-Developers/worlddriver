package net.magicterra.worlddriver.bot.behavior;

import net.magicterra.worlddriver.bot.world.HazardField;
import net.magicterra.worlddriver.bot.world.SurvivalMath;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.WorldConfig;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * A walkable heading away from a point: the one closest to straight away whose next cells are standable and not lethal,
 * read off the same {@link HazardField} the planner uses. Too urgent to plan a route for — a creeper's fuse, a zombie
 * inside its strike distance — but a blind step away runs off a ledge or, in a closed room, into the wall.
 */
public final class StepAway {

    /**
     * Candidate offsets in degrees from straight away, nearest first, so the bot peels along a wall or a ledge instead
     * of into it.
     */
    private static final double[] OFFSETS = {0, 25, -25, 50, -50, 75, -75, 90, -90, 115, -115, 135, -135, 160, -160, 180
    };

    private StepAway() {}

    /**
     * The unit horizontal heading {x, z} away from {@code from}, at most {@code maxOffset} degrees off straight away,
     * whose first {@code cells} cells are safe to step on; null when none is (cornered) or {@code from} is where the
     * body stands.
     */
    public static double[] heading(WorldView w, LivingEntity body, Vec3 from, int cells, double maxOffset) {
        double ax = body.getX() - from.x;
        double az = body.getZ() - from.z;
        double len = Math.sqrt(ax * ax + az * az);
        if (len <= 1.0e-3) {
            return null;
        }
        ax /= len;
        az /= len;
        HazardField hf = HazardField.compute(
                w,
                body.blockPosition(),
                Math.max(1, cells + 1),
                SurvivalMath.survivableFall(body.getHealth()),
                WorldConfig.deepWaterMax);
        for (double off : OFFSETS) {
            if (Math.abs(off) > maxOffset) {
                continue;
            }
            double r = Math.toRadians(off);
            double dx = ax * Math.cos(r) - az * Math.sin(r);
            double dz = ax * Math.sin(r) + az * Math.cos(r);
            boolean safe = true;
            for (int d = 1; d <= cells && safe; d++) {
                var cell = hf.at((int) Math.round(dx * d), (int) Math.round(dz * d));
                safe = cell.standable() && !cell.lethal();
            }
            if (safe) {
                return new double[] {dx, dz};
            }
        }
        return null;
    }

    /** The yaw (degrees) that faces along the horizontal heading {@code h}. */
    public static float yawOf(double[] h) {
        return (float) (Math.toDegrees(Math.atan2(h[1], h[0])) - 90.0);
    }
}
