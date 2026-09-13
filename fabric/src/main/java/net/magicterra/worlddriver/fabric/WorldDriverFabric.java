package net.magicterra.worlddriver.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.WorldDriverEvents;
import net.magicterra.worlddriver.bot.sim.ServerAvatarBodies;
import net.magicterra.worlddriver.fabric.sim.FabricAvatarBodies;

/**
 * Fabric entry. Installs the loader's body factory, then hands every event subscription to
 * {@link WorldDriverEvents}; what is left here is exactly what has no cross-loader form.
 */
public final class WorldDriverFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        // Install the fabric loader body factory behind the common ServerAvatarBodies seam BEFORE
        // anything can create a server-agent body — the FIRST statement of init, mirroring
        // WorldDriverNeoForge's ctor. NeoForge injects FakePlayerFactory bodies; fabric injects the
        // vanilla-only AvatarFakePlayer via FabricAvatarBodies (a faithful reimplementation of
        // FakePlayerFactory's cache).
        FabricAvatarBodies bodies = new FabricAvatarBodies();
        ServerAvatarBodies.install(bodies);
        // FakePlayerFactory.unloadLevel has no fabric built-in equivalent, so evict the
        // per-level body cache explicitly on world unload (mirrors NeoForge's level-unload hook).
        ServerWorldEvents.UNLOAD.register((server, world) -> bodies.unloadLevel(world));

        WorldDriverEvents.register();
        WorldDriverCommon.installTestContent();
        WorldDriverCommon.LOG.info("[{}] Fabric entry initialized", WorldDriverCommon.MOD_ID);
    }
}
