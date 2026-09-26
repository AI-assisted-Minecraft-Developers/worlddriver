package net.magicterra.worlddriver.bot.pathfinder.constraints;

import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.pathfinder.Constraint;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/** Hard leash: prune any move whose destination is farther than {@code radius}
 *  from the anchor. The hard twin of A4b's soft {@code LeashAnchor} — the route
 *  MAY NOT leave the radius at all (vs paying a cost to). Backs "when leading the
 *  way, do not stray too far" as a firm bound.
 *
 *  <p><b>Rejoin semantics</b>: a bot OUTSIDE the tether (the dynamic anchor
 *  jumped away, or the search starts outside the sphere) must not be fully
 *  pruned — from out there EVERY neighbor is also outside, so a plain radius
 *  check leaves zero admissible edges and bricks the bot permanently. Outside
 *  the sphere, only edges that STRICTLY APPROACH the anchor are allowed: the
 *  bot beelines back into the tether, then normal in-radius leash behavior
 *  resumes. Rejoin assumes a roughly clear line of approach: in a concave
 *  pocket where every detour edge temporarily INCREASES anchor distance, the
 *  approach-only rule still dead-ends (best-effort stall, no loop) — an
 *  accepted residual. Prune-only either way — costs are never modified. */
public record LeashHardRadius(double ax, double ay, double az, double radius) implements Constraint {
    @Override
    public boolean allows(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        if (radius <= 0) return true;   // unset → no leash
        double dto = distSq(to);
        if (dto <= radius * radius) return true;
        // Outside the tether (anchor jumped away or bot started outside): permit only
        // strict approach so the bot can rejoin instead of being fully pruned.
        return from != null && dto < distSq(from);
    }

    /** Squared distance from the cell CENTRE (x+0.5, y, z+0.5) to the anchor —
     *  the same convention the in-radius check has always used. */
    private double distSq(BlockPos p) {
        double dx = (p.getX() + 0.5) - ax, dy = p.getY() - ay, dz = (p.getZ() + 0.5) - az;
        return dx * dx + dy * dy + dz * dz;
    }
}
