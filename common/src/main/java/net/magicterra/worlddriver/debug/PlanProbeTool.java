package net.magicterra.worlddriver.debug;

import net.magicterra.worlddriver.api.game.Positions;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts.ChainPlan;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts.PlanEdge;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.bot.Bot;
import net.magicterra.worlddriver.bot.BotRegistry;
import net.magicterra.worlddriver.bot.GotoGoalResolver;
import net.magicterra.worlddriver.bot.RouteParams;
import net.magicterra.worlddriver.bot.world.LevelWorldView;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.PlanningConfig;
import net.minecraft.world.entity.LivingEntity;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts.Estimate;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts.Search;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts.MoveCost;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts.PlanParams;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts.PlanReply;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts.Segment;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts.SinglePlan;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.bot.control.route.PathSmoothing;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.planning.PathFinder;
import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * {@code mc.debug.plan}: a READ-ONLY, deterministic single-search probe, the measurement backbone of the
 * work on backtracking at pinch points.
 *
 * <p>It runs one {@link PathFinder#findPath} from a fixed start to a goto target and answers the committed
 * best-effort segment plus a backtrack verdict, WITHOUT engaging the Navigator — so it never walks, never
 * breaks a block, and never mutates the world. That removes the two confounds that made live A/B
 * unreliable: terrain mutation from the bot's own digging across runs, and wall-clock-budget jitter (use a
 * NODE budget — the {@code pathfinder.maxNodes} setting with a large {@code maxMs} — and the search is fully
 * repeatable). Call it from a fixed suite of trapped start feet to get a reproducible forward-rate metric;
 * re-run after a selectSegment change to measure the delta.
 *
 * <p>"Backward" = the committed segment's endpoint does NOT reduce the admissible goal estimate vs the
 * start (the best-effort commit went lateral/away from the goal — the oscillation seed).
 *
 * <p>A named bot is planned for on the server thread over its own level; {@code self} on the client thread over
 * the client's world, in {@link ClientPlanProbe}, which is the only part that names a client class.
 */
final class PlanProbeTool {
    private PlanProbeTool() {}

    /** On the client thread: the client's player in the client's world. */
    static PlanReply onClient(PlanParams p) {
        return ClientPlanProbe.plan(p);
    }

    /** On the server thread: the named bot in its level, its own bag deciding what it may place and dig with. */
    static PlanReply onServer(PlanParams p) {
        String ref = p.bot().orElseThrow();
        Bot bot = BotRegistry.get(ref);
        if (bot == null) throw ApiException.invalidParam("/bot", ApiIssue.Code.INVALID_VALUE, "no bot named " + ref);
        LivingEntity self = bot.entity();
        BlockPos start = p.from().map(Positions::blockPos).orElse(self.blockPosition());
        return run(LevelWorldView.forBody(self.level(), self), start, goal(p, self), p);
    }

    static Goal goal(PlanParams p, LivingEntity self) {
        try {
            return GotoGoalResolver.resolve(p.target(), false, self, Map.of(), id -> null);
        } catch (GotoGoalResolver.Unavailable e) {
            throw ApiException.invalidParam("/target", ApiIssue.Code.INVALID_VALUE, e.getMessage());
        }
    }

    /** One search, or the chained segments, over {@code world}; read-only. */
    static PlanReply run(WorldView world, BlockPos start, Goal goal, PlanParams p) {
        if (!p.chain()) {
            long t0 = System.nanoTime();
            PathFinder.Result r = finder(world, p).findPath(start, goal);
            return single(start, goal, r, (System.nanoTime() - t0) / 1_000_000L);
        }

        // Chained simulation of the Navigator's segment-commitment trajectory, read-only: feed each committed
        // best-effort endpoint back in as the next start. Reproduces the live repath trail with zero
        // walking / mutation / wall-clock noise, so the backtrack it surfaces is purely the selectSegment +
        // cost-model decision.
        List<Segment> segs = new ArrayList<>();
        BlockPos from = start;
        double hStart = goal.estimate(start);
        double bestH = hStart;             // best (lowest) goal estimate reached so far
        double maxRegression = 0;          // worst overshoot back past the best frontier
        int backwardSegs = 0;              // segments that ended farther from goal than they started
        boolean reached = false;
        long totalExpanded = 0;
        for (int n = 0; n < p.maxSegments(); n++) {
            PathFinder.Result r = finder(world, p).findPath(from, goal);
            totalExpanded += r.expanded();
            List<BlockPos> path = r.path();
            int cut = ControlConfig.navigatorCommitTailPeak && !r.goalReached() ? PathSmoothing.sunkTailCut(path) : -1;
            BlockPos end = path.isEmpty() ? from : path.get(cut > 0 ? cut : path.size() - 1);
            double hFrom = goal.estimate(from), hEnd = goal.estimate(end);
            if (hEnd > hFrom + 1e-6) backwardSegs++;
            maxRegression = Math.max(maxRegression, hEnd - bestH);
            bestH = Math.min(bestH, hEnd);
            segs.add(new Segment(Positions.position(end), Math.round(hEnd), Math.round(hEnd - hFrom), r.expanded(), r.goalReached(),
                    cut > 0 ? Optional.of(Positions.position(path.get(path.size() - 1))) : Optional.empty()));
            if (r.goalReached()) { reached = true; break; }
            if (end.equals(from)) break;            // no progress → stuck
            from = end;
        }
        return new ChainPlan(Positions.position(start), reached, segs.size(), backwardSegs, Math.round(maxRegression),
                totalExpanded, Math.round(hStart), Math.round(bestH), segs);
    }

    /** The search {@code mc.bot.goto} would run with this route and these budgets; a bare one without a route. */
    private static PathFinder finder(WorldView world, PlanParams p) {
        int nodes = p.maxNodes().orElse(PlanningConfig.pathfinderMaxNodes);
        long ms = p.maxMs().orElse(PlanningConfig.pathfinderMaxMs);
        PathFinder f = p.route().isPresent()
                ? new PathFinder(world, nodes, ms, RouteParams.parse(p.route().get()).profile())
                : p.maxNodes().isPresent() || p.maxMs().isPresent() ? new PathFinder(world, nodes, ms) : new PathFinder(world);
        return f.withOwner("debug.plan");
    }

    private static SinglePlan single(BlockPos start, Goal goal, PathFinder.Result r, long wallMs) {
        List<BlockPos> path = r.path();
        BlockPos end = path.isEmpty() ? start : path.get(path.size() - 1);
        double hStart = goal.estimate(start);
        double hEnd = goal.estimate(end);
        // maxStepDrop, the largest single-node Y decrease the PLANNER routed, tells a walkable staircase
        // (every step-down small) from a committed cliff drop; yProfile is the per-node Y trail, read as a
        // side elevation, so a graded staircase is visibly distinct from a plunge.
        int maxStepDrop = 0;
        List<Integer> yProfile = new ArrayList<>(path.size());
        for (int k = 0; k < path.size(); k++) {
            yProfile.add(path.get(k).getY());
            if (k > 0) maxStepDrop = Math.max(maxStepDrop, path.get(k - 1).getY() - path.get(k).getY());
        }
        // Per-move count and summed edge cost: which moves a route is made of and what the planner charged
        // for each, so a cost model can be checked against the time the walk actually took.
        Map<String, double[]> moves = new TreeMap<>();
        int breaks = 0, places = 0;
        for (Move.Edge e : r.edges()) {
            if (e == null || e.move == null) continue;
            double[] m = moves.computeIfAbsent(e.move, k -> new double[2]);
            m[0]++; m[1] += e.cost;
            breaks += e.toBreak.size(); places += e.toPlace.size();
        }
        List<PlanEdge> edges = new ArrayList<>(path.size());
        for (int k = 0; k < path.size(); k++) {
            Move.Edge e = k < r.edges().size() ? r.edges().get(k) : null;
            edges.add(new PlanEdge(Positions.position(path.get(k)), Optional.ofNullable(e == null ? null : e.move),
                    e == null ? 0 : Math.round(e.cost), e == null ? List.of() : positions(e.toBreak),
                    e == null ? List.of() : positions(e.toPlace)));
        }
        Map<String, MoveCost> moveOut = new LinkedHashMap<>();
        moves.forEach((k, m) -> moveOut.put(k, new MoveCost((int) m[0], Math.round(m[1]))));
        return new SinglePlan(Positions.position(start), r.goalReached(), path.size(), Positions.position(end),
                new Search(r.expanded(), r.ms(), wallMs, Math.round(r.finalCost())),
                new Estimate(Math.round(hStart), Math.round(hEnd), Math.round(hEnd - hStart), hEnd < hStart - 1e-6),
                maxStepDrop, yProfile,
                path.stream().map(n -> n.getX() + "," + n.getY() + "," + n.getZ()).toList(), moveOut, breaks, places, edges);
    }

    private static List<BlockPosition> positions(java.util.Collection<BlockPos> cells) {
        List<BlockPosition> out = new ArrayList<>(cells.size());
        for (BlockPos c : cells) out.add(Positions.position(c));
        return out;
    }

}
