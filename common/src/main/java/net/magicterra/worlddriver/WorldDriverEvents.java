package net.magicterra.worlddriver;

import com.mojang.brigadier.CommandDispatcher;
import net.magicterra.worlddriver.api.game.DriverHost;
import net.magicterra.worlddriver.api.game.WorldDriver;
import net.magicterra.worlddriver.api.methods.event.EventCatalog;
import net.magicterra.worlddriver.application.TickClock;
import net.magicterra.worlddriver.bot.BotCommand;
import net.magicterra.worlddriver.bot.BotTicker;
import net.magicterra.worlddriver.script.ScriptEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * The driver's server-side event hooks. Each loader subscribes its own callbacks to these methods: the Fabric entry
 * through Fabric API's events plus a few mixins where Fabric API has no event, the NeoForge entry through the game
 * event bus. This class names no loader, so the same handling runs on both.
 *
 * <p>The driver only listens: no hook cancels anything or returns a value. A listener of another mod that cancels the
 * action earlier in the chain still suppresses the report, since the loader then never calls the hook.
 *
 * <p>The external events ({@code block.break}, {@code block.place}, {@code entity.death}, {@code player.join},
 * {@code player.leave}, {@code chat.message}) go out through {@link DriverHost#emitExternal}. All of them are called on
 * the server thread. {@code block.break} is called while the block is still in place, and {@code entity.death} when the
 * death is decided, before the drops.
 */
public final class WorldDriverEvents {
    private WorldDriverEvents() {}

    // ---------------------------------------------------------------- lifecycle and ticks

    /**
     * The server is starting. Needs no level: the client calls the same start-up at the title screen. On Fabric this
     * runs before the server loads its levels, on NeoForge after; either way before any server-started handler, which
     * is where StageWright publishes its methods onto the registry this creates.
     */
    public static void serverStarting(MinecraftServer server) {
        WorldDriverCommon.onServerStarting();
    }

    /** StageWright arms itself from its own server-started handler; the driver does not forward lifecycle to it. */
    public static void serverStarted(MinecraftServer server) {
        WorldDriverCommon.onServerStarted(server);
    }

    public static void serverStopping(MinecraftServer server) {
        TickClock.server().reset();
        WorldDriverCommon.onServerStopping();
    }

    public static void serverStopped(MinecraftServer server) {
        WorldDriverCommon.onServerStopped(server);
    }

    /**
     * The end of a server tick: copy the tick's work time, fire the script tick, then drive the server-side agents.
     * StageWright ticks its own harness from its own registration; it is a mod, not a library the driver pumps.
     */
    public static void serverTickEnd(MinecraftServer server) {
        TickClock.server().record(server);
        ScriptEvents.fireTick();
        BotTicker.tickAll();
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        WorldDriverCommon.registerCommands(dispatcher);
        BotCommand.register(dispatcher);
    }

    // ---------------------------------------------------------------- external events

    /** A player is about to break {@code state} at {@code pos}; the block is still there. */
    public static void blockBreaking(Level level, BlockPos pos, BlockState state) {
        if (level.isClientSide()) return;
        DriverHost api = WorldDriver.host();
        if (api == null) return;
        api.emitExternal(EventCatalog.BLOCK_BREAK, pos.immutable(), blockId(state));
    }

    /** {@code placer} placed {@code state} at {@code pos}; ignored on a client level and without a placer. */
    public static void blockPlaced(Level level, BlockPos pos, BlockState state, @Nullable Entity placer) {
        if (level.isClientSide() || placer == null) return;
        DriverHost api = WorldDriver.host();
        if (api == null) return;
        api.emitExternal(EventCatalog.BLOCK_PLACE, pos.immutable(), blockId(state));
    }

    /** {@code entity}'s death is decided, before its drops; ignored on a client level. */
    public static void livingDying(LivingEntity entity, DamageSource source) {
        if (entity.level().isClientSide()) return;
        DriverHost api = WorldDriver.host();
        if (api == null) return;
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        api.emitExternal(EventCatalog.ENTITY_DEATH, entity.blockPosition(), id);
    }

    /** {@code player} has joined the server, at the end of the join. */
    public static void playerJoined(ServerPlayer player) {
        emitPlayer(EventCatalog.PLAYER_JOIN, player);
    }

    /** {@code player} is leaving the server, before it is taken off the player list. */
    public static void playerLeft(ServerPlayer player) {
        emitPlayer(EventCatalog.PLAYER_LEAVE, player);
    }

    /** The server received {@code message} from {@code player}, after other mods had their chance to refuse it. */
    public static void chatReceived(ServerPlayer player, Component message) {
        DriverHost api = WorldDriver.host();
        if (api == null) return;
        api.emitExternal(
                EventCatalog.CHAT_MESSAGE,
                player.blockPosition(),
                new EventCatalog.ChatMessage(player.getGameProfile().getName(), message.getString()));
    }

    private static void emitPlayer(EventCatalog.Entry<String> type, ServerPlayer player) {
        DriverHost api = WorldDriver.host();
        if (api == null) return;
        api.emitExternal(type, player.blockPosition(), player.getGameProfile().getName());
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }
}
