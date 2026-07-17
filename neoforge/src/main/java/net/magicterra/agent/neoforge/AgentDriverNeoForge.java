package net.magicterra.agent.neoforge;

import com.mojang.authlib.GameProfile;
import net.magicterra.agent.AgentDriverCommon;
import net.magicterra.agent.api.AgentApi;
import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.sim.ServerAgentBodies;
import net.minecraft.core.BlockPos;
import net.magicterra.agent.script.AgentEvents;
import net.magicterra.testkit.TestkitCommon;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.gametest.framework.GameTestServer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@Mod(AgentDriverCommon.MOD_ID)
public final class AgentDriverNeoForge {
    // P1c dogfood wiring (mc-testkit task 3): mirrors TestkitCommon's own internal
    // testkit.autorun gate. The double gate is intentionally redundant — it keeps
    // BotConfig.applyGameTestBaseline() (an agent-driver-side concern TestkitCommon
    // knows nothing about) conditioned on the exact same system property that decides
    // whether the harness itself runs, so the two can never diverge.
    private static final boolean TESTKIT_AUTORUN = Boolean.getBoolean("testkit.autorun");

    public AgentDriverNeoForge(IEventBus modBus, ModContainer container) {
        // P1.6 Task 1: inject the loader body factory behind the common ServerAgentBodies
        // seam. Backed by FakePlayerFactory (getMinecraft/get), so the server-agent sim core
        // — now in common — mints the SAME cached FakePlayer instances as before the migration
        // (byte-level metric gates unchanged). Installed once, at mod construction, before any
        // scene/GameTest/agentserver body is created.
        ServerAgentBodies.install(new ServerAgentBodies.BodyFactory() {
            @Override public ServerPlayer shared(ServerLevel level) { return FakePlayerFactory.getMinecraft(level); }
            @Override public ServerPlayer unique(ServerLevel level, GameProfile profile) { return FakePlayerFactory.get(level, profile); }
        });
        NeoForge.EVENT_BUS.register(this);
        modBus.addListener((RegisterGameTestsEvent event) -> {
            // AgentGameTest was split by arena family for file-size hygiene; every
            // @GameTestHolder class must be registered explicitly (NeoForge does not
            // auto-discover them here). AgentGameTestSupport holds only shared helpers
            // (no @GameTest methods) so it is intentionally not registered.
            event.register(AgentGameTest.class);
            event.register(AgentGameTestTerrain.class);
            event.register(AgentGameTestServer.class);
            event.register(AgentGameTestWaterBank.class);
            event.register(AgentGameTestWaterCross.class);
            event.register(AgentGameTestCombatSense.class);
            event.register(AgentGameTestBuildBlock.class);
        });
        AgentDriverCommon.LOG.info("[{}] NeoForge entry constructed", AgentDriverCommon.MOD_ID);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // GameTest suite runs on a dedicated GameTestServer: pin the legacy default-OFF
        // flag baseline the arenas were authored against (BotConfig.applyGameTestBaseline
        // doc). Live/integrated servers keep the new defaults.
        if (event.getServer() instanceof GameTestServer) {
            BotConfig.applyGameTestBaseline();
            GameTestManifest.reset();
        }
        AgentDriverCommon.onServerStarting();
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        AgentDriverCommon.onServerStarted(event.getServer());
        if (TESTKIT_AUTORUN) {
            BotConfig.applyGameTestBaseline();
            TestkitCommon.onServerStarted(event.getServer(), "neoforge");
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) { AgentDriverCommon.onServerStopping(); }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        AgentDriverCommon.registerCommands(event.getDispatcher());
        net.magicterra.agent.neoforge.sim.ServerAgentCommand.register(event.getDispatcher());   // Phase 2: /agentserver
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        AgentEvents.fireTick();
        net.magicterra.agent.neoforge.sim.ServerAgentManager.tickAll();   // Phase 2: drive server-side FakePlayer agents
        if (TESTKIT_AUTORUN) TestkitCommon.onServerTick(event.getServer());
    }

    // LOWEST priority: run after all other handlers so cancellations have settled
    // before we record the break. Cancellable event fires PRE-destruction, so this
    // is still slightly optimistic, but it's the closest hook NeoForge exposes.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        AgentApi api = AgentDriverCommon.api();
        if (api == null || event.isCanceled()) return;
        var pos = event.getPos();
        String id = BuiltInRegistries.BLOCK.getKey(event.getState().getBlock()).toString();
        api.emitExternal("block.break", new BlockPos(pos.getX(), pos.getY(), pos.getZ()), id);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        AgentApi api = AgentDriverCommon.api();
        if (api == null || event.isCanceled()) return;
        Entity placer = event.getEntity();
        if (placer == null) return;
        var pos = event.getPos();
        String id = BuiltInRegistries.BLOCK.getKey(event.getPlacedBlock().getBlock()).toString();
        api.emitExternal("block.place", new BlockPos(pos.getX(), pos.getY(), pos.getZ()), id);
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        AgentApi api = AgentDriverCommon.api();
        if (api == null) return;
        LivingEntity entity = event.getEntity();
        var p = entity.blockPosition();
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        api.emitExternal("entity.death", new BlockPos(p.getX(), p.getY(), p.getZ()), id);
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        AgentApi api = AgentDriverCommon.api();
        if (api == null) return;
        Player pl = event.getEntity();
        var p = pl.blockPosition();
        api.emitExternal("player.join", new BlockPos(p.getX(), p.getY(), p.getZ()),
                pl.getGameProfile().getName());
    }

    @SubscribeEvent
    public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        AgentApi api = AgentDriverCommon.api();
        if (api == null) return;
        Player pl = event.getEntity();
        var p = pl.blockPosition();
        api.emitExternal("player.leave", new BlockPos(p.getX(), p.getY(), p.getZ()),
                pl.getGameProfile().getName());
    }

    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        AgentApi api = AgentDriverCommon.api();
        if (api == null) return;
        Player pl = event.getPlayer();
        var p = pl.blockPosition();
        String text = event.getRawText();
        api.emitExternal("chat.message", new BlockPos(p.getX(), p.getY(), p.getZ()),
                pl.getGameProfile().getName() + ": " + text);
    }
}
