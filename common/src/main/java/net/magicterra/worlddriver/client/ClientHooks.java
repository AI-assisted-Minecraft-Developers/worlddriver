package net.magicterra.worlddriver.client;
import net.magicterra.worlddriver.WorldDriverCommon;

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
        WorldDriverCommon.ensureRpcUp();
        WorldDriverCommon.ensureMcpUp();
        // Optional, strippable: wire the path-debug recorder + mc.debug.pathChart now that
        // AgentApi exists. Removing the bot.debug package + this line fully strips the feature.
        net.magicterra.worlddriver.bot.debug.PathDebugBootstrap.init();
    }

    public static ClientAgentApi impl() {
        return impl;
    }

    public static boolean isAvailable() {
        return impl != null;
    }
}
