package net.magicterra.worlddriver.neoforge;

import net.magicterra.worlddriver.WorldDriverEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The driver's server hooks in {@link WorldDriverEvents}, subscribed to NeoForge's game event bus. Registered by class
 * from the mod's constructor. {@code HIGH} throughout and {@code LOWEST} for chat, so the driver sits where it always
 * did relative to other mods' listeners: a listener that cancels before it suppresses the report.
 */
final class NeoForgeServerEvents {
    private NeoForgeServerEvents() {}

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void starting(ServerStartingEvent e) {
        WorldDriverEvents.serverStarting(e.getServer());
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void started(ServerStartedEvent e) {
        WorldDriverEvents.serverStarted(e.getServer());
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void stopping(ServerStoppingEvent e) {
        WorldDriverEvents.serverStopping(e.getServer());
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void stopped(ServerStoppedEvent e) {
        WorldDriverEvents.serverStopped(e.getServer());
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void tick(ServerTickEvent.Post e) {
        WorldDriverEvents.serverTickEnd(e.getServer());
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void commands(RegisterCommandsEvent e) {
        WorldDriverEvents.registerCommands(e.getDispatcher());
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void breaking(BlockEvent.BreakEvent e) {
        if (e.getLevel() instanceof Level level) {
            WorldDriverEvents.blockBreaking(level, e.getPos(), e.getState());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void placed(BlockEvent.EntityPlaceEvent e) {
        if (e.getLevel() instanceof Level level) {
            WorldDriverEvents.blockPlaced(level, e.getPos(), e.getPlacedBlock(), e.getEntity());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void dying(LivingDeathEvent e) {
        WorldDriverEvents.livingDying(e.getEntity(), e.getSource());
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void joined(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer player) {
            WorldDriverEvents.playerJoined(player);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void left(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer player) {
            WorldDriverEvents.playerLeft(player);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void chat(ServerChatEvent e) {
        WorldDriverEvents.chatReceived(e.getPlayer(), e.getMessage());
    }
}
