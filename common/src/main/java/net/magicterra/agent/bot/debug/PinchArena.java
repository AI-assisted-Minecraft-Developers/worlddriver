package net.magicterra.agent.bot.debug;

import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.pathfinder.PathFinder;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic, fully-loaded reproduction of the planner BOXED LOCAL-MINIMUM pinch —
 * the bot wedged at the foot of a tall cliff toward an XZ goal, where the conservative
 * best-effort {@code selectSegment} returns null and the bot deadlocks (live: repath
 * from (44,89,-820) → end=start, pathLen=0). Built on {@link GridWorldView} so it has
 * none of the live measurement ceiling (loaded-chunk horizon, terrain mutation,
 * wall-clock jitter) that made the live pinch irreproducible across relaunches.
 *
 * <p>The arena: a flat south pocket, then a tall plateau wall blocking the goal-ward
 * direction; the goal sits on the plateau. The only way across is to PILLAR UP the
 * wall and walk over (the user-chosen vertical escape). Under search-budget pressure
 * (small {@code maxNodes}, mirroring the live 60k-on-huge-terrain case) a single search
 * can't find the whole pillar-then-over route, so the conservative selector is boxed —
 * which is exactly when the vertical-escape fix commits a pillar-up segment and the
 * re-plan chain scales the wall rung by rung. Asserted by {@link #run}.
 *
 * <p>Release strip: {@code bot.debug} package, deletable with the other probes.
 */
public final class PinchArena {
    private PinchArena() {}

    public static final class Result {
        public final boolean reached;
        public final int segments;
        public final int maxY;
        public final String trail;
        Result(boolean reached, int segments, int maxY, String trail) {
            this.reached = reached; this.segments = segments; this.maxY = maxY; this.trail = trail;
        }
        @Override public String toString() {
            return "reached=" + reached + " segments=" + segments + " maxY=" + maxY + " trail=" + trail;
        }
    }

    /**
     * Build the cliff arena and run the read-only segment-commitment chain (mirrors the
     * Walker / PlanProbeTool chain: feed each committed endpoint back as the next start).
     * {@code maxNodes} sets the per-search budget that forces the boxed condition.
     */
    public static Result run(int maxNodes) {
        // Grid: x[-8..15], y[60..99], z[-8..31].
        GridWorldView w = new GridWorldView(-8, 60, -8, 24, 40, 40);
        // Flat floor at y=60 across the whole south pocket (bot walks at y=61).
        w.fill(-8, 60, -8, 15, 60, 9, GridWorldView.SOLID);
        // Plateau north of z=10: solid ground from y60 up to y68 (top walkable at y69),
        // i.e. an 8-block step up from the south floor — impossible to jump (max +1), so
        // the only crossing is to pillar up the face and walk onto the plateau top.
        w.fill(-8, 60, 10, 15, 68, 31, GridWorldView.SOLID);

        BlockPos start = new BlockPos(0, 61, 9);   // south foot, hard against the plateau wall
        Goal goal = new Goal.XZ(0, 30);            // on the plateau, beyond the wall

        List<String> trail = new ArrayList<>();
        BlockPos from = start;
        boolean reached = false;
        int maxY = start.getY();
        int seg = 0;
        for (; seg < 60; seg++) {
            PathFinder.Result r = new PathFinder(w, maxNodes, 30_000).withOwner("debug.pinch").findPath(from, goal);
            List<BlockPos> path = r.path();
            BlockPos end = path.isEmpty() ? from : path.get(path.size() - 1);
            maxY = Math.max(maxY, end.getY());
            trail.add("(" + end.getX() + "," + end.getY() + "," + end.getZ() + ")"
                    + (r.goalReached() ? "*" : "") + "[" + path.size() + "]");
            if (r.goalReached()) { reached = true; break; }
            if (end.equals(from)) break;   // no progress → stuck
            from = end;
        }
        return new Result(reached, seg + 1, maxY, String.join(" → ", trail));
    }
}
