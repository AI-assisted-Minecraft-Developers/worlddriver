package net.magicterra.worlddriver.bot.pathfinder;

import net.magicterra.worlddriver.bot.Goal;
import net.minecraft.core.BlockPos;

/**
 * Where a {@link PathFinder} gets its {@link SearchScope}: called once by every
 * {@link PathFinder.Search} constructor, because a snapshot belongs to one search. The Walker
 * installs one that reads the body's level ({@link SearchScope#gather}); the debug tools and the
 * unit tests install none and get {@link SearchScope#EMPTY}, under which every
 * {@link SearchAware} component is inert.
 */
@FunctionalInterface
public interface ScopeSource {
    SearchScope gather(BlockPos start, Goal goal, SearchProfile profile);
}
