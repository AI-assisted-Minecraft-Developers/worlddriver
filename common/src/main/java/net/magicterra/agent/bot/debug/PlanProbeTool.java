package net.magicterra.agent.bot.debug;

import net.magicterra.agent.bot.ClientWorldView;
import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.pathfinder.PathFinder;
import net.magicterra.agent.client.internal.ClientThread;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handler for {@code mc.debug.plan} — a READ-ONLY, deterministic single-search probe
 * that is the measurement backbone of the pinch "走回头路" work.
 *
 * <p>It runs one {@link PathFinder#findPath} from a fixed start to an XZ goal and returns
 * the committed best-effort segment plus a backtrack verdict, WITHOUT engaging the Walker —
 * so it never walks, never breaks a block, and never mutates the world. That removes the two
 * confounds that made live A/B unreliable: terrain mutation from the bot's own digging across
 * runs, and wall-clock-budget jitter (use a NODE budget — {@code mc.bot.setting{pathfinder.maxNodes}}
 * with a large {@code maxMs} — and the search is fully repeatable). Call it from a fixed suite
 * of trapped start feet to get a reproducible forward-rate metric; re-run after a selectSegment
 * change to measure the delta.
 *
 * <p>"Backward" = the committed segment's endpoint does NOT reduce the admissible goal estimate
 * vs the start (the best-effort commit went lateral/away from the goal — the oscillation seed).
 *
 * <p>Release strip: part of the {@code bot.debug} package; deleting it leaves core unchanged.
 */
public final class PlanProbeTool {
    private PlanProbeTool() {}

    public static Map<String, Object> plan(Map<String, Object> p) {
        if (p == null) return err("missing params");
        Object g = p.get("goal");
        if (!(g instanceof Map<?, ?> gm) || !(gm.get("x") instanceof Number gx) || !(gm.get("z") instanceof Number gz))
            return err("goal:{x,z} required");
        Integer fx = null, fy = null, fz = null;
        if (p.get("from") instanceof Map<?, ?> fm
                && fm.get("x") instanceof Number a && fm.get("y") instanceof Number b && fm.get("z") instanceof Number c) {
            fx = a.intValue(); fy = b.intValue(); fz = c.intValue();
        }
        final int goalX = gx.intValue(), goalZ = gz.intValue();
        final Integer sfx = fx, sfy = fy, sfz = fz;
        final boolean chain = Boolean.TRUE.equals(p.get("chain"));
        final int maxSegments = (p.get("maxSegments") instanceof Number ms) ? Math.max(1, Math.min(200, ms.intValue())) : 40;

        return ClientThread.runOnClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return err("no world/player");
            BlockPos start = (sfx != null) ? new BlockPos(sfx, sfy, sfz) : mc.player.blockPosition();
            Goal goal = new Goal.XZ(goalX, goalZ);
            ClientWorldView world = new ClientWorldView();

            if (!chain) {
                long t0 = System.nanoTime();
                PathFinder.Result r = new PathFinder(world).withOwner("debug.plan").findPath(start, goal);
                long wallMs = (System.nanoTime() - t0) / 1_000_000L;
                return single(start, goal, goalX, goalZ, r, wallMs);
            }

            // Chained simulation of the Walker's segment-commitment trajectory, read-only:
            // feed each committed best-effort endpoint back in as the next start. Reproduces
            // the live repath trail with zero walking / mutation / wall-clock noise, so the
            // backtrack it surfaces is purely the selectSegment + cost-model decision.
            List<Map<String, Object>> segs = new java.util.ArrayList<>();
            BlockPos from = start;
            double hStart = goal.estimate(start);
            double bestH = hStart;             // best (lowest) goal estimate reached so far
            double maxRegression = 0;          // worst overshoot back past the best frontier
            int backwardSegs = 0;              // segments that ended farther from goal than they started
            boolean reached = false;
            long totalExpanded = 0;
            int n = 0;
            for (; n < maxSegments; n++) {
                PathFinder.Result r = new PathFinder(world).withOwner("debug.plan").findPath(from, goal);
                totalExpanded += r.expanded();
                List<BlockPos> path = r.path();
                BlockPos end = path.isEmpty() ? from : path.get(path.size() - 1);
                double hFrom = goal.estimate(from), hEnd = goal.estimate(end);
                if (hEnd > hFrom + 1e-6) backwardSegs++;
                maxRegression = Math.max(maxRegression, hEnd - bestH);
                bestH = Math.min(bestH, hEnd);
                Map<String, Object> seg = new LinkedHashMap<>();
                seg.put("end", xyz(end));
                seg.put("hEnd", Math.round(hEnd));
                seg.put("hDelta", Math.round(hEnd - hFrom));
                seg.put("expanded", r.expanded());
                seg.put("goalReached", r.goalReached());
                segs.add(seg);
                if (r.goalReached()) { reached = true; break; }
                if (end.equals(from)) break;            // no progress → stuck
                from = end;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("chain", true);
            out.put("start", xyz(start));
            out.put("goal", Map.of("x", goalX, "z", goalZ));
            out.put("reached", reached);
            out.put("segments", segs.size());
            out.put("backwardSegments", backwardSegs);
            out.put("maxRegression", Math.round(maxRegression));   // worst camera-jarring overshoot back past best progress (cost units; /10 ≈ blocks)
            out.put("totalExpanded", totalExpanded);
            out.put("hStart", Math.round(hStart));
            out.put("hFinal", Math.round(bestH));
            out.put("trail", segs);
            return out;
        });
    }

    private static Map<String, Object> single(BlockPos start, Goal goal, int goalX, int goalZ,
                                              PathFinder.Result r, long wallMs) {
        List<BlockPos> path = r.path();
        BlockPos end = path.isEmpty() ? start : path.get(path.size() - 1);
        double hStart = goal.estimate(start);
        double hEnd = goal.estimate(end);
        // Committed-path elevation profile — the discriminator for descent-goto lethal
        // falls (task#36): maxStepDrop = the largest single-node Y decrease the PLANNER
        // routed. A pure walkable staircase keeps every step-down small (≈ survivableFall
        // capped); a large maxStepDrop means the planner itself committed a cliff drop.
        // yProfile is a compact per-node absolute-Y trail (read as a side elevation) so a
        // staircase (graded) is visually distinguishable from a plunge. Read-only.
        int maxStepDrop = 0;
        List<Integer> yProfile = new java.util.ArrayList<>(path.size());
        for (int k = 0; k < path.size(); k++) {
            yProfile.add(path.get(k).getY());
            if (k > 0) maxStepDrop = Math.max(maxStepDrop, path.get(k - 1).getY() - path.get(k).getY());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("start", xyz(start));
        out.put("goal", Map.of("x", goalX, "z", goalZ));
        out.put("goalReached", r.goalReached());
        out.put("pathLen", path.size());
        out.put("end", xyz(end));
        out.put("expanded", r.expanded());
        out.put("computeMs", r.ms());
        out.put("wallMs", wallMs);
        out.put("finalCost", Math.round(r.finalCost()));
        out.put("hStart", Math.round(hStart));
        out.put("hEnd", Math.round(hEnd));
        out.put("hDelta", Math.round(hEnd - hStart));   // <0 = forward progress, >=0 = backward/lateral
        out.put("forward", hEnd < hStart - 1e-6);
        out.put("maxStepDrop", maxStepDrop);            // task#36 discriminator: planner-routed single-node Y drop (blocks)
        out.put("yProfile", yProfile);                  // per-node absolute Y along committed path (side elevation)
        return out;
    }

    private static Map<String, Object> xyz(BlockPos p) {
        return Map.of("x", p.getX(), "y", p.getY(), "z", p.getZ());
    }

    private static Map<String, Object> err(String msg) {
        return Map.of("ok", false, "error", msg);
    }
}
