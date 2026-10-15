package net.magicterra.worlddriver.bot.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** {@link Readiness#judge}: every refusal, its order, and the shape it is reported in. */
class ReadinessTest {

    private static Readiness.Facts ready() {
        return new Readiness.Facts(true, true, null, false, false, false, false, true);
    }

    @Test
    void aBodyThatCanActIsNotRefused() {
        assertNull(Readiness.judge(ready()));
        // A screen that neither pauses nor kills nor loads (chat, inventory) does not block the bot.
        assertNull(Readiness.judge(new Readiness.Facts(true, true, "ChatScreen", false, false, false, false, true)));
    }

    @Test
    void noWorldOrPlayerNamesTheScreenItIsOn() {
        Readiness.Refusal title = Readiness.judge(new Readiness.Facts(false, false, "TitleScreen", false, false, false, false, false));
        assertEquals(Readiness.Reason.NO_PLAYER, title.reason());
        assertTrue(title.error().contains("TitleScreen"), title.error());
        Readiness.Refusal none = Readiness.judge(new Readiness.Facts(false, false, null, false, false, false, false, false));
        assertEquals(Readiness.Reason.NO_PLAYER, none.reason());
        assertTrue(none.error().contains("no world is open"), none.error());
    }

    @Test
    void eachObstacleHasItsOwnWordAndTheOrderIsWhatAPersonWouldFixFirst() {
        assertEquals(Readiness.Reason.LOADING,
                Readiness.judge(new Readiness.Facts(true, true, "ReceivingLevelScreen", true, false, true, true, false)).reason());
        assertEquals(Readiness.Reason.DEAD,
                Readiness.judge(new Readiness.Facts(true, true, "DeathScreen", false, true, true, true, false)).reason());
        assertEquals(Readiness.Reason.PAUSED,
                Readiness.judge(new Readiness.Facts(true, true, "PauseScreen", false, true, false, true, false)).reason());
        assertEquals(Readiness.Reason.SLEEPING,
                Readiness.judge(new Readiness.Facts(true, true, "InBedChatScreen", false, false, false, true, false)).reason());
        assertEquals(Readiness.Reason.CHUNK_UNLOADED,
                Readiness.judge(new Readiness.Facts(true, true, null, false, false, false, false, false)).reason());
    }

    @Test
    void theDeathScreenIsNamedOnlyWhenItIsUp() {
        String onScreen = Readiness.judge(new Readiness.Facts(true, true, "DeathScreen", false, false, true, false, true)).error();
        String noScreen = Readiness.judge(new Readiness.Facts(true, true, null, false, false, true, false, true)).error();
        assertTrue(onScreen.contains("death screen"), onScreen);
        assertTrue(!noScreen.contains("death screen") && noScreen.contains("dead"), noScreen);
    }

    @Test
    void theResultIsTheStandardFailureShape() {
        Map<String, Object> r = new Readiness.Refusal(Readiness.Reason.DEAD, "the player is dead").result();
        assertEquals(Boolean.FALSE, r.get("ok"));
        assertEquals("the player is dead", r.get("error"));
        assertEquals("dead", r.get("reason"));
        assertEquals(3, r.size());
    }
}
