package net.magicterra.worlddriver.bot.pathfinder.modifiers;

import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.pathfinder.CostModifier;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.Region;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/**
 * Per-intent "route around this region" cost. A sphere charges a linear ramp from
 * {@code penalty} at the centre to 0 at the radius, matching the global {@code avoidPoints} ramp
 * in ClientWorldView so intent-scoped and global avoid behave identically; a box charges the
 * full {@code penalty} anywhere inside, having no centre to ramp toward. Admissible (>= 0).
 * Cells outside add nothing. {@code route.regions[].mode: "avoid"}.
 */
public record AvoidRegion(Region region, double penalty) implements CostModifier {

    /** The original sphere form, kept for the scenes that build one by hand. */
    public AvoidRegion(double cx, double cy, double cz, double radius, double penalty) {
        this(new Region.Sphere(cx, cy, cz, radius), penalty);
    }

    @Override
    public double extraCost(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        if (penalty <= 0) return 0;
        return penalty * region.weight(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
    }
}
