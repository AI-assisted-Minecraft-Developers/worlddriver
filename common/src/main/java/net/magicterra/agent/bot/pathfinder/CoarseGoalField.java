package net.magicterra.agent.bot.pathfinder;

import static net.magicterra.agent.AgentDriverCommon.LOG;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.Goal;
import net.minecraft.core.BlockPos;

import java.util.PriorityQueue;

/**
 * An obstacle-aware, goal-rooted cost-to-goal field over a COARSE, SYMMETRIC
 * grid — the heuristic source that lets the fine {@link PathFinder} A* route
 * <em>around</em> a concave obstacle (the spawn water+mountain pinch) instead of
 * burning its budget expanding into the wall and then committing a backward
 * best-effort segment (the "走回头路" oscillation).
 *
 * <h2>Why this exists</h2>
 * The Euclidean {@link Goal#estimate} points straight at the goal — through any
 * wall in the way. At a concave pinch the frontier can only expand backward, so
 * within the time/node budget the search never finds the way around and falls
 * back to a near-start (backward) segment. A goal-rooted distance field that
 * already accounts for the known terrain gives the fine A* an estimate that is
 * LARGER on the wrong side of the wall, redirecting expansions around it.
 *
 * <h2>Why coarse + symmetric (the D*-lite predecessor problem)</h2>
 * Classic D*-lite searches backward from the goal and needs {@code pred(node)}.
 * The full {@link Move} catalog is highly ASYMMETRIC (falls, parkour, MLG
 * buckets, break/place) — hand-writing a correct reverse for each is large and
 * bug-prone. This field instead runs on a coarse grid whose edges are SYMMETRIC
 * (cell A links to cell B iff both hold a standable foot and the step between
 * their representative feet is a normal walk/step/jump/fall), so
 * {@code pred == succ} trivially. The coarse graph is ONLY a heuristic source —
 * the fine A* still uses the exact asymmetric moves for the committed path — so
 * it needs approximate connectivity, not exact move semantics.
 *
 * <h2>Limited client horizon</h2>
 * The client only knows chunks within render distance, so a far goal lies
 * outside the box. We seed every OPEN cell on the box BOUNDARY with its
 * {@link Goal#estimate} (i.e. "exit the known region here, then travel the
 * unknown beyond as open Euclidean terrain") and any in-box cell that already
 * satisfies {@link Goal#reached} with 0, then run a backward Dijkstra inward.
 * The result: obstacle-aware inside the loaded box, Euclidean beyond it — which
 * is exactly right, and degrades to the plain Euclidean heuristic when the field
 * is unavailable for a cell (so it can never be worse than today).
 *
 * <h2>Phase</h2>
 * Phase 0 is a one-shot backward Dijkstra rebuilt per search (behind the
 * default-off {@code BotConfig.pathfinderGoalField} knob) to validate the
 * premise cheaply. The graph/walkability is separated from the propagation so
 * the Dijkstra can later be replaced by incremental D*-lite repair (Phase 1)
 * without touching the consumer.
 */
public final class CoarseGoalField {

    /** Sentinel returned by {@link #costToGoal} when the field has no value for a
     *  cell (closed / unreachable / out of box) — caller falls back to Euclidean. */
    public static final double UNKNOWN = -1.0;

    private final int cellSize;
    private final int hr;            // horizontal radius in cells
    private final int vr;            // vertical radius in cells
    private final int dim;           // 2*hr+1 (horizontal side, in cells)
    private final int dimY;          // 2*vr+1 (vertical side, in cells)
    private final int baseX, baseY, baseZ;   // block coords of cell (0,0,0)'s origin corner

    /** Per-cell representative foot Y (block), or Integer.MIN_VALUE when the cell
     *  holds no standable foot (closed). Indexed by {@link #idx}. */
    private final int[] footY;
    /** Per-cell cost-to-goal in Move cost units, or +inf until relaxed. */
    private final double[] dist;

    private CoarseGoalField(int cellSize, int hr, int vr, int baseX, int baseY, int baseZ) {
        this.cellSize = cellSize;
        this.hr = hr;
        this.vr = vr;
        this.dim = 2 * hr + 1;
        this.dimY = 2 * vr + 1;
        this.baseX = baseX;
        this.baseY = baseY;
        this.baseZ = baseZ;
        int n = dim * dimY * dim;
        this.footY = new int[n];
        this.dist = new double[n];
    }

    /**
     * Build the field for {@code goal} over a box centred on {@code start}.
     * Returns null when the field is disabled or the box has no usable cell, so
     * the caller simply keeps the Euclidean heuristic.
     */
    public static CoarseGoalField build(WorldView w, Goal goal, BlockPos start) {
        int s = Math.max(1, BotConfig.goalFieldCellSize);
        int hr = Math.max(1, BotConfig.goalFieldRadius / s);
        int vr = Math.max(1, BotConfig.goalFieldVerticalRadius / s);
        int baseX = (start.getX() - hr * s);
        int baseY = (start.getY() - vr * s);
        int baseZ = (start.getZ() - hr * s);
        CoarseGoalField f = new CoarseGoalField(s, hr, vr, baseX, baseY, baseZ);
        f.probeWalkability(w);
        boolean ok = f.propagate(goal);
        if (BotConfig.walkerDebug) {
            int total = f.dim * f.dimY * f.dim;
            double atStart = ok ? f.costToGoal(start) : UNKNOWN;
            LOG.info("[goalfield] cells={} open={} seeded={} start_h_field={} start_h_euclid={}",
                    total, f.openCells, ok, String.format("%.0f", atStart),
                    String.format("%.0f", goal.estimate(start)));
        }
        if (!ok) return null;
        return f;
    }

    // --- cell indexing -------------------------------------------------------

    private int cx(int blockX) { return Math.floorDiv(blockX - baseX, cellSize); }
    private int cy(int blockY) { return Math.floorDiv(blockY - baseY, cellSize); }
    private int cz(int blockZ) { return Math.floorDiv(blockZ - baseZ, cellSize); }

    private boolean inBox(int gx, int gy, int gz) {
        return gx >= 0 && gx < dim && gy >= 0 && gy < dimY && gz >= 0 && gz < dim;
    }

    private int idx(int gx, int gy, int gz) { return (gx * dimY + gy) * dim + gz; }

    /** Block X/Z of the centre column of cell grid-coord g. */
    private int centreX(int gx) { return baseX + gx * cellSize + cellSize / 2; }
    private int centreZ(int gz) { return baseZ + gz * cellSize + cellSize / 2; }
    private int cellBottomY(int gy) { return baseY + gy * cellSize; }

    // --- walkability probe ---------------------------------------------------

    private int openCells;   // diagnostics

    /**
     * For each cell, scan a small set of columns (centre + the 4 quarter points)
     * across the cell's Y span for a standable foot; the cell is OPEN if any
     * column holds one, with the foot nearest the cell's vertical centre kept as
     * the representative Y (closed otherwise). Sampling several columns — not just
     * the centre — is load-bearing: a single 4×4 cell straddles terrain, and a
     * centre-only probe wrongly closes most cells, leaving the graph too sparse to
     * carry a field. Approximate by design — this only feeds a heuristic.
     */
    private void probeWalkability(WorldView w) {
        int q = Math.max(1, cellSize / 4);
        int[] cols = {cellSize / 2, q, cellSize - 1 - q};   // centre + two quarter offsets
        for (int gx = 0; gx < dim; gx++) {
            int bx0 = baseX + gx * cellSize;
            for (int gz = 0; gz < dim; gz++) {
                int bz0 = baseZ + gz * cellSize;
                for (int gy = 0; gy < dimY; gy++) {
                    int by0 = cellBottomY(gy);
                    int centreYTarget = by0 + cellSize / 2;
                    int best = Integer.MIN_VALUE;
                    for (int ox : cols)
                        for (int oz : cols) {
                            for (int dy = 0; dy < cellSize; dy++) {
                                BlockPos foot = new BlockPos(bx0 + ox, by0 + dy, bz0 + oz);
                                if (w.canStandAt(foot)) {
                                    int fy = by0 + dy;
                                    if (best == Integer.MIN_VALUE
                                            || Math.abs(fy - centreYTarget) < Math.abs(best - centreYTarget)) {
                                        best = fy;
                                    }
                                    break;   // first standable in this column
                                }
                            }
                        }
                    if (best != Integer.MIN_VALUE) openCells++;
                    footY[idx(gx, gy, gz)] = best;
                }
            }
        }
    }

    private boolean open(int gx, int gy, int gz) {
        return footY[idx(gx, gy, gz)] != Integer.MIN_VALUE;
    }

    // --- backward Dijkstra ---------------------------------------------------

    /** The 26-ish coarse neighbour offsets: 8 horizontal at Δcy∈{-1,0,1} plus
     *  pure vertical Δcy=±1. */
    private static final int[][] NEIGHBOURS = buildNeighbours();

    private static int[][] buildNeighbours() {
        int[][] n = new int[3 * 8 + 2][];
        int i = 0;
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;        // pure-vertical handled separately
                    n[i++] = new int[]{dx, dy, dz};
                }
        n[i++] = new int[]{0, 1, 0};
        n[i++] = new int[]{0, -1, 0};
        return n;
    }

    private record QE(int cell, double d) {}

    /**
     * Seed boundary + goal cells, then relax inward. Returns false when nothing
     * was seeded (no reachable exit / goal in the box) → field is useless.
     */
    private boolean propagate(Goal goal) {
        java.util.Arrays.fill(dist, Double.POSITIVE_INFINITY);
        PriorityQueue<QE> pq = new PriorityQueue<>((a, b) -> Double.compare(a.d, b.d));
        boolean seeded = false;
        for (int gx = 0; gx < dim; gx++)
            for (int gy = 0; gy < dimY; gy++)
                for (int gz = 0; gz < dim; gz++) {
                    if (!open(gx, gy, gz)) continue;
                    BlockPos foot = new BlockPos(centreX(gx), footY[idx(gx, gy, gz)], centreZ(gz));
                    double seed = Double.POSITIVE_INFINITY;
                    if (goal.reached(foot)) {
                        seed = 0;
                    } else if (gx == 0 || gx == dim - 1 || gz == 0 || gz == dim - 1
                            || gy == 0 || gy == dimY - 1) {
                        seed = goal.estimate(foot);     // exit the known box here, Euclidean beyond
                    }
                    if (seed != Double.POSITIVE_INFINITY) {
                        int id = idx(gx, gy, gz);
                        dist[id] = seed;
                        pq.add(new QE(id, seed));
                        seeded = true;
                    }
                }
        if (!seeded) return false;

        while (!pq.isEmpty()) {
            QE e = pq.poll();
            if (e.d > dist[e.cell]) continue;
            // decode cell coords
            int gz = e.cell % dim;
            int gy = (e.cell / dim) % dimY;
            int gx = e.cell / (dim * dimY);
            int footYa = footY[e.cell];
            for (int[] off : NEIGHBOURS) {
                int nx = gx + off[0], ny = gy + off[1], nz = gz + off[2];
                if (!inBox(nx, ny, nz) || !open(nx, ny, nz)) continue;
                int nid = idx(nx, ny, nz);
                int footYb = footY[nid];
                double step = Goal.blockHeuristic(off[0] * cellSize, footYb - footYa, off[2] * cellSize);
                double nd = e.d + step;
                if (nd + 1e-6 < dist[nid]) {
                    dist[nid] = nd;
                    pq.add(new QE(nid, nd));
                }
            }
        }
        return true;
    }

    // --- consumer API --------------------------------------------------------

    /**
     * Obstacle-aware cost-to-goal estimate for a fine A* node at {@code p}, in
     * Move cost units, or {@link #UNKNOWN} when the field has no value (out of
     * box / closed cell / unreachable) — caller falls back to Euclidean.
     */
    public double costToGoal(BlockPos p) {
        int gx = cx(p.getX()), gy = cy(p.getY()), gz = cz(p.getZ());
        if (!inBox(gx, gy, gz)) return UNKNOWN;
        double d = dist[idx(gx, gy, gz)];
        return Double.isInfinite(d) ? UNKNOWN : d;
    }
}
