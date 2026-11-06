package net.magicterra.worlddriver.neoforge;

import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.WorldDriverEvents;
import net.magicterra.worlddriver.config.BuildStamp;
import net.magicterra.worlddriver.game.body.BotConfiguration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;

/**
 * NeoForge entry. Subscribes the driver's server hooks in {@link WorldDriverEvents} to the game event bus, through
 * {@link NeoForgeServerEvents}, before the test content registers.
 */
@Mod(WorldDriverCommon.MOD_ID)
public final class WorldDriverNeoForge {
    /** Called by FML: records the loader version, subscribes the server hooks and installs the test content. */
    public WorldDriverNeoForge(IEventBus modBus, ModContainer container) {
        BuildStamp.loaderVersion(container.getModInfo().getVersion().toString());
        NeoForge.EVENT_BUS.register(NeoForgeServerEvents.class);
        // A bot's configuration tasks: the event NeoForge posts for a joining client, posted for the bot's listener.
        BotConfiguration.gatherWith((listener, server, tasks) -> ModLoader.postEventWithReturn(
                        new RegisterConfigurationTasksEvent(listener))
                .getConfigurationTasks()
                .forEach(tasks));
        WorldDriverCommon.installTestContent();
        WorldDriverCommon.LOG.info("[{}] NeoForge entry constructed", WorldDriverCommon.MOD_ID);
    }
}
