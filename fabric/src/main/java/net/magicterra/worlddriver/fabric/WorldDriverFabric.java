package net.magicterra.worlddriver.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.sim.ServerAvatarBodies;
import net.magicterra.worlddriver.bot.sim.ServerAvatarManager;
import net.magicterra.worlddriver.fabric.sim.FabricAvatarBodies;
import net.minecraft.core.BlockPos;
import net.magicterra.worlddriver.script.ScriptEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

public final class WorldDriverFabric implements ModInitializer {
    // Reads the same -Dstagewright.autorun property StageWright arms on, but does NOT talk to
    // StageWright: applyGameTestBaseline() is a worlddriver concern (it pins the legacy default-OFF
    // flag baseline the scene arenas were authored against). The property is the only thing shared,
    // which is what lets the driver stay ignorant of whether a test framework is even installed.
    private static final boolean TESTKIT_AUTORUN = Boolean.getBoolean("stagewright.autorun");

    @Override
    public void onInitialize() {
        // P1.6 Task 3: install the fabric loader body factory behind the common
        // ServerAvatarBodies seam BEFORE anything can create a server-agent body — the
        // FIRST statement of init, mirroring WorldDriverNeoForge's ctor. NeoForge injects
        // FakePlayerFactory bodies; fabric injects the vanilla-only AvatarFakePlayer via
        // FabricAvatarBodies (a faithful reimplementation of FakePlayerFactory's cache).
        FabricAvatarBodies bodies = new FabricAvatarBodies();
        ServerAvatarBodies.install(bodies);
        // FakePlayerFactory.unloadLevel has no fabric built-in equivalent, so evict the
        // per-level body cache explicitly on world unload (mirrors NeoForge's level-unload hook).
        ServerWorldEvents.UNLOAD.register((server, world) -> bodies.unloadLevel(world));

        WorldDriverCommon.LOG.info("[{}] Fabric entry initialized", WorldDriverCommon.MOD_ID);
        ServerLifecycleEvents.SERVER_STARTING.register(server -> WorldDriverCommon.onServerStarting());
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            WorldDriverCommon.onServerStarted(server);
            // Gated on autorun: this pins the legacy default-OFF flag baseline the scene arenas were
            // authored against, and must NOT mutate a production server's live bot defaults — it only
            // matters where a suite auto-runs its scenes at boot. StageWright arms itself from its own
            // SERVER_STARTED handler; the driver does not forward lifecycle to it any more.
            if (TESTKIT_AUTORUN) {
                BotConfig.applyGameTestBaseline();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> WorldDriverCommon.onServerStopping());
        // Order mirrors WorldDriverNeoForge.onServerTick exactly: fireTick, then
        // ServerAvatarManager.tickAll() (drive server-side agents). StageWright ticks its own
        // harness from its own END_SERVER_TICK registration — it is a mod, not a library the
        // driver has to pump.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            ScriptEvents.fireTick();
            ServerAvatarManager.tickAll();
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, ctx, env) ->
                WorldDriverCommon.registerCommands(dispatcher));

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            DriverApi api = WorldDriverCommon.api();
            if (api == null) return;
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            api.emitExternal("block.break", new BlockPos(pos.getX(), pos.getY(), pos.getZ()), id);
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, src) -> {
            DriverApi api = WorldDriverCommon.api();
            if (api == null) return;
            var p = entity.blockPosition();
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            api.emitExternal("entity.death", new BlockPos(p.getX(), p.getY(), p.getZ()), id);
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            DriverApi api = WorldDriverCommon.api();
            if (api == null) return;
            ServerPlayer pl = handler.player;
            var p = pl.blockPosition();
            api.emitExternal("player.join", new BlockPos(p.getX(), p.getY(), p.getZ()),
                    pl.getGameProfile().getName());
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            DriverApi api = WorldDriverCommon.api();
            if (api == null) return;
            ServerPlayer pl = handler.player;
            var p = pl.blockPosition();
            api.emitExternal("player.leave", new BlockPos(p.getX(), p.getY(), p.getZ()),
                    pl.getGameProfile().getName());
        });

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            DriverApi api = WorldDriverCommon.api();
            if (api == null) return;
            Player pl = sender;
            var p = pl.blockPosition();
            String text = message.signedContent();
            api.emitExternal("chat.message", new BlockPos(p.getX(), p.getY(), p.getZ()),
                    pl.getGameProfile().getName() + ": " + text);
        });
    }
}
