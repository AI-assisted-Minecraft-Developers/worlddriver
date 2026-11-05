package net.magicterra.worlddriver.game.body;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

/**
 * What the server said to a bot: the chat, system and action bar lines a client would show, kept from the packets its
 * {@link SilentConnection} throws away, so a test can read which message a player was given. One per connection, so a
 * respawn keeps it and a reconnect starts a new one. It keeps the last {@value #CAPACITY}; a bot in a long run would
 * otherwise hold every line it was ever sent.
 */
public final class Inbox {

    static final int CAPACITY = 256;

    /** Where a client shows the line. */
    public enum Kind {
        /** Another player's chat, decorated as the chat type says ({@code <alice> hi}). */
        CHAT,
        /** A system line in the chat: command feedback, a mod's message. */
        SYSTEM,
        /** Above the hotbar: a system message sent as an overlay, or {@code /title … actionbar}. */
        ACTION_BAR
    }

    /** One line, as the client would show it. */
    public record Message(Component text, Kind kind) {}

    private final Deque<Message> messages = new ArrayDeque<>();

    /** Keep {@code packet}'s line if it is one a client shows in the chat or above the hotbar. */
    void receive(Packet<?> packet) {
        switch (packet) {
            case ClientboundSystemChatPacket system ->
                add(system.content(), system.overlay() ? Kind.ACTION_BAR : Kind.SYSTEM);
            case ClientboundSetActionBarTextPacket bar -> add(bar.text(), Kind.ACTION_BAR);
            case ClientboundPlayerChatPacket chat -> {
                Component content = chat.unsignedContent() != null
                        ? chat.unsignedContent()
                        : Component.literal(chat.body().content());
                add(chat.chatType().decorate(content), Kind.CHAT);
            }
            case ClientboundDisguisedChatPacket chat -> add(chat.chatType().decorate(chat.message()), Kind.CHAT);
            case BundlePacket<?> bundle -> bundle.subPackets().forEach(this::receive);
            default -> {
                // Not a line a client shows.
            }
        }
    }

    private synchronized void add(Component text, Kind kind) {
        if (messages.size() == CAPACITY) {
            messages.removeFirst();
        }
        messages.addLast(new Message(text, kind));
    }

    /** The lines kept, oldest first. */
    public synchronized List<Message> messages() {
        return List.copyOf(messages);
    }

    /** Forget every line kept so far. */
    public synchronized void clear() {
        messages.clear();
    }
}
