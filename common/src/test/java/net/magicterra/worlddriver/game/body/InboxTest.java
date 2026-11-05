package net.magicterra.worlddriver.game.body;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.junit.jupiter.api.Test;

class InboxTest {

    @Test
    void keepsSystemLinesAndOverlaysApart() {
        Inbox inbox = new Inbox();
        inbox.receive(new ClientboundSystemChatPacket(Component.literal("a"), false));
        inbox.receive(new ClientboundSystemChatPacket(Component.translatable("x.y"), true));
        inbox.receive(new ClientboundSetActionBarTextPacket(Component.literal("b")));
        assertEquals(
                List.of(
                        new Inbox.Message(Component.literal("a"), Inbox.Kind.SYSTEM),
                        new Inbox.Message(Component.translatable("x.y"), Inbox.Kind.ACTION_BAR),
                        new Inbox.Message(Component.literal("b"), Inbox.Kind.ACTION_BAR)),
                inbox.messages());
    }

    @Test
    void readsIntoBundlesAndIgnoresWhatIsNoLine() {
        Inbox inbox = new Inbox();
        inbox.receive(new ClientboundSetTimePacket(1, 1, true));
        inbox.receive(
                new ClientboundBundlePacket(List.of(new ClientboundSystemChatPacket(Component.literal("in"), false))));
        assertEquals(List.of(new Inbox.Message(Component.literal("in"), Inbox.Kind.SYSTEM)), inbox.messages());
        inbox.clear();
        assertTrue(inbox.messages().isEmpty());
    }

    @Test
    void keepsOnlyTheLatest() {
        Inbox inbox = new Inbox();
        for (int i = 0; i <= Inbox.CAPACITY; i++) {
            inbox.receive(new ClientboundSystemChatPacket(Component.literal(Integer.toString(i)), false));
        }
        List<Inbox.Message> kept = inbox.messages();
        assertEquals(Inbox.CAPACITY, kept.size());
        assertEquals(Component.literal("1"), kept.getFirst().text());
    }
}
