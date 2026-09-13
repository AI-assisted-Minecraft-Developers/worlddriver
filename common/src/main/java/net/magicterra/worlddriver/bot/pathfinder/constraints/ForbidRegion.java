package net.magicterra.worlddriver.bot.pathfinder.constraints;

import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.pathfinder.Constraint;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.Region;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/**
 * Hard "never enter this region": prune any move whose destination cell is inside the box or
 * sphere. The hard twin of the soft {@code AvoidRegion}.
 *
 * <p><b>Rejoin</b>, the rule every hard region-like constraint shares with
 * {@link LeashHardRadius}: a search that STARTS inside the region (the body was knocked into
 * it) must not be fully pruned, or the start has no successors and the body is stuck for good.
 * From inside, only edges whose destination is LESS deep in the region than the origin are
 * allowed — the body walks out by the nearest face and normal pruning resumes. The rule is
 * per-edge and stateless; in a concave pocket where every edge is deeper it can still dead-end,
 * an accepted residual that surfaces as a search failure naming this constraint.
 */
public record ForbidRegion(Region region) implements Constraint {
    @Override
    public boolean allows(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        double dto = region.depth(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
        if (dto <= 0) return true;
        return from != null && dto < region.depth(from.getX() + 0.5, from.getY(), from.getZ() + 0.5);
    }
}
