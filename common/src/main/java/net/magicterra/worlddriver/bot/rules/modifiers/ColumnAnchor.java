package net.magicterra.worlddriver.bot.rules.modifiers;

import net.magicterra.worlddriver.bot.rules.CostModifier;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.minecraft.core.BlockPos;

/**
 * The soft cylinder: {@code weight} per block that {@code to} is beyond {@code softRadius} from the {@code (cx, cz)}
 * column line, in XZ only. The soft twin of {@code ColumnRadius}, the way {@link LeashAnchor} is the soft twin of
 * {@code LeashHardRadius}. {@code route.leash} with {@code axis: "xz"} and {@code hard: false} lands here; the point of
 * the XZ measure is that a climb never counts as leaving the anchor.
 */
public record ColumnAnchor(double cx, double cz, double softRadius, double weight) implements CostModifier {
    @Override
    public double extraCost(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        if (weight <= 0) {
            return 0;
        }
        double dx = to.getX() + 0.5 - cx;
        double dz = to.getZ() + 0.5 - cz;
        double dist = Math.sqrt(dx * dx + dz * dz);
        return dist > softRadius ? weight * (dist - softRadius) : 0;
    }
}
