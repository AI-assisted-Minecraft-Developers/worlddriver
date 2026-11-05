package net.magicterra.worlddriver.game.body;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jetbrains.annotations.Nullable;

/**
 * A bot's connection: "up", and going nowhere.
 *
 * <p>{@code isConnected()} must be true: {@code Connection.send} only drops a packet when it believes it is connected —
 * otherwise it appends to {@code pendingActions}, unboundedly, for a client that will never arrive.
 *
 * <p>Being told to go is the one thing it does not swallow. A kick, a second login under the bot's name or the server
 * stopping ends in {@link #handleDisconnection}, and the bot leaves the game as a client told to go would, as Carpet's
 * fake players do; until 2026-11-03 it stayed, and a second player with its UUID joined beside it. No idle timeout
 * reaches it: that check is in the packet listener's tick, which only a real connection's tick runs, and that runs no
 * keep-alive a bot could not answer either.
 */
final class SilentConnection extends Connection {

    /**
     * A real netty channel that throws its writes away.
     *
     * <p>Vanilla's join path never touches {@code channel()} — every reach for it goes through {@code send}, which this
     * class swallows. NeoForge's does: it stores the connection type as a <b>channel attribute</b>, so
     * {@code placeNewPlayer} dies on {@code Connection.channel().attr(...)} before the player exists. Measured: the
     * whole armed NeoForge suite fell to 76 executed scenes, every avatar scene reporting the same NPE.
     *
     * <p>{@link EmbeddedChannel} supplies attributes for free, but its default tail queues outbound messages forever —
     * a silent leak in place of a loud crash. The handler discards and completes each write instead of letting it reach
     * that queue.
     *
     * <p>One packet never reaches {@code send}: NeoForge refuses a modded payload above the connection, for a channel
     * no client negotiated, and this connection negotiated none. The mixin on
     * {@code ServerCommonPacketListenerImpl.send} drops those first; see its javadoc.
     */
    @SuppressWarnings("unused") // held so the channel is not collected out from under us
    private final EmbeddedChannel wire;

    private PacketListener inbound;

    /** Why the server last told this connection to go: the reason in the disconnect packet it sent, then dropped. */
    private Component leaving = Component.translatable("multiplayer.disconnect.generic");

    private boolean left;

    private final Inbox inbox;

    SilentConnection(Inbox inbox) {
        super(PacketFlow.SERVERBOUND);
        this.inbox = inbox;
        // Registering fires channelActive through the pipeline, and Connection.channelActive is
        // what assigns its private `channel` field. That is the only way to fill it without
        // reflection — there is no setter, and `channel()` is NeoForge's accessor rather than a
        // vanilla method, so it cannot be overridden from :common either.
        wire = new EmbeddedChannel(discardOutbound(), this);
    }

    private static ChannelOutboundHandlerAdapter discardOutbound() {
        return new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                ReferenceCountUtil.release(msg);
                promise.setSuccess();
            }
        };
    }

    @Override
    public void send(Packet<?> packet) {
        send(packet, null, true);
    }

    @Override
    public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
        send(packet, listener, true);
    }

    @Override
    public void send(Packet<?> packet, @Nullable PacketSendListener listener, boolean flush) {
        // Swallowed: there is no client. A disconnect's reason is kept for the departure it announces, and a line the
        // client would show for the bot's inbox.
        if (packet instanceof ClientboundDisconnectPacket disconnect) {
            leaving = disconnect.reason();
        }
        inbox.receive(packet);
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public void tick() {
        // Nothing queued and no channel to poll.
    }

    @Override
    public void disconnect(Component reason) {
        // Stays "connected"; the departure is handleDisconnection's.
    }

    @Override
    public void disconnect(DisconnectionDetails details) {
        // Stays "connected"; the departure is handleDisconnection's.
    }

    @Override
    public void setListenerForServerboundHandshake(PacketListener listener) {
        // No handshake ever arrives.
    }

    @Override
    public void setReadOnly() {
        // Nothing is ever read from this connection.
    }

    @Override
    public void setupCompression(int threshold, boolean validate) {
        // A pipeline edit on a pipeline that goes nowhere.
    }

    /**
     * Called every server tick from {@code ServerCommonPacketListenerImpl.resumeFlushing}, and it reaches for
     * {@code channel.eventLoop()} directly rather than going through send.
     */
    @Override
    public void flushChannel() {
        // Nothing buffered to flush.
    }

    /**
     * Where {@code ServerCommonPacketListenerImpl.disconnect} ends, once it has sent the disconnect packet: the bot
     * leaves the game, saved and announced as any player who disconnects.
     *
     * <p>On a later server task, as a real connection's departure arrives from its netty thread and as Carpet defers
     * its fake player's. Leaving at once takes the bot off {@code PlayerList.players} inside the caller's loop, and
     * {@code PlayerList.removeAll} walks that list by index at shutdown: the player after each bot was never
     * disconnected.
     */
    @Override
    public void handleDisconnection() {
        if (left || !(inbound instanceof ServerGamePacketListenerImpl game)) {
            return;
        }
        left = true;
        if (game.player instanceof BotBody bot && !bot.claimLeaving()) {
            return; // discarded already, and off the list with it
        }
        MinecraftServer server = game.player.server;
        server.tell(new TickTask(server.getTickCount(), () -> {
            // What the loader hooks on a client's way out, which this method replaces.
            Departures.closing(game);
            game.onDisconnect(new DisconnectionDetails(leaving));
            if (game.player instanceof BotBody bot) {
                bot.logLeft();
            }
        }));
    }

    /**
     * The two that a swallowed {@code send} does not cover, and the reason the first attempt still died: a protocol
     * switch is not a packet, it is a pipeline edit, and {@code setupInboundProtocol} writes a marker straight to the
     * channel rather than through {@code send}. The join sequence changes protocol twice (login → configuration →
     * play), so this NPEs before the player is usable and after vanilla has already printed "logged in", which reads as
     * if the join succeeded.
     *
     * <p>Recording the listener is not optional: {@code setupInboundProtocol} is also where vanilla installs the packet
     * listener, and {@code getPacketListener()} is read during the join. Dropping the pipeline work while keeping the
     * assignment is the whole trick.
     */
    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T listener) {
        this.inbound = listener;
    }

    @Override
    public void setupOutboundProtocol(ProtocolInfo<?> protocol) {
        // A pipeline edit; outbound packets are swallowed in send either way.
    }

    @Override
    public PacketListener getPacketListener() {
        return inbound;
    }
}
