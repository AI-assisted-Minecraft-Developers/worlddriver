package net.magicterra.worlddriver.game.ui;

import net.magicterra.worlddriver.integration.debug.PathDebugBootstrap;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.thread.GameThreads;

/**
 * Broker between the platform-neutral driver and the platform-supplied
 * {@link ClientDriverApi} implementation. Lives in common so the static reference
 * doesn't pull client classes — only the {@link #register} caller does.
 *
 * Registration order is loose: the driver looks up the impl at call time, so the
 * client entrypoint may register before or after the integrated server starts.
 */
public final class ClientHooks {
    private static volatile ClientDriverApi impl;

    private ClientHooks() {}

    public static void register(ClientDriverApi api) {
        impl = api;
        // Only a client calls register, so naming the client class here never loads it on a server.
        GameThreads.installClient(() -> net.minecraft.client.Minecraft.getInstance().isSameThread());
        // Boot both RPC and MCP at client init so mc.client.* (and the script
        // evaluator) are reachable from TitleScreen / Pause — before any world
        // exists. Both ensure*Up() calls are idempotent so onServerStarting
        // can call them again without conflict.
        WorldDriverCommon.ensureRpcUp();
        WorldDriverCommon.ensureMcpUp();
        // Optional, strippable: wire the path-debug recorder + mc.debug.pathChart now that
        // the registry exists. Removing the bot.debug package + this line fully strips the feature.
        // ensureRpcUp logs and swallows a failed boot, leaving no registry to register on.
        ApiRegistry registry = WorldDriverCommon.registry();
        if (registry != null) PathDebugBootstrap.init(registry);
        else WorldDriverCommon.LOG.warn("[{}] driver not ready; mc.debug.* not registered", WorldDriverCommon.MOD_ID);
    }

    public static ClientDriverApi impl() {
        return impl;
    }

    public static boolean isAvailable() {
        return impl != null;
    }
}
