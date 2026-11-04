package net.magicterra.worlddriver.game.body;

import java.util.function.Consumer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * What a loader announces as a bot's connection closes, before the packet listener's {@code onDisconnect}: where a
 * client's departure passes through {@code Connection.handleDisconnection}, which a bot's connection replaces
 * ({@link SilentConnection}). Fabric fires its own disconnect event at that call; NeoForge's logout event comes later,
 * from {@code PlayerList.remove}, and needs nothing here.
 */
public final class Departures {

    private static volatile Consumer<ServerGamePacketListenerImpl> announce = listener -> {};

    private Departures() {}

    /** Set by a loader at start-up: what it does as a connection closes, with that connection's play listener. */
    public static void announceWith(Consumer<ServerGamePacketListenerImpl> hook) {
        announce = hook;
    }

    /** A bot's connection closes; server thread, before the listener's {@code onDisconnect}. */
    static void closing(ServerGamePacketListenerImpl listener) {
        announce.accept(listener);
    }
}
