package net.magicterra.worlddriver.bot.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import net.magicterra.stagewright.test.Bot;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Scene;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

/**
 * What the server says to a bot is kept for the test to read: system lines, the action bar, chat. Other tests in the
 * scene run beside this one and broadcast too, so each check picks this test's own lines out by their text. In
 * {@code wd.scenes}.
 */
@Scene("wd.scenes")
class JupiterBotMessagesTest {

    private static final String MARK = "wdMessagesMark";

    private static List<Bot.Message> ours(Bot bot) {
        return bot.messages().stream()
                .filter(m -> m.plain().contains(MARK)
                        || m.key().filter(k -> k.contains(MARK)).isPresent())
                .toList();
    }

    @Test
    void aBotKeepsTheLinesItIsSentUntilCleared(@Bot.Parked Bot bot) {
        bot.runOnServer(p -> {
            p.displayClientMessage(Component.translatable("x." + MARK), true);
            p.sendSystemMessage(Component.literal("system " + MARK));
        });
        List<Bot.Message> lines = ours(bot);
        assertEquals(2, lines.size(), () -> "both lines, in order: " + bot.messages());
        assertEquals(Bot.Message.Kind.ACTION_BAR, lines.get(0).kind());
        assertEquals(Optional.of("x." + MARK), lines.get(0).key(), "the translation key, not its text");
        assertEquals(Bot.Message.Kind.SYSTEM, lines.get(1).kind());
        assertEquals("system " + MARK, lines.get(1).plain());

        bot.clearMessages();
        assertTrue(ours(bot).isEmpty(), () -> "cleared: " + bot.messages());
    }

    @Test
    void aBotHearsChatAndKeepsItsLinesThroughARespawn(Game game, @Bot.Parked Bot bot) {
        game.runOnServer(s -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), "say " + MARK));
        assertTrue(
                ours(bot).stream()
                        .anyMatch(m ->
                                m.kind() == Bot.Message.Kind.CHAT && m.plain().equals("[Server] " + MARK)),
                () -> "/say as chat, decorated: " + bot.messages());

        bot.runOnServer(p -> p.kill());
        bot.respawn();
        assertFalse(ours(bot).isEmpty(), () -> "the player a respawn makes has the lines before it: " + bot.messages());
    }
}
