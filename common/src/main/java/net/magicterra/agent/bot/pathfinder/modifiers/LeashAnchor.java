package net.magicterra.agent.bot.pathfinder.modifiers;

import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.pathfinder.CostModifier;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/**
 * Per-intent "stay near this anchor" cost: {@code weight} per block that {@code to}
 * is BEYOND {@code softRadius} from the anchor. Inside the radius adds nothing.
 * A soft leash — the planner may still leave the radius (e.g. to route around an
 * obstacle) but pays for it, so it hugs the anchor. Admissible (>= 0). Backs
 * "lead player B to the village but don't stray far". (A4b uses a STATIC anchor;
 * a live entity anchor re-evaluated each re-solve is A3.)
 */
public record LeashAnchor(double ax, double ay, double az, double softRadius, double weight)
        implements CostModifier {
    @Override
    public double extraCost(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        if (weight <= 0) return 0;
        double dx = (to.getX() + 0.5) - ax, dy = to.getY() - ay, dz = (to.getZ() + 0.5) - az;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return dist > softRadius ? weight * (dist - softRadius) : 0;
    }
}
