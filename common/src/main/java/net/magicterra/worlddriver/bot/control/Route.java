package net.magicterra.worlddriver.bot.control;

import java.util.List;

import net.magicterra.worlddriver.bot.planning.PathFinder;
import net.magicterra.worlddriver.bot.rules.Move;
import net.minecraft.core.BlockPos;

/**
 * The plan a walk follows, and the only thing that changes it: the nodes, the edge entering each, the
 * node the body is heading for, and the searches that will replace or extend them.
 *
 * <p>Every other part of the walker reads the plan through the accessors here. Writing it is one of
 * four named acts — installing an adopted search result ({@link RouteAdoption#adopt}, the only caller of
 * {@link #install}), moving on to the next node, dropping the plan with a reason, or clearing it for a
 * new goal — so a reader can find every place a plan ends by searching for those, which the nine tick
 * phases' direct field writes never allowed.
 */
final class Route {

    /** What an adoption did with a search result. A refusal leaves the plan exactly as it was. */
    record Adoption(boolean adopted, String refusal) {
        static final Adoption ADOPTED = new Adoption(true, null);
        static Adoption refused(String why) { return new Adoption(false, why); }
    }

    private List<BlockPos> path;
    private List<Move.Edge> edges;   // aligned with path; edges.get(i) enters path.get(i)
    private int step;
    /** Following a fixed archived plan: nothing searches, and a drop ends the walk instead of replanning. */
    private boolean replay;
    /** Why the plan was last dropped; null until a drop, cleared with the goal. */
    private String lastDrop;

    final SegmentCommit seg = new SegmentCommit();

    /** Async search + best-effort segment commit: the in-flight time-sliced A* and the partial-segment
     *  plumbing around it. {@link #reset()} clears the search and continuation state;
     *  {@code pathBestEffort}/{@code searchSuppressedPlace} follow adoption, and
     *  {@code frontierWaitTicks} restarts when an adoption makes progress. */
    static final class SegmentCommit {
        PathFinder.Search activeSearch;             // in-flight time-sliced A* (null = none)
        boolean pathBestEffort;                     // current path is a best-effort partial (goal NOT reached) → commit to it before re-searching
        BlockPos commitEnd;                         // last node of the current best-effort segment (null for a full path) → where continuation searches launch from
        boolean searchFromEnd;                      // activeSearch is a continuation launched from commitEnd (deferred splice) vs a foot-search (splice immediately)
        PathFinder.Result pendingSegment;           // a finished continuation segment awaiting splice at the current segment's end
        boolean searchSuppressedPlace;              // the in-flight search dropped placing moves (block-budget reroute) → adopt its result without re-checking
        int frontierWaitTicks;                      // bounded retries re-searching at a loaded-chunk frontier before giving up (pathfinderFrontierCommit)
        void reset() {
            activeSearch = null;
            commitEnd = null;
            searchFromEnd = false;
            pendingSegment = null;
        }
    }

    // ---- reads ----

    /** The nodes, or null when there is no plan. */
    List<BlockPos> path() { return path; }
    List<Move.Edge> edges() { return edges; }
    /** Index of the node the body is heading for. */
    int step() { return step; }
    boolean replay() { return replay; }
    String lastDrop() { return lastDrop; }

    Move.Edge edgeAt(int i) {
        return (edges != null && i >= 0 && i < edges.size()) ? edges.get(i) : null;
    }

    // ---- writes ----

    /** Make {@code nodes} the plan, heading for {@code at}; a {@code bestEffort} plan stops short of the
     *  goal, and its last node is where the continuation will start. Only an adoption calls this. */
    void install(List<BlockPos> nodes, List<Move.Edge> nodeEdges, int at, boolean bestEffort) {
        path = nodes;
        edges = nodeEdges;
        step = at;
        seg.pathBestEffort = bestEffort;
        seg.commitEnd = (bestEffort && !nodes.isEmpty()) ? nodes.get(nodes.size() - 1) : null;
        seg.frontierWaitTicks = 0;          // progress made → reset the frontier re-search budget
    }

    /** The body reached the node it was heading for. */
    void advance() { step++; }

    /** Head for node {@code i}, clamped to the plan: a test seam that stages a body mid-plan. */
    void seek(int i) { step = Math.max(0, Math.min(i, path == null ? 1 : path.size())); }

    /**
     * Throw the plan away so the walk plans again from where the body is. {@code why} is kept for the
     * report: in a replay nothing plans again, so a drop there ends the walk and this is its reason.
     */
    void drop(String why) {
        path = null;
        edges = null;
        step = 0;
        lastDrop = why;
    }

    /** {@link #drop}, and forget the segment end too, so no continuation launches from a place the
     *  body never reached. */
    void abandonSegment(String why) {
        drop(why);
        seg.commitEnd = null;
    }

    /** Forget the plan and every search for it: a new goal, or a plan the caller no longer trusts. */
    void clear() {
        path = null;
        edges = null;
        step = 0;
        seg.reset();
    }

    /** A new goal: {@link #clear}, out of replay, no drop on record. */
    void restart() {
        clear();
        replay = false;
        lastDrop = null;
    }

    void enterReplay() { replay = true; }

    // ---- searches ----

    /** Start a search from the feet; its result replaces the plan when it lands. */
    void searchFromFoot(PathFinder.Search search, boolean placeSuppressed) {
        seg.activeSearch = search;
        seg.searchFromEnd = false;
        seg.searchSuppressedPlace = placeSuppressed;
        seg.pendingSegment = null;            // a foot-search supersedes any stashed continuation
    }

    /**
     * Start the continuation from the committed segment end — the one place one starts. Its result waits
     * in {@code pendingSegment} until the body reaches that end, and is spliced on there.
     */
    void searchFromEnd(Walker wk, net.magicterra.worlddriver.bot.world.WorldView world) {
        seg.activeSearch = wk.newPathFinder(world).newSearch(seg.commitEnd, wk.goal);
        seg.searchFromEnd = true;
        seg.searchSuppressedPlace = false;
    }

    /**
     * At a loaded-chunk frontier the (stale) eager continuation from commitEnd — computed before the bot
     * arrived — found no onward route. Re-search FRESH from the frontier: now that the bot stands there,
     * chunks ~render-distance further have loaded and the next segment is visible. Bounded by
     * {@link WalkerConstants#FRONTIER_WAIT_CAP} (the bot is stationary while waiting, so retrying more
     * can't load new chunks) so a genuine box-in still terminates.
     *
     * @return true while a retry is running, so the caller holds; false when out of retries
     */
    boolean retryAtFrontier(Walker wk, net.magicterra.worlddriver.bot.world.WorldView world) {
        if (replay || !net.magicterra.worlddriver.config.PlanningConfig.pathfinderFrontierCommit
                || seg.commitEnd == null || seg.frontierWaitTicks >= WalkerConstants.FRONTIER_WAIT_CAP) return false;
        seg.frontierWaitTicks++;
        if (seg.activeSearch == null) searchFromEnd(wk, world);
        return true;
    }

    /** Stop the search in flight, if any, without touching the plan. */
    void abortSearch() {
        seg.activeSearch = null;
        seg.searchFromEnd = false;
    }

    /** The landed search's result; the search slot is free afterwards. */
    PathFinder.Result takeSearchResult() {
        PathFinder.Result res = seg.activeSearch.result();
        seg.activeSearch = null;
        seg.searchFromEnd = false;
        return res;
    }

    /** Keep a landed continuation for the segment end. */
    void stashContinuation(PathFinder.Result res) { seg.pendingSegment = res; }

    /** The stashed continuation, removed. */
    PathFinder.Result takeContinuation() {
        PathFinder.Result next = seg.pendingSegment;
        seg.pendingSegment = null;
        return next;
    }
}
