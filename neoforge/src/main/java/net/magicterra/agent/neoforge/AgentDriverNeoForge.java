package net.magicterra.agent.neoforge;

import net.magicterra.agent.AgentDriverCommon;
import net.magicterra.agent.api.AgentApi;
import net.minecraft.core.BlockPos;
import net.magicterra.agent.script.AgentEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
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
    public AgentDriverNeoForge(IEventBus modBus, ModContainer container) {
        NeoForge.EVENT_BUS.register(this);
        modBus.addListener((RegisterGameTestsEvent event) -> event.register(AgentGameTest.class));
        AgentDriverCommon.LOG.info("[{}] NeoForge entry constructed", AgentDriverCommon.MOD_ID);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) { AgentDriverCommon.onServerStarting(); }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) { AgentDriverCommon.onServerStarted(event.getServer()); }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) { AgentDriverCommon.onServerStopping(); }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) { AgentDriverCommon.registerCommands(event.getDispatcher()); }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        AgentEvents.fireTick();
        net.magicterra.agent.neoforge.sim.ServerAgentManager.tickAll();   // Phase 2: drive server-side FakePlayer agents
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
