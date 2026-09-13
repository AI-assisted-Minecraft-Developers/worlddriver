package net.magicterra.worlddriver.client;

import dev.architectury.event.events.client.ClientGuiEvent;
import dev.architectury.event.events.client.ClientLifecycleEvent;
import dev.architectury.event.events.client.ClientTickEvent;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.BotApiImpl;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.bot.FocusPolicy;
import net.magicterra.worlddriver.bot.MouseYieldHud;

/**
 * The driver's client-side bootstrap and event subscriptions, shared by both loaders' client
 * entries. Only ever loaded on a client: the loader entries are dist-gated, and nothing on the
 * server path names this class.
 *
 * <p>Two calls, because the loaders differ in WHEN the registration may run. {@link #subscribe}
 * only appends listeners and is safe wherever the loader hands control to the mod;
 * {@link #install} constructs the client API and the bot and must run on the render thread, which
 * on NeoForge means inside {@code FMLClientSetupEvent.enqueueWork} (Architectury's own
 * {@code CLIENT_SETUP} is invoked directly from that event's handler, off the render thread).
 *
 * <p>Stays loader-side: the packet-level chat tap. It records lines other mods cancel, and
 * Architectury's {@code ClientChatEvent.RECEIVED} has no canceled variant.
 */
public final class WorldDriverClientEvents {
    private static volatile BotApiImpl bot;

    private WorldDriverClientEvents() {}

    /** Registers the client API and the bot. Render thread only. */
    public static void install() {
        ClientHooks.register(new ClientDriverApiImpl());
        BotApiImpl b = new BotApiImpl();
        BotHooks.register(b);
        bot = b;
        WorldDriverCommon.LOG.info("[{}] client api + bot registered", WorldDriverCommon.MOD_ID);
    }

    /** Subscribes the client-side handlers; they tolerate running before {@link #install}. */
    public static void subscribe() {
        ClientTickEvent.CLIENT_POST.register(mc -> {
            BotApiImpl b = bot;
            if (b != null) b.clientTick();
        });
        // FocusPolicy holds the human's real pauseOnLostFocus while the bot drives. Minecraft
        // saves options.txt on close, so a force-quit mid-drive would otherwise PERSIST our
        // temporary false into their settings. The tick's falling edge covers every normal
        // stop; this covers the one path where ticks just stop arriving.
        ClientLifecycleEvent.CLIENT_STOPPING.register(FocusPolicy::release);
        // "Bot is driving" badge — the visible half of the mouse-yield handshake
        // (BotConfig.mouseYield / mouseYieldHud). Drawn after the vanilla HUD so it lands on top.
        ClientGuiEvent.RENDER_HUD.register((graphics, delta) -> MouseYieldHud.render(graphics));
    }
}
