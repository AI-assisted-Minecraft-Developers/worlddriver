package net.magicterra.worlddriver.api.game;

import net.minecraft.core.BlockPos;

/**
 * An entity's own way of getting somewhere, for a creature whose movement the driver's route cannot follow cell by cell
 * (it teleports, burrows, or flies a path of its own). A goto to a cell hands the whole trip to it: the driver plans
 * nothing and only asks each tick how it is going. A goto whose target has no single cell (a Y level, a direction)
 * still uses the driver's own route.
 *
 * <p>Every method is called on the server thread.
 */
public interface Mover {

    /** How the trip stands after a tick. */
    enum Progress {
        MOVING,
        ARRIVED,
        FAILED
    }

    /**
     * One tick toward {@code target}; arriving within {@code within} blocks counts. Called every server tick of the
     * trip with the same target until it answers {@link Progress#ARRIVED} or {@link Progress#FAILED}, or the trip is
     * cancelled.
     */
    Progress drive(BlockPos target, double within);

    /** The trip ended before arriving, cancelled or superseded: stop moving. */
    default void stop() {}

    /** Why the last {@link Progress#FAILED} failed, for the action's message. */
    default String failure() {
        return "the mover could not reach the target";
    }
}
