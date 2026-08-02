package net.magicterra.worlddriver.neoforge.client;

import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.BotApiImpl;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.bot.MouseYieldHud;
import net.magicterra.worlddriver.client.ClientDriverApiImpl;
import net.magicterra.worlddriver.client.ClientHooks;
import net.magicterra.worlddriver.client.internal.ClientChat;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.minecraft.client.Minecraft;
import net.magicterra.worlddriver.bot.FocusPolicy;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * NeoForge client-side bootstrap. Dist-gated via {@link EventBusSubscriber} so
 * it's only wired up on the client jar. The class is instantiated lazily by
 * the MOD bus, and the impl reference is the first thing that triggers class
 * loading of {@link ClientDriverApiImpl} — keeping dedi-server JVMs free of
 * client classes.
 */
@EventBusSubscriber(modid = WorldDriverCommon.MOD_ID, value = Dist.CLIENT)
public final class WorldDriverNeoForgeClient {
    private WorldDriverNeoForgeClient() {}

    private static volatile BotApiImpl BOT;

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ClientHooks.register(new ClientDriverApiImpl());
            BOT = new BotApiImpl();
            BotHooks.register(BOT);
            WorldDriverCommon.LOG.info("[{}] NeoForge client api + bot registered", WorldDriverCommon.MOD_ID);
        });
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post evt) {
        BotApiImpl b = BOT;
        if (b != null) b.clientTick();
    }

    /** FocusPolicy holds the human's real pauseOnLostFocus while the bot drives. Minecraft saves
     *  options.txt on close, so a force-quit mid-drive would otherwise PERSIST our temporary
     *  false into their settings. The tick's falling edge covers every normal stop; this covers
     *  the one path where ticks just stop arriving. Fabric registers CLIENT_STOPPING for the
     *  same call, so the two loaders can't diverge on whose setting gets left mutated. */
    @SubscribeEvent
    public static void onGameShuttingDown(GameShuttingDownEvent evt) {
        FocusPolicy.release(Minecraft.getInstance());
    }

    /** "Bot is driving" badge — the visible half of the mouse-yield handshake
     *  (BotConfig.mouseYield / mouseYieldHud). Post so it lands on top of the vanilla
     *  HUD. Drawing lives in common; Fabric registers HudRenderCallback for the same
     *  call, so the two loaders can't draw different badges. */
    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post evt) {
        MouseYieldHud.render(evt.getGuiGraphics());
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
