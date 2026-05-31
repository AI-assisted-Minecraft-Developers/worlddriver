package net.magicterra.agent.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.magicterra.agent.AgentDriverCommon;
import net.magicterra.agent.api.AgentApi;
import net.minecraft.core.BlockPos;
import net.magicterra.agent.script.AgentEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

public final class AgentDriverFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        AgentDriverCommon.LOG.info("[{}] Fabric entry initialized", AgentDriverCommon.MOD_ID);
        ServerLifecycleEvents.SERVER_STARTING.register(server -> AgentDriverCommon.onServerStarting());
        ServerLifecycleEvents.SERVER_STARTED.register(server -> AgentDriverCommon.onServerStarted(server));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> AgentDriverCommon.onServerStopping());
        ServerTickEvents.END_SERVER_TICK.register(server -> AgentEvents.fireTick());
        CommandRegistrationCallback.EVENT.register((dispatcher, ctx, env) ->
                AgentDriverCommon.registerCommands(dispatcher));

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            AgentApi api = AgentDriverCommon.api();
            if (api == null) return;
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            api.emitExternal("block.break", new BlockPos(pos.getX(), pos.getY(), pos.getZ()), id);
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, src) -> {
            AgentApi api = AgentDriverCommon.api();
            if (api == null) return;
            var p = entity.blockPosition();
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            api.emitExternal("entity.death", new BlockPos(p.getX(), p.getY(), p.getZ()), id);
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            AgentApi api = AgentDriverCommon.api();
            if (api == null) return;
            ServerPlayer pl = handler.player;
            var p = pl.blockPosition();
            api.emitExternal("player.join", new BlockPos(p.getX(), p.getY(), p.getZ()),
                    pl.getGameProfile().getName());
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            AgentApi api = AgentDriverCommon.api();
            if (api == null) return;
            ServerPlayer pl = handler.player;
            var p = pl.blockPosition();
            api.emitExternal("player.leave", new BlockPos(p.getX(), p.getY(), p.getZ()),
                    pl.getGameProfile().getName());
        });

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            AgentApi api = AgentDriverCommon.api();
            if (api == null) return;
            Player pl = sender;
            var p = pl.blockPosition();
            String text = message.signedContent();
            api.emitExternal("chat.message", new BlockPos(p.getX(), p.getY(), p.getZ()),
                    pl.getGameProfile().getName() + ": " + text);
        });
    }
}
