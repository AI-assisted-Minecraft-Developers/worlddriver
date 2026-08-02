package net.magicterra.worlddriver.neoforge;

import com.mojang.authlib.GameProfile;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.sim.ServerAvatarBodies;
import net.minecraft.core.BlockPos;
import net.magicterra.worlddriver.script.ScriptEvents;
import net.magicterra.stagewright.StageWrightCommon;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
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
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@Mod(WorldDriverCommon.MOD_ID)
public final class WorldDriverNeoForge {
    // P1c dogfood wiring (stagewright task 3): mirrors StageWrightCommon's own internal
    // stagewright.autorun gate. The double gate is intentionally redundant — it keeps
    // BotConfig.applyGameTestBaseline() (an worlddriver-side concern StageWrightCommon
    // knows nothing about) conditioned on the exact same system property that decides
    // whether the harness itself runs, so the two can never diverge.
    private static final boolean TESTKIT_AUTORUN = Boolean.getBoolean("stagewright.autorun");

    public WorldDriverNeoForge(IEventBus modBus, ModContainer container) {
        // P1.6 Task 1: inject the loader body factory behind the common ServerAvatarBodies
        // seam. Backed by FakePlayerFactory (getMinecraft/get), so the server-agent sim core
        // — now in common — mints the SAME cached FakePlayer instances as before the migration
        // (byte-level metric gates unchanged). Installed once, at mod construction, before any
        // scene/GameTest/agentserver body is created.
        ServerAvatarBodies.install(new ServerAvatarBodies.BodyFactory() {
            @Override public ServerPlayer shared(ServerLevel level) { return FakePlayerFactory.getMinecraft(level); }
            @Override public ServerPlayer unique(ServerLevel level, GameProfile profile) { return FakePlayerFactory.get(level, profile); }
        });
        NeoForge.EVENT_BUS.register(this);
        // P4-final (campaign close): the legacy @GameTest suite and its dedicated-server run
        // machinery are fully retired — every arena was migrated to a dogfooded wd.* testkit
        // scene (common testmod source set) and the legacy test classes/scripts/run config were
        // removed. Scenes are now delivered by the testkit harness (dogfood autorun / mc.test.run).
        // See docs/stagewright/migration-log.md for the full retirement record. Nothing test-related
        // is registered from main, so the production jar carries no test classes.
        WorldDriverCommon.LOG.info("[{}] NeoForge entry constructed", WorldDriverCommon.MOD_ID);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        WorldDriverCommon.onServerStarting();
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        WorldDriverCommon.onServerStarted(event.getServer());
        // StageWright forwarding is UNCONDITIONAL (P3a controller adjudication): StageWrightCommon
        // implements the autorun-vs-armed split internally — with -Dstagewright.autorun unset it
        // only logs "armed, awaiting mc.test.run" and registers the mc.test.* verb hooks, which
        // the T2 on-demand topology (mc.test.run against a non-autorun dedicated server) needs.
        // applyGameTestBaseline STAYS gated on autorun: it pins the legacy default-OFF flag
        // baseline the arenas were authored against and must NOT mutate a production server's
        // live bot defaults — it only matters where the suite auto-runs its scenes at boot.
        if (TESTKIT_AUTORUN) {
            BotConfig.applyGameTestBaseline();
        }
        StageWrightCommon.onServerStarted(event.getServer(), "neoforge");
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) { WorldDriverCommon.onServerStopping(); }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        WorldDriverCommon.registerCommands(event.getDispatcher());
        net.magicterra.worlddriver.neoforge.sim.ServerAvatarCommand.register(event.getDispatcher());   // Phase 2: /agentserver
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        ScriptEvents.fireTick();
        net.magicterra.worlddriver.neoforge.sim.ServerAvatarManager.tickAll();   // Phase 2: drive server-side FakePlayer agents
        // Unconditional (P3a): a no-op until the harness is built (autorun at boot OR mc.test.run
        // on-demand), so the armed-awaiting T2 server advances its suite once triggered.
        StageWrightCommon.onServerTick(event.getServer());
    }

    // LOWEST priority: run after all other handlers so cancellations have settled
    // before we record the break. Cancellable event fires PRE-destruction, so this
    // is still slightly optimistic, but it's the closest hook NeoForge exposes.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        DriverApi api = WorldDriverCommon.api();
        if (api == null || event.isCanceled()) return;
        var pos = event.getPos();
        String id = BuiltInRegistries.BLOCK.getKey(event.getState().getBlock()).toString();
        api.emitExternal("block.break", new BlockPos(pos.getX(), pos.getY(), pos.getZ()), id);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        DriverApi api = WorldDriverCommon.api();
        if (api == null || event.isCanceled()) return;
        Entity placer = event.getEntity();
        if (placer == null) return;
        var pos = event.getPos();
        String id = BuiltInRegistries.BLOCK.getKey(event.getPlacedBlock().getBlock()).toString();
        api.emitExternal("block.place", new BlockPos(pos.getX(), pos.getY(), pos.getZ()), id);
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        DriverApi api = WorldDriverCommon.api();
        if (api == null) return;
        LivingEntity entity = event.getEntity();
        var p = entity.blockPosition();
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        api.emitExternal("entity.death", new BlockPos(p.getX(), p.getY(), p.getZ()), id);
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        DriverApi api = WorldDriverCommon.api();
        if (api == null) return;
        Player pl = event.getEntity();
        var p = pl.blockPosition();
        api.emitExternal("player.join", new BlockPos(p.getX(), p.getY(), p.getZ()),
                pl.getGameProfile().getName());
    }

    @SubscribeEvent
    public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        DriverApi api = WorldDriverCommon.api();
        if (api == null) return;
        Player pl = event.getEntity();
        var p = pl.blockPosition();
        api.emitExternal("player.leave", new BlockPos(p.getX(), p.getY(), p.getZ()),
                pl.getGameProfile().getName());
    }

    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        DriverApi api = WorldDriverCommon.api();
        if (api == null) return;
        Player pl = event.getPlayer();
        var p = pl.blockPosition();
        String text = event.getRawText();
        api.emitExternal("chat.message", new BlockPos(p.getX(), p.getY(), p.getZ()),
                pl.getGameProfile().getName() + ": " + text);
    }
}
