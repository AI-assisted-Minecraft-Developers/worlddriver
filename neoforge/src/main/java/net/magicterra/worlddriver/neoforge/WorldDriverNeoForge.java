package net.magicterra.worlddriver.neoforge;

import com.mojang.authlib.GameProfile;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.WorldDriverEvents;
import net.magicterra.worlddriver.bot.sim.AvatarNetHandler;
import net.magicterra.worlddriver.bot.sim.ServerAvatarBodies;
import net.magicterra.worlddriver.neoforge.sim.ServerAvatarCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/**
 * NeoForge entry. Installs the loader's body factory, then hands every event subscription to
 * {@link WorldDriverEvents}; what is left here is exactly what has no cross-loader form.
 */
@Mod(WorldDriverCommon.MOD_ID)
public final class WorldDriverNeoForge {
    public WorldDriverNeoForge(IEventBus modBus, ModContainer container) {
        // Inject the loader body factory behind the common ServerAvatarBodies seam. Backed by
        // FakePlayerFactory (getMinecraft/get), so the server-agent sim core — in common — mints the
        // SAME cached FakePlayer instances as before the migration (byte-level metric gates
        // unchanged). Installed once, at mod construction, before any scene or server-avatar body
        // is created.
        ServerAvatarBodies.install(new ServerAvatarBodies.BodyFactory() {
            @Override public ServerPlayer shared(ServerLevel level) {
                // install(): NeoForge's FakePlayer wears a listener whose teleport() is a no-op, and
                // ServerPlayer.changeDimension delivers the destination through exactly that call — so
                // without this a portal moves the body between dimensions and not between places.
                return AvatarNetHandler.install(FakePlayerFactory.getMinecraft(level));
            }
            @Override public ServerPlayer unique(ServerLevel level, GameProfile profile) {
                return AvatarNetHandler.install(FakePlayerFactory.get(level, profile));
            }
        });
        WorldDriverEvents.register();
        // The server-avatar command rides NeoForge's FakePlayer, so it has no Fabric twin.
        CommandRegistrationEvent.EVENT.register((dispatcher, context, selection) ->
                ServerAvatarCommand.register(dispatcher));
        WorldDriverCommon.installTestContent();
        WorldDriverCommon.LOG.info("[{}] NeoForge entry constructed", WorldDriverCommon.MOD_ID);
    }
}
