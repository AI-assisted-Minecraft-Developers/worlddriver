package net.magicterra.worlddriver;

import dev.architectury.event.events.client.ClientGuiEvent;
import dev.architectury.event.events.client.ClientLifecycleEvent;
import dev.architectury.event.events.client.ClientPlayerEvent;
import net.magicterra.worlddriver.api.action.ActionHandle;
import net.magicterra.worlddriver.api.action.ActionRegistry;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import dev.architectury.event.events.client.ClientTickEvent;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.api.game.DriverHost;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.schema.JsonValue;
import net.magicterra.worlddriver.api.thread.GameThreads;
import net.magicterra.worlddriver.debug.PathDebugBootstrap;
import net.magicterra.worlddriver.application.client.ClientBot;
import net.magicterra.worlddriver.bot.BotBrain;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.bot.world.ClientWorldView;
import net.magicterra.worlddriver.game.ui.FocusPolicy;
import net.magicterra.worlddriver.game.ui.MouseYieldHud;
import net.magicterra.worlddriver.game.ui.ClientThread;
import net.magicterra.worlddriver.application.client.ClientDriverApiImpl;
import net.magicterra.worlddriver.application.client.ClientHooks;

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
    private static volatile ClientBot bot;

    private WorldDriverClientEvents() {}

    /** Registers the client API and the bot. Render thread only. */
    public static void install() {
        ClientHooks.register(new ClientDriverApiImpl());
        // Only a client gets here, so naming the client class never loads it on a server.
        GameThreads.installClient(() -> net.minecraft.client.Minecraft.getInstance().isSameThread());
        // Boot both RPC and MCP at client init so mc.client.* (and the script
        // evaluator) are reachable from TitleScreen / Pause — before any world
        // exists. Both ensure*Up() calls are idempotent so onServerStarting
        // can call them again without conflict.
        WorldDriverCommon.ensureRpcUp();
        WorldDriverCommon.ensureMcpUp();
        // Optional, strippable: wire the path-debug recorder + mc.debug.pathChart now that
        // the registry exists. Removing the debug package + this line fully strips the feature.
        // ensureRpcUp logs and swallows a failed boot, leaving no registry to register on.
        ApiRegistry registry = WorldDriverCommon.registry();
        if (registry != null) PathDebugBootstrap.init(registry);
        else WorldDriverCommon.LOG.warn("[{}] driver not ready; mc.debug.* not registered", WorldDriverCommon.MOD_ID);
        // Looked up per event: a driver that failed to boot leaves api() null, and the bot still
        // drives without one, as it always has; only its events have nowhere to go.
        ClientWorldView world = new ClientWorldView(
                net.magicterra.worlddriver.game.ui.ClientWorld::level,
                net.magicterra.worlddriver.game.ui.ClientWorld::player);
        BotBrain brain = new BotBrain((type, pos, data) -> {
            DriverHost api = WorldDriverCommon.host();
            if (api != null) api.emitExternal(type, pos, JsonValue.of(data));
        }, () -> world);
        // The world view applies the world model's lethal-cell penalty in its danger cost.
        world.setWorldModel(brain.worldModel());
        ClientBot b = new ClientBot(brain);
        BotHooks.register(b);
        bot = b;
        WorldDriverCommon.LOG.info("[{}] client api + bot registered", WorldDriverCommon.MOD_ID);
    }

    /** Subscribes the client-side handlers; they tolerate running before {@link #install}. */
    public static void subscribe() {
        ClientTickEvent.CLIENT_POST.register(mc -> {
            ClientBot b = bot;
            if (b != null) b.clientTick();
            // Whatever asked for a tick boundary rather than the next render task — the release
            // half of mc.client.input.key's click, so far.
            ClientThread.drainNextTick();
        });
        // FocusPolicy holds the human's real pauseOnLostFocus while the bot drives. Minecraft
        // saves options.txt on close, so a force-quit mid-drive would otherwise PERSIST our
        // temporary false into their settings. The tick's falling edge covers every normal
        // stop; this covers the one path where ticks just stop arriving.
        ClientLifecycleEvent.CLIENT_STOPPING.register(FocusPolicy::release);
        // Leaving a server ends what this client's player was doing there; nothing may carry an old
        // task into the next world. Marked before the chain drops it, so the ending is the cause.
        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register(player -> {
            ActionRegistry.GLOBAL.cancelScope(ActionHandle.Scope.CLIENT, Responses.CancelReason.CONNECTION_CLOSED);
            ClientBot b = bot;
            if (b != null) b.onDisconnect();
        });
        // "Bot is driving" badge — the visible half of the mouse-yield handshake
        // (GameConfig.mouseYield / mouseYieldHud). Drawn after the vanilla HUD so it lands on top.
        ClientGuiEvent.RENDER_HUD.register((graphics, delta) -> MouseYieldHud.render(graphics));
    }
}
