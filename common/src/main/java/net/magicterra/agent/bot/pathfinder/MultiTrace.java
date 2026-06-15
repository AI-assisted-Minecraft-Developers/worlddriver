package net.magicterra.agent.bot.pathfinder;

import net.magicterra.agent.bot.Goal;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Fan-out {@link PathTrace} that forwards every callback to multiple sinks in
 * order. Lets {@link net.magicterra.agent.bot.debug.PathDebugBootstrap} install
 * both {@link net.magicterra.agent.bot.debug.PathDebugRecorder} and
 * {@link net.magicterra.agent.bot.debug.PathArchiveRecorder} behind a single
 * {@link PathTraceHolder#SINK}.
 */
public final class MultiTrace implements PathTrace {

    private final PathTrace[] sinks;

    public MultiTrace(PathTrace... sinks) {
        this.sinks = sinks;
    }

    @Override
    public void onSearchBegin(BlockPos start, Goal goal) {
        for (PathTrace s : sinks) s.onSearchBegin(start, goal);
    }

    @Override
    public void onNodeExpanded(BlockPos pos, double g) {
        for (PathTrace s : sinks) s.onNodeExpanded(pos, g);
    }

    @Override
    public void onSearchResult(List<BlockPos> path, List<Move.Edge> edges, boolean goalReached,
                               int expanded, long ms, double finalCost) {
        for (PathTrace s : sinks) s.onSearchResult(path, edges, goalReached, expanded, ms, finalCost);
    }

    @Override
    public void onWalkerTick(WalkerSample sample) {
        for (PathTrace s : sinks) s.onWalkerTick(sample);
    }

    @Override
    public void onTerminal(Outcome outcome, String reason) {
        for (PathTrace s : sinks) s.onTerminal(outcome, reason);
    }
}
