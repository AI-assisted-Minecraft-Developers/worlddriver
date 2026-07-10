package net.magicterra.agent.fabric.client;

import com.mojang.authlib.GameProfile;
import java.time.Instant;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.magicterra.agent.AgentDriverCommon;
import net.magicterra.agent.bot.BotApiImpl;
import net.magicterra.agent.bot.BotHooks;
import net.magicterra.agent.client.ClientAgentApiImpl;
import net.magicterra.agent.client.ClientHooks;
import net.magicterra.agent.client.internal.ClientChat;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;

/**
 * Fabric client-side bootstrap. Only loaded under Dist.CLIENT — Fabric Loader
 * skips client entrypoints on a dedicated-server install — so referencing
 * {@link ClientAgentApiImpl} here doesn't drag client classes into dedi-server
 * classloading.
 */
public final class AgentDriverFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientHooks.register(new ClientAgentApiImpl());
        BotApiImpl bot = new BotApiImpl();
        BotHooks.register(bot);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> bot.clientTick());
        // Packet-level chat tap for mc.client.chat.history / awaitReplyMs —
        // GAME carries system lines (command feedback, /say, server broadcasts),
        // CHAT carries player chat. Capture policy (overlay exclusion, sender→
        // kind classification) lives in ClientChat.recordReceived, shared with
        // NeoForge. The CANCELED variants keep the tap alive under chat-filter
        // mods that veto lines to redisplay them: the readback channel records
        // what the server delivered, not what survived other mods' filters.
        // Normal and CANCELED register the SAME method so they can't drift.
        ClientReceiveMessageEvents.GAME.register(AgentDriverFabricClient::onGameMessage);
        ClientReceiveMessageEvents.GAME_CANCELED.register(AgentDriverFabricClient::onGameMessage);
        ClientReceiveMessageEvents.CHAT.register(AgentDriverFabricClient::onChatMessage);
        ClientReceiveMessageEvents.CHAT_CANCELED.register(AgentDriverFabricClient::onChatMessage);
        AgentDriverCommon.LOG.info("[{}] Fabric client api + bot registered", AgentDriverCommon.MOD_ID);
    }

    private static void onGameMessage(Component message, boolean overlay) {
        ClientChat.recordReceived(message, null, overlay);
    }

    private static void onChatMessage(Component message, PlayerChatMessage signedMessage,
            GameProfile sender, ChatType.Bound params, Instant receptionTimestamp) {
        ClientChat.recordReceived(message, sender != null ? sender.getId() : null, false);
    }
}
