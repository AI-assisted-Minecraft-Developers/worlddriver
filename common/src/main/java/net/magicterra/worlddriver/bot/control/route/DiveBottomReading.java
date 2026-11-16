package net.magicterra.worlddriver.bot.control.route;

import static net.magicterra.worlddriver.config.Mod.LOG;

import java.util.List;
import java.util.Locale;
import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.game.observe.FlagsEcho;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * One line per dive, at the advance off the bottom node of a dive under water: the body's sprint and swim flags, pose
 * and speed there, and when the server last echoed its flags byte back. A slow swim on the swimming benchmark course
 * parted from the fast ones on this tick (it sank harder and lost a fifth of its speed one or two ticks early, then met
 * the bank short) and never did so in isolation, only inside full suite runs; this line is what such a run leaves
 * behind to say why. Not capped with the step-advance lines, which a long course runs out of before the pool.
 */
final class DiveBottomReading {

    private DiveBottomReading() {}

    /** Logs the reading when advancing off {@code w} is leaving the bottom of a dive. */
    static void note(Navigator nav, WorldView world, LivingEntity p, BlockPos w, BlockPos nx) {
        List<BlockPos> path = nav.route.path();
        int step = nav.route.step();
        if (path == null
                || world == null
                || step < 1
                || step >= path.size()
                || nx == null
                || !p.isInWater()
                || !world.isWater(w)
                || path.get(step - 1).getY() <= w.getY()
                || nx.getY() < w.getY()) {
            return;
        }
        Vec3 v = p.getDeltaMovement();
        int echo = FlagsEcho.lastTick();
        LOG.info(
                "[navigator] dive bottom: w={} at=({}) sprint={} swim={} pose={} under={} vh={} vy={} tick={} "
                        + "flagsEchoAgo={} echoFlags=0x{}",
                w.toShortString(),
                String.format(Locale.ROOT, "%.3f,%.3f,%.3f", p.getX(), p.getY(), p.getZ()),
                p.isSprinting(),
                p.isSwimming(),
                p.getPose(),
                p.isUnderWater(),
                String.format(Locale.ROOT, "%.4f", v.horizontalDistance()),
                String.format(Locale.ROOT, "%.4f", v.y),
                p.tickCount,
                echo < 0 ? "none" : Integer.toString(p.tickCount - echo),
                Integer.toHexString(FlagsEcho.lastFlags() & 0xff));
    }
}
