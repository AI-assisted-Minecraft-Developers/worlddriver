package net.magicterra.worlddriver.bot.util;

import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.magicterra.worlddriver.api.thread.ServerThreadHop;
import net.minecraft.client.Minecraft;

/**
 * The client-thread hop behind {@link BotUtil#onClient}: {@link ServerThreadHop}'s state machine
 * over the client's executor, so a timed-out task is withdrawn ({@code -32001}, safe to retry) or
 * reported as already running ({@code -32002}), never left queued to run after its caller was told
 * it failed. A class of its own because {@code BotUtil}'s static initialiser reads block registries,
 * which a JVM test cannot load.
 */
public final class ClientHop {
    private ClientHop() {}

    /** Default budget for waiting on a client-tick hop. Mirrors
     *  {@code DriverApplication.SERVER_THREAD_TIMEOUT_MS} — the server-side twin of this
     *  bridge — so a stalled client surfaces as a clear error instead of parking
     *  the calling RPC/MCP thread forever. Override with
     *  {@code -Dworlddriver.clientThreadTimeoutMs=N}. */
    private static final long CLIENT_THREAD_TIMEOUT_MS =
            Long.getLong("worlddriver.clientThreadTimeoutMs", 8_000L);

    /** Run {@code body} on the client thread and return its value; see {@link BotUtil#onClient}. */
    public static <T> T call(Supplier<T> body) {
        return hop().call(body);
    }

    /** The hop itself, for a caller that submits without blocking. */
    public static ServerThreadHop hop() {
        Minecraft mc = Minecraft.getInstance();
        return new ServerThreadHop(mc, mc::isSameThread, CLIENT_THREAD_TIMEOUT_MS, "client thread");
    }

    static <T> T call(Executor client, BooleanSupplier onClientThread, long timeoutMs, Supplier<T> body) {
        return new ServerThreadHop(client, onClientThread, timeoutMs, "client thread").call(body);
    }
}
