package net.magicterra.worlddriver.game.ui;

import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * How many frames the client has drawn, and a way to wait for the next one.
 *
 * <p>A capture reads the main render target, which holds whatever was drawn LAST. Nothing in the image says when that
 * was: a screen opened one millisecond ago is not in it until the next frame, and the picture that comes back is the
 * right size, entirely plausible, and shows the world as it was before the thing the caller just did. Two of those were
 * read as evidence before this counter existed, and the second one was misread even by someone who had just been warned
 * about the first.
 *
 * <p>{@code fps} does not settle it either — it only rules out a renderer that has stopped completely. A client drawing
 * at 15 fps still hands back a frame up to 66 ms stale, which is several RPC round-trips.
 *
 * <p>So the count is the measurement: two captures carrying the same {@code frame} are the same image, and a capture
 * taken after this counter moves is of a frame drawn after the caller asked for it. Advanced from the end of
 * {@code GameRenderer.render} — the call that fills the target the capture reads — and not from the window's buffer
 * swap, which vanilla runs even on a tick that drew nothing.
 */
public final class FrameClock {

    private static final AtomicLong DRAWN = new AtomicLong();
    private static final Queue<CompletableFuture<Long>> WAITERS = new ConcurrentLinkedQueue<>();

    private FrameClock() {}

    /** Called at the end of every rendered frame; see {@code GameRendererMixin}. */
    public static void frameDrawn() {
        long now = DRAWN.incrementAndGet();
        CompletableFuture<Long> w;
        while ((w = WAITERS.poll()) != null) {
            w.complete(now);
        }
    }

    public static long drawn() {
        return DRAWN.get();
    }

    /**
     * Completes once a frame is drawn after {@code from}, or {@code timeoutMs} passes, with the count reached either
     * way, so {@code result > from} is the test for "a fresh frame exists". Holds no thread, so it is safe on the
     * client thread too, where it simply completes after this frame.
     */
    public static CompletableFuture<Long> after(long from, long timeoutMs) {
        CompletableFuture<Long> f = new CompletableFuture<>();
        WAITERS.add(f);
        // A frame drawn between the caller reading `from` and the add above polled nothing for us.
        if (DRAWN.get() > from) {
            f.complete(DRAWN.get());
        }
        CompletableFuture.delayedExecutor(timeoutMs, TimeUnit.MILLISECONDS).execute(() -> {
            if (f.complete(DRAWN.get())) {
                WAITERS.remove(f);
            }
        });
        return f;
    }
}
