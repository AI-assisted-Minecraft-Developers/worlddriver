package net.magicterra.worlddriver.bot.control;

import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.bot.rules.SearchProfile;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.game.body.Body;

/**
 * Moves one body toward a goal, a tick at a time.
 *
 * <p>The caller says where ({@link #start}), may move the target ({@link #retarget}) or throw the
 * route away ({@link #replan}), and ticks it until the status is no longer {@link Status#MOVING}.
 * What a finished walk did is in {@link #result()}.
 */
public interface Locomotor {

    /** What one tick left the walk at. */
    enum Status {
        /** Still on the way. */
        MOVING,
        /** At the goal: its test passes at the feet, it is an open goal, or the nearest standable
         *  cell to an unstandable target was reached. */
        REACHED,
        /** Stopped short without an error: the route ran out short of the goal, the loaded-chunk
         *  frontier never opened, or the bot went round in circles. {@link WalkResult#reason} says how short. */
        GAVE_UP,
        /** Could not go on; {@link WalkResult#reason} says why. */
        FAILED;

        /** The walk is over, whichever way. */
        public boolean done() { return this != MOVING; }

        /** The walk is over without failing: reached, or gave up short. */
        public boolean ended() { return this == REACHED || this == GAVE_UP; }
    }

    /** A new walk to {@code goal}. Everything about the previous walk is dropped. */
    void start(Goal goal);

    /**
     * Move the target of the walk in progress, keeping what the walk has learned about how it is
     * going: a chase re-aims every time its quarry moves, and treating each re-aim as a new walk
     * would reset the guard that stops a walk searching forever for something it cannot reach.
     */
    void retarget(Goal goal);

    /** Drop the route and search again from where the body is now, toward the same goal. */
    void replan();

    /** {@link #replan()}, searching with {@code profile} from now on. */
    void replan(SearchProfile profile);

    /** Stop the walk before it ends; {@code reason} goes to the route trace. */
    void cancel(String reason);

    /** Drive {@code body} one tick. */
    Status tick(Body body, WorldView world);

    /** How the last walk ended; {@link WalkResult#NONE} while one is under way or none has run. */
    WalkResult result();

    /**
     * Nodes in the current route, <b>including the one the body started on</b>, or 0 without one.
     * A route of N nodes is N−1 moves; nor is it a distance, since one node can cover several blocks.
     */
    int pathLen();

    /** Index of the node being walked to. */
    int pathStep();
}
