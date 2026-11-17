package net.magicterra.worlddriver.bot.control.controller;

import static net.magicterra.worlddriver.bot.control.route.PathSmoothing.hasPendingEdge;
import static net.magicterra.worlddriver.config.Mod.LOG;
import static net.magicterra.worlddriver.game.body.BotUtil.blockReachToCentre;
import static net.magicterra.worlddriver.game.body.BotUtil.eyeWithin;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.bot.control.TickCtx;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.config.GameConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

/**
 * Breaking and placing the blocks the current edge needs before the bot walks into its cell. It holds the look and the
 * hands only: how the body moves meanwhile is the regular walk's call
 * ({@link net.magicterra.worlddriver.bot.control.steer.StandToWork}), which reads {@link #work()} to know what is being
 * worked on. Functional aim snaps the same tick, independent of look smoothing. Runs each tick until the edge is clear.
 *
 * <p>A break cell beyond mining reach is walked to, never dug at from here. The client's MultiPlayerGameMode takes any
 * cell: it accumulates progress and breaks the block LOCALLY at full progress, the server refuses the break by distance
 * and sends the block back, and the bot has stood still the whole time. Live: the pointer sat on a downBreak column 8
 * cells away, the block was "broken" and restored twice, the next plan stepped down through the phantom air, and the
 * bot orbited the column.
 */
public final class RouteDig implements Controller {

    private static final Set<Channel> CHANNELS = EnumSet.of(Channel.LOOK, Channel.HANDS);

    private Work work;

    private boolean breakInReach;

    /** What the route-dig controller is doing this tick: breaking, placing, settling, or dropped. */
    public enum Kind {
        /** Breaking {@link Work#cell}. */
        DIG,
        /** Placing into {@link Work#cell}. */
        PLACE,
        /** Nothing left to click this tick; the bot settles a tick before walking on. */
        SETTLE,
        /** Breaking the step of a bank the body then swims prone into, {@link Work#cell}; see {@link #crawlAshore}. */
        STEP,
        /** Swimming prone into the opened step of a bank, {@link Work#cell}; see {@link #crawlAshore}. */
        CRAWL,
        /** The work stopped and the route was dropped (timed out, or a dig the breath cannot finish). */
        DROPPED
    }

    /** What was worked on this tick: {@code move} is the edge's move. */
    public record Work(Kind kind, BlockPos cell, String move) {}

    @Override
    public String name() {
        return "routeDig";
    }

    @Override
    public Set<Channel> channels() {
        return CHANNELS;
    }

    @Override
    public int priority() {
        return 7;
    }

    /** Bounded by the action timeout. */
    @Override
    public int maxTicks() {
        return Integer.MAX_VALUE;
    }

    /** This tick's work, null when not running. */
    public Work work() {
        return work;
    }

    /**
     * The edge whose blocks are worked on: the current one, or, swimming up to a bank, the one after it. A person
     * starts on the bank while still swimming at it and has it open as they arrive; waiting to reach the last water
     * node first left the bot treading water for the whole dig (dig-ashore benchmark course, the bank at 1015,150,1200:
     * broken 1.6 s after arriving, against the moment of arrival for a person). The swim goes on meanwhile, up to the
     * tread short of the face; that swim is the surface one, so a body still under water swims on to the node first.
     */
    private static Move.Edge workEdge(Navigator nav, WorldView world, LivingEntity p) {
        int i = workIndex(nav, world, p);
        return i < 0 ? null : nav.route.edgeAt(i);
    }

    /** The path index of {@link #workEdge}'s edge, or -1 without a route. */
    private static int workIndex(Navigator nav, WorldView world, LivingEntity p) {
        if (nav.route.path() == null) {
            return -1;
        }
        int step = nav.route.step();
        Move.Edge edge = nav.route.edgeAt(step);
        if (ControlConfig.navigatorTreadWhileDigging && p.isInWater() && !hasPendingEdge(world, edge)) {
            Move.Edge next = nav.route.edgeAt(step + 1);
            if (next != null
                    && next.toPlace.isEmpty()
                    && hasPendingEdge(world, next)
                    && (!p.isUnderWater() || crawlAshore(nav, world, step + 1))) {
                return step + 1;
            }
        }
        return step;
    }

    /**
     * A bank climbed out of the water where the cell over its step is solid too: only the step itself is dug from the
     * water, and the body swims prone into the one-high notch that opens, where it lies on dry ground and digs the rest
     * at five times the speed. Vanilla mines at a fifth of the speed for a body off the ground, so a floating swimmer
     * took 37 ticks a stone block, and dug every cell of the bank that way: three at the last bank of the dig-ashore
     * benchmark course (1049,150,1200), with the ceiling over the water. The prone swimmer is 0.6 tall, and vanilla
     * lifts a swimmer pressing a face out of the water when the space 0.6 above it is free, which the opened step is.
     */
    static boolean crawlAshore(Navigator nav, WorldView world, int index) {
        if (!ControlConfig.navigatorCrawlAshore
                || index < 1
                || index >= nav.route.path().size()) {
            return false;
        }
        Move.Edge edge = nav.route.edgeAt(index);
        if (edge == null || edge.move == null || !edge.move.startsWith("swimAshore")) {
            return false;
        }
        BlockPos from = nav.route.path().get(index - 1);
        BlockPos to = edge.to;
        // Only under a ceiling over the water: there the body cannot stand up in its own column once out of the water,
        // so it stays prone whichever tick the lift tops out on. Without one, a lift that left the water a few
        // hundredths short of the step's floor stood the body up against the face, and it fell back to try again
        // (the second bank, 1031,150,1200, one run in four).
        if (!world.isWater(from)
                || !world.isSolid(from.above(2))
                || !world.isSolid(to.below())
                || !world.isSolid(to.above())
                || world.isSolid(from.above())
                || !world.isPassable(from.above()) && !world.isWater(from.above())) {
            return false;
        }
        for (BlockPos b : edge.toBreak) {
            if (!b.equals(to) && b.getY() <= to.getY()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void sense(Navigator nav, TickCtx cx, WorldView world) {
        work = null;
        Move.Edge edge = workEdge(nav, world, cx.frame.player);
        // Only the first solid cell on the edge becomes the work item.
        BlockPos b = edge == null ? null : firstSolid(edge, world);
        if (b == null) {
            breakInReach = true;
            return;
        }
        LivingEntity p = cx.frame.player;
        breakInReach = eyeWithin(p, b, blockReachToCentre(p));
        if (!breakInReach && GameConfig.navigatorDebug && p.tickCount % 20 == 0) {
            LOG.info(
                    "[navigator] break cell {},{},{} out of reach ({} blocks from the eye) → walk to it first",
                    b.getX(),
                    b.getY(),
                    b.getZ(),
                    String.format(
                            Locale.ROOT, "%.1f", Math.sqrt(p.getEyePosition().distanceToSqr(b.getCenter()))));
        }
    }

    private static BlockPos firstSolid(Move.Edge edge, WorldView world) {
        for (BlockPos b : edge.toBreak) {
            if (world.isSolid(b)) {
                return b;
            }
        }
        return null;
    }

    /**
     * The edge's cell to dig this tick: the first solid one the body can break from here, else the first solid one.
     * Under {@link GameConfig#digOnlyUnderCrosshair} a cell hidden behind a later cell of the same edge can never be
     * under the crosshair, and digging the edge in list order would hold that refused swing until the break timeout.
     */
    private static BlockPos digCell(Navigator nav, WorldView world, Move.Edge edge) {
        BlockPos first = null;
        for (BlockPos b : edge.toBreak) {
            if (!world.isSolid(b)) {
                continue;
            }
            if (!GameConfig.digOnlyUnderCrosshair || nav.blocks.canBreak(b)) {
                return b;
            }
            if (first == null) {
                first = b;
            }
        }
        return first;
    }

    @Override
    public String whyNot(Navigator nav, TickCtx cx, WorldView world) {
        Move.Edge edge = workEdge(nav, world, cx.frame.player);
        if (edge == null) {
            return "no edge";
        }
        // While a fall-below-route recovery climbs, the edge's HIGH break cell is out of reach from down
        // there: a dig would hold a futile swing until its own timeout and starve the recovery.
        if (nav.pillar.recoverLatch > 0) {
            return "a recovery pillar is climbing";
        }
        if (!breakInReach) {
            return "the break cell is out of reach";
        }
        if (!hasPendingEdge(world, edge)) {
            return "nothing left to break or place";
        }
        return null;
    }

    @Override
    public boolean canContinue(Navigator nav, TickCtx cx, WorldView world) {
        return whyNot(nav, cx, world) == null;
    }

    @Override
    public boolean tick(Navigator nav, TickCtx cx, Body a, WorldView world) {
        final LivingEntity p = cx.frame.player;
        final BlockPos foot = cx.frame.foot;
        final int index = workIndex(nav, world, p);
        final Move.Edge edge = nav.route.edgeAt(index);
        nav.totalTicks = 0; // breaking/placing IS progress
        nav.stuckTicks = 0; // foot stays put while placing — don't trip the wiggle-jump (it'd leap off a 1-wide bridge)
        if (ControlConfig.navigatorTreadWhileDigging) {
            // A bank dug from the water is dug from short of it, so the step is still to make when the last block
            // goes, and the dig's ticks would read as a wedge the tick it ends: the bot re-planned round the open
            // bank and dug a second way out (dig-ashore benchmark course, 1049,150,1200). The dig is the progress;
            // breakTimeoutTicks still bounds a dig that gets nowhere.
            nav.progress.stepProg.noStepProgressTicks = 0;
        }
        if (++nav.actionTicks > ControlConfig.breakTimeoutTicks) {
            // Lag or an unexpected obstruction — drop the path and let
            // the next tick repath from the current position.
            nav.blocks.breakHold(false);
            nav.error = "break/place stalled at " + nav.route.path().get(nav.route.step());
            nav.route.drop(nav.error);
            work = new Work(Kind.DROPPED, null, edge.move);
            return true;
        }
        boolean crawl = crawlAshore(nav, world, index);
        if (crawl && p.isInWater() && !world.isSolid(edge.to)) {
            nav.blocks.breakHold(false);
            work = new Work(Kind.CRAWL, edge.to, edge.move);
            return true;
        }
        // Not on whether the body is in the water this tick: the top of a bob leaves it, and switching cells there
        // threw the step's progress away.
        boolean step = crawl && world.isSolid(edge.to);
        BlockPos dig = step ? edge.to : digCell(nav, world, edge);
        if (dig != null) {
            nav.equipment.selectTool(dig);
            if (Digging.bailOnBreathInfeasibleDig(nav, a, p, dig)) {
                work = new Work(Kind.DROPPED, dig, edge.move);
                return true;
            }
            world.signals().digging = true;
            // Claim, aim and drive all live inside avatarDig: digging `dig` first and claiming it
            // afterwards drove a cell another phase already held.
            Digging.avatarDig(nav, a, dig, false);
            work = new Work(step ? Kind.STEP : Kind.DIG, dig, edge.move);
            return true;
        }
        nav.blocks.breakHold(false);
        for (BlockPos b : edge.toPlace) {
            if (!world.isSolid(b)) {
                if (GameConfig.navigatorDebug) {
                    LOG.info(
                            "[navigator] place-act foot={},{},{} y={} step={} placing={},{},{} onGround={} edge={}",
                            foot.getX(),
                            foot.getY(),
                            foot.getZ(),
                            String.format(Locale.ROOT, "%.2f", p.getY()),
                            nav.route.step(),
                            b.getX(),
                            b.getY(),
                            b.getZ(),
                            p.onGround(),
                            edge.move);
                }
                a.aimAtBlock(b);
                nav.blocks.place(world::isSolid, b);
                work = new Work(Kind.PLACE, b, edge.move);
                return true;
            }
        }
        work = new Work(Kind.SETTLE, null, edge.move);
        return true;
    }

    @Override
    public void stop(String why) {
        work = null;
    }
}
