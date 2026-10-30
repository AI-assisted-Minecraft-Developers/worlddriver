package net.magicterra.worlddriver.bot.rules.modifiers;

import net.magicterra.worlddriver.bot.rules.CostModifier;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.minecraft.core.BlockPos;

/**
 * Per-intent "prefer this Y band" cost: {@code weight} per block that {@code to} sits OUTSIDE [yMin, yMax]. Inside the
 * band adds nothing. Admissible (>= 0). Backs "walk on the 2nd floor" / "hug the surface". {@code yMin <= yMax}.
 */
public record PreferYBand(int yMin, int yMax, double weight) implements CostModifier {
    @Override
    public double extraCost(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        if (weight <= 0) return 0;
        int y = to.getY();
        int outside = y < yMin ? (yMin - y) : (y > yMax ? (y - yMax) : 0);
        return weight * outside;
    }
}
