package net.magicterra.worlddriver.bot.behavior;

import static net.magicterra.worlddriver.config.Mod.LOG;
import static net.magicterra.worlddriver.game.body.BotUtil.smoothAngle;

import java.util.Locale;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.GameConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Turns the body toward a block or a fixed yaw and pitch, for at most {@code MAX_TICKS} ticks. */
public final class LookProcess implements BotProcess {
    private static final int MAX_TICKS = 200; // ~10 s @ 20 tps

    private static final float ALIGN_EPS = 0.5f;

    private final BlockPos track; // null → fixed yaw/pitch

    private final float fixedYaw;

    private final float fixedPitch;

    private int ticks;

    private String failure;

    /** Creates a look that tracks {@code track} when it is set, otherwise holds {@code yaw} and {@code pitch}. */
    public LookProcess(BlockPos track, float yaw, float pitch) {
        this.track = track;
        this.fixedYaw = yaw;
        this.fixedPitch = pitch;
    }

    @Override
    public String kind() {
        return "look";
    }

    @Override
    public void attach(BotState st) {
        st.look.beginRun(
                track != null
                        ? "pos " + track.getX() + "," + track.getY() + "," + track.getZ()
                        : String.format(Locale.ROOT, "yaw %.1f pitch %.1f", fixedYaw, fixedPitch),
                track);
        if (GameConfig.navigatorDebug) {
            LOG.info(
                    "[look] attach goal={} smoothLook={} degPerTick={}",
                    st.look.goal,
                    GameConfig.smoothLook,
                    GameConfig.smoothLookDegPerTick);
        }
    }

    @Override
    public boolean tick(Body a, WorldView w, BotState st) {
        LivingEntity p = a.entity();
        if (p == null) {
            failure = st.look.lastError = "player vanished";
            st.look.reset();
            return true;
        }
        float ty = fixedYaw;
        float tp = fixedPitch;
        if (track != null) {
            Vec3 eye = p.getEyePosition();
            double dx = track.getX() + 0.5 - eye.x;
            double dy = track.getY() + 0.5 - eye.y;
            double dz = track.getZ() + 0.5 - eye.z;
            ty = (float) Math.toDegrees(Math.atan2(-dx, dz));
            tp = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        }
        float curY = p.getYRot();
        float curP = p.getXRot();
        float ny = smoothAngle(curY, ty);
        final float np = smoothAngle(curP, tp);
        p.setYRot(ny);
        p.yHeadRot = ny;
        p.yBodyRot = ny;
        p.setXRot(np);
        float yawErr = Math.abs(Mth.degreesDifference(ny, ty));
        boolean aligned = yawErr < ALIGN_EPS && Math.abs(tp - np) < ALIGN_EPS;
        if (GameConfig.navigatorDebug) {
            LOG.info(
                    "[look] t={} yaw {}->{} (tgt {}) pitch {}->{} (tgt {}) yawErr={} aligned={}",
                    ticks,
                    curY,
                    ny,
                    ty,
                    curP,
                    np,
                    tp,
                    yawErr,
                    aligned);
        }
        if (aligned) {
            st.look.reset();
            return true;
        }
        if (++ticks > MAX_TICKS) {
            // Split out of `aligned || ++ticks > MAX_TICKS`, which collapsed "the aim converged" and
            // "the aim never converged in 200 ticks" into ONE unstamped exit. `snapshot()` emits
            // lastError only when it is non-null and `attach` cleared it, so a look that never got
            // there reported exactly the clean finish a look that did reports. The residual comes
            // with it because an aim that stopped 0.6° out and one still 90° out want different
            // work, and the only place that distinction existed was the `navigatorDebug` line above —
            // which is off in every normal run, so it is not an instrument.
            failure = st.look.lastError = String.format(
                    Locale.ROOT,
                    "aim did not converge in %d ticks (yawErr=%.2f pitchErr=%.2f, eps=%.2f)",
                    MAX_TICKS,
                    yawErr,
                    Math.abs(tp - np),
                    ALIGN_EPS);
            st.look.reset();
            return true;
        }
        return false;
    }

    @Override
    public String failure() {
        return failure;
    }
}
