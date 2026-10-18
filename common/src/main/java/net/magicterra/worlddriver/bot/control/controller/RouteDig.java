package net.magicterra.worlddriver.bot.control.controller;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.config.GameConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import static net.magicterra.worlddriver.bot.control.route.PathSmoothing.hasPendingEdge;
import static net.magicterra.worlddriver.config.Mod.LOG;
import static net.magicterra.worlddriver.game.body.BotUtil.blockReachToCentre;
import static net.magicterra.worlddriver.game.body.BotUtil.eyeWithin;
import net.magicterra.worlddriver.bot.control.Walker;
import net.magicterra.worlddriver.bot.control.WalkerTickCtx;

/**
 * Breaking and placing the blocks the current edge needs before the bot walks into its cell. It holds the
 * look and the hands only: how the body moves meanwhile is the regular walk's call ({@link StandToWork}),
 * which reads {@link #work()} to know what is being worked on. Functional aim snaps the same tick,
 * independent of look smoothing. Runs each tick until the edge is clear.
 *
 * <p>A break cell beyond mining reach is walked to, never dug at from here. The client's
 * MultiPlayerGameMode takes any cell: it accumulates progress and breaks the block LOCALLY at full progress,
 * the server refuses the break by distance and sends the block back, and the bot has stood still the whole
 * time. Live: the pointer sat on a downBreak column 8 cells away, the block was "broken" and restored twice,
 * the next plan stepped down through the phantom air, and the bot orbited the column.
 */
public final class RouteDig implements Controller {

    private static final Set<Channel> CHANNELS = EnumSet.of(Channel.LOOK, Channel.HANDS);

    public enum Kind {
        /** Breaking {@link Work#cell}. */
        DIG,
        /** Placing into {@link Work#cell}. */
        PLACE,
        /** Nothing left to click this tick; the bot settles a tick before walking on. */
        SETTLE,
        /** The work stopped and the route was dropped (timed out, or a dig the breath cannot finish). */
        DROPPED
    }

    /** What was worked on this tick: {@code move} is the edge's move. */
    public record Work(Kind kind, BlockPos cell, String move) {}

    private Work work;
    private boolean breakInReach;

    @Override public String name() { return "routeDig"; }
    @Override public Set<Channel> channels() { return CHANNELS; }
    @Override public int priority() { return 7; }
    /** Bounded by the action timeout. */
    @Override public int maxTicks() { return Integer.MAX_VALUE; }

    /** This tick's work, null when not running. */
    public Work work() {
        return work;
    }

    @Override public void sense(Walker wk, WalkerTickCtx cx, WorldView world) {
        work = null;
        LivingEntity p = cx.frame.p;
        Move.Edge edge = wk.route.edgeAt(wk.route.step());
        breakInReach = true;
        if (edge == null) return;
        for (BlockPos b : edge.toBreak) {
            if (!world.isSolid(b)) continue;
            breakInReach = eyeWithin(p, b, blockReachToCentre(p));
            if (!breakInReach && GameConfig.walkerDebug && p.tickCount % 20 == 0)
                LOG.info("[walker] break cell {},{},{} out of reach ({} blocks from the eye) → walk to it first",
                        b.getX(), b.getY(), b.getZ(),
                        String.format(Locale.ROOT, "%.1f", Math.sqrt(p.getEyePosition().distanceToSqr(b.getCenter()))));
            break;
        }
    }

    @Override public String whyNot(Walker wk, WalkerTickCtx cx, WorldView world) {
        Move.Edge edge = wk.route.edgeAt(wk.route.step());
        if (edge == null) return "no edge";
        // While a fall-below-route recovery climbs, the edge's HIGH break cell is out of reach from down
        // there: a dig would hold a futile swing until its own timeout and starve the recovery.
        if (wk.pillar.recoverLatch > 0) return "a recovery pillar is climbing";
        if (!breakInReach) return "the break cell is out of reach";
        if (!hasPendingEdge(world, edge)) return "nothing left to break or place";
        return null;
    }

    @Override public boolean canContinue(Walker wk, WalkerTickCtx cx, WorldView world) {
        return whyNot(wk, cx, world) == null;
    }

    @Override public boolean tick(Walker wk, WalkerTickCtx cx, Body a, WorldView world) {
        LivingEntity p = cx.frame.p;
        BlockPos foot = cx.frame.foot;
        Move.Edge edge = wk.route.edgeAt(wk.route.step());
        wk.totalTicks = 0;                       // breaking/placing IS progress
        wk.stuckTicks = 0;                       // foot stays put while placing — don't trip the wiggle-jump (it'd leap off a 1-wide bridge)
        if (++wk.actionTicks > ControlConfig.breakTimeoutTicks) {
            // Lag or an unexpected obstruction — drop the path and let
            // the next tick repath from the current position.
            wk.blocks.breakHold(false);
            wk.error = "break/place stalled at " + wk.route.path().get(wk.route.step());
            wk.route.drop(wk.error);
            work = new Work(Kind.DROPPED, null, edge.move);
            return true;
        }
        for (BlockPos b : edge.toBreak) {
            if (world.isSolid(b)) {
                wk.equipment.selectTool(b);
                if (WalkerDig.bailOnBreathInfeasibleDig(wk, a, p, b)) {
                    work = new Work(Kind.DROPPED, b, edge.move);
                    return true;
                }
                world.signals().digging = true;
                // Claim, aim and drive all live inside avatarDig: digging `b` first and claiming it
                // afterwards drove a cell another phase already held.
                WalkerDig.avatarDig(wk, a, b, false);
                work = new Work(Kind.DIG, b, edge.move);
                return true;
            }
        }
        wk.blocks.breakHold(false);
        for (BlockPos b : edge.toPlace) {
            if (!world.isSolid(b)) {
                if (GameConfig.walkerDebug)
                    LOG.info(
                            "[walker] place-act foot={},{},{} y={} step={} placing={},{},{} onGround={} edge={}",
                            foot.getX(), foot.getY(), foot.getZ(), String.format(Locale.ROOT, "%.2f", p.getY()),
                            wk.route.step(), b.getX(), b.getY(), b.getZ(), p.onGround(), edge.move);
                a.aimAtBlock(b);
                wk.blocks.place(world::isSolid, b);
                work = new Work(Kind.PLACE, b, edge.move);
                return true;
            }
        }
        work = new Work(Kind.SETTLE, null, edge.move);
        return true;
    }

    @Override public void stop(String why) {
        work = null;
    }
}
