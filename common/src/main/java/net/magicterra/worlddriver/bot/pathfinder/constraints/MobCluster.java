package net.magicterra.worlddriver.bot.pathfinder.constraints;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.pathfinder.Constraint;
import net.magicterra.worlddriver.bot.pathfinder.CostModifier;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.SearchAware;
import net.magicterra.worlddriver.bot.pathfinder.SearchScope;
import net.magicterra.worlddriver.bot.pathfinder.ThreatSnapshot;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.magicterra.worlddriver.bot.world.ThreatAvoidance;
import net.minecraft.core.BlockPos;

/**
 * Hostile mobs as a route condition, {@code route.mobs}. Two halves on one snapshot:
 *
 * <ul>
 *   <li><b>Per-mob berth</b> (the cost modifier): every hostile in the search's
 *       {@link ThreatSnapshot} charges a cost that ramps from {@code penalty} at the mob to 0 at
 *       {@code radius} ({@code rangedRadius} for one that shoots) — the curve of
 *       {@link ThreatAvoidance#cost}, which is what {@code ClientWorldView.dangerCost} used to
 *       apply from its own client-only snapshot. Costs add, so three mobs together are dearer
 *       than one, and the server body avoids mobs now too.</li>
 *   <li><b>Cluster threshold</b> (the constraint, optional): a cell with {@code cluster.count} or
 *       more hostiles within {@code cluster.radius} is a cluster cell. {@code forbid} prunes it;
 *       {@code avoid} adds {@code cluster.penalty} flat. This is the qualitative step the ramp
 *       cannot make: however far the detour, a plain sum can be out-bid by it.</li>
 * </ul>
 *
 * <p><b>Rejoin from inside a cluster.</b> The violation here is a COUNT, and from the middle of
 * four mobs every neighbour usually counts four as well, so "strictly less" would leave no
 * legal edge. So the rule has two parts: the count may not rise, and the distance to the nearest
 * mob must strictly grow. The distance guarantees progress on every step; the count guarantees
 * the step does not go somewhere worse. Once the count is under the threshold, normal pruning.
 *
 * @param radius       berth of a melee mob, blocks
 * @param rangedRadius berth of a mob that shoots, blocks
 * @param penalty      peak cost at zero distance; 0 disables the ramp
 * @param cluster      the threshold, or null for the ramp alone
 * @param types        registry names to consider, empty for every hostile
 */
public final class MobCluster implements Constraint, CostModifier, SearchAware {

    /** @param count how many mobs make a cluster; @param radius within how many blocks of the
     *  cell; @param hard prune (forbid) or charge (avoid); @param penalty the flat charge when soft */
    public record Cluster(int count, double radius, boolean hard, double penalty) {}

    private final double radius, rangedRadius, penalty;
    private final Cluster cluster;
    private final Set<String> types;

    // Snapshot for this search: mobs as [x,y,z,r] stride-4 for the ramp, and the same mobs as
    // plain coordinates for the cluster count. Both rebuilt in beginSearch.
    private float[] mobXyzr = new float[0];
    private double[] mobs = new double[0];

    public MobCluster(double radius, double rangedRadius, double penalty, Cluster cluster, Set<String> types) {
        this.radius = radius;
        this.rangedRadius = rangedRadius;
        this.penalty = penalty;
        this.cluster = cluster;
        this.types = types == null ? Set.of() : Set.copyOf(types);
    }

    public double radius() { return radius; }
    public double rangedRadius() { return rangedRadius; }
    public double penalty() { return penalty; }
    public Cluster cluster() { return cluster; }
    public Set<String> types() { return types; }

    @Override public int scanRadius() {
        double r = Math.max(radius, rangedRadius);
        if (cluster != null) r = Math.max(r, cluster.radius());
        return (int) Math.ceil(r);
    }

    @Override public void beginSearch(SearchScope scope) {
        List<ThreatSnapshot.Threat> picked = new ArrayList<>();
        for (ThreatSnapshot.Threat t : scope.threats().threats()) {
            if (!t.hostile()) continue;
            if (!types.isEmpty() && !types.contains(t.type())) continue;
            picked.add(t);
        }
        float[] xyzr = new float[picked.size() * 4];
        double[] xyz = new double[picked.size() * 3];
        int i = 0, j = 0;
        for (ThreatSnapshot.Threat t : picked) {
            xyzr[i++] = (float) t.pos().x; xyzr[i++] = (float) t.pos().y; xyzr[i++] = (float) t.pos().z;
            xyzr[i++] = (float) (t.ranged() ? rangedRadius : radius);
            xyz[j++] = t.pos().x; xyz[j++] = t.pos().y; xyz[j++] = t.pos().z;
        }
        mobXyzr = xyzr;
        mobs = xyz;
    }

    /** How many snapshot mobs are within {@code r} of a cell centre. */
    public int count(BlockPos p, double r) {
        double x = p.getX() + 0.5, y = p.getY(), z = p.getZ() + 0.5, r2 = r * r;
        int n = 0;
        for (int i = 0; i + 2 < mobs.length; i += 3) {
            double dx = x - mobs[i], dy = y - mobs[i + 1], dz = z - mobs[i + 2];
            if (dx * dx + dy * dy + dz * dz <= r2) n++;
        }
        return n;
    }

    /** Distance from a cell centre to the nearest snapshot mob; +∞ with none. */
    public double nearest(BlockPos p) {
        double x = p.getX() + 0.5, y = p.getY(), z = p.getZ() + 0.5, best = Double.POSITIVE_INFINITY;
        for (int i = 0; i + 2 < mobs.length; i += 3) {
            double dx = x - mobs[i], dy = y - mobs[i + 1], dz = z - mobs[i + 2];
            best = Math.min(best, dx * dx + dy * dy + dz * dz);
        }
        return Math.sqrt(best);
    }

    private boolean clustered(int count) {
        return cluster != null && count >= cluster.count();
    }

    @Override
    public boolean allows(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        if (cluster == null || !cluster.hard() || mobs.length == 0) return true;
        int cto = count(to, cluster.radius());
        if (!clustered(cto)) return true;
        if (from == null) return false;
        int cfrom = count(from, cluster.radius());
        return cto <= cfrom && nearest(to) > nearest(from);
    }

    @Override
    public double extraCost(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        if (mobs.length == 0) return 0;
        double c = ThreatAvoidance.cost(mobXyzr, penalty, to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
        if (cluster != null && !cluster.hard() && cluster.penalty() > 0 && clustered(count(to, cluster.radius()))) {
            c += cluster.penalty();
        }
        return c;
    }

    @Override public String toString() {
        return "MobCluster[radius=" + radius + ", rangedRadius=" + rangedRadius + ", penalty=" + penalty
                + ", cluster=" + cluster + ", types=" + types + "]";
    }
}
