package net.magicterra.worlddriver.game.ui;

import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Running a task at the client's next tick boundary. A task that only needs the client thread goes through
 * {@link net.magicterra.worlddriver.game.ClientHop}, which withdraws it when it times out.
 */
public final class ClientThread {
    private ClientThread() {}

    /** Tasks waiting for a tick boundary; drained by {@link #drainNextTick()}. */
    private static final Queue<Runnable> NEXT_TICK = new ConcurrentLinkedQueue<>();

    /**
     * Runs {@code task} on the client thread at the next client TICK rather than the next render task.
     * {@code mc.execute} can run within the same tick the caller's previous task ran in, and some client state only
     * advances on a tick boundary — a keybind is consumed per tick, and a screen a key press opened is not the screen
     * the release should reach.
     *
     * <p>Holds no thread: the stage completes with the task's value when the tick runs it, or with null when the client
     * did not tick within {@code timeoutMs} (a stopped or hung client), so callers report a half-delivered event
     * instead of hanging. The task still runs when the client does tick. Queued from the render thread too, which runs
     * it at its own next tick.
     */
    public static <T> CompletableFuture<T> nextTick(Supplier<T> task, long timeoutMs) {
        CompletableFuture<T> f = new CompletableFuture<>();
        NEXT_TICK.add(() -> {
            try {
                f.complete(task.get());
            } catch (Throwable e) {
                f.completeExceptionally(e);
            }
        });
        return f.completeOnTimeout(null, timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** Runs what {@link #nextTick} queued. Render thread only, once per client tick. */
    public static void drainNextTick() {
        Runnable r;
        while ((r = NEXT_TICK.poll()) != null) r.run();
    }
}
