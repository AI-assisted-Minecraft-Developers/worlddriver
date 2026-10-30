package net.magicterra.worlddriver.game.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The idle client tick must not clear the human's keys unless the bot itself pressed them ({@link InputReleaseGate}).
 */
class InputReleaseGateTest {

    @Test
    void releasesOncePerDriveBurstAndNeverWhileIdle() {
        InputReleaseGate g = new InputReleaseGate();

        for (int t = 0; t < 100; t++)
            assertFalse(g.consumeRelease(), "released with no bot input at idle tick " + t + " (clobbers manual keys)");

        g.markDirtied();
        assertTrue(g.consumeRelease(), "no release after the bot dirtied the keybinds");
        assertFalse(g.consumeRelease(), "released twice for a single drive burst");

        for (int t = 0; t < 20; t++) g.markDirtied();
        assertTrue(g.consumeRelease(), "no release after a sustained drive burst");
        for (int t = 0; t < 50; t++)
            assertFalse(g.consumeRelease(), "released again while idle after the burst at tick " + t);

        g.markDirtied();
        assertTrue(g.consumeRelease(), "did not re-arm for a second drive burst");
    }
}
