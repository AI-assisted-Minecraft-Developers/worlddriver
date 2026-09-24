package net.magicterra.worlddriver.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** {@link BodyReady#judge}: every refusal, its order, and the shape it is reported in. */
class BodyReadyTest {

    private static BodyReady.Facts ready() {
        return new BodyReady.Facts(true, true, null, false, false, false, false, true);
    }

    @Test
    void aBodyThatCanActIsNotRefused() {
        assertNull(BodyReady.judge(ready()));
        // A screen that neither pauses nor kills nor loads (chat, inventory) does not block the bot.
        assertNull(BodyReady.judge(new BodyReady.Facts(true, true, "ChatScreen", false, false, false, false, true)));
    }

    @Test
    void noWorldOrPlayerNamesTheScreenItIsOn() {
        BodyReady.Refusal title = BodyReady.judge(new BodyReady.Facts(false, false, "TitleScreen", false, false, false, false, false));
        assertEquals(BodyReady.Reason.NO_PLAYER, title.reason());
        assertTrue(title.error().contains("TitleScreen"), title.error());
        BodyReady.Refusal none = BodyReady.judge(new BodyReady.Facts(false, false, null, false, false, false, false, false));
        assertEquals(BodyReady.Reason.NO_PLAYER, none.reason());
        assertTrue(none.error().contains("no world is open"), none.error());
    }

    @Test
    void eachObstacleHasItsOwnWordAndTheOrderIsWhatAPersonWouldFixFirst() {
        assertEquals(BodyReady.Reason.LOADING,
                BodyReady.judge(new BodyReady.Facts(true, true, "ReceivingLevelScreen", true, false, true, true, false)).reason());
        assertEquals(BodyReady.Reason.DEAD,
                BodyReady.judge(new BodyReady.Facts(true, true, "DeathScreen", false, true, true, true, false)).reason());
        assertEquals(BodyReady.Reason.PAUSED,
                BodyReady.judge(new BodyReady.Facts(true, true, "PauseScreen", false, true, false, true, false)).reason());
        assertEquals(BodyReady.Reason.SLEEPING,
                BodyReady.judge(new BodyReady.Facts(true, true, "InBedChatScreen", false, false, false, true, false)).reason());
        assertEquals(BodyReady.Reason.CHUNK_UNLOADED,
                BodyReady.judge(new BodyReady.Facts(true, true, null, false, false, false, false, false)).reason());
    }

    @Test
    void theDeathScreenIsNamedOnlyWhenItIsUp() {
        String onScreen = BodyReady.judge(new BodyReady.Facts(true, true, "DeathScreen", false, false, true, false, true)).error();
        String noScreen = BodyReady.judge(new BodyReady.Facts(true, true, null, false, false, true, false, true)).error();
        assertTrue(onScreen.contains("death screen"), onScreen);
        assertTrue(!noScreen.contains("death screen") && noScreen.contains("dead"), noScreen);
    }

    @Test
    void theResultIsTheStandardFailureShape() {
        Map<String, Object> r = new BodyReady.Refusal(BodyReady.Reason.DEAD, "the player is dead").result();
        assertEquals(Boolean.FALSE, r.get("ok"));
        assertEquals("the player is dead", r.get("error"));
        assertEquals("dead", r.get("reason"));
        assertEquals(3, r.size());
    }
}
