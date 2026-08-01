package net.magicterra.worlddriver.bot.pathfinder.constraints;

import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.pathfinder.Constraint;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/** Hard XZ-cylinder: prune any move whose destination leaves {@code radius} of the
 *  {@code (cx, cz)} vertical line, but leave Y completely unconstrained. The fix for the
 *  ascent-drift gap — a {@link Goal.YLevel} up/down goal is XZ-blind ({@code h = 10*|dy|}),
 *  so A* gains Y by whatever is cheapest and wanders sideways to cheap pre-existing air/ramps
 *  instead of pillaring/digging the start column. Binding the search to the start column
 *  makes the fresh vertical shaft the only route.
 *
 *  <p><b>Cylinder, not sphere</b>: distance is measured in XZ only. This is the crucial
 *  difference from the spherical {@link LeashHardRadius}, whose {@code dy²} term makes a
 *  climb "leave" the anchor (increasing 3D distance) so its approach-only rejoin rule would
 *  prune the very ascent this constraint exists to permit. Here climbing never changes the
 *  XZ distance, so an in-column pillar/dig is never pruned.
 *
 *  <p><b>Rejoin semantics</b> (mirrors {@link LeashHardRadius}): a search that STARTS outside
 *  the cylinder must not be fully pruned — from out there every neighbour is also outside, so
 *  a plain radius check leaves zero admissible edges and bricks the bot. Outside the cylinder
 *  only edges that STRICTLY approach the column (in XZ) are allowed, so the bot beelines back
 *  in and normal in-radius behaviour resumes. Prune-only — costs are never modified.
 *
 *  <p><b>Safety</b>: pruning happens before the successor node is ever generated (the A*
 *  neighbour loop {@code continue}s), so every best-effort selector (frontier/ashore/climb/
 *  escape/bestSoFar) can only ever see in-column nodes. When no in-column ascent exists the
 *  search commits nothing (returns "no path" — the bot stays put) rather than a lateral
 *  best-effort wander. */
public record ColumnRadius(double cx, double cz, double radius) implements Constraint {
    @Override
    public boolean allows(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        if (radius <= 0) return true;   // unset → no column bound
        double dto = distSqXZ(to);
        if (dto <= radius * radius) return true;
        // Outside the cylinder (search started off-column): permit only strict XZ-approach so
        // the bot can rejoin instead of being fully pruned. Y is excluded, so a vertical move
        // while outside is NOT an approach (dto unchanged) — you must close XZ distance first.
        return from != null && dto < distSqXZ(from);
    }

    /** Squared XZ distance from the cell CENTRE (x+0.5, z+0.5) to the column line — the same
     *  cell-centre convention {@link LeashHardRadius} and the arena checker use. */
    private double distSqXZ(BlockPos p) {
        double dx = (p.getX() + 0.5) - cx, dz = (p.getZ() + 0.5) - cz;
        return dx * dx + dz * dz;
    }
}
