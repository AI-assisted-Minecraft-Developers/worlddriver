package net.magicterra.agent.bot.debug;

import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.PathTrace;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Immutable snapshot of one goto session for rendering. Built by
 * {@link PathDebugRecorder#snapshot()} on the calling thread; contains only plain data
 * so the renderer can run off the client thread.
 */
public record PathSession(
        BlockPos start,
        BlockPos goalMarker,            // representative goal cell, or null for open goals
        List<Candidate> candidates,     // latest search's expanded nodes (downsampled)
        List<PlannedRoute> plannedRoutes,
        List<PathTrace.WalkerSample> trajectory,
        PathTrace.Outcome outcome,      // null while still running
        String reason,
        String goalDesc) {

    /** One expanded A* node. */
    public record Candidate(int x, int y, int z, double g) {}

    /** One adopted search result. {@code repathIndex} 0 = first plan of the session. */
    public record PlannedRoute(List<BlockPos> path, List<Move.Edge> edges, boolean goalReached,
                               int repathIndex, int expanded, long ms, double finalCost) {}
}
