package net.magicterra.agent.bot.debug;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.PathTrace;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The live {@link PathTrace} implementation. Accumulates one goto session, reset whenever a
 * new goal appears. Gated by {@link BotConfig#pathDebug}: when off, every callback returns
 * immediately. Snapshot is copy-on-read so the renderer never tears.
 *
 * Session boundary = a change of goal (compared by {@code toString()}); long gotos repath
 * from intermediate cells to the same goal and stay one session. Candidates reflect the
 * latest search only (cleared each {@code onSearchBegin}); planned routes accumulate.
 */
public final class PathDebugRecorder implements PathTrace {
    private static final Logger LOG = LoggerFactory.getLogger("agent-pathdebug");

    private final Object lock = new Object();

    private BlockPos start;
    private BlockPos goalMarker;
    private String goalDesc = "";
    private String goalKey = "";
    private int repathCount;
    private long expandedSinceBegin;   // total expansions this search (for reservoir scaling)

    private final List<PathSession.Candidate> candidates = new ArrayList<>();
    private final List<PathSession.PlannedRoute> plannedRoutes = new ArrayList<>();
    private final ArrayDeque<WalkerSample> trajectory = new ArrayDeque<>();
    private Outcome outcome;
    private String reason;

    @Override
    public void onSearchBegin(BlockPos searchStart, Goal goal) {
        if (!BotConfig.pathDebug) return;
        synchronized (lock) {
            String key = String.valueOf(goal);
            if (!key.equals(goalKey)) {                 // new goto → reset session
                goalKey = key;
                goalDesc = key;
                start = searchStart;
                goalMarker = GoalMarker.of(goal);
                plannedRoutes.clear();
                trajectory.clear();
                repathCount = 0;
                outcome = null;
                reason = null;
            }
            candidates.clear();                         // latest search's candidates only
            expandedSinceBegin = 0;
        }
    }

    @Override
    public void onNodeExpanded(BlockPos pos, double g) {
        if (!BotConfig.pathDebug) return;
        synchronized (lock) {
            expandedSinceBegin++;
            int cap = Math.max(100, BotConfig.pathDebugMaxNodes);
            if (candidates.size() < cap) {
                candidates.add(new PathSession.Candidate(pos.getX(), pos.getY(), pos.getZ(), g));
            } else {
                // Deterministic reservoir-style replacement: keep a uniform spread without RNG.
                long stride = expandedSinceBegin;
                int idx = (int) (stride % cap);
                candidates.set(idx, new PathSession.Candidate(pos.getX(), pos.getY(), pos.getZ(), g));
            }
        }
    }

    @Override
    public void onSearchResult(List<BlockPos> path, List<Move.Edge> edges, boolean goalReached,
                               int expanded, long ms, double finalCost) {
        if (!BotConfig.pathDebug) return;
        synchronized (lock) {
            // edges carries a null sentinel for the start node (no entering edge);
            // List.copyOf rejects nulls (NPE), so use a null-tolerant ArrayList copy.
            plannedRoutes.add(new PathSession.PlannedRoute(
                    new ArrayList<>(path), new ArrayList<>(edges), goalReached, repathCount++, expanded, ms, finalCost));
        }
    }

    @Override
    public void onWalkerTick(WalkerSample sample) {
        if (!BotConfig.pathDebug) return;
        synchronized (lock) {
            int cap = Math.max(100, BotConfig.pathDebugMaxSamples);
            trajectory.addLast(sample);
            while (trajectory.size() > cap) trajectory.removeFirst();
        }
    }

    @Override
    public void onTerminal(Outcome o, String r) {
        if (!BotConfig.pathDebug) return;
        boolean dump;
        PathSession snap;
        synchronized (lock) {
            this.outcome = o;
            this.reason = r;
            dump = BotConfig.pathChartAutoDump;
            snap = dump ? snapshotLocked() : null;
        }
        if (dump && snap != null) {
            // Render+write off the client thread so a terminal tick never hitches.
            Thread t = new Thread(() -> {
                try {
                    var meta = PathChartWriter.write(PathChartRenderer.render(snap, PathChartRenderer.Opts.defaults()), null);
                    LOG.info("[pathdebug] auto-dumped chart {} ({}x{}, outcome={})",
                            meta.get("path"), meta.get("width"), meta.get("height"), o);
                } catch (Exception e) {
                    LOG.warn("[pathdebug] auto-dump failed: {}", e.toString());
                }
            }, "pathdebug-autodump");
            t.setDaemon(true);
            t.start();
        }
    }

    /** Copy-on-read snapshot for the renderer. Safe to call from any thread. */
    public PathSession snapshot() {
        synchronized (lock) { return snapshotLocked(); }
    }

    private PathSession snapshotLocked() {
        return new PathSession(
                start, goalMarker,
                new ArrayList<>(candidates),
                new ArrayList<>(plannedRoutes),
                new ArrayList<>(trajectory),
                outcome, reason, goalDesc);
    }
}
