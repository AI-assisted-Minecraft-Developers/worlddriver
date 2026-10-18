package net.magicterra.worlddriver.bot.planning;

import net.magicterra.worlddriver.bot.rules.Goal;
import net.minecraft.core.BlockPos;
import net.magicterra.worlddriver.bot.rules.SearchScope;
import net.magicterra.worlddriver.bot.rules.SearchProfile;

/**
 * Where a {@link PathFinder} gets its {@link SearchScope}: called once by every
 * {@link PathFinder.Search} constructor, because a snapshot belongs to one search. The Navigator
 * installs one that reads the bot's level ({@link SearchScope#gather}); the debug tools and the
 * unit tests install none and get {@link SearchScope#EMPTY}, under which every
 * {@link SearchAware} component is inert.
 */
@FunctionalInterface
public interface ScopeSource {
    SearchScope gather(BlockPos start, Goal goal, SearchProfile profile);
}
