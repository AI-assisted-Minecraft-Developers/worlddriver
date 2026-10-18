package net.magicterra.worlddriver.bot.rules.modifiers;

import net.magicterra.worlddriver.bot.rules.CostModifier;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.Drops;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.minecraft.core.BlockPos;

/**
 * A flat {@code penalty} on every cell beside a drop deeper than {@code minDrop} (or a hazard at any depth), so a
 * route keeps off lips it could survive but would rather not fall from. {@code route.ledge}.
 */
public record LedgeTax(int minDrop, double penalty) implements CostModifier {
    @Override
    public double extraCost(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        return penalty > 0 && Drops.adjacentExceeds(world, to, minDrop) ? penalty : 0;
    }
}
