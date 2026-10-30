package net.magicterra.worlddriver.neoforge;

import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.WorldDriverEvents;
import net.magicterra.worlddriver.config.BuildStamp;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

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
        WorldDriverCommon.installTestContent();
        WorldDriverCommon.LOG.info("[{}] NeoForge entry constructed", WorldDriverCommon.MOD_ID);
    }
}
