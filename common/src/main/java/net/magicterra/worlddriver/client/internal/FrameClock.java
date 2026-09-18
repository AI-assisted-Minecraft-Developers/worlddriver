package net.magicterra.worlddriver.client.internal;

/**
 * How many frames the client has drawn, and a way to wait for the next one.
 *
 * <p>A capture reads the main render target, which holds whatever was drawn LAST. Nothing in the
 * image says when that was: a screen opened one millisecond ago is not in it until the next frame,
 * and the picture that comes back is the right size, entirely plausible, and shows the world as it
 * was before the thing the caller just did. Two of those were read as evidence before this counter
 * existed, and the second one was misread even by someone who had just been warned about the first.
 *
 * <p>{@code fps} does not settle it either — it only rules out a renderer that has stopped
 * completely. A client drawing at 15 fps still hands back a frame up to 66 ms stale, which is
 * several RPC round-trips.
 *
 * <p>So the count is the measurement: two captures carrying the same {@code frame} are the same
 * image, and a capture taken after this counter moves is of a frame drawn after the caller asked
 * for it. Advanced from the end of {@code GameRenderer.render} — the call that fills the target
 * the capture reads — and not from the window's buffer swap, which vanilla runs even on a tick
 * that drew nothing.
 */
public final class FrameClock {
    private FrameClock() {}

    private static final Object DRAWN_LOCK = new Object();
    private static long drawn;

    /** Called at the end of every rendered frame; see {@code GameRendererMixin}. */
    public static void frameDrawn() {
        synchronized (DRAWN_LOCK) {
            drawn++;
            DRAWN_LOCK.notifyAll();
        }
    }

    public static long drawn() {
        synchronized (DRAWN_LOCK) { return drawn; }
    }

    /**
     * Blocks until a frame is drawn after {@code from}, or {@code timeoutMs} passes; returns the
     * count reached either way, so {@code result > from} is the test for "a fresh frame exists".
     *
     * <p>Must not be called from the client thread — that is the thread that would draw the frame,
     * so waiting on it waits forever. The caller checks; this cannot check for itself without
     * reaching into Minecraft from a class that otherwise needs nothing from it.
     */
    public static long awaitAfter(long from, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (DRAWN_LOCK) {
            while (drawn <= from) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    DRAWN_LOCK.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return drawn;
        }
    }
}
