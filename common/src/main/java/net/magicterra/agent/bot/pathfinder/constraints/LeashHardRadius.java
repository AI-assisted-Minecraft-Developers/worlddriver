package net.magicterra.agent.bot.pathfinder.constraints;

import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.pathfinder.Constraint;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/** Hard leash: prune any move whose destination is farther than {@code radius}
 *  from the anchor. The hard twin of A4b's soft {@code LeashAnchor} — the route
 *  MAY NOT leave the radius at all (vs paying a cost to). Backs "带路别离太远"
 *  as a firm bound. */
public record LeashHardRadius(double ax, double ay, double az, double radius) implements Constraint {
    @Override
    public boolean allows(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        if (radius <= 0) return true;   // unset → no leash
        double dx = (to.getX() + 0.5) - ax, dy = to.getY() - ay, dz = (to.getZ() + 0.5) - az;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= radius;
    }
}
