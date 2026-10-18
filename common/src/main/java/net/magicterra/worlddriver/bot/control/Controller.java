package net.magicterra.worlddriver.bot.control;

import java.util.Set;

import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.game.body.Body;

/**
 * Something that takes over some of a walking tick's {@link Channel}s for a while: it starts when its
 * condition holds, drives the channels it claims every tick it runs, and stops when it can no longer go on,
 * has run its longest, or a higher-priority controller needs one of its channels. {@link Arbiter} runs the
 * lifecycle; a controller only answers the questions.
 *
 * <p>A controller's state is its own, and {@link #stop} is where it is cleared: a stopped controller holds
 * nothing that could leak into the next run or the next goal. Run lengths are fixed, so a replay or a scene
 * repeats tick for tick.
 */
interface Controller {

    /** The name the arbiter's snapshot shows. */
    String name();

    /** The channels it drives while running. */
    Set<Channel> channels();

    /** Lower runs first; a controller may take a channel from a running one with a higher number. */
    int priority();

    /** Null when it may start this tick, otherwise the condition that does not hold, for the snapshot. */
    String whyNot(Walker wk, WalkerTickCtx cx, WorldView world);

    /** Whether a running controller goes on this tick. */
    boolean canContinue(Walker wk, WalkerTickCtx cx, WorldView world);

    /** The most ticks one run may last; the arbiter stops it after that many. */
    int maxTicks();

    /** Drive the claimed channels for one tick, through {@code a}. */
    void tick(Walker wk, WalkerTickCtx cx, Body a, WorldView world);

    /** Stop and clear the run's state. */
    void stop(String why);
}
