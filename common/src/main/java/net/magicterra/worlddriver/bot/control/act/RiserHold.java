package net.magicterra.worlddriver.bot.control.act;

import net.magicterra.worlddriver.config.ControlConfig;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * {@link ControlConfig#navigatorRiserFaceHold}: no push into the face of a rise the body is against with its feet below
 * the rise's top, while it is in the air or taking off. On a staircase of one-block treads no take-off clears both
 * risers, so the body lands on the first tread against the second riser and jumps it from there; the walk kept forward
 * pressed into that face on the landing tick, through any tick vanilla's jump delay held it on the ground, and up the
 * first two ticks of the jump. Each of those ticks is a {@code horizontalCollision}, and four or more in a row are a
 * bump by the benchmark's rule, about one a run on the cave combat courses' approach. Pressing a face gains nothing
 * until the feet are over the top, where the push resumes; let go, the velocity into the face that the collision zeroed
 * stays zero and the body rises along the face without touching it. Only the part of the push into the face is dropped,
 * so a diagonal keeps sliding along it.
 */
final class RiserHold {

    /** A face this close to the body's side is one it is against: collision leaves the box touching it. */
    private static final double TOUCH = 0.01;

    /** How high a jump lifts the feet: 1.2522 at its top. */
    private static final double JUMP_RISE = 1.25;

    private RiserHold() {}

    /** Drops from {@code frame}'s move the part into the face of a rise {@code body} is against, when it applies. */
    static void apply(InputFrame frame, LivingEntity body) {
        if (!ControlConfig.navigatorRiserFaceHold
                || body == null
                || !frame.moving()
                || body.isInWater()
                || body.isInLava()
                || body.onClimbable()
                || body.isFallFlying()
                || body.onGround() && !Boolean.TRUE.equals(frame.jump())) {
            return;
        }
        float yaw = body.getYRot();
        double[] move = frame.worldMove(yaw);
        boolean dropX = move[0] != 0.0 && againstRise(body, Math.signum(move[0]), 0.0);
        boolean dropZ = move[1] != 0.0 && againstRise(body, 0.0, Math.signum(move[1]));
        if (dropX || dropZ) {
            frame.replaceMove(yaw, dropX ? 0.0 : move[0], dropZ ? 0.0 : move[1]);
        }
    }

    /**
     * Whether the body touches something along {@code sx}, {@code sz} that a jump from here clears: the box nudged that
     * way collides, and lifted by a jump's rise it fits there, with room overhead to rise into. A wall too high to jump
     * is left to the walk's own handling.
     */
    private static boolean againstRise(LivingEntity body, double sx, double sz) {
        Level level = body.level();
        AABB box = body.getBoundingBox();
        AABB nudged = box.move(sx * TOUCH, 0.0, sz * TOUCH);
        return !level.noCollision(body, nudged)
                && level.noCollision(body, box.move(0.0, JUMP_RISE, 0.0))
                && level.noCollision(body, nudged.move(0.0, JUMP_RISE, 0.0));
    }
}
