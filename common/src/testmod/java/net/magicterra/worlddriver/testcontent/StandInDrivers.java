package net.magicterra.worlddriver.testcontent;

import java.util.Optional;

import net.magicterra.worlddriver.api.game.EntityDriver;
import net.magicterra.worlddriver.api.game.Mover;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * The entity drivers the testmod registers, the way another mod would, for two stand-ins whose own
 * travel reads no input ({@link SelfMovingMob}): without a registered driver the bot cannot move either.
 */
public final class StandInDrivers {
    private StandInDrivers() {}

    /** Blocks a tick at full input. */
    private static final double SPEED = 0.2;
    /** Blocks a tick the mover covers. */
    private static final double STEP = 0.5;

    /** Turns each tick's input into the velocity the stand-in's own travel then moves it by. */
    public static EntityDriver velocity(Mob mob) {
        return new EntityDriver() {
            @Override public void input(Input in) {
                double yaw = Math.toRadians(in.yaw());
                double dx = in.left() * Math.cos(yaw) - in.forward() * Math.sin(yaw);
                double dz = in.forward() * Math.cos(yaw) + in.left() * Math.sin(yaw);
                Vec3 v = mob.getDeltaMovement();
                double vy = in.jump() && mob.onGround() ? 0.42 : v.y;
                mob.setDeltaMovement(dx * SPEED, vy, dz * SPEED);
            }

            @Override public void face(float yaw, float pitch) {
                mob.setYRot(yaw);
                mob.setXRot(pitch);
            }
        };
    }

    /** Takes each goto whole: half a block a tick straight at the target, through anything. */
    public static EntityDriver mover(Mob mob) {
        Mover mover = new Mover() {
            @Override public Progress drive(BlockPos target, double within) {
                Vec3 goal = Vec3.atBottomCenterOf(target);
                Vec3 to = goal.subtract(mob.position());
                if (to.length() <= Math.max(within, STEP)) {
                    mob.setPos(goal);
                    return Progress.ARRIVED;
                }
                Vec3 next = mob.position().add(to.normalize().scale(STEP));
                mob.setPos(next);
                return Progress.MOVING;
            }
        };
        return new EntityDriver() {
            @Override public void input(Input in) { /* only the mover moves it */ }
            @Override public void face(float yaw, float pitch) { mob.setYRot(yaw); }
            @Override public Optional<Mover> mover() { return Optional.of(mover); }
        };
    }
}
