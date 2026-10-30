package net.magicterra.worlddriver.bot.rules.constraints;

import net.magicterra.worlddriver.bot.rules.Constraint;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.minecraft.core.BlockPos;

/**
 * Hard floor: prune any move whose destination is below {@code minY}. Backs "don't go below Y=N" (e.g. stay out of the
 * caves).
 */
// The name is public API, used by the rule catalogue, so it stays.
@SuppressWarnings("checkstyle:abbreviationaswordinname")
public record YFloor(int minY) implements Constraint {
    @Override
    public boolean allows(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        return to.getY() >= minY;
    }
}
