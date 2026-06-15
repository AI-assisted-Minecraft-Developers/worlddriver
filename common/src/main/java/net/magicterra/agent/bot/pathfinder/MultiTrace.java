package net.magicterra.agent.bot.pathfinder;

import net.magicterra.agent.bot.Goal;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Fan-out {@link PathTrace} that forwards every callback to multiple sinks in
 * order. Lets {@link net.magicterra.agent.bot.debug.PathDebugBootstrap} install
 * both {@link net.magicterra.agent.bot.debug.PathDebugRecorder} and
 * {@link net.magicterra.agent.bot.debug.PathArchiveRecorder} behind a single
 * {@link PathTraceHolder#SINK}.
 *
 * <p><b>Sinks must never break the bot.</b> These callbacks fire from inside the
 * pathfinder/Walker tick (e.g. {@code onSearchBegin} from a {@code Search}
 * constructor, {@code onSearchResult}/{@code onWalkerTick} from {@code Walker.tick}).
 * A debug/archive sink that throws would propagate up and kill the active goto.
 * So every per-sink call is wrapped: an exception is logged (with stack) and
 * swallowed, and the remaining sinks still run. Observability is never worth a
 * dead pathfind.</p>
 */
public final class MultiTrace implements PathTrace {

    private static final Logger LOG = LoggerFactory.getLogger("agent-pathtrace");

    private final PathTrace[] sinks;

    public MultiTrace(PathTrace... sinks) {
        this.sinks = sinks;
    }

    /** Run {@code body} for one sink; never let it escape. */
    private interface SinkOp { void run(PathTrace s); }

    private void forEach(String cb, SinkOp op) {
        for (PathTrace s : sinks) {
            try {
                op.run(s);
            } catch (Throwable t) {
                // Full stack so the underlying bug is fixable, but never rethrow —
                // a trace sink must not take down the active goto.
                LOG.warn("[pathtrace] sink {} threw in {} (swallowed): {}",
                        s.getClass().getSimpleName(), cb, t.toString(), t);
            }
        }
    }

    @Override
    public void onSearchBegin(BlockPos start, Goal goal) {
        forEach("onSearchBegin", s -> s.onSearchBegin(start, goal));
    }

    @Override
    public void onNodeExpanded(BlockPos pos, double g) {
        forEach("onNodeExpanded", s -> s.onNodeExpanded(pos, g));
    }

    @Override
    public void onSearchResult(List<BlockPos> path, List<Move.Edge> edges, boolean goalReached,
                               int expanded, long ms, double finalCost) {
        forEach("onSearchResult", s -> s.onSearchResult(path, edges, goalReached, expanded, ms, finalCost));
    }

    @Override
    public void onWalkerTick(WalkerSample sample) {
        forEach("onWalkerTick", s -> s.onWalkerTick(sample));
    }

    @Override
    public void onTerminal(Outcome outcome, String reason) {
        forEach("onTerminal", s -> s.onTerminal(outcome, reason));
    }
}
