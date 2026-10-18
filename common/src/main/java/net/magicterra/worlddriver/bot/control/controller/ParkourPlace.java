package net.magicterra.worlddriver.bot.control.controller;

import net.magicterra.worlddriver.bot.control.act.Keys;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.config.GameConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.body.LookController;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import static net.magicterra.worlddriver.bot.control.route.PathSmoothing.hasPendingEdge;
import static net.magicterra.worlddriver.bot.control.NavigatorConstants.*;
import static net.magicterra.worlddriver.config.Mod.LOG;
import static net.magicterra.worlddriver.game.body.BotUtil.eyeWithin;
import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.bot.control.TickCtx;

/**
 * A parkour leap that places its own landing block mid-air (Baritone allowParkourPlace), owned whole so the
 * route's place, which stops the body to place, can't kill the arc's momentum.
 * <ul>
 *   <li>LEAP (floor still air): forward + sprint + jump off the lip with the run-up PRESERVED, then place
 *       the landing block mid-air the instant a support is in reach. The hit is synthetic, so aiming down
 *       isn't needed — the crosshair stays on the destination for the whole arc.</li>
 *   <li>SETTLE (floor placed, still airborne): keep driving forward to clear the gap, but DROP sprint and
 *       hold sneak — sneak's ledge guard stops the bot on the fresh 1-wide block on touchdown instead of
 *       letting sprint momentum carry it off the far edge into a gap beyond.</li>
 * </ul>
 * Once placed AND grounded it lets the regular walk and the arrival check have the tick.
 */
public final class ParkourPlace implements Controller {

    private static final Set<Channel> CHANNELS = EnumSet.allOf(Channel.class);

    @Override public String name() { return "parkourPlace"; }
    @Override public Set<Channel> channels() { return CHANNELS; }
    @Override public int priority() { return 5; }
    /** Bounded by the action timeout. */
    @Override public int maxTicks() { return Integer.MAX_VALUE; }

    @Override public String whyNot(Navigator nav, TickCtx cx, WorldView world) {
        Move.Edge edge = nav.route.edgeAt(nav.route.step());
        if (edge == null || edge.move == null || !edge.move.startsWith("parkourPlace")) return "not a parkour-place edge";
        if (!hasPendingEdge(world, edge) && cx.frame.p.onGround()) return "landed on the placed block";
        return null;
    }

    @Override public boolean canContinue(Navigator nav, TickCtx cx, WorldView world) {
        return whyNot(nav, cx, world) == null;
    }

    @Override public boolean tick(Navigator nav, TickCtx cx, Body a, WorldView world) {
        LivingEntity p = cx.frame.p;
        Move.Edge edge = nav.route.edgeAt(nav.route.step());
        BlockPos dest = nav.route.path().get(nav.route.step());
        BlockPos floor = edge.toPlace.get(0);
        boolean placed = !hasPendingEdge(world, edge);   // landing block is down
        boolean grounded = p.onGround();
        nav.totalTicks = 0;
        nav.stuckTicks = 0;       // owns this cell while leaping — not "stuck"
        if (++nav.actionTicks > ControlConfig.breakTimeoutTicks) {
            nav.error = "parkour-place stalled at " + dest;
            nav.route.drop(nav.error);
            return true;
        }
        // Snap heading at the destination — can't course-correct mid-air.
        double adx = (dest.getX() + 0.5) - p.getX();
        double adz = (dest.getZ() + 0.5) - p.getZ();
        if (Math.abs(adx) > 1e-4 || Math.abs(adz) > 1e-4) {
            float yaw = (float) Math.toDegrees(Math.atan2(-adx, adz));
            p.setYRot(yaw);
            p.yHeadRot = yaw;
            p.yBodyRot = yaw;
            LookController.requestSnap();   // a parkour leap's heading is functional — exempt from the global slew
        }
        p.setXRot(0f);
        Keys.forward(a, true);
        nav.keys.jump(a, !placed && grounded);   // jump off the lip once
        boolean sprint = !placed;                          // brake after the block is down
        p.setSprinting(sprint);
        Keys.sneak(a, placed);               // sneak-brake / ledge-guard on landing
        p.setShiftKeyDown(placed);
        if (!placed && !grounded && nav.equipment.holdPlaceable()) {
            // 4.0, not the full blockReachToCentre(p): this fires MID-LEAP, so the eye read
            // here is already a tick stale by the time the place lands. The margin is bought
            // on purpose — see BotUtil#standingEye for the table of what each reach site pays.
            if (eyeWithin(p, floor, PARKOUR_PLACE_REACH)) {
                nav.blocks.place(world::isSolid, floor);
                if (GameConfig.navigatorDebug)
                    LOG.info(
                            "[navigator] parkour-place floor={},{},{} y={} dy={} solid={}",
                            floor.getX(), floor.getY(), floor.getZ(),
                            String.format(Locale.ROOT, "%.2f", p.getY()),
                            String.format(Locale.ROOT, "%.2f", p.getDeltaMovement().y),
                            world.isSolid(floor));
            }
        }
        return true;
    }

    @Override public void stop(String why) {}
}
