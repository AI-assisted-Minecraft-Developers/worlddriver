package net.magicterra.agent.bot.movement;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;

/**
 * Path post-processing for the {@link Walker}: string-pulling flat staircase
 * runs into straight segments (danger-aware), line-of-sight walkability checks,
 * and the pending-edge predicate. Extracted from the former BotApiImpl
 * god-class. All members are package-internal to bot.movement; the Walker pulls
 * them in via {@code import static …PathSmoothing.*}.
 */
public final class PathSmoothing {

    private PathSmoothing() {}

    /** A smoothed (string-pulled) path and its aligned edges. */
    public static final class SmoothResult {
        final List<BlockPos> path;
        final List<Move.Edge> edges;
        SmoothResult(List<BlockPos> path, List<Move.Edge> edges) { this.path = path; this.edges = edges; }
    }

    /** True if an edge is a plain flat walk/diagonal with no break/place — the
     *  only kind we may collapse when string-pulling (vertical, parkour, climb,
     *  swim, and action edges stay as hard waypoints). */
    public static boolean plainFlatWalk(Move.Edge e) {
        if (e == null || e.move == null) return false;
        if (!e.toBreak.isEmpty() || !e.toPlace.isEmpty()) return false;
        return e.move.equals("walk") || e.move.equals("diag");
    }

    /**
     * String-pull the path: replace flat same-Y walk/diagonal staircase runs
     * with the straight segment between their endpoints (kept only when the
     * straight line is walkable). Removes the cardinal/diagonal zigzag that made
     * the heading — and therefore the camera — wobble left-right, and makes the
     * bot walk in straight lines. Vertical / parkour / climb / break / place
     * nodes are preserved as hard waypoints so movement timing is unaffected.
     */
    public static SmoothResult stringPull(WorldView w, List<BlockPos> path, List<Move.Edge> edges) {
        if (path.size() <= 2) return new SmoothResult(path, edges);
        List<BlockPos> np = new ArrayList<>();
        List<Move.Edge> ne = new ArrayList<>();
        np.add(path.get(0));
        ne.add(edges.get(0));   // start: null edge
        int i = 0;
        while (i < path.size() - 1) {
            int next = i + 1;
            if (!plainFlatWalk(edges.get(next)) || path.get(next).getY() != path.get(i).getY()) {
                np.add(path.get(next));
                ne.add(edges.get(next));
                i = next;
                continue;
            }
            int j = next;
            while (j + 1 < path.size()
                    && plainFlatWalk(edges.get(j + 1))
                    && path.get(j + 1).getY() == path.get(i).getY()
                    // Only straighten genuinely AXIS-ALIGNED corridors (the merged
                    // segment shares an x or z with the start). Collapsing a zigzag
                    // into a multi-block DIAGONAL fabricates a long corner-cut the
                    // walker can't thread in tight quarters — it aims at the far
                    // node and grinds every wall the diagonal skims past (the
                    // spawn-maze stall). Diagonal staircases stay as their cardinal
                    // steps, which the walker threads one cell at a time.
                    && (path.get(j + 1).getX() == path.get(i).getX()
                        || path.get(j + 1).getZ() == path.get(i).getZ()
                        // §90: a DIAGONAL merge is admitted only under the honest
                        // body-width corridor probe — the ray-only guard is what made
                        // diagonal collapsing fabricate unthreadable corner-cuts.
                        || (BotConfig.walkerDiagonalStringPull
                            && losWalkableBody(w, path.get(i), path.get(j + 1))))
                    && losWalkable(w, path.get(i), path.get(j + 1))) {
                j++;
            }
            // Don't straighten a bow that A* made to dodge danger: if the
            // straight line i→j routes through MORE dangerCost than the original
            // (bowed) waypoints, keep the waypoints. Without this the smoother
            // silently re-introduces the cliff edge / lava graze the planner paid
            // to avoid (losWalkable only checks walkability, not danger). On safe
            // ground both sums are 0, so normal zigzag staircases still collapse.
            if (BotConfig.avoidDanger
                    && straightLineDanger(w, path.get(i), path.get(j))
                       > waypointDanger(w, path, i, j) + 1e-6) {
                for (int k = next; k <= j; k++) {
                    np.add(path.get(k));
                    ne.add(edges.get(k));
                }
            } else {
                np.add(path.get(j));
                ne.add(new Move.Edge(path.get(j), 0, List.of(), List.of(), "walk"));
            }
            i = j;
        }
        // ne.get(0) is the null start edge — List.copyOf rejects nulls, so use a
        // null-tolerant unmodifiable wrapper for the edges (path has no nulls).
        return new SmoothResult(List.copyOf(np), Collections.unmodifiableList(ne));
    }

    /** True if the straight horizontal line from {@code a} to {@code b} is
     *  walkable the whole way (floor support + foot/head clearance + no hazard),
     *  sampled per cell. Used by the Walker's carrot aim to avoid pointing the
     *  camera through walls / across gaps. */
    public static boolean losWalkable(WorldView w, BlockPos a, BlockPos b) {
        int steps = Math.max(Math.abs(b.getX() - a.getX()), Math.abs(b.getZ() - a.getZ()));
        if (steps == 0) return true;
        int px = a.getX(), pz = a.getZ();        // previous sampled cell (corner check)
        for (int s = 1; s <= steps; s++) {
            double t = (double) s / steps;
            int x = (int) Math.round(a.getX() + (b.getX() - a.getX()) * t);
            int y = (int) Math.round(a.getY() + (b.getY() - a.getY()) * t);
            int z = (int) Math.round(a.getZ() + (b.getZ() - a.getZ()) * t);
            BlockPos c = new BlockPos(x, y, z);
            if (!w.isPassable(c) || w.isHazard(c)) return false;
            if (!w.isPassable(c.offset(0, 1, 0)) || w.isHazard(c.offset(0, 1, 0))) return false;
            if (!w.isSolid(c.offset(0, -1, 0)) && !w.isWater(c) && !w.isClimbable(c)) return false;
            // Diagonal hop between samples: the two L-corner cells must BOTH be
            // clear, else the straight line clips a solid corner the walker would
            // wedge on. Without this, string-pulling collapses an L-shaped pair of
            // safe cardinals into a single corner-cutting diagonal and the bot
            // snags (the spawn-pit sandstone-corner stall). Match foot+head.
            if (x != px && z != pz) {
                if (cornerBlocked(w, new BlockPos(x, y, pz))      // corner sharing this x
                 || cornerBlocked(w, new BlockPos(px, y, z))) return false; // corner sharing this z
            }
            px = x; pz = z;
        }
        return true;
    }

    /** Body-width-aware variant of {@link #losWalkable}: samples the interpolated
     *  CONTINUOUS line and requires foot+head passability for every cell the 0.6-wide
     *  hitbox overlaps (4-corner AABB probe, half-width 0.3), plus the centre cell's
     *  floor support. Catches the "ray threads a slit the body can't" case (jungle
     *  trunks: the centre line stays in passable cells while the body radius clips a
     *  trunk column — the walkerCarrotHColShrink A/B showed the far carrot bearing is
     *  the right detour, so the cure is to make the carrot's LOS honest about body
     *  width instead of shrinking pursuit). Used by the Walker's carrotPoint behind
     *  {@code walkerCarrotBodyLos}; path smoothing keeps the cheaper ray test. */
    public static boolean losWalkableBody(WorldView w, BlockPos a, BlockPos b) {
        int steps = 2 * Math.max(Math.abs(b.getX() - a.getX()), Math.abs(b.getZ() - a.getZ()));
        if (steps == 0) return true;
        double ax = a.getX() + 0.5, az = a.getZ() + 0.5;
        double bx = b.getX() + 0.5, bz = b.getZ() + 0.5;
        for (int s = 1; s <= steps; s++) {
            double t = (double) s / steps;
            double fx = ax + (bx - ax) * t;
            double fz = az + (bz - az) * t;
            int y = (int) Math.round(a.getY() + (b.getY() - a.getY()) * t);
            for (int cx = -1; cx <= 1; cx += 2)
                for (int cz = -1; cz <= 1; cz += 2) {
                    BlockPos c = new BlockPos((int) Math.floor(fx + cx * 0.3), y, (int) Math.floor(fz + cz * 0.3));
                    if (!w.isPassable(c) || w.isHazard(c)) return false;
                    if (!w.isPassable(c.offset(0, 1, 0)) || w.isHazard(c.offset(0, 1, 0))) return false;
                }
            BlockPos mid = new BlockPos((int) Math.floor(fx), y, (int) Math.floor(fz));
            if (!w.isSolid(mid.offset(0, -1, 0)) && !w.isWater(mid) && !w.isClimbable(mid)) return false;
        }
        return true;
    }

    /** A corner column the diagonal straight-line skims: blocked if either the
     *  foot or head cell is non-passable or a hazard. */
    private static boolean cornerBlocked(WorldView w, BlockPos c) {
        return !w.isPassable(c) || w.isHazard(c)
            || !w.isPassable(c.offset(0, 1, 0)) || w.isHazard(c.offset(0, 1, 0));
    }

    /** Sum of {@link WorldView#dangerCost} over the original path waypoints in
     *  {@code (i, end]} — the cells the smoother would replace by collapsing the
     *  run to a straight line. Compared against {@link #straightLineDanger} so a
     *  bow A* made to avoid a hazard isn't straightened back through it. */
    public static double waypointDanger(WorldView w, List<BlockPos> path, int i, int end) {
        double sum = 0;
        for (int k = i + 1; k <= end; k++) sum += w.dangerCost(path.get(k));
        return sum;
    }

    /** Sum of {@link WorldView#dangerCost} over the cells the bot would actually
     *  enter walking the straight line {@code a}→{@code b} (same per-cell
     *  sampling as {@link #losWalkable}, excluding the start cell). */
    public static double straightLineDanger(WorldView w, BlockPos a, BlockPos b) {
        int steps = Math.max(Math.abs(b.getX() - a.getX()), Math.abs(b.getZ() - a.getZ()));
        if (steps == 0) return 0;
        double sum = 0;
        for (int s = 1; s <= steps; s++) {
            double t = (double) s / steps;
            int x = (int) Math.round(a.getX() + (b.getX() - a.getX()) * t);
            int y = (int) Math.round(a.getY() + (b.getY() - a.getY()) * t);
            int z = (int) Math.round(a.getZ() + (b.getZ() - a.getZ()) * t);
            sum += w.dangerCost(new BlockPos(x, y, z));
        }
        return sum;
    }

    /** An edge still needs work iff a block it must break is still solid, or a
     *  block it must place isn't solid yet. */
    public static boolean hasPendingEdge(WorldView w, Move.Edge e) {
        if (e == null) return false;
        for (BlockPos b : e.toBreak) if (w.isSolid(b)) return true;
        for (BlockPos b : e.toPlace) if (!w.isSolid(b)) return true;
        return false;
    }
}
