package net.magicterra.worlddriver.bot.pathfinder.constraints;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.pathfinder.Constraint;
import net.magicterra.worlddriver.bot.pathfinder.CostModifier;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.SearchAware;
import net.magicterra.worlddriver.bot.pathfinder.SearchScope;
import net.magicterra.worlddriver.bot.pathfinder.ThreatSnapshot;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Lines of sight as a route condition, {@code route.sight}. A cell is exposed to an observer when
 * a ray from the observer's eye to the cell plus {@code eye} is clear of colliding blocks — the
 * shape of vanilla's own "can it see the target" test (eye to eye), made with the same clip
 * {@code ThreatScanner} uses, so water does not block it. Only observers within their
 * {@code range} of the cell are asked; beyond it a cell is unexposed without a ray.
 *
 * <p>Soft ({@code avoid}): {@code penalty} per exposed cell per observer. Hard ({@code forbid}):
 * an exposed cell is pruned, with the rejoin rule for a count: the number of observers seeing
 * the destination may not exceed the number seeing the origin, and the distance to the nearest
 * observer that sees the destination must strictly grow.
 *
 * <p><b>Rays are the one expensive thing in this design.</b> Results are cached per (cell,
 * observer) for the search, and a search may fire at most {@code rayBudget} rays. When the
 * budget is spent the answer is NOT "count the rest as exposed": that would make a cell's cost
 * depend on how many cells were expanded before it, and A* does not reopen closed nodes, so the
 * same scene would give different routes run to run. Instead {@link RayBudgetExhausted} is
 * thrown, {@code PathFinder.Search} abandons the search and reruns it without this component,
 * and the result says so with {@code sightBudgetExhausted}. The result is then a function of
 * (cell, snapshot) only, at the price of two searches in the worst case.
 *
 * <p>Geometry only: no light level, no sneaking, no invisibility, no per-mob aggro range.
 */
public final class SightExposure implements Constraint, CostModifier, SearchAware {

    /** Thrown from inside the neighbour loop when the search's ray budget is spent. */
    public static final class RayBudgetExhausted extends RuntimeException {
        public RayBudgetExhausted(int budget) {
            super("sight: ray budget of " + budget + " spent", null, false, false);
        }
    }

    /** Who to hide from. */
    public enum Of { RANGED, HOSTILE, PLAYERS, LISTED }

    private final Of of;
    private final List<String> listed;
    private final double rangeOverride;   // <= 0 → each observer's default
    private final boolean hard;
    private final double penalty;
    private final double eye;
    private final int rayBudget;

    // Per-search state.
    private List<ThreatSnapshot.Threat> observers = List.of();
    private double[] ranges = new double[0];
    private SearchScope.LineOfSight los = SearchScope.ALWAYS_CLEAR;
    /** (cell, observer index) → seen. Keyed by the cell's packed long and the index. */
    private final Map<Long, boolean[]> cache = new HashMap<>();
    private int raysFired;

    /**
     * @param of which observers
     * @param listed ids, names or types when {@code of} is {@link Of#LISTED}
     * @param rangeOverride an effective range for every observer, or 0 for the per-kind default
     * @param hard prune (forbid) or charge (avoid)
     * @param penalty cost per exposed cell per observer when soft
     * @param eye the height above the cell the ray is aimed at
     * @param rayBudget the most rays one search may fire
     */
    public SightExposure(Of of, List<String> listed, double rangeOverride, boolean hard, double penalty,
                         double eye, int rayBudget) {
        this.of = of == null ? Of.RANGED : of;
        this.listed = listed == null ? List.of() : List.copyOf(listed);
        this.rangeOverride = rangeOverride;
        this.hard = hard;
        this.penalty = penalty;
        this.eye = eye;
        this.rayBudget = Math.max(1, rayBudget);
    }

    /** The same observers and geometry with another ray budget — an observation overlay asks
     *  for every cell of a grid, which is more rays than one search may spend. */
    public SightExposure withRayBudget(int budget) {
        return new SightExposure(of, listed, rangeOverride, hard, penalty, eye, budget);
    }

    public Of of() { return of; }
    public List<String> listed() { return listed; }
    public boolean hard() { return hard; }
    public double penalty() { return penalty; }
    public double eye() { return eye; }
    public int rayBudget() { return rayBudget; }
    /** How many rays the current search has fired. */
    public int raysFired() { return raysFired; }
    /** The observers this search hides from, after the {@code of} filter. */
    public List<ThreatSnapshot.Threat> observers() { return observers; }

    @Override public int scanRadius() {
        if (rangeOverride > 0) return (int) Math.ceil(rangeOverride);
        return 64;   // the largest default range (a ghast's)
    }

    @Override public void beginSearch(SearchScope scope) {
        List<ThreatSnapshot.Threat> picked = new ArrayList<>();
        for (ThreatSnapshot.Threat t : scope.threats().threats()) {
            boolean want = switch (of) {
                case RANGED -> t.hostile() && t.ranged();
                case HOSTILE -> t.hostile();
                case PLAYERS -> t.player();
                case LISTED -> listed.stream().anyMatch(t::matches);
            };
            if (want) picked.add(t);
        }
        observers = List.copyOf(picked);
        ranges = new double[picked.size()];
        for (int i = 0; i < ranges.length; i++) ranges[i] = rangeOverride > 0 ? rangeOverride : picked.get(i).range();
        los = scope.los();
        cache.clear();
        raysFired = 0;
    }

    /** Whether observer {@code i} sees the cell; cached, and charged to the ray budget. */
    private boolean seenBy(int i, BlockPos cell) {
        boolean[] row = cache.computeIfAbsent(cell.asLong(), k -> new boolean[observers.size() * 2]);
        // row[2i] = answered, row[2i+1] = seen
        if (row[2 * i]) return row[2 * i + 1];
        ThreatSnapshot.Threat t = observers.get(i);
        double x = cell.getX() + 0.5, y = cell.getY() + eye, z = cell.getZ() + 0.5;
        double dx = x - t.eye().x, dy = y - t.eye().y, dz = z - t.eye().z;
        boolean seen;
        if (dx * dx + dy * dy + dz * dz > ranges[i] * ranges[i]) {
            seen = false;   // out of range: no ray
        } else {
            if (raysFired >= rayBudget) throw new RayBudgetExhausted(rayBudget);
            raysFired++;
            seen = los.clear(t.eye(), new Vec3(x, y, z));
        }
        row[2 * i] = true;
        row[2 * i + 1] = seen;
        return seen;
    }

    /** The observers that see the cell, in snapshot order — what a preview segment names. */
    public List<ThreatSnapshot.Threat> seeing(BlockPos cell) {
        List<ThreatSnapshot.Threat> out = new ArrayList<>();
        for (int i = 0; i < observers.size(); i++) if (seenBy(i, cell)) out.add(observers.get(i));
        return out;
    }

    /** How many observers see the cell. */
    public int seen(BlockPos cell) {
        int n = 0;
        for (int i = 0; i < observers.size(); i++) if (seenBy(i, cell)) n++;
        return n;
    }

    /** Distance from the cell centre to the nearest observer that sees it; +∞ when unseen. */
    public double nearestSeeing(BlockPos cell) {
        double best = Double.POSITIVE_INFINITY;
        double x = cell.getX() + 0.5, y = cell.getY(), z = cell.getZ() + 0.5;
        for (int i = 0; i < observers.size(); i++) {
            if (!seenBy(i, cell)) continue;
            Vec3 p = observers.get(i).pos();
            double dx = x - p.x, dy = y - p.y, dz = z - p.z;
            best = Math.min(best, dx * dx + dy * dy + dz * dz);
        }
        return Math.sqrt(best);
    }

    @Override
    public boolean allows(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        if (!hard || observers.isEmpty()) return true;
        int sto = seen(to);
        if (sto == 0) return true;
        if (from == null) return false;
        return sto <= seen(from) && nearestSeeing(to) > nearestSeeing(from);
    }

    @Override
    public double extraCost(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        if (hard || penalty <= 0 || observers.isEmpty()) return 0;
        return penalty * seen(to);
    }

    @Override public String toString() {
        return "SightExposure[of=" + of + (of == Of.LISTED ? listed : "") + ", range="
                + (rangeOverride > 0 ? rangeOverride : "default") + ", " + (hard ? "forbid" : "avoid " + penalty)
                + ", eye=" + eye + "]";
    }
}
