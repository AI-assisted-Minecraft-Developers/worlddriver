package net.magicterra.agent.bot.pathfinder.modifiers;

import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.pathfinder.CostModifier;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/**
 * Per-intent "route around this sphere" cost: a linear ramp from {@code penalty}
 * at the centre to 0 at {@code radius}, matching the global {@code avoidPoints}
 * ramp in ClientWorldView so intent-scoped and global avoid behave identically.
 * Admissible (>= 0). Cells outside the radius add nothing.
 */
public record AvoidRegion(double cx, double cy, double cz, double radius, double penalty)
        implements CostModifier {
    @Override
    public double extraCost(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        if (radius <= 0 || penalty <= 0) return 0;
        double dx = (to.getX() + 0.5) - cx, dy = to.getY() - cy, dz = (to.getZ() + 0.5) - cz;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return dist < radius ? penalty * (radius - dist) / radius : 0;
    }
}
