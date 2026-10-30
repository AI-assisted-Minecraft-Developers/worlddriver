package net.magicterra.worlddriver.bot.rules.constraints;

import net.magicterra.worlddriver.bot.rules.Constraint;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.minecraft.core.BlockPos;

/** Hard ceiling: prune any move whose destination is above {@code maxY}. */
// The name is public API, used by the rule catalogue, so it stays.
@SuppressWarnings("checkstyle:abbreviationaswordinname")
public record YCeil(int maxY) implements Constraint {
    @Override
    public boolean allows(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        return to.getY() <= maxY;
    }
}
