package net.magicterra.worlddriver.bot.process;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.body.Body;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import static net.magicterra.worlddriver.WorldDriverCommon.LOG;
import static net.magicterra.worlddriver.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.worlddriver.bot.util.BotInteract.*;
import static net.magicterra.worlddriver.bot.util.BotUtil.*;
import java.util.Locale;

public final class LookProcess implements BotProcess {
    private static final int MAX_TICKS = 200;     // ~10 s @ 20 tps
    private static final float ALIGN_EPS = 0.5f;
    private final BlockPos track;                 // null → fixed yaw/pitch
    private final float fixedYaw, fixedPitch;
    private int ticks;

    public LookProcess(BlockPos track, float yaw, float pitch) {
        this.track = track;
        this.fixedYaw = yaw;
        this.fixedPitch = pitch;
    }

    public String kind() { return "look"; }

    public void attach(BotState st) {
        st.look.active = true;
        st.look.goal = track != null
                ? "pos " + track.getX() + "," + track.getY() + "," + track.getZ()
                : String.format(Locale.ROOT, "yaw %.1f pitch %.1f", fixedYaw, fixedPitch);
        if (track != null) st.look.target = track;
        st.look.startedAtMs = System.currentTimeMillis();
        st.look.lastError = null;
        if (BotConfig.walkerDebug)
            LOG.info("[look] attach goal={} smoothLook={} degPerTick={}",
                    st.look.goal, BotConfig.smoothLook, BotConfig.smoothLookDegPerTick);
    }

    public boolean tick(Body a, WorldView w, BotState st) {
        LivingEntity p = a.entity();
        if (p == null) { failure = st.look.lastError = "player vanished"; st.look.reset(); return true; }
        float ty = fixedYaw, tp = fixedPitch;
        if (track != null) {
            Vec3 eye = p.getEyePosition();
            double dx = track.getX() + 0.5 - eye.x, dy = track.getY() + 0.5 - eye.y, dz = track.getZ() + 0.5 - eye.z;
            ty = (float) Math.toDegrees(Math.atan2(-dx, dz));
            tp = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        }
        float curY = p.getYRot(), curP = p.getXRot();
        float ny = smoothAngle(curY, ty);
        float np = smoothAngle(curP, tp);
        p.setYRot(ny); p.yHeadRot = ny; p.yBodyRot = ny; p.setXRot(np);
        float yawErr = Math.abs(((ty - ny) % 360f + 540f) % 360f - 180f);
        boolean aligned = yawErr < ALIGN_EPS && Math.abs(tp - np) < ALIGN_EPS;
        if (BotConfig.walkerDebug)
            LOG.info("[look] t={} yaw {}->{} (tgt {}) pitch {}->{} (tgt {}) yawErr={} aligned={}",
                    ticks, curY, ny, ty, curP, np, tp, yawErr, aligned);
        if (aligned) { st.look.reset(); return true; }
        if (++ticks > MAX_TICKS) {
            // Split out of `aligned || ++ticks > MAX_TICKS`, which collapsed「the aim converged」and
            //「the aim never converged in 200 ticks」into ONE unstamped exit. `snapshot()` emits
            // lastError only when it is non-null and `attach` cleared it, so a look that never got
            // there reported exactly the clean finish a look that did reports. The residual comes
            // with it because an aim that stopped 0.6° out and one still 90° out want different
            // work, and the only place that distinction existed was the `walkerDebug` line above —
            // which is off in every normal run, so it is not an instrument.
            failure = st.look.lastError = String.format(Locale.ROOT,
                    "aim did not converge in %d ticks (yawErr=%.2f pitchErr=%.2f, eps=%.2f)",
                    MAX_TICKS, yawErr, Math.abs(tp - np), ALIGN_EPS);
            st.look.reset();
            return true;
        }
        return false;
    }

    private String failure;

    @Override public String failure() { return failure; }
}
