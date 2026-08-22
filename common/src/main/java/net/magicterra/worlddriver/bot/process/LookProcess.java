package net.magicterra.worlddriver.bot.process;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.movement.Avatar;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
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

    public boolean tick(Avatar a, WorldView w, BotState st) {
        Player p = a.player();
        if (p == null) { st.look.lastError = "player vanished"; st.look.reset(); return true; }
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
        if (aligned || ++ticks > MAX_TICKS) { st.look.reset(); return true; }
        return false;
    }
}
