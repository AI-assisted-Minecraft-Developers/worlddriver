package net.magicterra.agent.bot.pathfinder.modifiers;

import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.pathfinder.CostModifier;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/**
 * Per-intent "prefer this Y band" cost: {@code weight} per block that {@code to}
 * sits OUTSIDE [yMin, yMax]. Inside the band adds nothing. Admissible (>= 0).
 * Backs "walk on the 2nd floor" / "hug the surface". {@code yMin <= yMax}.
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
