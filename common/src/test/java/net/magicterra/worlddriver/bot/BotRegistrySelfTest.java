package net.magicterra.worlddriver.bot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A client's own player answers to its {@code player:<name>} as well as to {@code self}, as a harness names it. */
class BotRegistrySelfTest {
    @AfterEach
    void noClient() {
        BotRegistry.ownName(() -> null);
    }

    @Test
    void selfAndAbsentAreSelf() {
        assertTrue(BotRegistry.isSelf(null));
        assertTrue(BotRegistry.isSelf(""));
        assertTrue(BotRegistry.isSelf("self"));
    }

    @Test
    void theOwnPlayersRefIsSelfOnAClient() {
        BotRegistry.ownName(() -> "Dev");
        assertTrue(BotRegistry.isSelf("player:Dev"));
        assertFalse(BotRegistry.isSelf("player:Other"));
        assertFalse(BotRegistry.isSelf("mob:Dev"));
    }

    @Test
    void onADedicatedServerOnlySelfIsSelf() {
        assertFalse(BotRegistry.isSelf("player:Dev"));
    }
}
