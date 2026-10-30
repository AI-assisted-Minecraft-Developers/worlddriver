package net.magicterra.worlddriver.api.game;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.registry.Scripting;

/**
 * Where code in the same game finds the driver: the registry every method is called through, the host the game side
 * reports to, and the ports the two servers listen on. The driver comes up at client init on a client and at server
 * start on a dedicated server, after every mod's setup, so a mod that publishes methods of its own does it in
 * {@link #whenReady}.
 */
public final class WorldDriver {
    private WorldDriver() {}

    private static volatile ApiRegistry registry;
    private static volatile DriverHost host;
    private static volatile int rpcPort = -1;
    private static volatile int mcpPort = -1;
    private static volatile Scripting scripting;
    private static final List<Consumer<ApiRegistry>> WAITING = new ArrayList<>();

    /** Every method the driver answers; null until the driver is up. */
    public static ApiRegistry registry() {
        return registry;
    }

    /** What the game side calls on the driver: lifecycle, events, restore. Null until the driver is up. */
    public static DriverHost host() {
        return host;
    }

    /** The WebSocket RPC server's port, -1 until it listens. */
    public static int rpcPort() {
        return rpcPort;
    }

    /** The MCP HTTP server's port, -1 until it listens. */
    public static int mcpPort() {
        return mcpPort;
    }

    /** Evaluates JavaScript bound to this game's methods; null until the driver is up. */
    public static Scripting scripting() {
        return scripting;
    }

    /** Run {@code then} with the registry once the driver is up: now if it is, else on the thread that brings it up. */
    public static void whenReady(Consumer<ApiRegistry> then) {
        ApiRegistry r;
        synchronized (WAITING) {
            r = registry;
            if (r == null) {
                WAITING.add(then);
                return;
            }
        }
        then.accept(r);
    }

    /** The mod's bootstrap: the driver is up. Runs everything {@link #whenReady} held, in order. */
    public static void up(ApiRegistry registry, DriverHost host) {
        List<Consumer<ApiRegistry>> ready;
        synchronized (WAITING) {
            WorldDriver.host = host;
            WorldDriver.registry = registry;
            ready = List.copyOf(WAITING);
            WAITING.clear();
        }
        for (Consumer<ApiRegistry> c : ready) {
            c.accept(registry);
        }
    }

    /** The mod's bootstrap: the RPC server listens on {@code port}. */
    public static void rpcListening(int port) {
        rpcPort = port;
    }

    /** The mod's bootstrap: the MCP server listens on {@code port}. */
    public static void mcpListening(int port) {
        mcpPort = port;
    }

    /** The mod's bootstrap: JavaScript can be evaluated through {@code engine}. */
    public static void scriptingReady(Scripting engine) {
        scripting = engine;
    }
}
