package net.magicterra.worlddriver.application.client;

/**
 * Broker between the platform-neutral driver and the platform-supplied
 * {@link ClientDriverApi} implementation. Lives in common so the static reference
 * doesn't pull client classes — only the {@link #register} caller does. What else a client
 * boots alongside it (the client thread, RPC and MCP, the debug tools) is
 * {@code WorldDriverClientEvents.install}'s.
 *
 * Registration order is loose: the driver looks up the impl at call time, so the
 * client entrypoint may register before or after the integrated server starts.
 */
public final class ClientHooks {
    private static volatile ClientDriverApi impl;

    private ClientHooks() {}

    public static void register(ClientDriverApi api) {
        impl = api;
    }

    public static ClientDriverApi impl() {
        return impl;
    }

    public static boolean isAvailable() {
        return impl != null;
    }
}
