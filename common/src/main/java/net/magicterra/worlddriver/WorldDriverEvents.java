package net.magicterra.worlddriver;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.BlockEvent;
import dev.architectury.event.events.common.ChatEvent;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import net.magicterra.worlddriver.api.game.DriverHost;
import net.magicterra.worlddriver.api.game.WorldDriver;
import net.magicterra.worlddriver.api.methods.event.EventCatalog;
import net.magicterra.worlddriver.application.TickClock;
import net.magicterra.worlddriver.bot.BotCommand;
import net.magicterra.worlddriver.bot.BotTicker;
import net.magicterra.worlddriver.script.ScriptEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The driver's server-side event subscriptions, written once against Architectury's events and
 * registered by both loader entries at mod construction, {@code /worlddriver server} included.
 *
 * <p>The external events ({@code block.break}, {@code block.place}, {@code entity.death},
 * {@code player.join}, {@code player.leave}, {@code chat.message}) go out through
 * {@link DriverHost#emitExternal}. Two of them changed timing when the loaders' handlers were
 * merged: {@code block.break} fires BEFORE the block is removed on both loaders (Fabric used to
 * report after), and {@code entity.death} fires as the death is decided rather than after it.
 * Neither carries state that the timing changes, and a listener that cancels the vanilla action
 * earlier in the chain suppresses the report the way it always did on NeoForge.
 */
public final class WorldDriverEvents {
    private WorldDriverEvents() {}

    /** Subscribes every server-side handler. Call once, from the loader's mod-construction entry. */
    public static void register() {
        LifecycleEvent.SERVER_STARTING.register(server -> WorldDriverCommon.onServerStarting());
        // StageWright arms itself from its own server-started handler; the driver does not forward lifecycle to it.
        LifecycleEvent.SERVER_STARTED.register(WorldDriverCommon::onServerStarted);
        LifecycleEvent.SERVER_STOPPING.register(server -> {
            TickClock.server().reset();
            WorldDriverCommon.onServerStopping();
        });
        LifecycleEvent.SERVER_STOPPED.register(WorldDriverCommon::onServerStopped);
        // Copy the tick's work time, fireTick, then drive the server-side agents. StageWright ticks its own
        // harness from its own registration — it is a mod, not a library the driver has to pump.
        TickEvent.SERVER_POST.register(server -> {
            TickClock.server().record(server);
            ScriptEvents.fireTick();
            BotTicker.tickAll();
        });
        CommandRegistrationEvent.EVENT.register((dispatcher, context, selection) -> {
            WorldDriverCommon.registerCommands(dispatcher);
            BotCommand.register(dispatcher);
        });

        BlockEvent.BREAK.register(WorldDriverEvents::onBlockBreak);
        BlockEvent.PLACE.register(WorldDriverEvents::onBlockPlace);
        EntityEvent.LIVING_DEATH.register(WorldDriverEvents::onLivingDeath);
        PlayerEvent.PLAYER_JOIN.register(player -> emitPlayer(EventCatalog.PLAYER_JOIN, player));
        PlayerEvent.PLAYER_QUIT.register(player -> emitPlayer(EventCatalog.PLAYER_LEAVE, player));
        ChatEvent.RECEIVED.register(WorldDriverEvents::onChat);
    }

    private static EventResult onBlockBreak(net.minecraft.world.level.Level level, BlockPos pos,
            BlockState state, ServerPlayer player, dev.architectury.utils.value.IntValue xp) {
        DriverHost api = WorldDriver.host();
        if (api != null) {
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            api.emitExternal(EventCatalog.BLOCK_BREAK, pos.immutable(), id);
        }
        return EventResult.pass();
    }

    private static EventResult onBlockPlace(net.minecraft.world.level.Level level, BlockPos pos,
            BlockState state, Entity placer) {
        DriverHost api = WorldDriver.host();
        if (api != null && placer != null) {
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            api.emitExternal(EventCatalog.BLOCK_PLACE, pos.immutable(), id);
        }
        return EventResult.pass();
    }

    private static EventResult onLivingDeath(LivingEntity entity,
            net.minecraft.world.damagesource.DamageSource source) {
        DriverHost api = WorldDriver.host();
        if (api != null) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            api.emitExternal(EventCatalog.ENTITY_DEATH, entity.blockPosition(), id);
        }
        return EventResult.pass();
    }

    private static void emitPlayer(EventCatalog.Entry<String> type, ServerPlayer player) {
        DriverHost api = WorldDriver.host();
        if (api == null) return;
        api.emitExternal(type, player.blockPosition(), player.getGameProfile().getName());
    }

    private static EventResult onChat(ServerPlayer player, net.minecraft.network.chat.Component message) {
        DriverHost api = WorldDriver.host();
        if (api != null) {
            api.emitExternal(EventCatalog.CHAT_MESSAGE, player.blockPosition(),
                    new EventCatalog.ChatMessage(player.getGameProfile().getName(), message.getString()));
        }
        return EventResult.pass();
    }
}
