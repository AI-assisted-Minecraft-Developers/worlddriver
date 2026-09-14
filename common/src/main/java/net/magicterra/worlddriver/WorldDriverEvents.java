package net.magicterra.worlddriver;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.BlockEvent;
import dev.architectury.event.events.common.ChatEvent;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.sim.ServerAvatarCommand;
import net.magicterra.worlddriver.bot.sim.ServerAvatarManager;
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
 * {@link DriverApi#emitExternal}. Two of them changed timing when the loaders' handlers were
 * merged: {@code block.break} fires BEFORE the block is removed on both loaders (Fabric used to
 * report after), and {@code entity.death} fires as the death is decided rather than after it.
 * Neither carries state that the timing changes, and a listener that cancels the vanilla action
 * earlier in the chain suppresses the report the way it always did on NeoForge.
 */
public final class WorldDriverEvents {
    // Reads the same -Dstagewright.autorun property StageWright arms on, but does NOT talk to
    // StageWright: applyGameTestBaseline() is a worlddriver concern (it pins the legacy default-OFF
    // flag baseline the scene arenas were authored against). The property is the only thing shared,
    // which is what lets the driver stay ignorant of whether a test framework is even installed.
    private static final boolean TESTKIT_AUTORUN = Boolean.getBoolean("stagewright.autorun");

    private WorldDriverEvents() {}

    /** Subscribes every server-side handler. Call once, from the loader's mod-construction entry. */
    public static void register() {
        LifecycleEvent.SERVER_STARTING.register(server -> WorldDriverCommon.onServerStarting());
        LifecycleEvent.SERVER_STARTED.register(server -> {
            WorldDriverCommon.onServerStarted(server);
            // Gated on autorun: this pins the legacy default-OFF flag baseline the scene arenas were
            // authored against, and must NOT mutate a production server's live bot defaults — it only
            // matters where a suite auto-runs its scenes at boot. StageWright arms itself from its own
            // server-started handler; the driver does not forward lifecycle to it.
            if (TESTKIT_AUTORUN) {
                BotConfig.applyGameTestBaseline();
            }
        });
        LifecycleEvent.SERVER_STOPPING.register(server -> WorldDriverCommon.onServerStopping());
        // fireTick, then drive the server-side agents. StageWright ticks its own harness from its
        // own registration — it is a mod, not a library the driver has to pump.
        TickEvent.SERVER_POST.register(server -> {
            ScriptEvents.fireTick();
            ServerAvatarManager.tickAll();
        });
        CommandRegistrationEvent.EVENT.register((dispatcher, context, selection) -> {
            WorldDriverCommon.registerCommands(dispatcher);
            ServerAvatarCommand.register(dispatcher);
        });

        BlockEvent.BREAK.register(WorldDriverEvents::onBlockBreak);
        BlockEvent.PLACE.register(WorldDriverEvents::onBlockPlace);
        EntityEvent.LIVING_DEATH.register(WorldDriverEvents::onLivingDeath);
        PlayerEvent.PLAYER_JOIN.register(player -> emitPlayer("player.join", player));
        PlayerEvent.PLAYER_QUIT.register(player -> emitPlayer("player.leave", player));
        ChatEvent.RECEIVED.register(WorldDriverEvents::onChat);
    }

    private static EventResult onBlockBreak(net.minecraft.world.level.Level level, BlockPos pos,
            BlockState state, ServerPlayer player, dev.architectury.utils.value.IntValue xp) {
        DriverApi api = WorldDriverCommon.api();
        if (api != null) {
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            api.emitExternal("block.break", pos.immutable(), id);
        }
        return EventResult.pass();
    }

    private static EventResult onBlockPlace(net.minecraft.world.level.Level level, BlockPos pos,
            BlockState state, Entity placer) {
        DriverApi api = WorldDriverCommon.api();
        if (api != null && placer != null) {
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            api.emitExternal("block.place", pos.immutable(), id);
        }
        return EventResult.pass();
    }

    private static EventResult onLivingDeath(LivingEntity entity,
            net.minecraft.world.damagesource.DamageSource source) {
        DriverApi api = WorldDriverCommon.api();
        if (api != null) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            api.emitExternal("entity.death", entity.blockPosition(), id);
        }
        return EventResult.pass();
    }

    private static void emitPlayer(String type, ServerPlayer player) {
        DriverApi api = WorldDriverCommon.api();
        if (api == null) return;
        api.emitExternal(type, player.blockPosition(), player.getGameProfile().getName());
    }

    private static EventResult onChat(ServerPlayer player, net.minecraft.network.chat.Component message) {
        DriverApi api = WorldDriverCommon.api();
        if (api != null) {
            api.emitExternal("chat.message", player.blockPosition(),
                    player.getGameProfile().getName() + ": " + message.getString());
        }
        return EventResult.pass();
    }
}
