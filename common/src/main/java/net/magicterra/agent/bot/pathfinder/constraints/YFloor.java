package net.magicterra.agent.bot.pathfinder.constraints;

import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.pathfinder.Constraint;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/** Hard floor: prune any move whose destination is below {@code minY}. Backs
 *  "don't go below Y=N" (e.g. stay out of the caves). */
public record YFloor(int minY) implements Constraint {
    @Override
    public boolean allows(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        return to.getY() >= minY;
    }
}
