package net.magicterra.worlddriver.bot.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import net.magicterra.stagewright.test.Bot;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.testcontent.TestMod;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * What the server says to a bot is kept for the test to read: system lines, the action bar, chat, and the payloads mods
 * send it. Other tests in the scene run beside these and broadcast too, the two here among them, so each test picks its
 * own lines out by a mark of its own. In {@code wd.scenes}.
 */
@Scene("wd.scenes")
class JupiterBotMessagesTest {

    private static List<Bot.Message> marked(Bot bot, String mark) {
        return bot.messages().stream()
                .filter(m -> m.plain().contains(mark)
                        || m.key().filter(k -> k.contains(mark)).isPresent())
                .toList();
    }

    @Test
    void aBotKeepsTheLinesItIsSentUntilCleared(@Bot.Parked Bot bot) {
        String mark = "wdMessagesSent";
        bot.runOnServer(p -> {
            p.displayClientMessage(Component.translatable("x." + mark), true);
            p.sendSystemMessage(Component.literal("system " + mark));
        });
        List<Bot.Message> lines = marked(bot, mark);
        assertEquals(2, lines.size(), () -> "both lines, in order: " + bot.messages());
        assertEquals(Bot.Message.Kind.ACTION_BAR, lines.get(0).kind());
        assertEquals(Optional.of("x." + mark), lines.get(0).key(), "the translation key, not its text");
        assertEquals(Bot.Message.Kind.SYSTEM, lines.get(1).kind());
        assertEquals("system " + mark, lines.get(1).plain());

        bot.clearMessages();
        assertTrue(marked(bot, mark).isEmpty(), () -> "cleared: " + bot.messages());
    }

    /** A mod's own payload, on a channel the bot never negotiated; only this test sends it. */
    private record MarkPayload(String mark) implements CustomPacketPayload {
        static final Type<MarkPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(TestMod.MOD_ID, "bot_payload_mark"));

        @Override
        public Type<MarkPayload> type() {
            return TYPE;
        }
    }

    @Test
    void aBotKeepsThePayloadsModsSendItUntilCleared(@Bot.Parked Bot bot) {
        MarkPayload first = new MarkPayload("first");
        MarkPayload second = new MarkPayload("second");
        bot.runOnServer(p -> {
            // The path a mod's PacketDistributor.sendToPlayer takes, and a bundle, which a mod's batch may come in.
            p.connection.send(new ClientboundCustomPayloadPacket(first));
            p.connection.send(new ClientboundBundlePacket(List.of(new ClientboundCustomPayloadPacket(second))));
        });
        assertEquals(List.of(first, second), bot.payloads(MarkPayload.class), "the objects sent, in order");

        bot.clearPayloads();
        assertTrue(bot.payloads(MarkPayload.class).isEmpty(), "cleared");
    }

    @Test
    void aBotHearsChatAndKeepsItsLinesThroughARespawn(Game game, @Bot.Parked Bot bot) {
        String mark = "wdMessagesSaid";
        game.runOnServer(s -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), "say " + mark));
        assertTrue(
                marked(bot, mark).stream()
                        .anyMatch(m ->
                                m.kind() == Bot.Message.Kind.CHAT && m.plain().equals("[Server] " + mark)),
                () -> "/say as chat, decorated: " + bot.messages());

        bot.runOnServer(p -> p.kill());
        bot.respawn();
        assertFalse(
                marked(bot, mark).isEmpty(),
                () -> "the player a respawn makes has the lines before it: " + bot.messages());
    }
}
