package net.magicterra.worlddriver.bot.sim;

import net.magicterra.worlddriver.bot.movement.Walker;

/**
 * Something {@link ServerAvatarManager} advances once per server tick until it reports finished: a
 * {@link ServerWorldDriver} over a joined player, or a driver over an entity that is not a player.
 */
public interface BodyDriver {

    /** One server tick of driving. */
    Walker.Step tick();

    /** Whether the driver is done; the manager drops it then. */
    boolean finished();
}
