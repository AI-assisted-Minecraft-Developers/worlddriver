package net.magicterra.worlddriver.integration.debug;

import net.magicterra.worlddriver.api.Positions;
import net.magicterra.worlddriver.api.contract.ApiError;
import net.magicterra.worlddriver.api.contract.ApiException;
import net.magicterra.worlddriver.api.methods.DebugContracts.ChainPlan;
import net.magicterra.worlddriver.api.methods.DebugContracts.Column;
import net.magicterra.worlddriver.api.methods.DebugContracts.Estimate;
import net.magicterra.worlddriver.api.methods.DebugContracts.Search;
import net.magicterra.worlddriver.api.methods.DebugContracts.MoveCost;
import net.magicterra.worlddriver.api.methods.DebugContracts.PlanParams;
import net.magicterra.worlddriver.api.methods.DebugContracts.PlanReply;
import net.magicterra.worlddriver.api.methods.DebugContracts.Segment;
import net.magicterra.worlddriver.api.methods.DebugContracts.SinglePlan;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.ClientWorldView;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.movement.PathSmoothing;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.PathFinder;
import net.minecraft.client.Minecraft;
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
 * <p>It runs one {@link PathFinder#findPath} from a fixed start to an XZ goal and answers the committed
 * best-effort segment plus a backtrack verdict, WITHOUT engaging the Walker — so it never walks, never
 * breaks a block, and never mutates the world. That removes the two confounds that made live A/B
 * unreliable: terrain mutation from the bot's own digging across runs, and wall-clock-budget jitter (use a
 * NODE budget — the {@code pathfinder.maxNodes} setting with a large {@code maxMs} — and the search is fully
 * repeatable). Call it from a fixed suite of trapped start feet to get a reproducible forward-rate metric;
 * re-run after a selectSegment change to measure the delta.
 *
 * <p>"Backward" = the committed segment's endpoint does NOT reduce the admissible goal estimate vs the
 * start (the best-effort commit went lateral/away from the goal — the oscillation seed).
 *
 * <p>Client thread only: it reads the client's world through {@link ClientWorldView}.
 */
final class PlanProbeTool {
    private PlanProbeTool() {}

    static PlanReply plan(PlanParams p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            throw ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE, "no world is open on this client");
        }
        BlockPos start = p.from().map(Positions::blockPos).orElse(mc.player.blockPosition());
        Goal goal = new Goal.XZ(p.goal().x(), p.goal().z());
        ClientWorldView world = new ClientWorldView();
        if (!p.chain()) {
            long t0 = System.nanoTime();
            PathFinder.Result r = new PathFinder(world).withOwner("debug.plan").findPath(start, goal);
            return single(start, goal, p.goal(), r, (System.nanoTime() - t0) / 1_000_000L);
        }

        // Chained simulation of the Walker's segment-commitment trajectory, read-only: feed each committed
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
            PathFinder.Result r = new PathFinder(world).withOwner("debug.plan").findPath(from, goal);
            totalExpanded += r.expanded();
            List<BlockPos> path = r.path();
            int cut = BotConfig.walkerCommitTailPeak && !r.goalReached() ? PathSmoothing.sunkTailCut(path) : -1;
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
        return new ChainPlan(Positions.position(start), p.goal(), reached, segs.size(), backwardSegs, Math.round(maxRegression),
                totalExpanded, Math.round(hStart), Math.round(bestH), segs);
    }

    private static SinglePlan single(BlockPos start, Goal goal, Column column, PathFinder.Result r, long wallMs) {
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
        Map<String, MoveCost> moveOut = new LinkedHashMap<>();
        moves.forEach((k, m) -> moveOut.put(k, new MoveCost((int) m[0], Math.round(m[1]))));
        return new SinglePlan(Positions.position(start), column, r.goalReached(), path.size(), Positions.position(end),
                new Search(r.expanded(), r.ms(), wallMs, Math.round(r.finalCost())),
                new Estimate(Math.round(hStart), Math.round(hEnd), Math.round(hEnd - hStart), hEnd < hStart - 1e-6),
                maxStepDrop, yProfile,
                path.stream().map(n -> n.getX() + "," + n.getY() + "," + n.getZ()).toList(), moveOut, breaks, places);
    }

}
