package net.magicterra.worlddriver.neoforge;

import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.WorldDriverEvents;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * NeoForge entry. Hands every event subscription to {@link WorldDriverEvents}; what is left here is
 * exactly what has no cross-loader form.
 */
@Mod(WorldDriverCommon.MOD_ID)
public final class WorldDriverNeoForge {
    public WorldDriverNeoForge(IEventBus modBus, ModContainer container) {
        WorldDriverEvents.register();
        WorldDriverCommon.installTestContent();
        WorldDriverCommon.LOG.info("[{}] NeoForge entry constructed", WorldDriverCommon.MOD_ID);
    }
}
