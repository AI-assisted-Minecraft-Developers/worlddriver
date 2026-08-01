package net.magicterra.worlddriver.fabric.client;

import com.mojang.authlib.GameProfile;
import java.time.Instant;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.BotApiImpl;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.bot.MouseYieldHud;
import net.magicterra.worlddriver.client.ClientAgentApiImpl;
import net.magicterra.worlddriver.client.ClientHooks;
import net.magicterra.worlddriver.client.internal.ClientChat;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;

/**
 * Fabric client-side bootstrap. Only loaded under Dist.CLIENT — Fabric Loader
 * skips client entrypoints on a dedicated-server install — so referencing
 * {@link ClientAgentApiImpl} here doesn't drag client classes into dedi-server
 * classloading.
 */
public final class WorldDriverFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientHooks.register(new ClientAgentApiImpl());
        BotApiImpl bot = new BotApiImpl();
        BotHooks.register(bot);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> bot.clientTick());
        // "Bot is driving" badge — the visible half of the mouse-yield handshake
        // (BotConfig.mouseYield / mouseYieldHud). Drawing lives in common; this is
        // only the loader's render hook. NeoForge subscribes RenderGuiEvent.Post.
        HudRenderCallback.EVENT.register((gfx, tickCounter) -> MouseYieldHud.render(gfx));
        // Packet-level chat tap for mc.client.chat.history / awaitReplyMs —
        // GAME carries system lines (command feedback, /say, server broadcasts),
        // CHAT carries player chat. Capture policy (overlay exclusion, sender→
        // kind classification) lives in ClientChat.recordReceived, shared with
        // NeoForge. The CANCELED variants keep the tap alive under chat-filter
        // mods that veto lines to redisplay them: the readback channel records
        // what the server delivered, not what survived other mods' filters.
        // Normal and CANCELED register the SAME method so they can't drift.
        ClientReceiveMessageEvents.GAME.register(WorldDriverFabricClient::onGameMessage);
        ClientReceiveMessageEvents.GAME_CANCELED.register(WorldDriverFabricClient::onGameMessage);
        ClientReceiveMessageEvents.CHAT.register(WorldDriverFabricClient::onChatMessage);
        ClientReceiveMessageEvents.CHAT_CANCELED.register(WorldDriverFabricClient::onChatMessage);
        WorldDriverCommon.LOG.info("[{}] Fabric client api + bot registered", WorldDriverCommon.MOD_ID);
    }

    private static void onGameMessage(Component message, boolean overlay) {
        ClientChat.recordReceived(message, null, overlay);
    }

    private static void onChatMessage(Component message, PlayerChatMessage signedMessage,
            GameProfile sender, ChatType.Bound params, Instant receptionTimestamp) {
        ClientChat.recordReceived(message, sender != null ? sender.getId() : null, false);
    }
}
