package net.magicterra.agent.bot.pathfinder;

import net.magicterra.agent.bot.Goal;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import net.magicterra.agent.bot.BotConfig;
import java.util.Arrays;

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

    /** Minimum heuristic reduction (cost units ≈ 5 blocks toward the goal) a
     *  loaded-chunk frontier node must offer over the start before a frontier
     *  segment is committed — so a sideways/backward chunk edge never pulls the
     *  bot off course (see {@link BotConfig#pathfinderFrontierCommit}). */
    private static final double MIN_FRONTIER_GAIN = 50;

    /** Min height (blocks) a vertical-escape segment must climb above the start to
     *  be committed when the search is BOXED (no horizontal forward progress) — the
     *  user-chosen "pillar-up / dig-up over the obstacle" escape from a local-minimum
     *  pinch (bot wedged at the foot of a tall cliff, conservative selectSegment
     *  returns null and it deadlocks). Pure up + forward, so no backtrack/oscillation;
     *  height is monotone across the re-plan chain. */
    private static final int MIN_CLIMB_ESCAPE = 2;

    /** g-cost ceiling for a water-start climb-out to count as CHEAP (dig-free):
     *  swimming runs ~10-30 cost/block so 3000 covers a ~100-block swim-and-walk
     *  to shore, while one submerged hard-stone dig alone prices ~7500 (5×
     *  underwater multiplier). Cheap ashore commits immediately; an expensive one
     *  defers to the surfacing bestClimb / relaxed tier (see chooseSegment). A
     *  couple of soft submerged digs (dirt ≈750 each) still pass as cheap — mud
     *  banks are common and fast to punch through. */
    private static final double ASHORE_CHEAP_G = 3000;

    /** A repropagated route must beat the incumbent g by more than this to be
     *  accepted — Baritone's minimum-improvement repropagation (0.01 ticks ≈
     *  0.1 cost units here). Re-opening a closed node to save a sliver of cost
     *  costs more CPU in repropagation than the path-time it buys. */
    private static final double MIN_IMPROVEMENT = 0.1;

    /** How many node expansions between wall-clock checks in a time-sliced search.
     *  Small enough that a slice can't overshoot {@code sliceMs} by much even when
     *  each expansion is doing slow world/chunk access, large enough that the
     *  {@code nanoTime()} cost stays negligible (~30ns × this ÷ work). */
    private static final int TIME_CHECK_INTERVAL = 16;

    private final WorldView world;
    private final int maxNodes;
    private final long maxMs;

    /** Default ctor reads live tunables from {@link net.magicterra.agent.bot.BotConfig}
     *  so {@code mc.bot.setting{pathfinder.maxNodes:...}} can resize the budget
     *  without restarting the JVM. */
    public PathFinder(WorldView world) {
        this(world,
                BotConfig.pathfinderMaxNodes,
                BotConfig.pathfinderMaxMs);
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
        return new Search(start, goal, false);
    }

    /** Search variant that DROPS all block-placing moves (bridge/pillar/parkour-place)
     *  when {@code suppressPlace} is true — used by the Walker to re-plan a route the
     *  bot can afford (dig through / go around) after a normal search returned a path
     *  needing more placed blocks than the inventory holds. */
    public Search newSearch(BlockPos start, Goal goal, boolean suppressPlace) {
        return new Search(start, goal, suppressPlace);
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
        // Total-order comparator: equal-f ties broken by a STABLE key (packed block
        // position). Open water (and any flat region) produces vast plateaus of
        // equal-f nodes; comparing on f alone leaves their pop order to PriorityQueue
        // heap internals — perturbed by the lazy in-place f-mutation re-add below — so
        // the same start explored a different node count and committed a different
        // equal-cost path run-to-run (buoyantWallArena: expanded 175 vs 85, swimUp@z61
        // vs walk@z60 from an identical start → the flaky bobTicks). A deterministic
        // tie-break makes the whole search reproducible; the chosen path is identical
        // in cost, just canonical.
        private final PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> {
            int c = Double.compare(a.f, b.f);
            return c != 0 ? c : Long.compare(a.pos.asLong(), b.pos.asLong());
        });
        private final double[] bestHeuristic = new double[COEFFICIENTS.length];
        private final Node[] bestSoFar = new Node[COEFFICIENTS.length];
        /** Reachable node nearest the goal (min raw h) that borders an unloaded
         *  chunk — the goal-ward edge of known terrain. Drives segmented planning
         *  to the loaded-chunk frontier (see {@link BotConfig#pathfinderFrontierCommit}). */
        private Node bestFrontier;
        private double bestFrontierH = Double.POSITIVE_INFINITY;
        // Water-escape best-effort: when the search STARTS in water, track the
        // reachable dry-ground (ashore) node nearest the goal, so an unreachable
        // goal commits to climbing ASHORE rather than a fake in-water segment.
        private Node bestAshore;
        private double bestAshoreH = Double.POSITIVE_INFINITY;
        // Vertical-escape best-effort: when boxed at the foot of a tall obstacle, the
        // node that climbed ABOVE the start and got nearest the goal (pillar-up / dig-up).
        // Committed by chooseSegment only when selectSegment is null (no horizontal escape),
        // so the bot scales the obstacle instead of deadlocking — see MIN_CLIMB_ESCAPE.
        private Node bestClimb;
        private double bestClimbScore = Double.POSITIVE_INFINITY;
        // Last-resort escape: the expanded node FARTHEST from the start, regardless
        // of goal direction. Committed only when every other selector is null at the
        // hard cap — a pocket whose free exits all head AWAY from the goal (cave
        // network behind the bot, goal walled off) otherwise returns "no path"
        // even though the terrain is escapable (live 2026-06-09: 60k nodes spread
        // across a NE cave web, goal SW, hDelta=0 → goto FAILED in a cave pocket).
        private Node bestEscape;
        private double bestEscapeD2;
        private final boolean startInWater;
        private final Node startNode;
        /** Move-set pruned to the catalog entries that can fire under this search's
         *  world/config constants (see {@link Move#availableInSearch}). Built once
         *  here so the per-node neighbour loop skips, e.g., all ~68 WaterBucketFall
         *  variants for a bucketless bot and the Parkour4 tier when it's off —
         *  ~100/259 fewer dispatch-and-reject per expansion in the default config. */
        private final Move[] activeMoves;
        /** Obstacle-aware goal-distance field, or null when disabled / unusable
         *  (then the heuristic is the plain Euclidean {@link Goal#estimate}). */
        private final CoarseGoalField goalField;
        private int expanded;
        private long elapsedNanos;     // cumulative compute time across slices
        private Result result;         // null until done

        private Search(BlockPos start, Goal goal, boolean suppressPlace) {
            this.start = start;
            this.startInWater = world.isWater(start);
            this.goal = goal;
            world.beginSearch();       // snapshot per-search state (e.g. nearby mobs)
            // Prune the move catalog to this search's relevant subset (after
            // beginSearch so bucket/flag snapshots are live). One pass over ALL.
            // suppressPlace additionally drops every block-placing move so an
            // out-of-blocks re-plan digs/routes around instead of bridging.
            List<Move> active = new ArrayList<>(Move.ALL.size());
            for (Move m : Move.ALL) {
                if (!m.availableInSearch(world)) continue;
                if (suppressPlace && m.placesBlock()) continue;
                active.add(m);
            }
            this.activeMoves = active.toArray(new Move[0]);
            // Build the obstacle-aware heuristic field once per search (before any
            // node is created so the start node gets the field estimate too).
            this.goalField = BotConfig.pathfinderGoalField
                    ? CoarseGoalField.build(world, goal, start) : null;
            this.startNode = new Node(start, null, null, 0, heuristic(start));
            nodes.put(start, startNode);
            open.add(startNode);
            PathTraceHolder.SINK.onSearchBegin(start, goal);
            Arrays.fill(bestHeuristic, Double.POSITIVE_INFINITY);
        }

        public boolean done() { return result != null; }
        public Result result() { return result; }
        public int expanded() { return expanded; }

        /** A* heuristic for a node: the obstacle-aware goal-field estimate when
         *  available (max'd with the admissible Euclidean lower bound so it never
         *  drops below it), else the plain Euclidean estimate. Falls back per-cell,
         *  so an unbuilt or partial field simply yields today's behaviour. */
        private double heuristic(BlockPos p) {
            double h = goal.estimate(p);
            if (goalField != null) {
                double field = goalField.costToGoal(p);
                if (field != CoarseGoalField.UNKNOWN) h = Math.max(h, field);
            }
            // Anti-basin-dive: an XZ goal's estimate ignores Y, so descending reads
            // as free progress and the search dives into a dead-end low valley. Charge
            // descent below this search's start (asymmetric — climbing stays free).
            if (BotConfig.pfDepthPenalty() > 0) {
                int below = start.getY() - BotConfig.pathfinderDepthSlack - p.getY();
                if (below > 0) h += BotConfig.pfDepthPenalty() * below;
            }
            return h;
        }

        /** REAL edge cost (added to g, not h) for descending IN WATER or by BREAKING
         *  a block, below {@code startY − slack}. This is what actually makes a
         *  dive-and-tunnel path cost more than a climb-ashore one — a heuristic bias
         *  can only reorder the search, never change which reachable path is cheapest,
         *  so an XZ goal at a sheer-walled water pit still drilled underground / bobbed
         *  until the descent paid its true cost. GATED to watery-or-breaking descents:
         *  a dry stepped descent over solid ground (StepDown/Fall/DiagonalDescend, no
         *  block broken, not in water) is a legitimate downhill walk and pays nothing,
         *  so normal terrain pathing is byte-for-byte unchanged. Only the portion of
         *  the step below the slack threshold is charged, and only when going down. */
        /** True when the goal converges on an UNDERWATER target — a deliberate dive
         *  (seabed monument / shipwreck). The buoyancy water-taxes (descend / per-cell /
         *  submerged / climb-out) are suppressed for such a goal so the intended descent
         *  isn't fought. For EVERY other goal — XZ columns AND land-target Block/Near
         *  (a {@code goto pos} to dry land, where rivers/lakes are transient obstacles) —
         *  the taxes apply. The old {@code !ignoresY()} gate wrongly disabled them for
         *  ALL Y-aware goals, so a normal {@code goto pos} land journey got NO water
         *  modelling: A* freely routed the buoyant bot to walk/stepDown onto submerged
         *  riverbed cells it floats above and can't follow → the live deep-water churn
         *  (replay: 100% inWater stepDown stalls, 6-10 s each, 57 repaths). */
        private boolean diveGoal() {
            BlockPos t = goal.targetPos();
            return t != null && world.isWater(t);
        }

        private double descendTax(BlockPos from, BlockPos to, Move.Edge edge) {
            double per = BotConfig.pathfinderDescendCost;
            if (per <= 0 || to.getY() >= from.getY()) return 0;        // off, or not descending
            // ONLY for Y-agnostic (XZ) goals: those let descent read as free progress
            // (the root cause). A goal that knows its target Y (pos/block — a seabed
            // monument, shipwreck) guides a genuine dive correctly and must not be
            // taxed, so deep-water exploration / ocean-monument runs are unaffected.
            // (Land-target submerged routing is handled by submergedTax, not here, to
            // keep the deep-water climb-out arenas' descent behaviour unchanged.)
            if (!goal.ignoresY()) return 0;
            // Any water-involved descent (SwimDown into the depths OR a Fall/MLG INTO
            // water) or a block-breaking descent. Including isWater(to) is needed: at a
            // water bowl the cheapest dive uses fall-INTO-water rungs that an
            // isWater(from)-only test misses, leaving A* a tax-free dive-and-tunnel
            // back-door (verified: cost flat across descendCost 40→120, end stayed y50).
            // This does NOT over-tax ocean diving: the whole method is gated above to
            // Y-agnostic XZ goals, and a seabed dive uses a Y-aware pos/block goal.
            boolean watery = world.isWater(from) || world.isWater(to);
            boolean breaks = !edge.toBreak.isEmpty();
            if (!watery && !breaks) return 0;                          // dry stepped descent over solid ground — free
            // A search that STARTS in water gets NO slack: the bot floats at the
            // surface, so ANY planned descent fights buoyancy the executor cannot
            // deliver (live wedge 2026-06-09: from a river surface A* committed
            // fallWater4→seabed-walk under a sheer bank — slack let the first 4-block
            // dive go untaxed, the floating bot could never follow the path down, and
            // every wedge-repath recommitted the same dive → permanent pin). A DRY
            // start keeps the slack so a normal downhill into a stream stays free.
            int slack = world.isWater(start) ? 0 : BotConfig.pathfinderDepthSlack;
            int threshold = start.getY() - slack;
            int hiY = Math.min(from.getY(), threshold);
            int loY = Math.min(to.getY(), threshold);
            int belowDrop = hiY - loY;                                 // descent of THIS edge below threshold
            return belowDrop > 0 ? per * belowDrop : 0;
        }

        /** Per-water-cell tax for EVERY move entering a water cell, Y-agnostic (XZ)
         *  goals only. An XZ goal makes swimming at depth read as free progress, so A*
         *  threads long underwater corridors / dives back into water it just climbed
         *  out of, oscillating against the executor's climb-out (live round70/71). A
         *  PER-CELL tax (a one-time entry tax a bot already in the water never pays)
         *  makes a long water route cost ∝ its length, tipping A* onto an available
         *  LAND route even from a submerged start (verified via mc.debug.plan: from an
         *  in-water cave it routes UP to dry y84 land). A shorter / sole water crossing
         *  is still taken; Y-aware pos/block goals are exempt exactly like descendTax. */
        private double waterCellTax(BlockPos to) {
            double tax = BotConfig.pathfinderWaterCellCost;
            if (tax <= 0 || !goal.ignoresY() || !world.isWater(to)) return 0;
            // A SUBMERGED cell (water directly above → a surface bot must dive under to
            // thread it) costs extra, so A* keeps the route ON THE SURFACE instead of
            // dropping onto the seabed / a seagrass corridor it can't climb out of
            // (live round75 80 s "未能上浮" death-lock). A surface cell (air overhead)
            // pays only the base tax — an ordinary surface crossing is unchanged.
            // ...and so is a CAPPED cell: a surface water cell under a SOLID overhang
            // (rock/dirt ceiling at head+1, i.e. foot+2) is a submerged CHAMBER / tunnel
            // the bot can't cruise at an open top — it must thread under the shelf. With
            // only the water-above test, an under-shelf surface cell read as cheap open
            // water (base tax), so A* routed an XZ goal straight THROUGH the canyon
            // pocket (2358,1863) (water y58-62 under a y64 rock shelf) and the buoyant
            // bot churned / swam-ashore for minutes instead of routing AROUND it — the
            // one clean arrival went around the overhang via the NE bank. Price a capped
            // cell like submerged so A* prefers open water / a land detour. Open surface
            // water (sky above) and the Goal.Block water arenas (foot+2 clear) are
            // unchanged.
            boolean submerged = world.isWater(to.offset(0, 1, 0));
            boolean cappedChamber = !submerged && world.isSolid(to.offset(0, 2, 0));
            double overheadExtra = (BotConfig.pathfinderSubmergedWaterCost > 0
                    && (submerged || cappedChamber))
                    ? BotConfig.pathfinderSubmergedWaterCost : 0;
            return tax + overheadExtra;
        }

        /** Per-RISE tax on an edge that CLIMBS OUT of water onto a higher bank
         *  ({@code from} in water, {@code to} dry and above). The entry/descend water
         *  taxes never see this edge (its {@code to} is dry and the move rises), so
         *  without it A* freely picks a TALL near-bank exit a buoyant bot can't step onto
         *  — every such exit forces the Walker's bob-stuttery bank-dig climb-out (live
         *  "卡在土墙 / 反复挖同一土块 / 横跳" windows). Pricing the exit ∝ its rise tips A* onto the
         *  LOWEST available exit (a surface-level bank = rise 0 = free Walk), without
         *  forbidding a tall one when that's all the shoreline offers. XZ goals ONLY: for a
         *  Y-aware land goal it BACKFIRED live (replay A/B) — pricing the climb-OUT makes A*
         *  keep the bot IN the water to dodge the tax, which REVIVES the submerged-descent
         *  churn {@link #submergedTax} just removed (z1957 cluster 0→303 ticks, total stall
         *  14s→37s). So land-goal water exits are governed by submergedTax (stay on the
         *  surface), not by taxing the exit. */
        private double climbOutTax(BlockPos from, BlockPos to) {
            double per = BotConfig.pathfinderWaterClimbOutCost;
            if (per <= 0 || !goal.ignoresY()) return 0;
            if (!world.isWater(from) || world.isWater(to)) return 0;   // only water → dry
            int rise = to.getY() - from.getY();
            return rise > 0 ? per * rise : 0;                          // surface-level/down exits free
        }

        /** Penalty for DESCENDING into a SUBMERGED water cell (water directly above) on the
         *  way to a LAND-target goal. A buoyant bot floats at ~surface+0.4 and can't follow
         *  a plan DOWN into deep water, but {@link WorldView#canStandAt} treats ANY water
         *  cell as a floor, so A* otherwise routes stepDown/diagDown nodes onto the
         *  submerged riverbed the floating bot can't reach → the live deep-water churn
         *  (replay of a single river crossing: 100% inWater, stepDown 173/221 ticks +
         *  diagDown, 6-10 s stalls, 57 repaths). Taxing the DESCENT into submerged water
         *  keeps the route ON THE SURFACE where the bot can swim. Restricted to a DESCENT
         *  ({@code to.y < from.y}) so a bot rising/traversing OUT of a deep pocket — e.g.
         *  the pillar/dig climb-out arenas, which start submerged and ascend — is untouched.
         *  Scoped to land-target Y-aware goals: XZ goals already price submerged cells via
         *  {@link #waterCellTax}; a deliberate dive to an UNDERWATER target ({@link #diveGoal})
         *  is exempt. Reuses {@link BotConfig#pathfinderSubmergedWaterCost}. */
        private double submergedTax(BlockPos from, BlockPos to) {
            double tax = BotConfig.pathfinderSubmergedWaterCost;
            if (tax <= 0 || goal.ignoresY() || diveGoal()) return 0;
            if (to.getY() >= from.getY() || !world.isWater(to)) return 0;   // only DESCENDING into water
            return world.isWater(to.offset(0, 1, 0)) ? tax : 0;            // ...that is SUBMERGED
        }

        /** Expand nodes until {@code sliceMs} of wall-clock elapses this call (or
         *  the search finishes / hits its total budget). Returns true once done;
         *  the {@link Result} is then available from {@link #result()}. */
        public boolean advance(long sliceMs) {
            if (result != null) return true;
            long sliceStart = System.nanoTime();
            long sliceLimit = (sliceMs >= Long.MAX_VALUE / 2) ? Long.MAX_VALUE : sliceMs * 1_000_000L;
            int sinceCheck = 0;
            // Cache is LIVE only while this slice expands nodes (static-world memoise);
            // cleared off in finally so the Walker's between-slice reads stay fresh.
            world.cacheActive(true);
            try {
                while (!open.isEmpty()) {
                    // Check the clock every TIME_CHECK_INTERVAL expansions, not every 128:
                    // each expansion evaluates all moves with world/chunk lookups (costly at
                    // high render distance), so a 128-expansion gap let a slice overshoot the
                    // cap by ~200ms (a visible hitch). nanoTime() is ~30ns so a 16-wide gap is
                    // still <0.2ms total over a 100k-node search while bounding overshoot ~8x
                    // tighter, keeping each slice near sliceMs.
                    if (++sinceCheck >= TIME_CHECK_INTERVAL) {
                        sinceCheck = 0;
                        if (System.nanoTime() - sliceStart >= sliceLimit) return false;  // pause, resume next call
                    }
                    Node cur = open.poll();
                    if (cur.closed) continue;
                    cur.closed = true;
                    expanded++;
                    PathTraceHolder.SINK.onNodeExpanded(cur.pos, cur.g);

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
                    // Loaded-chunk frontier: a reachable node bordering an unloaded
                    // chunk is the edge of known terrain. Track the one nearest the
                    // goal (min raw h) so a horizon-truncated search can commit toward
                    // it (segmented planning, BotConfig.pathfinderFrontierCommit).
                    if (BotConfig.pathfinderFrontierCommit && !startInWater
                            && cur.h < bestFrontierH && bordersUnknown(cur.pos)) {
                        bestFrontierH = cur.h;
                        bestFrontier = cur;
                        // EARLY STOP: A* pops nodes by f, so the first expanded frontier
                        // node that makes real progress toward the goal IS the optimal path
                        // to the loaded-chunk edge. Commit it now and stop the search —
                        // "plan only as far as loaded chunks reach, walk there, load more,
                        // replan" — instead of burning the whole budget grinding toward an
                        // out-of-render goal it can never reach this search. Gated like the
                        // chooseSegment frontier branch (real goal-ward gain + min distance)
                        // so a sideways/backward chunk edge can't trigger it. (Water starts
                        // are excluded above so the bestAshore climb-out wins there.)
                        if (cur.h < startNode.h - MIN_FRONTIER_GAIN
                                && cur.pos.distSqr(start) > (long) MIN_DIST_PATH * MIN_DIST_PATH) {
                            result = build(cur, false, expanded, totalMs(sliceStart), cur.g);
                            return true;
                        }
                    }
                    // Receding-horizon early-stop (BotConfig.pathfinderHorizonBlocks):
                    // generalises the loaded-chunk frontier commit above to ANY terrain.
                    // The frontier branch only fires at an UNLOADED-chunk edge, so a far
                    // goal in fully-loaded terrain grinds the whole node budget for a tiny
                    // best-effort segment (the long-haul freeze). Here, the instant A* pops
                    // a node that has advanced >= horizon blocks toward the goal (h dropped
                    // by horizon*10 cost units; A* pops by f so this node is ~optimal to the
                    // horizon), commit it and STOP — an unbounded far grind becomes a cheap
                    // fixed-length forward hop the bot walks while the next search runs.
                    // Self-disables near the goal (h can't drop that far → search reaches the
                    // real goal). Only real goal-ward progress, so a pinch/wall (no forward
                    // node) falls through to the unchanged best-effort backoff. Water starts
                    // excluded (bestAshore climb-out wins).
                    int horizonBlocks = BotConfig.pfHorizonBlocks();
                    if (horizonBlocks > 0
                            && cur.h < startNode.h - 10.0 * horizonBlocks
                            && cur.pos.distSqr(start) > (long) MIN_DIST_PATH * MIN_DIST_PATH
                            // A water start may ALSO horizon-commit, but only onto dry
                            // land or a SURFACE cell (water foot, air head) — never a
                            // submerged node, so the climb-out triage below still owns
                            // those. Without this, an open-sea leg burned the full 60k
                            // nodes / ~22 s per repath for pathLen=0 (the shore lies
                            // beyond any budget, so bestAshore stays null) while 600-node
                            // quick-start micro-segments carried the actual swimming
                            // (round43: three consecutive 60k/22s/pathLen=0 repaths
                            // during a perfectly healthy 2 b/s surface cruise).
                            && (!startInWater
                                || isAshore(cur.pos)
                                || (world.isWater(cur.pos)
                                    && !world.isWater(cur.pos.offset(0, 1, 0))))) {
                        result = build(cur, false, expanded, totalMs(sliceStart), cur.g);
                        return true;
                    }
                    // Water escape: track the reachable ASHORE node (dry ground) closest
                    // to the goal, for the best-effort commit when the goal isn't reached.
                    if (startInWater && cur.h < bestAshoreH && isAshore(cur.pos)) {
                        bestAshoreH = cur.h;
                        bestAshore = cur;
                    }
                    // Vertical escape: track the best node reached by climbing ABOVE the
                    // start (pillar-up / dig-up). Score = h biased slightly toward greater
                    // height, so it prefers a node that climbed AND advanced toward the goal
                    // (lower h); on an h-tie (a straight-up pillar leaves the XZ estimate
                    // unchanged) it prefers the highest rung, gaining the most vantage. Only
                    // for Y-agnostic XZ goals — there "over the obstacle toward the column"
                    // is the intent; a Y-aware goal guides height via its own 3D heuristic.
                    // Inert unless the bot can place/break (else no climbed node exists), so
                    // the headless GameTest view (canPlace=false, breakCost=∞) never trips it.
                    if (goal.ignoresY() && cur.pos.getY() > start.getY()) {
                        double climbScore = cur.h - 0.01 * (cur.pos.getY() - start.getY());
                        if (climbScore < bestClimbScore) {
                            bestClimbScore = climbScore;
                            bestClimb = cur;
                        }
                    }
                    // Track the farthest reachable node for the last-resort escape
                    // commit (see chooseSegment) — direction-agnostic on purpose.
                    double escD2 = cur.pos.distSqr(start);
                    if (escD2 > bestEscapeD2) {
                        bestEscapeD2 = escD2;
                        bestEscape = cur;
                    }

                    if (expanded >= maxNodes) break;
                    if (totalMs(sliceStart) > maxMs) break;
                    // Soft commit (BotConfig.pathfinderSoftCommitNodes): the horizon
                    // early-stop above only fires on real forward progress; when the bot is
                    // BOXED at an obstacle no such node appears and the search would grind the
                    // whole hard maxNodes budget (~3.4 s) for a short best-effort segment — a
                    // multi-second freeze at every cliff/wall. Once the soft budget is spent
                    // AND a committable best-effort segment already exists, stop and commit it
                    // now. The hard maxNodes still governs the "no segment yet" case (a deep
                    // pinch still hunting its first viable move / vertical escape), so hard
                    // reachability is unchanged.
                    if (BotConfig.pfSoftCommitNodes() > 0
                            && expanded >= BotConfig.pfSoftCommitNodes()
                            && hasCommittableSegment()) break;

                    for (Move m : activeMoves) {
                        // eval() → null for an inadmissible move, else a concrete
                        // edge (dynamic cost + any break/place actions).
                        Move.Edge edge = m.eval(world, cur.pos);
                        if (edge == null) continue;
                        BlockPos npos = edge.to;
                        // Soft danger penalty per entered cell (Baritone avoidance);
                        // ≥ 0 so the heuristic stays admissible.
                        double ng = cur.g + edge.cost + world.dangerCost(npos)
                                + world.directionalCost(cur.pos, npos)
                                + descendTax(cur.pos, npos, edge)
                                + waterCellTax(npos)
                                + climbOutTax(cur.pos, npos)
                                + submergedTax(cur.pos, npos);
                        Node existing = nodes.get(npos);
                        if (existing != null && ng > existing.g - MIN_IMPROVEMENT) continue;
                        if (existing == null) {
                            Node next = new Node(npos, cur, edge, ng, heuristic(npos));
                            nodes.put(npos, next);
                            open.add(next);
                        } else {
                            existing.parent = cur;
                            existing.edge = edge;
                            existing.g = ng;
                            existing.f = ng + BotConfig.pathfinderHeuristicWeight * existing.h;
                            existing.closed = false;
                            open.add(existing);
                        }
                    }
                }
                // open empty / node budget / time budget → commit best-effort segment
                Node segment = chooseSegment();
                result = (segment == null)
                        ? new Result(List.of(), List.of(), false, expanded, totalMs(sliceStart), startNode.h)
                        : build(segment, false, expanded, totalMs(sliceStart), segment.g);
                return true;
            } finally {
                world.cacheActive(false);
                elapsedNanos += System.nanoTime() - sliceStart;
            }
        }

        /** True if any cardinal-horizontal neighbour of {@code p} sits in an
         *  unloaded chunk — i.e. {@code p} is on the edge of known terrain. */
        private boolean bordersUnknown(BlockPos p) {
            return !world.isKnown(p.offset(1, 0, 0)) || !world.isKnown(p.offset(-1, 0, 0))
                || !world.isKnown(p.offset(0, 0, 1)) || !world.isKnown(p.offset(0, 0, -1));
        }

        /** Pick the segment to commit when the goal wasn't reached. With frontier
         *  planning on, prefer walking to the goal-ward edge of known terrain (so the
         *  bot advances, loads new chunks, and the next search extends) — but only
         *  when that frontier is real progress toward the goal (else a backward chunk
         *  edge would pull the bot the wrong way). Otherwise fall back to Baritone's
         *  conservative best-effort backoff. */
        private Node chooseSegment() {
            // Water escape (highest priority): a search that STARTED in water and
            // didn't reach the goal may ONLY commit to a segment that climbs ASHORE
            // (feet on dry ground). Priorities fall out of move cost: a FLUSH/step-up
            // bank (Walk 10 / StepUp 15) beats a BREAK-climb (SwimBankClimbBreak 22 +
            // dig), so the cheapest reachable shore is chosen first and break-climb is
            // the fallback — and only when allowBreak is on (its moves are pruned
            // otherwise). If NO ashore node is reachable (allowBreak off + a sheer-walled
            // bowl, say), return null = "no path": the bot stays put rather than commit a
            // fake in-water segment or dive. Land searches keep the conservative backoff.
            if (startInWater) {
                // A CHEAP climb-out (pure swim+walk, no submerged dig priced in)
                // commits as before. An EXPENSIVE one means the only dry cell the
                // budget reached sits behind a 5×-priced underwater dig (round37b:
                // lake-bed cave — the sole 6k-node "ashore" was a deepslate tunnel
                // while an open water column to the surface stood ONE CELL east,
                // ignored because surfacing isn't ashore). Prefer SURFACING: commit
                // the monotone-up bestClimb through open water and re-plan from the
                // surface vantage where the real shore is reachable. The dig-ashore
                // stays as the genuine last resort (a fully roofed water pocket).
                if (bestAshore != null && bestAshore.g <= ASHORE_CHEAP_G) return bestAshore;
                if (bestClimb != null && bestClimb.pos.getY() - start.getY() >= MIN_CLIMB_ESCAPE) {
                    return bestClimb;
                }
                return bestAshore;
            }

            if (BotConfig.pathfinderFrontierCommit && bestFrontier != null
                    && bestFrontier.h < startNode.h - MIN_FRONTIER_GAIN
                    && bestFrontier.pos.distSqr(start) > MIN_DIST_PATH * MIN_DIST_PATH) {
                return bestFrontier;
            }
            Node seg = selectSegment(bestSoFar, start);
            if (seg != null) return seg;
            // Walled-to-frontier exploration (progressive planning): no goal-WARD segment
            // (the direct line is walled) but the search reached the edge of KNOWN terrain.
            // The detour around the wall lies in UNLOADED chunks, so grinding the hard
            // maxNodes budget can't find it — it isn't in the graph yet. Commit the frontier
            // node CLOSEST to the goal (min raw h) so the bot heads toward the goal-tangent,
            // loads new chunks, and the NEXT search sees around the wall: the bot WALL-FOLLOWS
            // smoothly instead of pillaring blindly up the wall (bestClimb below) or grinding
            // to the hard cap for a bestEscape (a ~3.4 s freeze per segment). Prefer this over
            // bestClimb: walking around a far mountain beats a blind +30 pillar that tops out
            // under a canopy (live boxed-pocket pillar storm). Guard out a strongly-BACKWARD
            // frontier (h much worse than start → would walk away from the goal); leave that
            // pathological concave pocket to bestClimb / bestEscape. The min-h selection makes
            // the committed edge the known point nearest the goal — the tangent, not a detour
            // away. INERT wherever every chunk is loaded (GameTest/arena): no node borders
            // unknown terrain, so bestFrontier stays null and this branch never fires.
            if (BotConfig.pathfinderFrontierCommit && bestFrontier != null
                    && bestFrontier.pos.distSqr(start) > MIN_DIST_PATH * MIN_DIST_PATH
                    && bestFrontier.h < startNode.h + MIN_FRONTIER_GAIN) {
                return bestFrontier;
            }
            // Boxed: no horizontal segment made real progress (conservative selector
            // refuses a backward/lateral hop). Escape VERTICALLY over the obstacle if we
            // climbed meaningfully above the start — pillar-up / dig-up, pure up + forward,
            // so it never introduces the camera-jarring backtrack the selector guards
            // against. The re-plan chain runs from the higher vantage; height is monotone
            // so it can't oscillate, and it self-terminates when blocks run out (no
            // climbed node → null → today's "no path"). User-chosen vertical escape.
            if (bestClimb != null && bestClimb.pos.getY() - start.getY() >= MIN_CLIMB_ESCAPE) {
                return bestClimb;
            }
            // LAST RESORT — boxed with no goal-ward, frontier or upward escape at
            // the hard cap: every reachable cell lies BEHIND the start (e.g. a cave
            // network whose only free exits head away from a walled-off goal — the
            // detour REQUIRES backtracking, which the conservative selector above
            // refuses by design). Returning null here fails the goto even though
            // the terrain is escapable; instead commit the farthest reachable node
            // so the bot physically leaves the dead pocket — the next search runs
            // from a new vantage (outside the cave / fresh chunks) and can find the
            // way around. Oscillation is bounded by the stuck-penalties + anti-spin.
            // Unreachable only via hard-cap exhaustion: hasCommittableSegment()
            // deliberately ignores bestEscape, so soft-commit can never fire on it.
            if (bestEscape != null && bestEscapeD2 > (long) MIN_DIST_PATH * MIN_DIST_PATH) {
                return bestEscape;
            }
            return null;
        }

        /** Cheap check: does a committable best-effort segment already exist? Mirrors the
         *  branches of {@link #chooseSegment} (ashore climb-out in water; else a bestSoFar
         *  node past MIN_DIST_PATH, or a vertical-escape climb above the start) without
         *  building the path. Drives the soft-commit early-stop
         *  ({@link BotConfig#pathfinderSoftCommitNodes}). */
        private boolean hasCommittableSegment() {
            // Water start mirrors chooseSegment's tiering: a cheap (dig-free)
            // ashore or a surfacing climb may early-stop; an expensive dig-ashore
            // alone keeps the search burning toward the relaxed tier — stopping at
            // 6k nodes on a 5×-priced underwater tunnel is exactly the round37b
            // lake-bed trap.
            if (startInWater) {
                boolean relaxedW = BotConfig.pfSoftCommitNodes() > 0
                        && expanded >= BotConfig.pfSoftCommitNodes() * 4L;
                return (bestAshore != null && (relaxedW || bestAshore.g <= ASHORE_CHEAP_G))
                        || (bestClimb != null && bestClimb.pos.getY() - start.getY() >= MIN_CLIMB_ESCAPE);
            }
            double minSq = MIN_DIST_PATH * MIN_DIST_PATH;
            // TIERED soft-commit: past 4× the soft budget (~24k nodes — the measured
            // canyon-exit cost), stop demanding the quality gain and take the best
            // segment/climb on offer. Without this tier the quality gate rode all the
            // way to the hard 60k cap on the nastiest cliff descents — a ~12 s
            // stand-still the video flagged ("悬崖边缘原地停顿约14秒"). Tiers: open
            // terrain commits at the soft budget (gain clears instantly); boxed
            // terrain burns up to 4× hunting a worthwhile segment (canyon exits fit
            // here); only a truly walled-in search degrades to best-available at 4×,
            // capping the planning stall at ~a third of the hard budget.
            boolean relaxed = BotConfig.pfSoftCommitNodes() > 0
                    && expanded >= BotConfig.pfSoftCommitNodes() * 4L;
            // Walled-to-frontier (progressive): reaching the edge of KNOWN terrain with no
            // goal-ward gain is committable NOW — the detour around the wall is beyond loaded
            // chunks, so grinding the hard budget can't find it (~3.4 s freeze for nothing).
            // Mirrors chooseSegment's walled-frontier branch so the soft early-stop fires on
            // it and the bot wall-follows at speed. Inert when fully loaded (bestFrontier null).
            if (BotConfig.pathfinderFrontierCommit && bestFrontier != null
                    && bestFrontier.pos.distSqr(start) > minSq
                    && bestFrontier.h < startNode.h + MIN_FRONTIER_GAIN) return true;
            for (Node n : bestSoFar) {
                // Distance alone is NOT committable: boxed in a canyon every repath
                // found SOME 5-block sideways scrap at the 6000-node soft budget and
                // committed it, each pointing a different way — the bot paced a
                // ~15-block box for minutes (live 2026-06-09, canyon at (415,-362):
                // the real exit was a +30 climb the probe only found at ~24k nodes).
                // Demand a real heuristic gain (≥ SOFT_MIN_GAIN ≈ 6 blocks toward an
                // XZ goal) before the soft early-stop may fire; open terrain clears
                // that instantly, while a boxed search keeps burning toward the hard
                // maxNodes cap until a worthwhile segment (or vertical escape) shows.
                if (n != null && n.pos.distSqr(start) > minSq
                        && (relaxed || startNode.h - n.h >= softMinGain())) return true;
            }
            // bestClimb does NOT soft-stop at the base tier. Letting a 3-5-block climb
            // scrap satisfy the soft budget recommitted pillar shreds in every direction
            // at a canyon whose real exit was +30 (live: pathLen 4-8 dirt-ladder
            // segments, bot burned 13 dirt pacing). At the RELAXED tier (4× burned,
            // nothing better found) a climb escape is accepted — by then it is the best
            // escape a substantial search produced, not a 6k-node shred.
            return relaxed && bestClimb != null
                    && bestClimb.pos.getY() - start.getY() >= MIN_CLIMB_ESCAPE;
        }

        /** Minimum heuristic improvement a best-effort segment must show before the
         *  soft-commit early-stop fires: HALF the horizon distance (~blocks×10 toward
         *  an XZ goal; horizon 48 → gain 240 ≈ 24 blocks). 60 (6 blocks) proved too
         *  low live: a boxed canyon offered 8-block pseudo-forward scraps in every
         *  direction at the 6000-node budget, so each repath committed a different
         *  scrap and the bot paced a 15-block box — while the true exit (a +30 climb)
         *  needed ~24k nodes. Open terrain clears 24 blocks within the soft budget
         *  easily, keeping the early-stop (and its no-freeze feel) intact there. */
        private static double softMinGain() {
            int hb = BotConfig.pfHorizonBlocks();
            return hb > 0 ? hb * 10 / 2.0 : 240;
        }

        /** True if {@code p} is a dry standing cell — feet on solid dry ground, not
         *  in water. The "climbed ashore" test for the water-escape best-effort. */
        private boolean isAshore(BlockPos p) {
            return !world.isWater(p) && world.canStandOn(p.offset(0, -1, 0));
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
        // (Anti-backtrack A/B-INCONCLUSIVE 2026-06-06: preferring the best-effort node that
        // REDUCES h — to stop the chain committing to backward/sideways hops — only helped
        // marginally and noisily. The real backtrack cause in dense jungle is the SEARCH
        // BUDGET: at a terrain pinch the search hits maxMs (~31k nodes) before finding the
        // forward route, so even pass-1 finds no h-reducing node and falls back to a backward
        // hop anyway. Reverted to keep the core pathfinder unchanged; the real fix is a more
        // efficient search / better heuristic / budget tuning, not the segment-selection rule.)
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
            this.h = h;            // raw (admissible) heuristic — best-effort selection reads this
            // Weighted A*: ordering key inflates h by W so the frontier drives harder
            // toward the goal within the time budget (see BotConfig.pathfinderHeuristicWeight).
            this.f = g + BotConfig.pathfinderHeuristicWeight * h;
        }
    }
}
