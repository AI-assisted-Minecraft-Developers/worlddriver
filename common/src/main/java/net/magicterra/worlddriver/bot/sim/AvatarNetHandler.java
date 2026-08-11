package net.magicterra.worlddriver.bot.sim;

import java.util.Set;

import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jetbrains.annotations.Nullable;

/**
 * The packet listener a code-driven body wears: everything outbound is swallowed, and
 * <b>{@code teleport} is not</b>.
 *
 * <p>Both loaders' fake players ship a listener whose methods are all no-ops, which is right for
 * every one of them but one. {@code ServerPlayer.changeDimension} does not move the body itself —
 * it sets the new level and then delivers the destination <i>through {@code connection.teleport}</i>.
 * Swallowed, the body arrives in the new dimension still holding its old coordinates.
 *
 * <p>Measured by {@code wd.serverEntersTheNether} before the fix: an overworld portal at
 * {@code x=100001} put the body at nether {@code x=100001} instead of {@code x=12500} — 87 501
 * blocks out, at {@code y=221} against a logical height of 128, standing on air, with the return
 * portal correctly built 87 501 blocks away where the body should have been. The dimension
 * assertion passed. Everything downstream of it — a fortress search, a stronghold, the End — would
 * have been looking at the wrong world, and nothing in the run would have said so.
 *
 * <p>{@code ServerPlayer.teleportTo} routes here too, so the same swallow is why no vanilla
 * mechanism could reposition a driven body at all. The End portal and the dragon's gateways use it.
 *
 * <p>Why a shared class rather than two copies: NeoForge's {@code FakePlayer} is not ours to
 * subclass, but {@code ServerPlayer.connection} is a public field, so the loader shim installs this
 * over the stub NeoForge built. The body keeps its {@code FakePlayer} identity — which mods look
 * for — and only the listener changes.
 */
public class AvatarNetHandler extends ServerGamePacketListenerImpl {

    /** Dummy never-connected {@link Connection} (SERVERBOUND), as in {@code FakePlayer$FakeConnection}. */
    private static final class AgentFakeConnection extends Connection {
        AgentFakeConnection() { super(PacketFlow.SERVERBOUND); }
    }

    private static final Connection DUMMY_CONNECTION = new AgentFakeConnection();

    public AvatarNetHandler(MinecraftServer server, ServerPlayer player) {
        super(server, DUMMY_CONNECTION, player,
                CommonListenerCookie.createInitial(player.getGameProfile(), false));
    }

    /**
     * Put this listener on {@code body} unless it already wears one.
     *
     * <p>Idempotent because both loaders cache bodies per profile per level, so this is reached
     * again every time a driver asks for the same body.
     */
    public static ServerPlayer install(ServerPlayer body) {
        if (!(body.connection instanceof AvatarNetHandler))
            body.connection = new AvatarNetHandler(body.server, body);
        return body;
    }

    @Override public void tick() { }

    @Override public void send(Packet<?> packet) { }

    @Override public void send(Packet<?> packet, @Nullable PacketSendListener sendListener) { }

    @Override public void resetPosition() { }

    @Override public void disconnect(Component reason) { }

    @Override public void onDisconnect(DisconnectionDetails details) { }

    /** The one that must actually happen — see the class javadoc. This is what vanilla's real
     *  listener does in {@code internalTeleport}, minus the packet there is nobody to send. */
    @Override public void teleport(double x, double y, double z, float yaw, float pitch) {
        this.player.absMoveTo(x, y, z, yaw, pitch);
    }

    @Override public void teleport(double x, double y, double z, float yaw, float pitch,
                                   Set<net.minecraft.world.entity.RelativeMovement> relativeSet) {
        this.teleport(x, y, z, yaw, pitch);
    }

    @Override public void ackBlockChangesUpTo(int sequence) { }
}
