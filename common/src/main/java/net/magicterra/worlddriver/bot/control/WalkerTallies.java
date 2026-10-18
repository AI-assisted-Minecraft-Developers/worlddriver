package net.magicterra.worlddriver.bot.control;

import net.magicterra.worlddriver.bot.planning.PathFinder;
import net.minecraft.core.BlockPos;

/**
 * Monotonic per-{@link Walker} event counts — a caller that wants a window takes the difference.
 * One walker's, where {@link WalkerTelemetry} is every walker that drove a body: a process reading
 * what ITS walk did wants this one. Written on the walker's tick thread, read from wherever a scene
 * runs, so the fields are volatile; a reader sees a value at most one tick stale.
 *
 * <ul>
 *   <li>{@link #searches} — deep searches that finished (a replan is one more of these);</li>
 *   <li>{@link #recoveryHops} — entries into the stuck-wiggle window where the hop was allowed
 *       to fire ({@link Walker#wiggleHop}), not ticks spent airborne;</li>
 *   <li>{@link #digs} — distinct cells the walker drove a dig at ({@link WalkerDig#avatarDig}):
 *       a dig held across ticks on one cell is one dig, and the same cell dug again after another
 *       cell is a second.</li>
 * </ul>
 *
 * <p>Beside the counts, the last deep search itself ({@link #lastResult}, {@link #lastSearch}):
 * a process that judges route events reads the result the counter just ticked for, and the
 * search it came from is what prices the taxes ({@code Search.taxTotals}) along it.
 */
public final class WalkerTallies {
    public volatile int searches;
    public volatile int recoveryHops;
    public volatile int digs;
    private volatile BlockPos lastDigCell;
    /** The result of the deep search {@link #searches} last counted, before any walker post-processing. */
    public volatile PathFinder.Result lastResult;
    /** The finished search that produced {@link #lastResult}. */
    public volatile PathFinder.Search lastSearch;
    /** Search results the adoption refused, and why it refused the last one; a refused result leaves
     *  the old plan in place, so without these a refusal looks exactly like a search that kept the route. */
    public volatile int refusals;
    public volatile String lastRefusal;
    /** Replans started because a block on the plan ahead changed shape. */
    public volatile int blockReplans;

    /** A dig drove {@code cell} this tick; counted when it is not the cell of the previous dig. */
    void dig(BlockPos cell) {
        if (cell == null) return;
        if (!cell.equals(lastDigCell)) {
            digs++;
            lastDigCell = cell.immutable();
        }
    }

    @Override public String toString() {
        return "searches=" + searches + " recoveryHops=" + recoveryHops + " digs=" + digs + " refusals=" + refusals + " blockReplans=" + blockReplans;
    }
}
