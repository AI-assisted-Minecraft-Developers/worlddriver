package net.magicterra.worlddriver.bot.pathfinder.constraints;

import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.pathfinder.Constraint;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/** Hard "never enter water": prune any move whose destination foot cell is water.
 *  canStandAt treats a water cell as a floor, so plain Walk edges DO route through
 *  water — a move-type gate can't express this; only an edge prune can. */
public record NoWater() implements Constraint {
    @Override
    public boolean allows(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        return !world.isWater(to);
    }
}
