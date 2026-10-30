package net.magicterra.worlddriver.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.WorldDriverEvents;
import net.magicterra.worlddriver.config.BuildStamp;

/**
 * Fabric entry. Subscribes the driver's server hooks in {@link WorldDriverEvents} to Fabric API's events. Block
 * placement, death, join and leave have no Fabric API event at the point the driver reports them, so the mixins in
 * {@code worlddriver-fabric.mixins.json} call those hooks instead.
 */
public final class WorldDriverFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        // Main entrypoints run before the client one, which opens the transports that answer this version.
        FabricLoader.getInstance()
                .getModContainer(WorldDriverCommon.MOD_ID)
                .ifPresent(mod ->
                        BuildStamp.loaderVersion(mod.getMetadata().getVersion().getFriendlyString()));
        // Fabric's SERVER_STARTING fires before the server loads its levels, earlier than NeoForge's. The driver's
        // start-up needs no level, and it has to come before every SERVER_STARTED listener: StageWright's publishes
        // its methods onto the registry this creates.
        ServerLifecycleEvents.SERVER_STARTING.register(WorldDriverEvents::serverStarting);
        ServerLifecycleEvents.SERVER_STARTED.register(WorldDriverEvents::serverStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(WorldDriverEvents::serverStopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(WorldDriverEvents::serverStopped);
        ServerTickEvents.END_SERVER_TICK.register(WorldDriverEvents::serverTickEnd);
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, context, selection) -> WorldDriverEvents.registerCommands(dispatcher));
        // Both return true: the driver only listens. A listener before it that returns false stops the chain,
        // and with it the report.
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            WorldDriverEvents.blockBreaking(level, pos, state);
            return true;
        });
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            WorldDriverEvents.chatReceived(sender, message.decoratedContent());
            return true;
        });
        WorldDriverCommon.installTestContent();
        WorldDriverCommon.LOG.info("[{}] Fabric entry initialized", WorldDriverCommon.MOD_ID);
    }
}
