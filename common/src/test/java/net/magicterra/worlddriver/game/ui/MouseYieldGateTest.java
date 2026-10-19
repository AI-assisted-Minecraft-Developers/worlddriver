package net.magicterra.worlddriver.game.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The cursor side of human/bot coexistence ({@link MouseYieldGate}): release the cursor while the bot drives,
 * re-release it after a click grabs it back, let a double-tap of ESC take it for the rest of the burst, and
 * hand it back when the burst ends.
 */
class MouseYieldGateTest {
    private static final MouseYieldGate.Action NONE = MouseYieldGate.Action.NONE;
    private static final MouseYieldGate.Action RELEASE = MouseYieldGate.Action.RELEASE;
    private static final MouseYieldGate.Action GRAB = MouseYieldGate.Action.GRAB;

    @Test
    void yieldsWhileDrivingAndHandsTheCursorBack() {
        final int linger = 3;
        MouseYieldGate g = new MouseYieldGate(linger);

        // Idle: never touches a cursor nobody asked us to touch.
        for (int t = 0; t < 50; t++)
            assertEquals(NONE, g.tick(true, false, false, true), "acted on an idle tick " + t);

        // Drive → release once, then stay released without re-issuing.
        g.markDriving();
        assertEquals(RELEASE, g.tick(true, false, false, true), "no release on the first driving tick");
        assertTrue(g.yielded(), "yielded() false right after releasing");
        g.markDriving();
        assertEquals(NONE, g.tick(true, false, false, false), "acted again while the cursor was already free");

        // Sticky: a click re-grabbed the cursor (vanilla MouseHandler.onPress) → release again.
        g.markDriving();
        assertEquals(RELEASE, g.tick(true, false, false, true), "did not re-release after a click re-grabbed the cursor");

        // A screen owns the cursor: hands off entirely.
        g.markDriving();
        assertEquals(NONE, g.tick(true, false, true, false), "touched the cursor while a screen was open");

        // Burst ends → cursor handed back exactly once.
        for (int t = 0; t <= linger; t++) g.tick(true, false, false, false);
        assertFalse(g.yielded(), "still yielded after the burst ended");
        for (int t = 0; t < 20; t++)
            assertEquals(NONE, g.tick(true, false, false, true), "kept acting after handing the cursor back at tick " + t);

        // Double-tap ESC: the human owns the cursor for the REST of this burst.
        g.markDriving();
        assertEquals(RELEASE, g.tick(true, false, false, true), "did not re-arm for a second drive burst");
        g.markDriving();
        assertEquals(GRAB, g.tick(true, true, false, false), "ESC double-tap did not grab the cursor back");
        assertTrue(g.reclaimed(), "reclaimed() false after the ESC double-tap");
        for (int t = 0; t < 20; t++) {
            g.markDriving();
            assertEquals(NONE, g.tick(true, false, false, true), "stole the cursor back at tick " + t + " after the human reclaimed it");
        }

        // Next burst re-arms the yield.
        for (int t = 0; t <= linger; t++) g.tick(true, false, false, true);
        assertFalse(g.reclaimed(), "reclaim latch survived the end of the burst");
        g.markDriving();
        assertEquals(RELEASE, g.tick(true, false, false, true), "did not yield again on the burst after a reclaim");

        // Setting off mid-yield → cursor returned, and never taken again.
        assertEquals(GRAB, g.tick(false, false, false, false), "disabling mouseYield did not hand the cursor back");
        for (int t = 0; t < 20; t++) {
            g.markDriving();
            assertEquals(NONE, g.tick(false, false, false, true), "acted with mouseYield off at tick " + t);
        }
    }
}
