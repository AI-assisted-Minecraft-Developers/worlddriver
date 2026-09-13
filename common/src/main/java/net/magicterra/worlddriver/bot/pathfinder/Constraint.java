package net.magicterra.worlddriver.bot.pathfinder;

import net.magicterra.worlddriver.bot.Goal;
import net.minecraft.core.BlockPos;

/** A hard, per-intent edge predicate: return {@code false} to PRUNE the move to
 *  {@code to} (the successor is never generated). Unlike {@link CostModifier}
 *  (soft, additive cost) a Constraint removes the edge entirely. Checked in the
 *  A* neighbor loop. Must be a pure function of its args (no side effects). */
@FunctionalInterface
public interface Constraint {
    boolean allows(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world);

    /** How this constraint is named where a search accounts for its prunes and a
     *  {@code route.blocked} event names the culprit. The class's simple name, the same
     *  convention {@link PathFinder} uses to name a {@link CostModifier} in its tax log. */
    default String name() { return getClass().getSimpleName(); }
}
