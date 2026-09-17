package net.magicterra.worlddriver.client.internal;

import net.minecraft.client.Minecraft;

import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Shared client-thread marshaller for the {@code client.internal.*} helpers.
 * Every {@code mc.client.*} behavior runs its body through {@link #runOnClient}
 * so it executes on the Minecraft render thread while the RPC handler thread
 * blocks for the result. Extracted from {@code ClientDriverApiImpl} so the
 * per-concern helper classes can share one implementation.
 */
public final class ClientThread {
    private ClientThread() {}

    public static <T> T runOnClient(Supplier<T> task) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) return task.get();
        CompletableFuture<T> f = new CompletableFuture<>();
        mc.execute(() -> {
            try { f.complete(task.get()); }
            catch (Throwable e) { f.completeExceptionally(e); }
        });
        try { return f.get(30, TimeUnit.SECONDS); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Tasks waiting for a tick boundary; drained by {@link #drainNextTick()}. */
    private static final Queue<Runnable> NEXT_TICK = new ConcurrentLinkedQueue<>();

    /**
     * Like {@link #runOnClient} but waits for the next client TICK rather than the next render
     * task. {@code mc.execute} can run within the same tick the caller's previous task ran in, and
     * some client state only advances on a tick boundary — a keybind is consumed per tick, and a
     * screen a key press opened is not the screen the release should reach.
     *
     * <p>Returns null when the client did not tick within {@code timeoutMs} (a stopped or hung
     * client), so callers report a half-delivered event instead of hanging. On the render thread
     * the task runs immediately: that thread is the one that would deliver the tick, so waiting
     * for it there is a deadlock.
     */
    public static <T> T runNextTick(Supplier<T> task, long timeoutMs) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) return task.get();
        CompletableFuture<T> f = new CompletableFuture<>();
        NEXT_TICK.add(() -> {
            try { f.complete(task.get()); }
            catch (Throwable e) { f.completeExceptionally(e); }
        });
        try { return f.get(timeoutMs, TimeUnit.MILLISECONDS); }
        catch (TimeoutException e) { return null; }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Runs what {@link #runNextTick} queued. Render thread only, once per client tick. */
    public static void drainNextTick() {
        Runnable r;
        while ((r = NEXT_TICK.poll()) != null) r.run();
    }
}
