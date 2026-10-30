package net.magicterra.worlddriver.api.game;

import java.util.Optional;
import java.util.Set;
import net.minecraft.world.Container;

/**
 * How a bot moves one entity of a kind the driver's own mob body cannot steer: a modded creature that moves by code of
 * its own. Registered per entity type with {@link EntityDrivers}; adopting a mob of that type (an order to
 * {@code entity:<uuid>}, {@code mc.bot.roster} adopt) builds one with the registered factory instead of the driver's
 * own.
 *
 * <p>The driver keeps the route: it searches, picks each step and turns the body, and every server tick hands this the
 * input a player would press for that step. The implementation turns it into motion however its entity moves. Taking
 * the entity's own AI off while it is driven is the implementation's job too, since only it knows what that AI is.
 *
 * <p>Every method is called on the server thread.
 */
public interface EntityDriver {

    /**
     * One tick of input, as a player would press it. {@code left} and {@code forward} are in [-1, 1], relative to
     * {@code yaw}, the heading the driver has turned the entity to (its {@code yRot}); {@code jump} and {@code sneak}
     * also mean up and down to a swimmer or a flyer.
     */
    record Input(float yaw, float left, float forward, boolean jump, boolean sneak, boolean sprint) {}

    /** A way of moving; the planner searches the moves of each one the entity has. */
    enum Movement {
        WALK,
        SWIM,
        FLY
    }

    /** Apply this tick's input. Called once per server tick while the entity is driven, idle ones included. */
    void input(Input input);

    /** Look at a point: turn to {@code yaw} and {@code pitch}, in degrees as vanilla measures them. */
    void face(float yaw, float pitch);

    /** The ways it moves. Empty: read from the entity as for any mob. */
    default Set<Movement> movements() {
        return Set.of();
    }

    /** What it carries, when processes that count items should see it. */
    default Optional<Container> bag() {
        return Optional.empty();
    }

    /** Its own way of reaching a goto's target, used instead of the driver's route when present. */
    default Optional<Mover> mover() {
        return Optional.empty();
    }

    /**
     * Whether held input has failed to move the entity since the last time this was asked. True ends the task it was on
     * as unresponsive. Asking clears it.
     */
    default boolean takeUnresponsive() {
        return false;
    }

    /** The driver lets go: hand the entity back to its own AI. */
    default void release() {
        // Nothing to hand back by default: the entity already runs its own AI.
    }
}
