package net.magicterra.worlddriver.game.body;

import net.minecraft.world.Container;

/**
 * What the controlled entity carries besides what it holds.
 *
 * <p>One of the optional capabilities a {@link Body} may have. A player's bag is its inventory; an allay's or a
 * piglin's is the container vanilla gives it, which is why this answers vanilla's {@link Container} rather than the
 * player-only {@code Inventory} (and why it is not called {@code Inventory}: that name is vanilla's).
 */
public interface Bag {

    /** Everything carried, as vanilla stores it. */
    Container contents();
}
