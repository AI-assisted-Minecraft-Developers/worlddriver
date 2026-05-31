package net.magicterra.agent.client.internal;

import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Shared client-thread marshaller for the {@code client.internal.*} helpers.
 * Every {@code mc.client.*} behavior runs its body through {@link #runOnClient}
 * so it executes on the Minecraft render thread while the RPC handler thread
 * blocks for the result. Extracted from {@code ClientAgentApiImpl} so the
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
}
