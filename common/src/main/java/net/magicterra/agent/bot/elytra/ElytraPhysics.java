package net.magicterra.agent.bot.elytra;

import net.minecraft.world.phys.Vec3;

/**
 * Faithful, pure (no world / no side effects) per-tick simulation of Minecraft
 * 1.21.1 elytra flight — the foundation Baritone's elytra builds its forward
 * physics simulation on. The reactive controller (milestone B) simulates many
 * candidate pitches over a horizon with these steps, raytraces each predicted
 * trajectory against terrain, and picks the pitch that best follows the path
 * without crashing.
 *
 * Constants are decompiled exact from {@code LivingEntity.travel} (fall-flying
 * branch) and {@code FireworkRocketEntity.tick} for 1.21.1 — see the project
 * memory {@code reference-elytra-physics}. Do NOT "tidy" the magic numbers; they
 * mirror vanilla bit-for-bit and the simulator is validated tick-by-tick against
 * the live client.
 *
 * Mutation order within a glide tick matters and follows vanilla exactly: the
 * horizontal speed {@code hSpeed} is sampled from the velocity at the START of
 * the tick (used by the dive-redirect and pitch-up terms), while the running
 * velocity components are read back already-mutated where vanilla does.
 *
 * PITCH SIGN (MC convention, a classic footgun): {@code xRot} is POSITIVE
 * looking DOWN and NEGATIVE looking UP. So a positive pitch is a DIVE that
 * BUILDS speed (gravity redirected forward by the {@code vy<0} term), and a
 * negative pitch is a CLIMB that BLEEDS speed (the {@code pitchRad<0} term
 * trades horizontal speed for altitude). Verified against the live speeds:
 * pitch +30 ≈ 30 b/s dive, level glide ≈ 18 b/s, level + fireworks ≈ 30 b/s.
 */
public final class ElytraPhysics {
    private ElytraPhysics() {}

    /** Player base gravity ({@code LivingEntity.DEFAULT_BASE_GRAVITY}). Clamped
     *  to {@link #GRAVITY_SLOW_FALLING} only under Slow Falling while descending. */
    public static final double GRAVITY = 0.08;
    public static final double GRAVITY_SLOW_FALLING = 0.01;

    /** Position + velocity (deltaMovement) of a flying body. Immutable. */
    public record State(Vec3 pos, Vec3 vel) {}

    /**
     * Unit look vector from yaw/pitch in DEGREES, MC convention
     * ({@code Entity.calculateViewVector}): {@code (-sinYaw·cosPitch, -sinPitch,
     * cosYaw·cosPitch)} with angles in radians. Length is 1.
     */
    public static Vec3 lookVec(float yawDeg, float pitchDeg) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        double cosPitch = Math.cos(pitch);
        return new Vec3(-Math.sin(yaw) * cosPitch, -Math.sin(pitch), Math.cos(yaw) * cosPitch);
    }

    /**
     * One tick of elytra glide (no firework boost), returning the new velocity.
     * Mirrors the {@code isFallFlying()} branch of {@code LivingEntity.travel}.
     * {@code look} must be a unit vector (e.g. from {@link #lookVec}); since its
     * length is 1, vanilla's {@code d5 = cos²(pitch)·min(1, lookLen/0.4)}
     * collapses to {@code cos²(pitch)}.
     *
     * @param gravity {@link #GRAVITY} normally, {@link #GRAVITY_SLOW_FALLING}
     *                only when Slow Falling and {@code vel.y <= 0}.
     */
    public static Vec3 glideStep(Vec3 vel, Vec3 look, float pitchDeg, double gravity) {
        double pitchRad = Math.toRadians(pitchDeg);
        double hLook = Math.sqrt(look.x * look.x + look.z * look.z);    // = cos(pitch)
        double hSpeed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);       // sampled at tick start
        double cosP = Math.cos(pitchRad);
        double d5 = cosP * cosP;

        double vx = vel.x, vy = vel.y, vz = vel.z;
        vy += gravity * (-1.0 + d5 * 0.75);
        if (vy < 0.0 && hLook > 0.0) {                                  // dive: redirect fall along look
            double d6 = vy * -0.1 * d5;
            vx += look.x * d6 / hLook;
            vy += d6;
            vz += look.z * d6 / hLook;
        }
        if (pitchRad < 0.0 && hLook > 0.0) {                           // nose up: trade speed for climb
            double d10 = hSpeed * (-Math.sin(pitchRad)) * 0.04;
            vx += -look.x * d10 / hLook;
            vy += d10 * 3.2;
            vz += -look.z * d10 / hLook;
        }
        if (hLook > 0.0) {                                             // steer horizontal velocity toward look
            vx += (look.x / hLook * hSpeed - vx) * 0.1;
            vz += (look.z / hLook * hSpeed - vz) * 0.1;
        }
        return new Vec3(vx * 0.99, vy * 0.98, vz * 0.99);              // drag
    }

    /**
     * The velocity increment a live firework rocket adds each tick it is alive
     * while the holder is fall-flying ({@code FireworkRocketEntity.tick}): pulls
     * the velocity toward {@code look·1.5}. Applied as a SEPARATE entity tick
     * from the player's glide; {@link #stepTick} applies it before the glide
     * (the order validated against live telemetry).
     */
    public static Vec3 fireworkBoost(Vec3 vel, Vec3 look) {
        return vel.add(
                look.x * 0.1 + (look.x * 1.5 - vel.x) * 0.5,
                look.y * 0.1 + (look.y * 1.5 - vel.y) * 0.5,
                look.z * 0.1 + (look.z * 1.5 - vel.z) * 0.5);
    }

    /**
     * Advance one full tick at a fixed heading: optional firework boost, then the
     * glide step, then integrate position by the resulting velocity (NO world
     * collision — the controller raytraces separately). Uses normal gravity.
     */
    public static State stepTick(State s, float yawDeg, float pitchDeg, boolean fireworkActive) {
        return stepTick(s, yawDeg, pitchDeg, fireworkActive, GRAVITY);
    }

    public static State stepTick(State s, float yawDeg, float pitchDeg, boolean fireworkActive, double gravity) {
        Vec3 look = lookVec(yawDeg, pitchDeg);
        Vec3 vel = s.vel();
        if (fireworkActive) vel = fireworkBoost(vel, look);
        vel = glideStep(vel, look, pitchDeg, gravity);
        return new State(s.pos().add(vel), vel);
    }
}
