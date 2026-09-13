package net.magicterra.worlddriver.neoforge.client;

import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.client.WorldDriverClientEvents;
import net.magicterra.worlddriver.client.internal.ClientChat;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;

/**
 * NeoForge client entry. Dist-gated via {@link EventBusSubscriber} so it's only wired up on the
 * client jar; the class is instantiated lazily by the MOD bus, and the first reference to
 * {@link WorldDriverClientEvents} is what triggers class loading of the client API — keeping
 * dedi-server JVMs free of client classes.
 */
@EventBusSubscriber(modid = WorldDriverCommon.MOD_ID, value = Dist.CLIENT)
public final class WorldDriverNeoForgeClient {
    private WorldDriverNeoForgeClient() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        WorldDriverClientEvents.subscribe();
        // enqueueWork: FMLClientSetupEvent runs on a mod-loading worker; the client API and the
        // bot are constructed on the render thread, as the Fabric client entrypoint does natively.
        event.enqueueWork(WorldDriverClientEvents::install);
    }

    /** Packet-level chat tap for mc.client.chat.history / awaitReplyMs. Fires
     *  for system lines (command feedback, /say, broadcasts), player chat and
     *  disguised chat. Capture policy (action-bar overlay exclusion, sender→
     *  kind classification — disguised chat's NIL_UUID normalizes to "system",
     *  matching Fabric's null GameProfile) lives in the shared
     *  ClientChat.recordReceived funnel. receiveCanceled keeps the tap alive
     *  under chat-filter mods that cancel lines to redisplay them: the
     *  readback channel records what the server delivered, not what survived
     *  other mods' filters. Architectury's ClientChatEvent.RECEIVED has no
     *  canceled variant, which is why this one handler stays loader-side. */
    @SubscribeEvent(receiveCanceled = true)
    public static void onChatReceived(ClientChatReceivedEvent evt) {
        ClientChat.recordReceived(evt.getMessage(),
                evt.isSystem() ? null : evt.getSender(),
                evt instanceof ClientChatReceivedEvent.System sys && sys.isOverlay());
    }
}
