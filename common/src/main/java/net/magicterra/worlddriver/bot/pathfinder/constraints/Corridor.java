package net.magicterra.worlddriver.bot.pathfinder.constraints;

import java.util.List;

import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.pathfinder.Constraint;
import net.magicterra.worlddriver.bot.pathfinder.CostModifier;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Keep the route within {@code radius} of a polyline the caller drew. Hard ({@code forbid}):
 * a cell farther than the radius from the line is pruned; soft ({@code avoid}): it costs
 * {@code penalty} per block beyond the radius. The LLM draws the line, A* still does the
 * per-cell work of getting over the ditch and up the step.
 *
 * <p>Rejoin, as {@link LeashHardRadius}: from outside the corridor only edges that strictly
 * close the distance to the line are allowed, so a bot pushed out walks back in instead of
 * having no successors.
 *
 * @param points the polyline's vertices in order; one point is a degenerate line
 * @param radius how far from the line a cell may be, blocks, measured from the cell centre
 * @param hard prune (true) or charge (false)
 * @param penalty cost per block beyond the radius when soft
 */
public record Corridor(List<Vec3> points, double radius, boolean hard, double penalty)
        implements Constraint, CostModifier {

    public Corridor {
        points = List.copyOf(points);
        if (points.isEmpty()) throw new IllegalArgumentException("corridor: needs at least one point");
    }

    /** Distance from a point to the polyline: the minimum over its segments. */
    public double distance(double x, double y, double z) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < points.size(); i++) {
            Vec3 a = points.get(i);
            Vec3 b = points.get(Math.min(i + 1, points.size() - 1));
            best = Math.min(best, segmentDistance(x, y, z, a, b));
            if (i + 1 >= points.size()) break;
        }
        return best;
    }

    private static double segmentDistance(double x, double y, double z, Vec3 a, Vec3 b) {
        double abx = b.x - a.x, aby = b.y - a.y, abz = b.z - a.z;
        double apx = x - a.x, apy = y - a.y, apz = z - a.z;
        double len2 = abx * abx + aby * aby + abz * abz;
        double t = len2 <= 1e-12 ? 0 : Math.max(0, Math.min(1, (apx * abx + apy * aby + apz * abz) / len2));
        double cx = a.x + t * abx, cy = a.y + t * aby, cz = a.z + t * abz;
        double dx = x - cx, dy = y - cy, dz = z - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double excess(BlockPos p) {
        double d = distance(p.getX() + 0.5, p.getY(), p.getZ() + 0.5) - radius;
        return d > 0 ? d : 0;
    }

    @Override
    public boolean allows(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        if (!hard) return true;
        double eto = excess(to);
        if (eto <= 0) return true;
        return from != null && eto < excess(from);
    }

    @Override
    public double extraCost(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        if (hard || penalty <= 0) return 0;
        return penalty * excess(to);
    }
}
