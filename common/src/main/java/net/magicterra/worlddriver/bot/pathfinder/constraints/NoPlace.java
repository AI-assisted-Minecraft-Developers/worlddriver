package net.magicterra.worlddriver.bot.pathfinder.constraints;

import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.pathfinder.Constraint;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/** Hard "never place a block": prune any edge that plans a placement (bridge, pillar, parkour
 *  place). Keyed off the edge's own place list, the same shape as {@link NoBreak}, so a move
 *  with a non-placing variant keeps that variant. The global {@code allowPlace} stays the master
 *  switch; this only ever tightens. */
public record NoPlace() implements Constraint {
    @Override
    public boolean allows(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        return edge == null || edge.toPlace.isEmpty();
    }
}
