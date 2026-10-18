package net.magicterra.worlddriver.bot.behavior;

import net.magicterra.worlddriver.bot.control.Navigator;

/** A process that walks with a {@link Navigator} of its own, so a reading of the walk can find it. */
public interface Navigating {
    Navigator navigator();
}
