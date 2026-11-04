package net.magicterra.worlddriver.bot.rules.constraints;

import net.magicterra.worlddriver.bot.rules.Constraint;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.minecraft.core.BlockPos;

/** Hard ceiling: prune any move whose destination is above {@code maxY}. */
// Domain vocabulary: Y is the vertical axis; the name is part of the public rule catalogue and the log text.
@SuppressWarnings("checkstyle:abbreviationaswordinname")
public record YCeil(int maxY) implements Constraint {
    @Override
    public boolean allows(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        return to.getY() <= maxY;
    }
}
