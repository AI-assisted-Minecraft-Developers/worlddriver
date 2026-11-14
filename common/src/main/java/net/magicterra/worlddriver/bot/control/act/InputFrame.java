package net.magicterra.worlddriver.bot.control.act;

/**
 * One walking tick's movement commands, held until the tick has been decided and the guards have had their say: the
 * move channel (a push, or a raw forward key), jump and sneak. {@link Actuate} hands them to the body afterwards, once.
 *
 * <p>A push is kept in the world's frame, not the camera's. A body moves relative to where it faces at the moment it
 * integrates the input, and the navigator turns the head after the drive has computed its push — the pre-dig aim is the
 * standing example — so a camera-relative push recorded early was carried off by the later turn. Kept in the world
 * frame, the push lands along the bearing it was computed for, whatever the head does afterwards.
 *
 * <p>Look is not held here: the phases turn the head on the entity directly, and the frame reads where it ended up.
 * Sprint is not either; it is a flag on the entity that the phases set and read back within the same tick.
 */
final class InputFrame {

    /**
     * A push and a raw forward can both be commanded in one tick, and the body applies them in the order given; these
     * remember which came last.
     */
    private boolean push;

    private boolean forward;

    private boolean pushLast;

    /** The push as commanded, the camera yaw it was relative to, and the same push in the world frame. */
    private float left;

    private float fwd;

    private float pushYaw;

    private double worldX;

    private double worldZ;

    private float rawForward;

    private Boolean jump;

    private Boolean sneak;

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
        double r = Math.toRadians(yaw);
        double sin = Math.sin(r);
        double cos = Math.cos(r);
        this.worldX = left * cos - forward * sin;
        this.worldZ = forward * cos + left * sin;
    }

    /** The raw forward key: straight along wherever the body faces when it moves. */
    void forward(float forward) {
        this.forward = true;
        this.pushLast = false;
        this.rawForward = forward;
    }

    void jump(boolean v) {
        jump = v;
    }

    /** Null when no one commanded the channel this tick, which leaves the body's own input alone. */
    Boolean jump() {
        return jump;
    }

    void sneak(boolean v) {
        sneak = v;
    }

    Boolean sneak() {
        return sneak;
    }

    boolean hasPush() {
        return push;
    }

    boolean hasForward() {
        return forward;
    }

    boolean pushLast() {
        return pushLast;
    }

    float rawForward() {
        return rawForward;
    }

    /** True when either move command this tick asks the body to move at all. */
    boolean moving() {
        return (push && (left != 0f || fwd != 0f)) || (forward && rawForward != 0f);
    }

    /**
     * The move the body will make, in the world's frame {x, z}, when at most one move was commanded: the push, else the
     * raw forward along a camera at {@code yaw}; {0, 0} when neither was. With both, which one the body takes is up to
     * the body, so a caller has to rule that case out first.
     */
    double[] worldMove(float yaw) {
        if (push) {
            return new double[] {worldX, worldZ};
        }
        if (forward) {
            double r = Math.toRadians(yaw);
            return new double[] {-Math.sin(r) * rawForward, Math.cos(r) * rawForward};
        }
        return new double[] {0.0, 0.0};
    }

    /**
     * Replaces this tick's move with a push of {@code x}, {@code z} in the world's frame, for a camera at {@code yaw}.
     */
    void replaceMove(float yaw, double x, double z) {
        double r = Math.toRadians(yaw);
        double sin = Math.sin(r);
        double cos = Math.cos(r);
        forward = false;
        push(yaw, (float) (x * cos + z * sin), (float) (-x * sin + z * cos));
    }

    /**
     * The push relative to a camera at {@code yaw}: as commanded when the head has not turned since, otherwise the
     * world-frame push turned into that camera's frame. {left, forward}.
     */
    float[] pushFor(float yaw) {
        if (yaw == pushYaw) {
            return new float[] {left, fwd};
        }
        double r = Math.toRadians(yaw);
        double sin = Math.sin(r);
        double cos = Math.cos(r);
        return new float[] {(float) (worldX * cos + worldZ * sin), (float) (-worldX * sin + worldZ * cos)};
    }
}
