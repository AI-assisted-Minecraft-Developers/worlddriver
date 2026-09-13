package net.magicterra.worlddriver.bot.pathfinder.modifiers;

import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.pathfinder.CostModifier;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/**
 * {@code route.break: "prefer"}: a flat {@code penalty} on every edge that does NOT break a
 * block, so tunnelling through is relatively cheaper. Default 10, one extra cell of walking.
 *
 * <p>Deliberately not a discount on breaking edges. Break cost is priced by
 * {@link WorldView#breakCost} times the global {@code pathfinderBreakCostMultiplier}, a layer a
 * {@code SearchProfile} cannot reach; and a multiplier under 1 would let the Euclidean heuristic
 * overestimate, ending A*'s optimality. Adding only keeps admissibility.
 */
public record PreferBreak(double penalty) implements CostModifier {
    @Override
    public double extraCost(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        if (penalty <= 0) return 0;
        return (edge == null || edge.toBreak.isEmpty()) ? penalty : 0;
    }
}
