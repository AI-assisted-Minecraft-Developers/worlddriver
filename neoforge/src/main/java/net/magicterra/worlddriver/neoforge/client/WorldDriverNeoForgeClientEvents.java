package net.magicterra.worlddriver.neoforge.client;

import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.WorldDriverClientEvents;
import net.magicterra.worlddriver.application.client.ClientChat;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

/**
 * The driver's client hooks on NeoForge's game bus, each forwarding to {@link WorldDriverClientEvents}.
 * Game-bus listeners only; see {@link WorldDriverNeoForgeClient} for why the two buses are two classes.
 */
@SuppressWarnings("removal")
@EventBusSubscriber(modid = WorldDriverCommon.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class WorldDriverNeoForgeClientEvents {
    private WorldDriverNeoForgeClientEvents() {}

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onClientTick(ClientTickEvent.Post event) {
        WorldDriverClientEvents.clientTickEnd();
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onHud(RenderGuiEvent.Post event) {
        WorldDriverClientEvents.hudRendered(event.getGuiGraphics());
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        WorldDriverClientEvents.clientPlayerLeft();
    }

    /** Fires in {@code Minecraft.stop()}, before the options are written on close, which is all the hook needs. */
    @SubscribeEvent
    public static void onShuttingDown(GameShuttingDownEvent event) {
        WorldDriverClientEvents.clientStopping(Minecraft.getInstance());
    }

    /** Packet-level chat tap for mc.client.chat.history / awaitReplyMs. Fires
     *  for system lines (command feedback, /say, broadcasts), player chat and
     *  disguised chat. Capture policy (action-bar overlay exclusion, sender→
     *  kind classification — disguised chat's NIL_UUID normalizes to "system",
     *  matching Fabric's null GameProfile) lives in the shared
     *  ClientChat.recordReceived funnel. receiveCanceled keeps the tap alive
     *  under chat-filter mods that cancel lines to redisplay them: the
     *  readback channel records what the server delivered, not what survived
     *  other mods' filters. */
    @SubscribeEvent(receiveCanceled = true)
    public static void onChatReceived(ClientChatReceivedEvent evt) {
        ClientChat.recordReceived(evt.getMessage(),
                evt.isSystem() ? null : evt.getSender(),
                evt instanceof ClientChatReceivedEvent.System sys && sys.isOverlay());
    }
}
