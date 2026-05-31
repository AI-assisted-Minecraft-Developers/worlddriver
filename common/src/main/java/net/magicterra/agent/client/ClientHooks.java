package net.magicterra.agent.client;

/**
 * Broker between the platform-neutral {@code AgentApi} and the platform-supplied
 * {@link ClientAgentApi} implementation. Lives in common so the static reference
 * doesn't pull client classes — only the {@link #register} caller does.
 *
 * Registration order is loose: AgentApi looks up the impl at call time, so the
 * client entrypoint may register before or after the integrated server starts.
 */
public final class ClientHooks {
    private static volatile ClientAgentApi impl;

    private ClientHooks() {}

    public static void register(ClientAgentApi api) {
        impl = api;
        // Boot both RPC and MCP at client init so mc.client.* (and the script
        // evaluator) are reachable from TitleScreen / Pause — before any world
        // exists. Both ensure*Up() calls are idempotent so onServerStarting
        // can call them again without conflict.
        net.magicterra.agent.AgentDriverCommon.ensureRpcUp();
        net.magicterra.agent.AgentDriverCommon.ensureMcpUp();
    }

    public static ClientAgentApi impl() {
        return impl;
    }

    public static boolean isAvailable() {
        return impl != null;
    }
}
