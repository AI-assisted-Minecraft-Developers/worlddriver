package net.magicterra.worlddriver.game.body;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.magicterra.worlddriver.VanillaBoot;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class InboxTest {

    @BeforeAll
    static void boot() {
        // A custom payload packet reaches the registries as it is made.
        VanillaBoot.boot();
    }

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
        inbox.clearMessages();
        assertTrue(inbox.messages().isEmpty());
    }

    @Test
    void keepsNoPayloadUntilAskedTo() {
        Inbox.keepPayloads(false);
        Inbox inbox = new Inbox();
        inbox.receive(new ClientboundCustomPayloadPacket(new BrandPayload("unread")));
        assertTrue(inbox.payloads(CustomPacketPayload.class).isEmpty());
    }

    @Test
    void keepsAModsPayloadsApartFromItsLines() {
        Inbox.keepPayloads(true);
        try {
            Inbox inbox = new Inbox();
            BrandPayload first = new BrandPayload("first");
            BrandPayload second = new BrandPayload("second");
            inbox.receive(new ClientboundCustomPayloadPacket(first));
            inbox.receive(new ClientboundBundlePacket(List.of(new ClientboundCustomPayloadPacket(second))));
            assertEquals(
                    List.of(first, second),
                    inbox.payloads(BrandPayload.class),
                    "the objects sent, in order, bundles read into");
            assertTrue(inbox.messages().isEmpty(), "a payload is not a line");
            inbox.clearPayloads();
            assertTrue(inbox.payloads(BrandPayload.class).isEmpty());
        } finally {
            Inbox.keepPayloads(false);
        }
    }

    @Test
    void aPayloadSentOftenPushesOutOnlyItsOwnKind() {
        Inbox.keepPayloads(true);
        try {
            Inbox inbox = new Inbox();
            BrandPayload waited = new BrandPayload("waited for");
            inbox.receive(new ClientboundCustomPayloadPacket(waited));
            Often last = null;
            for (int i = 0; i <= Inbox.CAPACITY; i++) {
                last = new Often(i);
                inbox.receive(new ClientboundCustomPayloadPacket(last));
            }
            assertEquals(List.of(waited), inbox.payloads(BrandPayload.class));
            List<Often> often = inbox.payloads(Often.class);
            assertEquals(Inbox.CAPACITY, often.size());
            assertEquals(new Often(1), often.getFirst());
            assertEquals(waited, inbox.payloads(CustomPacketPayload.class).getFirst(), "all kinds, in the order sent");
            assertEquals(last, inbox.payloads(CustomPacketPayload.class).getLast());
        } finally {
            Inbox.keepPayloads(false);
        }
    }

    /** A mod's payload sent every tick. */
    private record Often(int n) implements CustomPacketPayload {
        static final Type<Often> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("test", "often"));

        @Override
        public Type<Often> type() {
            return TYPE;
        }
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
