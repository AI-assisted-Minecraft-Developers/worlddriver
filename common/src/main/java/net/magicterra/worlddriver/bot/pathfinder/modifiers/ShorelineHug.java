package net.magicterra.worlddriver.bot.pathfinder.modifiers;

import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.pathfinder.CostModifier;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/** 沿河岸走: tax every node that has NO water among its 4 cardinal neighbors
 *  (checked at the foot level and one below — a bank cell's adjacent water
 *  surface usually sits one below the bank foot). Routes inside the shoreline
 *  band pay nothing; anything inland pays {@code weight} per node, so with
 *  weight well above the per-node walk cost (10) A* hugs the waterline.
 *  Pair with {@code forbidWater} to stay dry. Penalty-only — admissible. */
public record ShorelineHug(double weight) implements CostModifier {
    @Override
    public double extraCost(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        if (weight <= 0) return 0;   // admissibility guard, same as sibling modifiers
        if (nearWater(to, world)) return 0;
        return weight;
    }

    private static boolean nearWater(BlockPos p, WorldView w) {
        for (int i = 0; i < 4; i++) {
            int dx = (i == 0) ? 1 : (i == 1) ? -1 : 0;
            int dz = (i == 2) ? 1 : (i == 3) ? -1 : 0;
            BlockPos side = p.offset(dx, 0, dz);
            if (w.isWater(side) || w.isWater(side.below())) return true;
        }
        return false;
    }
}
