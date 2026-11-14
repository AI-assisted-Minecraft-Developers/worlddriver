package net.magicterra.worlddriver.bot.behavior;

import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.BehaviorConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * The melee footwork's step against the terrain it steps into. The footwork (walking in on the cooldown, the strafe
 * round a target, the step back from a close mob) decides a camera-frame impulse from the mobs alone; in a cave that
 * impulse pressed the body into a one-block rise until the next crit jump lifted it over, or into a wall for as long as
 * the fight lasted, and each press was a bump (cave combat courses: on the ground against a face for a quarter of a
 * second to a second and a quarter, while a mob was in reach). A person jumps the rise as they reach it and does not
 * walk into a wall. So a rise ahead is jumped before the body meets it, and stepped onto once the feet are over its
 * top, and the part of the impulse that heads into a wall, a face two blocks high, is dropped, leaving the part along
 * it.
 *
 * <p>With {@link BehaviorConfig#combatFootingDrift} the step also answers for where the body is carried, not only where
 * it is pushed. Of the contacts left in melee on the cave courses, two in three were against a one-block rise with the
 * body in the air: a crit jump keeps the speed it left the ground with, 0.91 of it a tick, so a body stepping back at
 * 0.1 blocks a tick drifts three quarters of a block before it lands, onto a face the old probe, a third of a block
 * ahead, had not reached yet. One in four touched a block beside the column under the body's middle, which the old
 * probe did not look at: the body is 0.6 wide and meets whatever its sides overlap.
 */
final class MeleeFooting {

    /** How far past the body's side the cell ahead is looked for: a little over a tick of a walk. */
    private static final double PROBE = 0.3;

    /** Below this, a world-frame impulse is no step at all. */
    private static final double STILL = 1e-3;

    /** Below this, in blocks a tick, the body is not being carried anywhere. */
    private static final double CARRIED = 0.02;

    /**
     * On the ground with the step let go, the body slides this many times its speed before it stops: vanilla keeps
     * 0.546 of a walk's speed a tick, and 0.546 / (1 - 0.546) is 1.2.
     */
    private static final double GROUND_STOP = 1.2;

    /**
     * In the air the body keeps 0.91 of its speed a tick, and a jump is about a dozen ticks off the ground: from a jump
     * or the run-up to one, it covers about six times its speed before it lands.
     */
    private static final double AIR_DRIFT = 6.0;

    private MeleeFooting() {}

    /**
     * The footing for a {@code left}, {@code forward} impulse in the body's own frame: the impulse with whatever heads
     * into a wall taken out, and whether to jump for a rise one block up ahead. The result is {@code {left, forward,
     * jump}}, the jump 1 or 0.
     */
    static float[] step(Player p, WorldView w, float left, float forward) {
        return step(p, w, left, forward, true, false);
    }

    /**
     * {@link #step(Player, WorldView, float, float)}, saying whether the step may {@code climb} a rise (the run from a
     * creeper's blast must, a fight need not) and whether a jump is {@code jumpSoon} (the crit's), which carries the
     * body as far as a jump does. Both matter only with {@link BehaviorConfig#combatFootingDrift}.
     */
    static float[] step(Player p, WorldView w, float left, float forward, boolean climb, boolean jumpSoon) {
        double yaw = Math.toRadians(p.getYRot());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        // The camera frame to the world's: forward is (-sin, cos), left is (cos, sin).
        double dx = -sin * forward + cos * left;
        double dz = cos * forward + sin * left;
        boolean drift = BehaviorConfig.combatFootingDrift;
        Vec3 v = p.getDeltaMovement();
        if (Math.hypot(dx, dz) < STILL && !(drift && Math.hypot(v.x, v.z) >= CARRIED)) {
            return new float[] {left, forward, 0f};
        }
        BlockPos feet = BlockPos.containing(p.getX(), p.getY() + STILL, p.getZ());
        boolean airborne = !p.onGround();
        Face x;
        Face z;
        int fx;
        int fz;
        if (drift) {
            // A climb keeps the short look: it jumps the rise it reaches, and a face it is about to clear in the air
            // is not one to stop for.
            double carry = climb ? 0.0 : airborne || jumpSoon ? AIR_DRIFT : GROUND_STOP;
            fx = heading(dx, v.x);
            fz = heading(dz, v.z);
            x = fx == 0 ? Face.OPEN : faceAcross(w, p, feet, true, fx, PROBE + Math.abs(v.x) * carry);
            z = fz == 0 ? Face.OPEN : faceAcross(w, p, feet, false, fz, PROBE + Math.abs(v.z) * carry);
        } else {
            double reach = p.getBbWidth() / 2 + PROBE;
            fx = Math.abs(dx) < STILL ? 0 : (int) Math.signum(dx);
            fz = Math.abs(dz) < STILL ? 0 : (int) Math.signum(dz);
            x = fx == 0 ? Face.OPEN : face(w, feet, BlockPos.containing(p.getX() + fx * reach, feet.getY(), p.getZ()));
            z = fz == 0 ? Face.OPEN : face(w, feet, BlockPos.containing(p.getX(), feet.getY(), p.getZ() + fz * reach));
        }
        // In the air with a rise still beside the feet, the step waits until the feet are over the rise's top: pressed
        // on, the body met the face on the way up (or, off a crit jump that was not for the rise, fell back from it).
        // A fight does not climb at all under combatFootingDrift: a rise is stood off like a wall, and the swing
        // reaches a mob on top of it from below.
        boolean stopX = x == Face.WALL || x == Face.RISE && (airborne || drift && !climb);
        boolean stopZ = z == Face.WALL || z == Face.RISE && (airborne || drift && !climb);
        // Braking in the air is for a face the body will not clear: any wall, and a rise to a fight, which never jumps
        // one.
        if (stopX) {
            dx = stopped(dx, v.x, fx, drift && airborne && (!climb || x == Face.WALL));
        }
        if (stopZ) {
            dz = stopped(dz, v.z, fz, drift && airborne && (!climb || z == Face.WALL));
        }
        boolean rise = x == Face.RISE || z == Face.RISE;
        boolean jump = drift ? x == Face.RISE && !stopX || z == Face.RISE && !stopZ : rise;
        // Back to the camera frame.
        float outForward = (float) (-sin * dx + cos * dz);
        float outLeft = (float) (cos * dx + sin * dz);
        return new float[] {outLeft, outForward, jump && p.onGround() ? 1f : 0f};
    }

    /**
     * The way the body heads along one axis: the impulse's, or with none along it, the way it is still being carried.
     */
    private static int heading(double impulse, double speed) {
        if (Math.abs(impulse) >= STILL) {
            return (int) Math.signum(impulse);
        }
        return Math.abs(speed) >= CARRIED ? (int) Math.signum(speed) : 0;
    }

    /**
     * The impulse along an axis that heads {@code toward} a face it must not meet: none, or in the air against the
     * speed still carrying the body there ({@code brake}), since the air keeps nine tenths of it a tick and only the
     * step can take any off.
     */
    private static double stopped(double impulse, double speed, int toward, boolean brake) {
        if (brake && Math.signum(speed) == toward && Math.abs(speed) >= CARRIED) {
            return -toward;
        }
        return Math.signum(impulse) == toward ? 0.0 : impulse;
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

    /**
     * The worst face along an axis ({@code alongX}, else z) the way {@code toward}, within {@code past} of the body's
     * side, across every column the body overlaps: a body straddling two columns meets the face of either. A cell open
     * at the feet but closed at the head is a wall too.
     */
    private static Face faceAcross(WorldView w, Player p, BlockPos feet, boolean alongX, int toward, double past) {
        double half = p.getBbWidth() / 2;
        double at = alongX ? p.getX() : p.getZ();
        double across = alongX ? p.getZ() : p.getX();
        int own = Mth.floor(at);
        int last = Mth.floor(at + toward * (half + past));
        int lo = Mth.floor(across - half);
        int hi = Mth.floor(across + half - STILL);
        Face worst = Face.OPEN;
        for (int a = own + toward; toward > 0 ? a <= last : a >= last; a += toward) {
            for (int c = lo; c <= hi; c++) {
                BlockPos cell = alongX ? new BlockPos(a, feet.getY(), c) : new BlockPos(c, feet.getY(), a);
                Face f = w.isPassable(cell) && !w.isPassable(cell.above()) ? Face.WALL : face(w, feet, cell);
                worst = f.ordinal() > worst.ordinal() ? f : worst;
            }
            if (worst != Face.OPEN) {
                return worst;
            }
        }
        return worst;
    }
}
