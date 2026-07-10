package net.magicterra.agent.neoforge.client;

import net.magicterra.agent.AgentDriverCommon;
import net.magicterra.agent.bot.BotApiImpl;
import net.magicterra.agent.bot.BotHooks;
import net.magicterra.agent.client.ClientAgentApiImpl;
import net.magicterra.agent.client.ClientHooks;
import net.magicterra.agent.client.internal.ClientChat;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * NeoForge client-side bootstrap. Dist-gated via {@link EventBusSubscriber} so
 * it's only wired up on the client jar. The class is instantiated lazily by
 * the MOD bus, and the impl reference is the first thing that triggers class
 * loading of {@link ClientAgentApiImpl} — keeping dedi-server JVMs free of
 * client classes.
 */
@EventBusSubscriber(modid = AgentDriverCommon.MOD_ID, value = Dist.CLIENT)
public final class AgentDriverNeoForgeClient {
    private AgentDriverNeoForgeClient() {}

    private static volatile BotApiImpl BOT;

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ClientHooks.register(new ClientAgentApiImpl());
            BOT = new BotApiImpl();
            BotHooks.register(BOT);
            AgentDriverCommon.LOG.info("[{}] NeoForge client api + bot registered", AgentDriverCommon.MOD_ID);
        });
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post evt) {
        BotApiImpl b = BOT;
        if (b != null) b.clientTick();
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
