package net.magicterra.worlddriver.bot.pathfinder.constraints;

import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.pathfinder.Constraint;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/** Hard "never break a block": prune any edge that plans a dig. Keyed off the
 *  edge's OWN break list, so a conditional digger (PillarUp under an open sky)
 *  stays allowed while its digging variant is pruned — more precise than the
 *  global {@code allowBreak} kill-switch, and per-intent. */
public record NoBreak() implements Constraint {
    @Override
    public boolean allows(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        return edge == null || edge.toBreak.isEmpty();
    }
}
