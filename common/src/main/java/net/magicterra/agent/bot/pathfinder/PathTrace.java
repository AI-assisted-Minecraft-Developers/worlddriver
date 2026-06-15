package net.magicterra.agent.bot.pathfinder;

import net.magicterra.agent.bot.Goal;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Inert observation seam for pathfinding debug tooling. The core ({@link PathFinder},
 * {@code Walker}) calls {@link PathTraceHolder#SINK} unconditionally; in a release build
 * with the {@code bot.debug} package removed the sink stays {@link #NOOP} and every method
 * is an empty virtual call the JIT elides. The real implementation lives in
 * {@code net.magicterra.agent.bot.debug.PathDebugRecorder} and is registered at client init.
 *
 * Stripping for release: delete the {@code bot.debug} package and the single
 * {@code PathDebugBootstrap.init()} call. This interface + {@link PathTraceHolder} remain,
 * inert. Nothing else in core changes.
 */
public interface PathTrace {

    /** Terminal outcome of a goto session, as classified by the Walker. */
    enum Outcome { SUCCESS, NO_PATH, STUCK, TIMEOUT, CANCELLED, ERROR }

    /**
     * One per-tick execution sample. Plain data only (no Minecraft refs) so the recorder
     * can be snapshotted and rendered off the client thread. {@code targetX/targetZ} are the
     * centre of the path node the Walker was steering toward this tick (NaN when no path);
     * {@code yawActual} is the body yaw at tick entry. Speed and heading-error are derived in
     * the renderer from consecutive samples + targets.
     */
    record WalkerSample(long tick, double x, double y, double z, float yawActual,
                        double targetX, double targetZ, int stepIndex, String moveType,
                        boolean onGround, boolean inWater,
                        String pose, boolean aabbOverlap) {}

    /** Fired in {@code PathFinder.Search}'s constructor — one per (re)path search. */
    void onSearchBegin(BlockPos start, Goal goal);

    /** Fired once per A* node expansion. {@code g} is the accumulated cost at the node. */
    void onNodeExpanded(BlockPos pos, double g);

    /** Fired when the Walker adopts a search Result (success or best-effort fallback). */
    void onSearchResult(List<BlockPos> path, List<Move.Edge> edges, boolean goalReached,
                        int expanded, long ms, double finalCost);

    /** Fired once per Walker tick while a goal is active. */
    void onWalkerTick(WalkerSample sample);

    /** Fired when a goto session terminates. */
    void onTerminal(Outcome outcome, String reason);

    /** No-op sink — the default; release builds keep this and nothing else. */
    PathTrace NOOP = new PathTrace() {
        public void onSearchBegin(BlockPos start, Goal goal) {}
        public void onNodeExpanded(BlockPos pos, double g) {}
        public void onSearchResult(List<BlockPos> path, List<Move.Edge> edges, boolean goalReached,
                                   int expanded, long ms, double finalCost) {}
        public void onWalkerTick(WalkerSample sample) {}
        public void onTerminal(Outcome outcome, String reason) {}
    };
}
