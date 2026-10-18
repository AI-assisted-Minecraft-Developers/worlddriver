package net.magicterra.worlddriver.bot.control;

import java.util.EnumSet;
import java.util.Set;

import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.config.GameConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import static net.magicterra.worlddriver.config.Mod.LOG;

/**
 * Surfacing for air (walkerDrowningEscape). A submerged climb-out deadlock can pin the bot under a bank lip
 * until its air runs out — Peaceful does not prevent drowning (live 2026-06-29: died at (-21,60,-42) while
 * the climb-out place spun 26 engage/bail cycles). Air below ~3 s while under water latches a surface-for-air
 * override that outranks every climb, dig and pillar: hold the swim-up jump, and if a solid lip caps the
 * head (or a wall blocks the rise) swim toward open surface. Released once air recovers or out of water;
 * the interrupted climb resumes fresh.
 *
 * <p>The latch is read from the world, not only from the body's flags: the client's under-water and air
 * flags survive a teleport (and a corpse) un-ticked — a replay put the bot on DRY land with stale
 * under-water and zero air, and the latch froze the whole walk. It engages and holds only while the world
 * has water at the feet or eyes and the bot is alive.
 */
final class DrownEscape implements Controller {

    private static final Set<Channel> CHANNELS = EnumSet.allOf(Channel.class);

    /** Ticks a picked heading is held, so the bot crosses cells instead of jittering between picks. */
    private static final int HEADING_HOLD_TICKS = 25;

    /** Whether the world has water where the bot is, this tick. */
    private boolean reallyInWater;

    @Override public String name() { return "drownEscape"; }
    @Override public Set<Channel> channels() { return CHANNELS; }
    @Override public int priority() { return 1; }
    /** As long as the air takes to come back; the latch, not a count, ends it. */
    @Override public int maxTicks() { return Integer.MAX_VALUE; }

    @Override public void sense(Walker wk, WalkerTickCtx cx, WorldView world) {
        if (!ControlConfig.walkerDrowningEscape) return;
        LivingEntity p = cx.frame.p;
        BlockPos foot = cx.frame.foot;
        reallyInWater = p.getHealth() > 0 && (world.isWater(foot) || world.isWater(foot.above()));
        if (reallyInWater && p.isUnderWater() && p.getAirSupply() <= 60) {
            if (!wk.drownGuard.latch && GameConfig.walkerDebug)
                LOG.info("[walker] DROWNING-ESCAPE engaged: air={} foot={},{},{} → surface for air",
                        p.getAirSupply(), foot.getX(), foot.getY(), foot.getZ());
            wk.drownGuard.latch = true;
        } else if (!reallyInWater || !p.isInWater() || p.getAirSupply() >= 240) {
            if (wk.drownGuard.latch && GameConfig.walkerDebug)
                LOG.info("[walker] DROWNING-ESCAPE released: air={} → resume", p.getAirSupply());
            wk.drownGuard.latch = false;
        }
    }

    @Override public String whyNot(Walker wk, WalkerTickCtx cx, WorldView world) {
        if (!ControlConfig.walkerDrowningEscape) return "switched off";
        if (!wk.drownGuard.latch) return "air is fine";
        if (!reallyInWater || !cx.frame.p.isInWater()) return "not in water";
        return null;
    }

    @Override public boolean canContinue(Walker wk, WalkerTickCtx cx, WorldView world) {
        return whyNot(wk, cx, world) == null;
    }

    @Override public boolean tick(Walker wk, WalkerTickCtx cx, Body a, WorldView world) {
        LivingEntity p = cx.frame.p;
        BlockPos foot = cx.frame.foot;
        wk.avatarJump(a, true);
        wk.blocks.breakHold(false);
        p.setSprinting(false);
        boolean riseBlocked = world.isSolid(foot.offset(0, 2, 0)) || p.horizontalCollision;
        if (riseBlocked) {
            // Swim toward open surface. A single fixed "reverse" heading dies in a POCKET (live
            // 2026-06-29 (-254,61,-219): capped head + the reverse heading also walled → the bot spun in
            // place a full minute and drowned at hp 5→0). Probe the 8 horizontal directions for one whose
            // column two cells out is water at head height with NO solid lid two above (a buoyant rise is
            // possible there); rotate the probe start each pick so a falsely-open direction can't be
            // re-picked forever.
            if (wk.drownGuard.turnTicks <= 0) {
                wk.drownGuard.turnTicks = HEADING_HOLD_TICKS;
                float pick = p.getYRot() + 180f;   // fallback: straight back
                for (int i = 0; i < 8; i++) {
                    float cand = ((wk.drownGuard.probe + i) % 8) * 45f;
                    int dx = (int) Math.round(-Math.sin(Math.toRadians(cand)));
                    int dz = (int) Math.round(Math.cos(Math.toRadians(cand)));
                    BlockPos out = foot.offset(dx * 2, 0, dz * 2);
                    if (world.isWater(out.above()) && !world.isSolid(out.offset(0, 2, 0))) {
                        pick = cand;
                        wk.drownGuard.probe = (wk.drownGuard.probe + i + 1) % 8;
                        break;
                    }
                }
                wk.drownGuard.heading = pick;
            }
            wk.drownGuard.turnTicks--;
            p.setYRot(wk.drownGuard.heading);
            p.yHeadRot = wk.drownGuard.heading; p.yBodyRot = wk.drownGuard.heading;
            p.setXRot(0f);
            Walker.avatarForward(a, true);
        } else {
            Walker.avatarForward(a, false);
        }
        return true;
    }

    @Override public void stop(String why) {}
}
