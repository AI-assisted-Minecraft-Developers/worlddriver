package net.magicterra.worlddriver.bot.movement;

import net.magicterra.worlddriver.bot.pathfinder.PathFinder;

/**
 * Runs the client walker's in-flight path search in the time the frame limiter would otherwise sleep.
 *
 * <p>A search advanced a slice per client tick (6 ms walking, 30 ms with no path) took the render thread
 * for that long on the frame the tick ran in: at a 120 fps cap R1's 60 000-node and 22 000-node searches
 * held the client at 60-65 fps for as long as they ran. The limiter sleeps out whatever is left of each
 * frame's 1/cap, so a search spending exactly that leftover costs no frames, and at 120 fps the leftover
 * adds up to as much search time per second as the 30 ms tick slice gave.
 *
 * <p>Render thread only: the walker registers from its tick, the limiter hook spends, and the tick takes off
 * its own slice what the frames spent since the tick before, keeping at least 1 ms so it still collects the
 * result. A frame that only just meets the cap has little slack to give, and the tick tops the search up to
 * what it got before. With no limiter (an uncapped client, a dedicated server) nothing pumps and the tick
 * slices are unchanged.
 */
public final class FrameSearchPump {
    private FrameSearchPump() {}

    /** Left unspent before the limiter's deadline, for the swap and the limiter's own wakeup. */
    private static final long MARGIN_NANOS = 700_000L;
    /** How long a registration stays current. */
    private static final long STALE_NANOS = 100_000_000L;

    private static volatile Thread frameThread;
    private static Walker walker;
    private static long offeredAt, frameStart, spentNanos;

    /** Right after the limiter returns: the next frame's 1/cap starts now. */
    public static void frameStarted() {
        frameThread = Thread.currentThread();
        frameStart = System.nanoTime();
    }

    /** Just before the limiter sleeps out a frame capped at {@code fps}: spend the slack on the search. */
    public static void spendSlack(int fps) {
        if (fps <= 0 || frameStart == 0) return;
        long now = System.nanoTime();
        long slackMs = (frameStart + 1_000_000_000L / fps - now - MARGIN_NANOS) / 1_000_000L;
        if (slackMs < 1) return;
        Walker wk = walker;
        if (wk == null || now - offeredAt > STALE_NANOS) return;
        PathFinder.Search s = wk.seg.activeSearch;
        if (s == null || s.done()) { walker = null; return; }
        s.advance(slackMs);
        spentNanos += System.nanoTime() - now;
    }

    /** From the walker's tick: this walker has a search in flight. */
    static void offer(Walker wk) {
        if (Thread.currentThread() != frameThread) return;
        if (wk != walker) spentNanos = 0;
        walker = wk;
        offeredAt = System.nanoTime();
    }

    /** Milliseconds frames on this thread spent on the search since the last call, which the tick need not spend. */
    static long takeSpentMs() {
        if (Thread.currentThread() != frameThread) return 0;
        long ms = spentNanos / 1_000_000L;
        spentNanos = 0;
        return ms;
    }
}
