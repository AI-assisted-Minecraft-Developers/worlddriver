package net.magicterra.worlddriver.bot.control.controller;

import java.util.Set;

import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.bot.control.Walker;
import net.magicterra.worlddriver.bot.control.WalkerTickCtx;

/**
 * Something that takes over some of a walking tick's {@link Channel}s for a while: it starts when its
 * condition holds, drives the channels it claims every tick it runs, and stops when it can no longer go on,
 * has run its longest, lets go, or a higher-priority controller needs one of its channels. {@link Arbiter}
 * runs the lifecycle; a controller only answers the questions.
 *
 * <p>A controller's state is its own, and {@link #stop} is where its run state is cleared. Run lengths are
 * fixed, so a replay or a scene repeats tick for tick.
 */
interface Controller {

    /** The name the arbiter's snapshot shows. */
    String name();

    /** The channels it drives while running. */
    Set<Channel> channels();

    /** Lower runs first; a controller may take a channel from a running one with a higher number. */
    int priority();

    /**
     * Read the world for this tick, whether running or not. Called for every controller before any of
     * them is asked anything, so the counters a controller keeps about its situation (how long it has
     * been stalled, whether it was in water lately) advance on every tick, not only on ticks it runs.
     */
    default void sense(Walker wk, WalkerTickCtx cx, WorldView world) {}

    /** Null when it may start this tick, otherwise the condition that does not hold, for the snapshot. */
    String whyNot(Walker wk, WalkerTickCtx cx, WorldView world);

    /** Whether a running controller goes on this tick. */
    boolean canContinue(Walker wk, WalkerTickCtx cx, WorldView world);

    /** The most ticks one run may last; the arbiter stops it after that many. */
    int maxTicks();

    /**
     * Drive the claimed channels for one tick, through {@code a}. False when it found, part-way through
     * the tick, that it has nothing to do after all: it is stopped and its channels go to the next
     * controller down, this same tick, as if it had not started.
     */
    boolean tick(Walker wk, WalkerTickCtx cx, Body a, WorldView world);

    /** Stop and clear the run's state. */
    void stop(String why);

    /**
     * Forget what it remembers between runs about the walk being replaced: a gave-up latch, a cooldown, a
     * counter {@link #sense} keeps. Called on every controller, running or not, when the walk gets a new goal
     * or drops its plan; a running one is stopped first only for a new goal.
     */
    default void reset(Walker.Scope scope) {}
}
