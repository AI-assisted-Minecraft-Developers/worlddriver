package net.magicterra.worlddriver.game.body;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.ResourceLocation;

/**
 * What the server said to a bot: the chat, system and action bar lines a client would show, and, while a test harness
 * asks for them, the custom payloads mods sent it, kept from the packets its {@link SilentConnection} throws away, so a
 * test can read which message a player was given or which update a mod pushed. One per connection, from before its
 * configuration, so a respawn keeps it and a reconnect starts a new one. It keeps the last {@value #CAPACITY} lines,
 * and as many payloads on each channel; a bot in a long run would otherwise hold everything it was ever sent.
 */
public final class Inbox {

    static final int CAPACITY = 256;

    /**
     * Whether payloads are kept at all. Off unless a test harness turns it on: a payload can be large, and a bot an
     * agent drives for hours has no test to read them.
     */
    private static volatile boolean keepPayloads;

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

    /**
     * A payload, numbered in the order the bot was sent them, which the per-channel queues do not keep between them.
     */
    private record Sent(long seq, CustomPacketPayload payload) {}

    /**
     * By the payload's channel, its {@code type()}, so a mod that sends on one often does not push out the one a test
     * waits for, even where two channels share a class.
     */
    private final Map<ResourceLocation, Deque<Sent>> payloads = new HashMap<>();

    private long sent;

    /** Keep the custom payloads sent to every bot from now on, for a test to read; until then none is kept. */
    public static void keepPayloads(boolean keep) {
        keepPayloads = keep;
    }

    /**
     * Keep {@code packet}'s line if it is one a client shows in the chat or above the hotbar, or its payload if it is a
     * mod's and payloads are kept. The payload is the object the mod sent, never written to a wire: nothing decodes it,
     * and a mod that changes it after sending changes what is kept.
     */
    void receive(Packet<?> packet) {
        switch (packet) {
            case ClientboundCustomPayloadPacket custom -> {
                if (keepPayloads) {
                    addPayload(custom.payload());
                }
            }
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
        bounded(messages, new Message(text, kind));
    }

    private synchronized void addPayload(CustomPacketPayload payload) {
        bounded(payloads.computeIfAbsent(payload.type().id(), c -> new ArrayDeque<>()), new Sent(sent++, payload));
    }

    private static <T> void bounded(Deque<T> kept, T item) {
        if (kept.size() == CAPACITY) {
            kept.removeFirst();
        }
        kept.addLast(item);
    }

    /** The lines kept, oldest first. */
    public synchronized List<Message> messages() {
        return List.copyOf(messages);
    }

    /** Forget every line kept so far. */
    public synchronized void clearMessages() {
        messages.clear();
    }

    /**
     * {@link #clearMessages}, under the name a StageWright built before payloads were kept calls.
     *
     * @deprecated use {@link #clearMessages}, which says what it clears
     */
    @Deprecated
    public void clear() {
        clearMessages();
    }

    /** The payloads of {@code type} kept, a subclass's too, in the order they were sent. */
    public synchronized <T extends CustomPacketPayload> List<T> payloads(Class<T> type) {
        return payloads.values().stream()
                .flatMap(Deque::stream)
                .filter(sent -> type.isInstance(sent.payload()))
                .sorted(Comparator.comparingLong(Sent::seq))
                .map(sent -> type.cast(sent.payload()))
                .toList();
    }

    /** Forget every payload kept so far. */
    public synchronized void clearPayloads() {
        payloads.clear();
    }
}
