package net.magicterra.worlddriver.neoforge.client;

import net.magicterra.worlddriver.WorldDriverClientEvents;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * NeoForge client entry. Dist-gated via {@link EventBusSubscriber} so it's only wired up on the client jar; the class
 * is instantiated lazily by the MOD bus, and the first reference to {@link WorldDriverClientEvents} is what triggers
 * class loading of the client API — keeping dedi-server JVMs free of client classes.
 *
 * <p>Mod-bus listeners only; the game-bus hooks are in {@link WorldDriverNeoForgeClientEvents}. The bus is named on
 * both classes because FML before 21.1.80 registers the whole class on the one bus the annotation names (game by
 * default), and a mod-bus event there fails the mod's load. Newer FML routes each listener by its event type and
 * ignores the attribute.
 */
@SuppressWarnings("removal")
@EventBusSubscriber(modid = WorldDriverCommon.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class WorldDriverNeoForgeClient {
    private WorldDriverNeoForgeClient() {}

    /** Installs the driver's client half on the render thread once client setup runs. */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // enqueueWork: FMLClientSetupEvent runs on a mod-loading worker; the client API and the
        // bot are constructed on the render thread, as the Fabric client entrypoint does natively.
        event.enqueueWork(WorldDriverClientEvents::install);
    }
}
