package net.magicterra.worlddriver.bot.planning;

import java.util.List;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts.Route;
import net.magicterra.worlddriver.bot.RouteParams;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.PlanningConfig;
import net.minecraft.core.BlockPos;

/**
 * The one search {@code mc.debug.plan} runs for a bot ({@code PlanProbeTool.run}, the single-search branch): a bare
 * finder when there is no route and no budget, the route's profile when there is a route, the budgets otherwise taken
 * from the settings; advanced once with no slice limit.
 */
final class PlanProbe {
    private PlanProbe() {}

    static PathFinder.Result plan(WorldView w, BlockPos start, Goal goal) {
        return plan(w, start, goal, null, null, null);
    }

    static PathFinder.Result plan(WorldView w, BlockPos start, Goal goal, Route route) {
        return plan(w, start, goal, route, null, null);
    }

    static PathFinder.Result plan(WorldView w, BlockPos start, Goal goal, Route route, Integer maxNodes, Long maxMs) {
        int nodes = maxNodes != null ? maxNodes : PlanningConfig.pathfinderMaxNodes;
        long ms = maxMs != null ? maxMs : PlanningConfig.pathfinderMaxMs;
        PathFinder f = route != null
                ? new PathFinder(w, nodes, ms, RouteParams.parse(route).profile())
                : maxNodes != null || maxMs != null ? new PathFinder(w, nodes, ms) : new PathFinder(w);
        PathFinder.Search s = f.withOwner("debug.plan").newSearch(start, goal);
        s.advance(Long.MAX_VALUE);
        return s.result();
    }

    /** Highest Y on the path, −1 for an empty one. */
    static int maxY(List<BlockPos> path) {
        int max = Integer.MIN_VALUE;
        for (BlockPos p : path) max = Math.max(max, p.getY());
        return path.isEmpty() ? -1 : max;
    }

    /** Lowest Y on the path, {@code Integer.MAX_VALUE} for an empty one. */
    static int minY(List<BlockPos> path) {
        int min = Integer.MAX_VALUE;
        for (BlockPos p : path) min = Math.min(min, p.getY());
        return min;
    }
}
