package net.magicterra.worlddriver.bot.control;

/**
 * One walking tick's movement commands, held until the tick has been decided and the guards have had
 * their say: the move channel (a push, or a raw forward key), jump and sneak. {@link Actuate} hands them
 * to the body afterwards, once.
 *
 * <p>A push is kept in the world's frame, not the camera's. A body moves relative to where it faces at
 * the moment it integrates the input, and the walker turns the head after the drive has computed its
 * push — the pre-dig aim is the standing example — so a camera-relative push recorded early was carried
 * off by the later turn. Kept in the world frame, the push lands along the bearing it was computed for,
 * whatever the head does afterwards.
 *
 * <p>Look is not held here: the phases turn the head on the entity directly, and the frame reads where it
 * ended up. Sprint is not either; it is a flag on the entity that the phases set and read back within the
 * same tick.
 */
final class InputFrame {

    /** A push and a raw forward can both be commanded in one tick, and the body applies them in the
     *  order given; these remember which came last. */
    private boolean push, forward, pushLast;
    /** The push as commanded, the camera yaw it was relative to, and the same push in the world frame. */
    private float left, fwd, pushYaw;
    private double worldX, worldZ;
    private float rawForward;
    private Boolean jump, sneak;

    void clear() {
        push = forward = pushLast = false;
        jump = sneak = null;
    }

    /** {@code left}/{@code forward} relative to a camera at {@code yaw}, as vanilla's input vector reads it. */
    void push(float yaw, float left, float forward) {
        this.push = true;
        this.pushLast = true;
        this.left = left;
        this.fwd = forward;
        this.pushYaw = yaw;
        double r = Math.toRadians(yaw), sin = Math.sin(r), cos = Math.cos(r);
        this.worldX = left * cos - forward * sin;
        this.worldZ = forward * cos + left * sin;
    }

    /** The raw forward key: straight along wherever the body faces when it moves. */
    void forward(float forward) {
        this.forward = true;
        this.pushLast = false;
        this.rawForward = forward;
    }

    void jump(boolean v) { jump = v; }
    void sneak(boolean v) { sneak = v; }

    boolean hasPush() { return push; }
    boolean hasForward() { return forward; }
    boolean pushLast() { return pushLast; }
    float rawForward() { return rawForward; }
    /** Null when no one commanded the channel this tick, which leaves the body's own input alone. */
    Boolean jump() { return jump; }
    Boolean sneak() { return sneak; }

    /** True when either move command this tick asks the body to move at all. */
    boolean moving() {
        return (push && (left != 0f || fwd != 0f)) || (forward && rawForward != 0f);
    }

    /** The push relative to a camera at {@code yaw}: as commanded when the head has not turned since,
     *  otherwise the world-frame push turned into that camera's frame. {left, forward}. */
    float[] pushFor(float yaw) {
        if (yaw == pushYaw) return new float[]{left, fwd};
        double r = Math.toRadians(yaw), sin = Math.sin(r), cos = Math.cos(r);
        return new float[]{(float) (worldX * cos + worldZ * sin), (float) (-worldX * sin + worldZ * cos)};
    }
}
