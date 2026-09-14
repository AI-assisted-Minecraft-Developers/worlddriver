package net.magicterra.worlddriver.fabric;

import net.fabricmc.api.ModInitializer;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.WorldDriverEvents;

/**
 * Fabric entry. Hands every event subscription to {@link WorldDriverEvents}; what is left here is
 * exactly what has no cross-loader form.
 */
public final class WorldDriverFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        WorldDriverEvents.register();
        WorldDriverCommon.installTestContent();
        WorldDriverCommon.LOG.info("[{}] Fabric entry initialized", WorldDriverCommon.MOD_ID);
    }
}
