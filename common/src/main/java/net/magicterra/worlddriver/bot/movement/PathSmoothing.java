package net.magicterra.worlddriver.bot.movement;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.pathfinder.CostModifier;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.PathFinder;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;

import static net.magicterra.worlddriver.WorldDriverCommon.LOG;

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
        /** Public accessors for out-of-package callers (GameTest arenas). */
        public List<BlockPos> path() { return path; }
        public List<Move.Edge> edges() { return edges; }
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
        return stringPull(w, path, edges, List.of());
    }

    /**
     * Bias-aware overload: {@code bias} is the active per-intent
     * {@code SearchProfile.bias()} modifiers. Exactly like the dangerCost
     * rejection below, a bow A* paid extra bias cost to avoid (e.g. a
     * ShorelineHug tax bow hugging the waterline) must not be straightened back
     * through the taxed region — the straight line is rejected when its summed
     * bias cost exceeds the kept waypoints'. Modifiers are probed with a
     * synthetic flat "walk" edge and a null goal (every per-intent citizen —
     * route.regions/leash/yRange/hug — ignores both).
     */
    public static SmoothResult stringPull(WorldView w, List<BlockPos> path, List<Move.Edge> edges,
            List<CostModifier> bias) {
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
            // A surface swim keeps a node every few cells: the step pointer over water advances
            // only by passing nodes (the buoyant bot never closes the reach gate), so one long
            // pulled edge reads as a wedge a hundred ticks in and the crossing is churned.
            boolean afloat = w.isWater(path.get(i));
            while (j + 1 < path.size()
                    && plainFlatWalk(edges.get(j + 1))
                    && path.get(j + 1).getY() == path.get(i).getY()
                    && (!afloat || Math.max(Math.abs(path.get(j + 1).getX() - path.get(i).getX()),
                            Math.abs(path.get(j + 1).getZ() - path.get(i).getZ())) <= WalkerConstants.WATER_PULL_SPAN)
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
                        // player-width corridor probe — the ray-only guard is what made
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
            if ((BotConfig.avoidDanger
                    && straightLineDanger(w, path.get(i), path.get(j))
                       > waypointDanger(w, path, i, j) + 1e-6)
                    // Same rule for per-intent bias (ShorelineHug / AvoidRegion /
                    // PreferYBand / LeashAnchor): don't straighten a bow the
                    // planner paid bias cost to make.
                    || (!bias.isEmpty()
                        && straightLineBias(w, path.get(i), path.get(j), bias)
                           > waypointBias(w, path, i, j, bias) + 1e-6)) {
                for (int k = next; k <= j; k++) {
                    np.add(path.get(k));
                    ne.add(edges.get(k));
                }
            } else {
                auditEmit(w, path.get(i), path.get(j));
                np.add(path.get(j));
                ne.add(new Move.Edge(path.get(j), 0, List.of(), List.of(), "walk"));
            }
            i = j;
        }
        // ne.get(0) is the null start edge — List.copyOf rejects nulls, so use a
        // null-tolerant unmodifiable wrapper for the edges (path has no nulls).
        return new SmoothResult(List.copyOf(np), Collections.unmodifiableList(ne));
    }

    /**
     * Does the line this merge is about to emit actually pass the test that admitted it?
     *
     * <h2>Why this is a QUESTION and not a fix</h2>
     *
     * A rehearsal of rung 20 produced a smoothed segment {@code 98,49,0 → 95,49,0} whose middle two
     * cells ({@code 97,49,0}, {@code 96,49,0}) have nothing under them, and the bot began falling at
     * {@code 96,46,0}. The natural reading — "the string-pull only checked its endpoints" — is
     * <b>contradicted by this file</b>: the merge loop above extends {@code j} only while
     * {@link #losWalkable}{@code (w, path.get(i), path.get(j + 1))} holds, and {@code losWalkable}
     * samples EVERY cell and rejects one whose floor is not solid (and not water, and not climbable).
     * Applied to that span it must return false at {@code 97,49,0}. So the emitted segment cannot
     * have come from a merge this loop admitted — and yet the move counts ({@code stepUp} and
     * {@code parkour3} unchanged at 8 and 1, {@code walk} 38→7) are this loop's own signature.
     *
     * <p>Writing a "verify each cell" fix on top of a loop that already verifies each cell would add
     * a no-op and credit it with a pass. So this re-asks the question at the moment of emission and
     * records the answer instead:
     *
     * <ul>
     *   <li><b>It fires</b> ⇒ a segment left here that the admitting test rejects, so the merge
     *       reached the emit point by a path that skipped the test — the defect is in this loop's
     *       control flow, and the recorded span says which one.</li>
     *   <li><b>It never fires</b> ⇒ every segment this file emits is walkable, and the unwalkable one
     *       observed downstream was produced by something else between here and
     *       {@code adoptPath}'s assignment — the search moves to that stretch, not to smoothing.</li>
     * </ul>
     *
     * <p>Static because it is read from a scene through {@link #smoothingAudit()}: this is a pure
     * function with no instance to hang state on, and the alternative — threading a recorder through
     * a call the Walker makes on every adopt — would cost more than the question is worth.
     */
    private static void auditEmit(WorldView w, BlockPos a, BlockPos b) {
        emits++;
        if (losWalkable(w, a, b)) return;
        unwalkableEmits++;
        lastUnwalkableEmit = a.toShortString() + "→" + b.toShortString();
    }

    private static volatile int emits, unwalkableEmits;
    private static volatile String lastUnwalkableEmit;

    /** What {@link #auditEmit} has seen. A count of zero unwalkable emits is the load-bearing half:
     *  it says the smoother is not the source, which is the harder claim to establish. */
    public static String smoothingAudit() {
        return "segmentsEmitted=" + emits + " unwalkable=" + unwalkableEmits
                + " (last: " + (lastUnwalkableEmit == null ? "none" : lastUnwalkableEmit) + ")";
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

    /** Player-width-aware variant of {@link #losWalkable}: samples the interpolated
     *  CONTINUOUS line and requires foot+head passability for every cell the 0.6-wide
     *  hitbox overlaps (4-corner AABB probe, half-width 0.3), plus the centre cell's
     *  floor support. Catches the "ray threads a slit the bot can't" case (jungle
     *  trunks: the centre line stays in passable cells while the bot radius clips a
     *  trunk column — the walkerCarrotHColShrink A/B showed the far carrot bearing is
     *  the right detour, so the cure is to make the carrot's LOS honest about the player's
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

    /** Sum of the per-intent bias modifiers over the original waypoints in
     *  {@code (i, end]} — the bias twin of {@link #waypointDanger}. Probed with
     *  a synthetic flat "walk" edge and null goal (see the bias-aware
     *  {@code stringPull} overload's contract). */
    public static double waypointBias(WorldView w, List<BlockPos> path, int i, int end,
            List<CostModifier> bias) {
        double sum = 0;
        for (int k = i + 1; k <= end; k++) {
            BlockPos from = path.get(k - 1), to = path.get(k);
            Move.Edge probe = new Move.Edge(to, 0, List.of(), List.of(), "walk");
            for (CostModifier m : bias) sum += m.extraCost(from, to, probe, null, w);
        }
        return sum;
    }

    /** Sum of the per-intent bias modifiers over the straight-line cells
     *  {@code a}→{@code b} (same sampling as {@link #straightLineDanger}) — the
     *  bias twin used by the bias-aware collapse rejection. */
    public static double straightLineBias(WorldView w, BlockPos a, BlockPos b,
            List<CostModifier> bias) {
        int steps = Math.max(Math.abs(b.getX() - a.getX()), Math.abs(b.getZ() - a.getZ()));
        if (steps == 0) return 0;
        double sum = 0;
        BlockPos prev = a;
        for (int s = 1; s <= steps; s++) {
            double t = (double) s / steps;
            int x = (int) Math.round(a.getX() + (b.getX() - a.getX()) * t);
            int y = (int) Math.round(a.getY() + (b.getY() - a.getY()) * t);
            int z = (int) Math.round(a.getZ() + (b.getZ() - a.getZ()) * t);
            BlockPos c = new BlockPos(x, y, z);
            Move.Edge probe = new Move.Edge(c, 0, List.of(), List.of(), "walk");
            for (CostModifier m : bias) sum += m.extraCost(prev, c, probe, null, w);
            prev = c;
        }
        return sum;
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
    /** How many leading edges {@link #dropStalePrefix} re-evaluates: the part of a segment the bot
     *  executes before the next periodic search could correct it. */
    private static final int STALE_CHECK_EDGES = 12;

    /**
     * Cut a fresh search result at the first leading edge the CURRENT world no longer admits.
     *
     * <p>A sliced search runs for seconds of wall-clock while the bot keeps executing the plan
     * it already has, and inside rock that plan is digging. Live: the quick-start stub tunnelled
     * three cells forward while the 6000-node search ran; that search, launched from the old foot,
     * came back with a staircase whose first riser stood on the very cell the stub had just dug
     * out. The bot was walked back to a step with no floor under it, hopped, rammed, charged the
     * pocket with stuck penalties, and the next three searches each started one cell further back:
     * fifty seconds to climb eight blocks. Re-evaluating each leading edge with the move that made
     * it, against the world as it is now, is exactly the question the planner asked a few seconds
     * too early. An edge whose move cannot be found by name and delta is left alone, and water
     * moves are left alone too: their pricing depends on per-search state (escape origin, dive)
     * that a re-evaluation outside a search does not carry.
     *
     * @return the result unchanged, a shorter best-effort result, or an empty one when even the
     *         first edge is gone — the caller then keeps what it has and searches again from here.
     */
    public static PathFinder.Result dropStalePrefix(WorldView w, PathFinder.Result res) {
        List<BlockPos> path = res.path();
        List<Move.Edge> edges = res.edges();
        int lim = Math.min(path.size(), STALE_CHECK_EDGES + 1);
        for (int i = 1; i < lim; i++) {
            Move.Edge e = i < edges.size() ? edges.get(i) : null;
            if (e == null || e.move == null || w.isWater(path.get(i - 1))
                    || e.move.startsWith("swim") || e.move.startsWith("surface") || e.move.startsWith("fallWater")
                    || e.move.startsWith("waterBucket")) continue;
            BlockPos from = path.get(i - 1);
            Move m = null;
            for (Move cand : Move.ALL)
                if (e.move.equals(cand.name()) && cand.apply(from).equals(e.to)) { m = cand; break; }
            if (m == null || m.eval(w, from) != null) continue;
            if (BotConfig.walkerDebug)
                LOG.info("[walker] stale plan: edge {} {} {},{},{} → {},{},{} no longer admitted by the world → {}",
                        i, e.move, from.getX(), from.getY(), from.getZ(), e.to.getX(), e.to.getY(), e.to.getZ(),
                        i == 1 ? "discard" : "cut to " + i + " nodes");
            if (i == 1) return new PathFinder.Result(List.of(), List.of(), false, res.expanded(), res.ms(), res.finalCost());
            return new PathFinder.Result(List.copyOf(path.subList(0, i)),
                    Collections.unmodifiableList(new ArrayList<>(edges.subList(0, i))),
                    false, res.expanded(), res.ms(), res.finalCost());
        }
        return res;
    }

    /**
     * Is the bot BEYOND node {@code step} along the route, i.e. past it in the direction the
     * previous node approaches it from? The horizontal-distance tie-break in the step-pointer
     * re-sync cannot answer this for a next node stacked on the current one (same column), and a
     * bot 25 cells short of the node looks the same to it as one that drifted past. With no
     * previous node the answer is no.
     */
    public static boolean beyondNode(List<BlockPos> path, int step, LivingEntity p) {
        if (step <= 0 || step >= path.size()) return false;
        BlockPos w = path.get(step), pv = path.get(step - 1);
        double ax = w.getX() - pv.getX(), az = w.getZ() - pv.getZ();
        if (ax == 0 && az == 0) return false;
        return ax * (p.getX() - (w.getX() + 0.5)) + az * (p.getZ() - (w.getZ() + 0.5)) > 0;
    }

    public static boolean hasPendingEdge(WorldView w, Move.Edge e) {
        if (e == null) return false;
        for (BlockPos b : e.toBreak) if (w.isSolid(b)) return true;
        for (BlockPos b : e.toPlace) if (!w.isSolid(b)) return true;
        return false;
    }
}
