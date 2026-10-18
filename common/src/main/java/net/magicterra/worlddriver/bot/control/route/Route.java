package net.magicterra.worlddriver.bot.control.route;

import java.util.List;

import net.magicterra.worlddriver.bot.planning.PathFinder;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.game.observe.BlockChanges;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.magicterra.worlddriver.bot.control.Walker;
import net.magicterra.worlddriver.bot.control.WalkerConstants;

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
public final class Route {

    /** What an adoption did with a search result. A refusal leaves the plan exactly as it was. */
    public record Adoption(boolean adopted, String refusal) {
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

    public final SegmentCommit seg = new SegmentCommit();

    /** Async search + best-effort segment commit: the in-flight time-sliced A* and the partial-segment
     *  plumbing around it. {@link #reset()} clears all of it with the plan: {@code pathBestEffort}
     *  describes a plan that is gone, and the two search flags a search that is gone. Adoption sets
     *  them again, and {@code frontierWaitTicks} also restarts when an adoption makes progress. */
    public static final class SegmentCommit {
        public PathFinder.Search activeSearch;             // in-flight time-sliced A* (null = none)
        public boolean pathBestEffort;                     // current path is a best-effort partial (goal NOT reached) → commit to it before re-searching
        public BlockPos commitEnd;                         // last node of the current best-effort segment (null for a full path) → where continuation searches launch from
        public boolean searchFromEnd;                      // activeSearch is a continuation launched from commitEnd (deferred splice) vs a foot-search (splice immediately)
        PathFinder.Result pendingSegment;           // a finished continuation segment awaiting splice at the current segment's end
        public boolean searchSuppressedPlace;              // the in-flight search dropped placing moves (block-budget reroute) → adopt its result without re-checking
        boolean afterBlockChange;                   // the in-flight foot-search was started because a block on the plan changed → its result replaces the plan even if it turns back
        private int frontierWaitTicks;                      // bounded retries re-searching at a loaded-chunk frontier before giving up (pathfinderFrontierCommit)
        void reset() {
            activeSearch = null;
            pathBestEffort = false;
            commitEnd = null;
            searchFromEnd = false;
            pendingSegment = null;
            searchSuppressedPlace = false;
            afterBlockChange = false;
            frontierWaitTicks = 0;
        }
    }

    // ---- reads ----

    /** The nodes, or null when there is no plan. */
    public List<BlockPos> path() { return path; }
    public List<Move.Edge> edges() { return edges; }
    /** Index of the node the body is heading for. */
    public int step() { return step; }
    public boolean replay() { return replay; }
    String lastDrop() { return lastDrop; }

    public Move.Edge edgeAt(int i) {
        return (edges != null && i >= 0 && i < edges.size()) ? edges.get(i) : null;
    }

    // ---- writes ----

    /** Make {@code nodes} the plan, heading for {@code at}; a {@code bestEffort} plan stops short of the
     *  goal, and its last node is where the continuation will start. Only an adoption calls this. */
    public void install(List<BlockPos> nodes, List<Move.Edge> nodeEdges, int at, boolean bestEffort) {
        path = nodes;
        edges = nodeEdges;
        step = at;
        seg.pathBestEffort = bestEffort;
        seg.commitEnd = (bestEffort && !nodes.isEmpty()) ? nodes.get(nodes.size() - 1) : null;
        seg.frontierWaitTicks = 0;          // progress made → reset the frontier re-search budget
        watchFromNow();
    }

    /** The body reached the node it was heading for. */
    public void advance() { step++; }

    /** Head for node {@code i}, clamped to the plan: a test seam that stages a body mid-plan. */
    public void seek(int i) { step = Math.max(0, Math.min(i, path == null ? 1 : path.size())); }

    /**
     * Throw the plan away so the walk plans again from where the body is. {@code why} is kept for the
     * report: in a replay nothing plans again, so a drop there ends the walk and this is its reason.
     */
    public void drop(String why) {
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
    public void clear() {
        path = null;
        edges = null;
        step = 0;
        seg.reset();
        changedAt = null;
    }

    /** A new goal: {@link #clear}, out of replay, no drop on record. */
    public void restart() {
        clear();
        replay = false;
        lastDrop = null;
    }

    public void enterReplay() { replay = true; }

    // ---- searches ----

    /** Start a search from the feet; its result replaces the plan when it lands. */
    public void searchFromFoot(PathFinder.Search search, boolean placeSuppressed) {
        seg.activeSearch = search;
        seg.searchFromEnd = false;
        seg.searchSuppressedPlace = placeSuppressed;
        seg.afterBlockChange = false;
        seg.pendingSegment = null;            // a foot-search supersedes any stashed continuation
    }

    /** {@link #searchFromFoot} for the change {@link #blockChangeDue} reported; returns a line saying where
     *  it was, and holds the next such replan off for {@link #CHANGE_REPLAN_GAP} ticks. */
    String searchAfterBlockChange(PathFinder.Search search) {
        String where = changedAt.toShortString() + " on the plan " + changedAhead + " nodes ahead";
        searchFromFoot(search, false);
        seg.afterBlockChange = true;
        changedAt = null;
        changeReplanCooldown = CHANGE_REPLAN_GAP;
        return where;
    }

    /**
     * Start the continuation from the committed segment end — the one place one starts. Its result waits
     * in {@code pendingSegment} until the body reaches that end, and is spliced on there.
     */
    public void searchFromEnd(Walker wk, net.magicterra.worlddriver.bot.world.WorldView world) {
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
    public boolean retryAtFrontier(Walker wk, net.magicterra.worlddriver.bot.world.WorldView world) {
        if (replay || !net.magicterra.worlddriver.config.PlanningConfig.pathfinderFrontierCommit
                || seg.commitEnd == null || seg.frontierWaitTicks >= WalkerConstants.FRONTIER_WAIT_CAP) return false;
        seg.frontierWaitTicks++;
        if (seg.activeSearch == null) searchFromEnd(wk, world);
        return true;
    }

    /** Stop the search in flight, if any, without touching the plan. */
    public void abortSearch() {
        seg.activeSearch = null;
        seg.searchFromEnd = false;
    }

    /** The landed search's result; the search slot is free afterwards. */
    public PathFinder.Result takeSearchResult() {
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

    // ---- block changes ----

    /** Squared distance from the body's eyes within which a changed block may be the body's own work —
     *  a dig, a placed rung, a plug — and is left to the follower, which made it on purpose or will bump
     *  into it. Survival block reach is 4.5; the margin keeps a stretch of arm out of the question. */
    private static final double OWN_REACH_SQ = 6.0 * 6.0;
    /** How close, across, a changed cell's middle must be to a leg of the plan to be in the body's way: a
     *  cell's half-width plus a player's, so a block beside the corridor is not one in it. */
    private static final double IN_THE_WAY = 0.8;
    /** Ticks between two replans for block changes: water spreading or a farm cycling beside a route
     *  would otherwise start a search every tick. */
    private static final int CHANGE_REPLAN_GAP = 20;

    /** The level whose change log this plan reads, and the sequence number it has read up to. */
    private Level changesLevel;
    private long changesRead;
    /** A change on the plan ahead waiting for its replan, and how many nodes ahead of the body it was. */
    private BlockPos changedAt;
    private int changedAhead;
    private int changeReplanCooldown;

    /** Changes from before a plan existed are what it was planned against. */
    private void watchFromNow() {
        changedAt = null;
        if (changesLevel != null) changesRead = BlockChanges.head(changesLevel);
    }

    /**
     * Read what changed in the body's level since the last tick. A collision change on the plan ahead and
     * beyond the body's reach is held for a replan; a stashed continuation it lands on is thrown away, so
     * the next one is planned on the new shape.
     *
     * <p>The question vanilla asks of every mob navigation on each block update, with two differences: it
     * is asked once a tick from the log rather than inside the update, and changes within the body's reach
     * are ignored, because the walker digs and places there itself and a replan for each of its own blocks
     * would tear up every bridge and climb it builds.
     *
     * @return true when the held change should be replanned now
     */
    boolean blockChangeDue(LivingEntity body) {
        if (changeReplanCooldown > 0) changeReplanCooldown--;
        Level level = body.level();
        if (level != changesLevel) {
            changesLevel = level;
            changesRead = BlockChanges.head(level);
            return false;
        }
        long[] cells = BlockChanges.since(level, changesRead);
        // Overflowed: more changed than the log holds (a scene rebuilding its arena). What was lost
        // cannot be judged, and replanning on every overflow would replan on every rebuild.
        if (cells == null) { changesRead = BlockChanges.head(level); return false; }
        changesRead += cells.length;
        if (replay || path == null) return false;
        for (long c : cells) {
            BlockPos at = BlockPos.of(c);
            if (beyondReach(at, body) && !plannedEdit(at)) {
                int ahead = legInTheWay(path, Math.max(step, 1), at);
                if (ahead >= 0 && changedAt == null) { changedAt = at; changedAhead = ahead - step; }
                PathFinder.Result next = seg.pendingSegment;
                if (next != null && next.hasPath() && legInTheWay(next.path(), 1, at) >= 0) seg.pendingSegment = null;
            }
        }
        return changedAt != null && changeReplanCooldown == 0;
    }

    private static boolean beyondReach(BlockPos at, LivingEntity body) {
        double dx = at.getX() + 0.5 - body.getX(), dy = at.getY() + 0.5 - body.getEyeY(), dz = at.getZ() + 0.5 - body.getZ();
        return dx * dx + dy * dy + dz * dz > OWN_REACH_SQ;
    }

    /** True when the plan itself breaks or fills {@code at} further on: that change is the plan's own. */
    private boolean plannedEdit(BlockPos at) {
        for (int i = Math.max(step, 0); i < path.size(); i++) {
            Move.Edge e = edgeAt(i);
            if (e != null && (e.toBreak.contains(at) || e.toPlace.contains(at))) return true;
        }
        return false;
    }

    /** The index of the first leg from {@code from} on (the leg into node i) whose corridor holds {@code at}
     *  — its floor, feet or head row — or -1. */
    private static int legInTheWay(List<BlockPos> nodes, int from, BlockPos at) {
        double cx = at.getX() + 0.5, cz = at.getZ() + 0.5;
        for (int i = Math.max(from, 1); i < nodes.size(); i++) {
            BlockPos a = nodes.get(i - 1), b = nodes.get(i);
            if (at.getY() < Math.min(a.getY(), b.getY()) - 1 || at.getY() > Math.max(a.getY(), b.getY()) + 1) continue;
            double ax = a.getX() + 0.5, az = a.getZ() + 0.5, bx = b.getX() + 0.5 - ax, bz = b.getZ() + 0.5 - az;
            double len2 = bx * bx + bz * bz;
            double t = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((cx - ax) * bx + (cz - az) * bz) / len2));
            double ex = ax + bx * t - cx, ez = az + bz * t - cz;
            if (ex * ex + ez * ez <= IN_THE_WAY * IN_THE_WAY) return i;
        }
        return -1;
    }
}
