package net.magicterra.agent.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.magicterra.agent.AgentDriverCommon;
import net.magicterra.agent.api.AgentApi;
import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.sim.ServerAgentBodies;
import net.magicterra.agent.bot.sim.ServerAgentManager;
import net.magicterra.agent.fabric.sim.FabricAgentBodies;
import net.minecraft.core.BlockPos;
import net.magicterra.agent.script.AgentEvents;
import net.magicterra.testkit.TestkitCommon;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

public final class AgentDriverFabric implements ModInitializer {
    // P1.6 Task 3 dogfood wiring — mirrors AgentDriverNeoForge's TESTKIT_AUTORUN double
    // gate exactly: the same -Dtestkit.autorun system property that arms the mc-testkit
    // harness also conditions BotConfig.applyGameTestBaseline() (an agent-driver concern
    // TestkitCommon knows nothing about), so the two can never diverge across loaders.
    private static final boolean TESTKIT_AUTORUN = Boolean.getBoolean("testkit.autorun");

    @Override
    public void onInitialize() {
        // P1.6 Task 3: install the fabric loader body factory behind the common
        // ServerAgentBodies seam BEFORE anything can create a server-agent body — the
        // FIRST statement of init, mirroring AgentDriverNeoForge's ctor. NeoForge injects
        // FakePlayerFactory bodies; fabric injects the vanilla-only AgentFakePlayer via
        // FabricAgentBodies (a faithful reimplementation of FakePlayerFactory's cache).
        FabricAgentBodies bodies = new FabricAgentBodies();
        ServerAgentBodies.install(bodies);
        // FakePlayerFactory.unloadLevel has no fabric built-in equivalent, so evict the
        // per-level body cache explicitly on world unload (mirrors NeoForge's level-unload hook).
        ServerWorldEvents.UNLOAD.register((server, world) -> bodies.unloadLevel(world));

        AgentDriverCommon.LOG.info("[{}] Fabric entry initialized", AgentDriverCommon.MOD_ID);
        ServerLifecycleEvents.SERVER_STARTING.register(server -> AgentDriverCommon.onServerStarting());
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            AgentDriverCommon.onServerStarted(server);
            if (TESTKIT_AUTORUN) {
                BotConfig.applyGameTestBaseline();
                TestkitCommon.onServerStarted(server, "fabric");
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> AgentDriverCommon.onServerStopping());
        // Order mirrors AgentDriverNeoForge.onServerTick exactly: fireTick, then
        // ServerAgentManager.tickAll() (drive server-side agents), then — if gated —
        // TestkitCommon.onServerTick() (advance the dogfood scene runner).
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            AgentEvents.fireTick();
            ServerAgentManager.tickAll();
            if (TESTKIT_AUTORUN) TestkitCommon.onServerTick(server);
        });
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
