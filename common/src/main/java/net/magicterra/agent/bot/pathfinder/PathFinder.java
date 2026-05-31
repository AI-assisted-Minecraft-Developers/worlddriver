package net.magicterra.agent.bot.pathfinder;

import net.magicterra.agent.bot.Goal;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* over BlockPos with the {@link Move} catalog as the neighbor function.
 *
 * Single-threaded, single-shot: {@link #findPath} runs to completion. Caller
 * is responsible for off-thread invocation if the path may be large.
 * Termination conditions, in order:
 *   1. Goal reached → return optimal path.
 *   2. Node budget exhausted → return a best-effort segment (see below).
 *   3. Wall-clock budget exhausted → same as (2).
 *   4. Open set empty (unreachable) → same as (2), or empty if boxed in.
 *
 * Best-effort fallback matters because Minecraft worlds are mostly unreachable
 * in some direction (caves, walls, water) and we'd rather make progress than
 * stand still. The fallback uses Baritone's <b>incremental cost backoff</b>
 * rather than naively returning the single lowest-h node: a node that shaves a
 * hair off the heuristic by wandering far away is a poor segment to commit to,
 * so we track the best node under several g-vs-h weightings (COEFFICIENTS) and
 * commit to the most conservative one that still travels at least
 * {@link #MIN_DIST_PATH} blocks from the start. Repeated {@code findPath} calls
 * from successive segment ends give Baritone-style long-distance splicing for
 * free.
 */
public final class PathFinder {

    public static final int DEFAULT_MAX_NODES = 100_000;
    public static final long DEFAULT_MAX_MS = 1500;

    /**
     * Incremental cost backoff weightings (Baritone's {@code COEFFICIENTS}).
     * For coefficient {@code c} we track the node minimizing {@code h + g/c}:
     * a small c weights travelled cost heavily (prefer staying near the start,
     * a safe commit), a large c almost ignores it (prefer the node closest to
     * the goal, an aggressive commit). On early exit we pick the smallest-c
     * (most conservative) candidate that still made real progress.
     */
    private static final double[] COEFFICIENTS = {1.5, 2, 2.5, 3, 4, 5, 10};

    /** Min straight-line distance (blocks) a best-effort segment must cover to
     *  be worth committing to — Baritone's {@code MIN_DIST_PATH}. Shorter
     *  candidates are treated as "no real progress" and rejected. */
    public static final double MIN_DIST_PATH = 5;

    /** A repropagated route must beat the incumbent g by more than this to be
     *  accepted — Baritone's minimum-improvement repropagation (0.01 ticks ≈
     *  0.1 cost units here). Re-opening a closed node to save a sliver of cost
     *  costs more CPU in repropagation than the path-time it buys. */
    private static final double MIN_IMPROVEMENT = 0.1;

    private final WorldView world;
    private final int maxNodes;
    private final long maxMs;

    /** Default ctor reads live tunables from {@link net.magicterra.agent.bot.BotConfig}
     *  so {@code mc.bot.setting{pathfinder.maxNodes:...}} can resize the budget
     *  without restarting the JVM. */
    public PathFinder(WorldView world) {
        this(world,
                net.magicterra.agent.bot.BotConfig.pathfinderMaxNodes,
                net.magicterra.agent.bot.BotConfig.pathfinderMaxMs);
    }
    public PathFinder(WorldView world, int maxNodes, long maxMs) {
        this.world = world;
        this.maxNodes = maxNodes;
        this.maxMs = maxMs;
    }

    /** Run a search to completion in one call (synchronous). Kept for callers
     *  that don't need to time-slice; the Walker uses {@link #newSearch} +
     *  {@link Search#advance} to spread a big search across client ticks so it
     *  never blocks the render thread for more than one slice. */
    public Result findPath(BlockPos start, Goal goal) {
        Search s = newSearch(start, goal);
        s.advance(Long.MAX_VALUE);   // no per-slice cap → runs until done
        return s.result();
    }

    public Search newSearch(BlockPos start, Goal goal) {
        return new Search(start, goal);
    }

    /**
     * A resumable A* search. {@link #advance(long)} does as much work as fits in
     * a per-call wall-clock slice and returns whether the search is done; the
     * total work is still bounded by {@code maxNodes} / {@code maxMs} (cumulative
     * compute, summed across slices). The algorithm and result are identical to
     * a one-shot run — only the time is chunked — so paths and the best-effort
     * backoff segment are unchanged; only the render-thread hitch is removed.
     */
    public final class Search {
        private final BlockPos start;
        private final Goal goal;
        private final Map<BlockPos, Node> nodes = new HashMap<>();
        private final PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        private final double[] bestHeuristic = new double[COEFFICIENTS.length];
        private final Node[] bestSoFar = new Node[COEFFICIENTS.length];
        private final Node startNode;
        private int expanded;
        private long elapsedNanos;     // cumulative compute time across slices
        private Result result;         // null until done

        private Search(BlockPos start, Goal goal) {
            this.start = start;
            this.goal = goal;
            world.beginSearch();       // snapshot per-search state (e.g. nearby mobs)
            this.startNode = new Node(start, null, null, 0, goal.estimate(start));
            nodes.put(start, startNode);
            open.add(startNode);
            java.util.Arrays.fill(bestHeuristic, Double.POSITIVE_INFINITY);
        }

        public boolean done() { return result != null; }
        public Result result() { return result; }
        public int expanded() { return expanded; }

        /** Expand nodes until {@code sliceMs} of wall-clock elapses this call (or
         *  the search finishes / hits its total budget). Returns true once done;
         *  the {@link Result} is then available from {@link #result()}. */
        public boolean advance(long sliceMs) {
            if (result != null) return true;
            long sliceStart = System.nanoTime();
            long sliceLimit = (sliceMs >= Long.MAX_VALUE / 2) ? Long.MAX_VALUE : sliceMs * 1_000_000L;
            int sinceCheck = 0;
            try {
                while (!open.isEmpty()) {
                    if (++sinceCheck >= 128) {           // amortise nanoTime() calls; bounds overshoot
                        sinceCheck = 0;
                        if (System.nanoTime() - sliceStart >= sliceLimit) return false;  // pause, resume next call
                    }
                    Node cur = open.poll();
                    if (cur.closed) continue;
                    cur.closed = true;
                    expanded++;

                    if (goal.reached(cur.pos)) {
                        result = build(cur, true, expanded, totalMs(sliceStart), cur.g);
                        return true;
                    }
                    for (int i = 0; i < COEFFICIENTS.length; i++) {
                        double weighted = cur.h + cur.g / COEFFICIENTS[i];
                        if (weighted < bestHeuristic[i]) {
                            bestHeuristic[i] = weighted;
                            bestSoFar[i] = cur;
                        }
                    }

                    if (expanded >= maxNodes) break;
                    if (totalMs(sliceStart) > maxMs) break;

                    for (Move m : Move.ALL) {
                        // eval() → null for an inadmissible move, else a concrete
                        // edge (dynamic cost + any break/place actions).
                        Move.Edge edge = m.eval(world, cur.pos);
                        if (edge == null) continue;
                        BlockPos npos = edge.to;
                        // Soft danger penalty per entered cell (Baritone avoidance);
                        // ≥ 0 so the heuristic stays admissible.
                        double ng = cur.g + edge.cost + world.dangerCost(npos);
                        Node existing = nodes.get(npos);
                        if (existing != null && ng > existing.g - MIN_IMPROVEMENT) continue;
                        if (existing == null) {
                            Node next = new Node(npos, cur, edge, ng, goal.estimate(npos));
                            nodes.put(npos, next);
                            open.add(next);
                        } else {
                            existing.parent = cur;
                            existing.edge = edge;
                            existing.g = ng;
                            existing.f = ng + existing.h;
                            existing.closed = false;
                            open.add(existing);
                        }
                    }
                }
                // open empty / node budget / time budget → commit best-effort segment
                Node segment = selectSegment(bestSoFar, start);
                result = (segment == null)
                        ? new Result(List.of(), List.of(), false, expanded, totalMs(sliceStart), startNode.h)
                        : build(segment, false, expanded, totalMs(sliceStart), segment.g);
                return true;
            } finally {
                elapsedNanos += System.nanoTime() - sliceStart;
            }
        }

        private long totalMs(long sliceStart) {
            return (elapsedNanos + (System.nanoTime() - sliceStart)) / 1_000_000L;
        }
    }

    /**
     * Incremental cost backoff selection: walk the coefficients from most
     * conservative (smallest, weights travelled cost heavily) to most
     * aggressive, returning the first candidate that travelled at least
     * {@link #MIN_DIST_PATH} blocks from the start. Returns null when no
     * candidate made real progress (we're boxed in) — caller treats that as
     * "no path" rather than committing to a segment that goes nowhere.
     */
    private static Node selectSegment(Node[] bestSoFar, BlockPos start) {
        double minSq = MIN_DIST_PATH * MIN_DIST_PATH;
        for (Node n : bestSoFar) {
            if (n == null) continue;
            if (n.pos.distSqr(start) > minSq) return n;
        }
        return null;
    }

    /**
     * Rebuild the foot-position sequence and the parallel per-step edge list by
     * walking parent pointers from the end node back to the start. {@code edges}
     * is aligned with {@code path}: {@code edges.get(i)} is the edge that enters
     * {@code path.get(i)} (so {@code edges.get(0)} is null — the start has no
     * incoming edge). The Walker consults each edge's break/place actions before
     * stepping into the cell.
     */
    private static Result build(Node end, boolean reached, int expanded, long ms, double cost) {
        List<BlockPos> pos = new ArrayList<>();
        List<Move.Edge> eds = new ArrayList<>();
        for (Node n = end; n != null; n = n.parent) { pos.add(n.pos); eds.add(n.edge); }
        Collections.reverse(pos);
        Collections.reverse(eds);
        return new Result(List.copyOf(pos), Collections.unmodifiableList(eds), reached, expanded, ms, cost);
    }

    /**
     * Result of a path search. {@code path} is the foot-position sequence from
     * start (inclusive) to the goal or to the best-effort fallback (inclusive).
     * {@code edges} is aligned with {@code path} — {@code edges.get(i)} carries
     * the cost and any blocks to break/place to enter {@code path.get(i)};
     * {@code edges.get(0)} is null. If unreachable, both are empty.
     */
    public record Result(List<BlockPos> path, List<Move.Edge> edges, boolean goalReached, int expanded, long ms, double finalCost) {
        /**
         * True when the result contains a walkable path (which may be the
         * best-effort fallback, not a path that actually reaches the goal).
         * For "did we reach the goal" use {@link #goalReached()} directly.
         */
        public boolean hasPath() { return !path.isEmpty(); }
    }

    private static final class Node {
        final BlockPos pos;
        Node parent;
        Move.Edge edge;     // the edge used to reach this node (null for start)
        double g, h, f;
        boolean closed;
        Node(BlockPos pos, Node parent, Move.Edge edge, double g, double h) {
            this.pos = pos;
            this.parent = parent;
            this.edge = edge;
            this.g = g;
            this.h = h;
            this.f = g + h;
        }
    }
}
