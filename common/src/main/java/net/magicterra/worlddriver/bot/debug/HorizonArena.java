package net.magicterra.worlddriver.bot.debug;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.pathfinder.PathFinder;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Deterministic gate for the receding-horizon early-stop
 * ({@link BotConfig#pathfinderHorizonBlocks}).
 *
 * <p>Reproduces the long-haul FREEZE root cause on a {@link GridWorldView} (so it has
 * none of the live measurement ceiling): a long flat corridor toward a FAR XZ goal that
 * sits far beyond the corridor's end, i.e. unreachable in any single search. Because
 * {@code GridWorldView.isKnown()} is always true, the loaded-chunk frontier commit can
 * NEVER fire here — exactly the "fully-loaded terrain" case (e.g. the spawn mountains)
 * where, with horizon OFF, every search grinds the entire node budget over the whole
 * reachable corridor only to commit a long best-effort segment, then the bot re-searches
 * from there and freezes again.
 *
 * <p>With a horizon set, the search instead STOPS the instant A* advances {@code horizon}
 * blocks toward the goal: each search expands far fewer nodes (cheap → no freeze) and
 * commits a ~{@code horizon}-block forward hop; the re-plan chain still walks the whole
 * corridor. {@link #run} returns both the first-search cost and the full-chain coverage
 * so the GameTest can assert horizon truncates the search WITHOUT losing forward reach.
 *
 * <p>Release strip: {@code bot.debug} package, deletable with the other probes.
 */
public final class HorizonArena {
    private HorizonArena() {}

    /** Corridor spans x[0..CORRIDOR_LEN] at y=60 (bot walks y=61), 3 wide in z. */
    public static final int CORRIDOR_LEN = 210;

    public static final class Result {
        public final int firstExpanded;   // nodes the FIRST search expanded (≈ freeze cost)
        public final int firstEndX;       // x of the first committed segment's end (≈ segment length)
        public final int chainEndX;       // x reached after chaining segments (forward coverage)
        public final int segments;
        public final boolean firstGoalReached;
        Result(int firstExpanded, int firstEndX, int chainEndX, int segments, boolean firstGoalReached) {
            this.firstExpanded = firstExpanded; this.firstEndX = firstEndX;
            this.chainEndX = chainEndX; this.segments = segments; this.firstGoalReached = firstGoalReached;
        }
        @Override public String toString() {
            return "firstExpanded=" + firstExpanded + " firstEndX=" + firstEndX
                    + " chainEndX=" + chainEndX + " segments=" + segments
                    + " firstGoalReached=" + firstGoalReached;
        }
    }

    /**
     * Build the corridor and run the read-only segment-commitment chain (mirrors the
     * Walker: feed each committed endpoint back as the next start) under the given
     * {@code horizonBlocks}. Saves/restores the live {@link BotConfig#pathfinderHorizonBlocks}.
     */
    public static Result run(int horizonBlocks) {
        return run(horizonBlocks, 0);
    }

    /** As {@link #run(int)} but also sets {@link BotConfig#pathfinderSoftCommitNodes}
     *  (0 = off) for the duration, so a test can isolate the soft-commit early-stop. */
    public static Result run(int horizonBlocks, int softCommitNodes) {
        // place=false: no bridging, so the search is BOUNDED by the corridor (a few
        // hundred nodes) instead of bridging out toward the far goal and grinding the
        // full node budget every segment — which, run synchronously in the GameTest,
        // would starve the server thread and time out the concurrent RPC sub-tests.
        // The horizon mechanism is unaffected (it early-stops on goal-ward progress,
        // independent of whether the segment walks or bridges).
        GridWorldView w = new GridWorldView(0, 60, -1, CORRIDOR_LEN + 1, 12, 3).withPlace(false);
        // Flat floor the length of the corridor, 3 cells wide; everything above is AIR.
        w.fill(0, 60, -1, CORRIDOR_LEN, 60, 1, GridWorldView.SOLID);

        BlockPos start = new BlockPos(0, 61, 0);
        Goal goal = new Goal.XZ(1000, 0);   // far beyond the corridor end → never reached in one search

        // NOTHING GLOBAL IS TOUCHED HERE, and that is the point of the shape.
        //
        // This used to write BotConfig.pathfinderHorizonBlocks / pathfinderSoftCommitNodes and put
        // them back in a finally. That is unsound the moment anything else in the JVM is planning,
        // and it silently disconnected this arena's only variable: pfHorizonBlocks() returns 0
        // whenever BotConfig.pathfinderBoxedEscalate is set, and a client Walker on another thread
        // writes that flag every tick it runs. On integratedServerNeoforge, with the client bot
        // churning on an unreachable goal, run(48) planned with the horizon OFF and returned numbers
        // byte-identical to run(0) — the scene measured nothing and still reported a colour.
        //
        // Per-finder tuning removes the shared knob rather than trying to time the sharing.
        int firstExpanded = -1, firstEndX = -1, segments = 0;
        boolean firstGoalReached = false;
        BlockPos from = start;
        {
            for (; segments < 60; segments++) {
                PathFinder.Result r = new PathFinder(w, 8_000, 30_000).withOwner("debug.horizon")
                        .withTuning(horizonBlocks, softCommitNodes, BotConfig.pathfinderDepthPenalty)
                        .findPath(from, goal);
                List<BlockPos> path = r.path();
                BlockPos end = path.isEmpty() ? from : path.get(path.size() - 1);
                if (segments == 0) {
                    firstExpanded = r.expanded();
                    firstEndX = end.getX();
                    firstGoalReached = r.goalReached();
                }
                if (r.goalReached()) { from = end; segments++; break; }
                if (end.equals(from)) break;   // no progress
                // A segment ending FARTHER from the goal is the last-resort ESCAPE
                // (boxed search, budget burned): the corridor is over. The Walker
                // penalizes the dead pocket and breaks out; this read-only probe
                // has no penalty model, so chaining on would just bounce 0↔210.
                if (goal.estimate(end) > goal.estimate(from)) break;
                from = end;
            }
        }
        return new Result(firstExpanded, firstEndX, from.getX(), segments, firstGoalReached);
    }
}
