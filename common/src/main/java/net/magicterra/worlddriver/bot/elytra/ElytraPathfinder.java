package net.magicterra.worlddriver.bot.elytra;

import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Collections;

/**
 * Coarse 3D path planner for elytra flight — milestone C of the Baritone
 * elytra-alignment. The reactive controller ({@link ElytraController}) only
 * sees one horizon ahead (~30 ticks), so it can climb over a ridge but cannot
 * decide to fly <em>around</em> a barrier taller than it can clear or longer
 * than its sightline. This planner fills that gap: a bounded A* over a coarse
 * air-voxel grid finds a clear corridor of line-of-sight waypoints from the
 * current position to the goal, and the controller then flies waypoint to
 * waypoint while still doing its own fine, per-tick obstacle avoidance between
 * them. It is the elytra analogue of the ground A* + {@code stringPull}.
 *
 * <p>The grid is coarse (cells {@link #GRID} blocks apart) so the search stays
 * cheap over long distances — the controller handles block-level detail, so the
 * plan only needs to be roughly right. A node is "free" only if a small clear
 * box surrounds it, and an edge is kept only if the gap between two free nodes
 * is also clear, so corridors keep a one-block margin off walls. After the
 * search the node chain is string-pulled (collapse any run the straight line can
 * see through) down to the few turn points that actually matter.
 *
 * <p><b>Loaded-chunk caveat.</b> Reads of unloaded chunks come back non-solid
 * (air), so far terrain reads as free at plan time; that's intentional in the
 * layered design — the plan is optimistic about the unknown, and the controller
 * (with live, loaded terrain in its lookahead) handles whatever is really there.
 * The flight re-plans on a cadence so newly loaded terrain refines the route.
 * Open sky is the common case and short-circuits to a direct goal with no search.
 */
public final class ElytraPathfinder {
    private ElytraPathfinder() {}

    /** Coarse grid spacing (blocks). Bigger = cheaper search, rougher route. */
    public static final int GRID = 4;
    /** A node is free only if no solid block lies within this many blocks of its
     *  centre (a clear box → corridors keep a margin off walls). */
    private static final int NODE_CLEAR = 1;
    /** Half-extent of the clearance box swept along a string-pull LOS segment. */
    private static final int LOS_CLEAR = 1;
    /** Hard node-expansion cap — degrade to a direct goal (let the reactive
     *  controller cope) rather than hitch the client thread on a huge search. */
    private static final int MAX_NODES = 12_000;
    /** Search is confined to the start↔goal AABB grown by this many blocks, so a
     *  blocked goal can't make A* flood the whole world. */
    private static final int BOX_MARGIN = 96;

    /**
     * Plan a corridor of waypoints from {@code start} to {@code goal}. The
     * returned list ends at {@code goal} and excludes the start; never empty.
     * Falls back to {@code [goal]} (direct) when start↔goal is already clear, the
     * endpoints are unusable, or the search is exhausted — in every fallback the
     * controller still flies, it just steers straight at the goal.
     */
    public static List<Vec3> plan(WorldView w, Vec3 start, Vec3 goal) {
        if (losClear(w, start, goal)) return List.of(goal);

        long startNode = key(snap(start.x), snap(start.y), snap(start.z));
        long goalNode = key(snap(goal.x), snap(goal.y), snap(goal.z));

        // Search box (grid units) around start+goal so A* can't flood outward.
        int minX = Math.min(snap(start.x), snap(goal.x)) - BOX_MARGIN / GRID;
        int maxX = Math.max(snap(start.x), snap(goal.x)) + BOX_MARGIN / GRID;
        int minY = Math.min(snap(start.y), snap(goal.y)) - BOX_MARGIN / GRID;
        int maxY = Math.max(snap(start.y), snap(goal.y)) + BOX_MARGIN / GRID;
        int minZ = Math.min(snap(start.z), snap(goal.z)) - BOX_MARGIN / GRID;
        int maxZ = Math.max(snap(start.z), snap(goal.z)) + BOX_MARGIN / GRID;

        Map<Long, Double> g = new HashMap<>();
        Map<Long, Long> parent = new HashMap<>();
        Map<Long, Boolean> freeCache = new HashMap<>();
        PriorityQueue<long[]> open = new PriorityQueue<>((a, b) -> Long.compare(a[1], b[1]));

        g.put(startNode, 0.0);
        open.add(new long[]{startNode, 0});
        int expanded = 0;
        long best = startNode;
        double bestH = hWorld(startNode, goal);

        while (!open.isEmpty() && expanded < MAX_NODES) {
            long cur = open.poll()[0];
            expanded++;
            double curG = g.getOrDefault(cur, Double.POSITIVE_INFINITY);

            double h = hWorld(cur, goal);
            if (h < bestH) { bestH = h; best = cur; }

            // Reached: the goal cell is within sight of this node.
            if (h <= GRID * 1.5 && losClear(w, center(cur), goal)) {
                return stringPull(w, reconstruct(parent, cur, startNode), start, goal);
            }

            int cx = gx(cur), cy = gy(cur), cz = gz(cur);
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        int nx = cx + dx, ny = cy + dy, nz = cz + dz;
                        if (nx < minX || nx > maxX || ny < minY || ny > maxY || nz < minZ || nz > maxZ) continue;
                        long nb = key(nx, ny, nz);
                        if (!nodeFree(w, nb, freeCache)) continue;
                        if (!edgeClear(w, cx, cy, cz, nx, ny, nz)) continue;
                        double step = Math.sqrt((double) (dx * dx + dy * dy + dz * dz)) * GRID;
                        double ng = curG + step;
                        if (ng < g.getOrDefault(nb, Double.POSITIVE_INFINITY)) {
                            g.put(nb, ng);
                            parent.put(nb, cur);
                            double fpri = ng + hWorld(nb, goal);
                            open.add(new long[]{nb, (long) (fpri * 1000)});
                        }
                    }
        }
        // Exhausted / capped: route to the closest node we reached, then straight
        // at the goal (better than nothing; reactive control finishes the job).
        if (best != startNode) {
            List<Vec3> partial = stringPull(w, reconstruct(parent, best, startNode), start, goal);
            return partial;
        }
        return List.of(goal);
    }

    // --- string-pulling -----------------------------------------------------

    /** Collapse the node chain to turn points: from each kept point, skip ahead
     *  to the farthest later point still in clear sight. Bookended by the real
     *  start/goal; the returned list drops the start and ends at the goal. */
    private static List<Vec3> stringPull(WorldView w, List<Long> nodes, Vec3 start, Vec3 goal) {
        List<Vec3> pts = new ArrayList<>();
        pts.add(start);
        for (long n : nodes) pts.add(center(n));
        pts.add(goal);

        List<Vec3> out = new ArrayList<>();
        int i = 0;
        while (i < pts.size() - 1) {
            int j = pts.size() - 1;
            while (j > i + 1 && !losClear(w, pts.get(i), pts.get(j))) j--;
            out.add(pts.get(j));
            i = j;
        }
        if (out.isEmpty()) out.add(goal);
        return out;
    }

    private static List<Long> reconstruct(Map<Long, Long> parent, long end, long start) {
        List<Long> chain = new ArrayList<>();
        long c = end;
        while (c != start) {
            chain.add(c);
            Long p = parent.get(c);
            if (p == null) break;
            c = p;
        }
        Collections.reverse(chain);
        return chain;
    }

    // --- clearance tests ----------------------------------------------------

    private static boolean nodeFree(WorldView w, long node, Map<Long, Boolean> cache) {
        Boolean c = cache.get(node);
        if (c != null) return c;
        boolean free = boxClear(w, gx(node) * GRID, gy(node) * GRID, gz(node) * GRID, NODE_CLEAR, NODE_CLEAR);
        cache.put(node, free);
        return free;
    }

    /** Keep an edge only if the gap between two free nodes is itself clear — a
     *  single solid block sitting exactly between adjacent free cells (a thin
     *  wall) is caught at the midpoint, and a diagonal can't cut a solid corner. */
    private static boolean edgeClear(WorldView w, int ax, int ay, int az, int bx, int by, int bz) {
        double mx = (ax + bx) * 0.5 * GRID, my = (ay + by) * 0.5 * GRID, mz = (az + bz) * 0.5 * GRID;
        if (!boxClear(w, (int) Math.round(mx), (int) Math.round(my), (int) Math.round(mz), NODE_CLEAR, NODE_CLEAR))
            return false;
        // No diagonal corner-cut: each single-axis neighbour cell must be free.
        if (ax != bx && solid(w, bx * GRID, ay * GRID, az * GRID) && solid(w, ax * GRID, by * GRID, bz * GRID))
            return false;
        return true;
    }

    /** True if no solid block lies within the box of half-extents (rXZ,rY,rXZ)
     *  centred on the world coord. */
    private static boolean boxClear(WorldView w, int x, int y, int z, int rXZ, int rY) {
        for (int dx = -rXZ; dx <= rXZ; dx++)
            for (int dy = -rY; dy <= rY; dy++)
                for (int dz = -rXZ; dz <= rXZ; dz++)
                    if (solid(w, x + dx, y + dy, z + dz)) return false;
        return true;
    }

    /** True if the straight segment a→b keeps a clear corridor the whole way
     *  (sampled per block, a clear box swept along it). */
    public static boolean losClear(WorldView w, Vec3 a, Vec3 b) {
        double dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(len));
        for (int i = 0; i <= steps; i++) {
            double f = (double) i / steps;
            int x = (int) Math.floor(a.x + dx * f);
            int y = (int) Math.floor(a.y + dy * f);
            int z = (int) Math.floor(a.z + dz * f);
            if (!boxClear(w, x, y, z, LOS_CLEAR, LOS_CLEAR)) return false;
        }
        return true;
    }

    private static boolean solid(WorldView w, int x, int y, int z) {
        return w.isSolid(new BlockPos(x, y, z));
    }

    // --- grid <-> world helpers (node packed into a long key) ----------------

    private static int snap(double world) { return (int) Math.round(world / GRID); }
    private static Vec3 center(long node) {
        return new Vec3(gx(node) * (double) GRID, gy(node) * (double) GRID, gz(node) * (double) GRID);
    }
    private static double hWorld(long node, Vec3 goal) { return center(node).distanceTo(goal); }

    // Pack three signed grid coords (each within ±1M) into one long.
    private static long key(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }
    private static int gx(long k) { return signed((int) ((k >> 42) & 0x1FFFFF)); }
    private static int gy(long k) { return signed((int) ((k >> 21) & 0x1FFFFF)); }
    private static int gz(long k) { return signed((int) (k & 0x1FFFFF)); }
    private static int signed(int v) { return (v & 0x100000) != 0 ? v - 0x200000 : v; }
}
