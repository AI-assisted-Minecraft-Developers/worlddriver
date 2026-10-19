package net.magicterra.worlddriver.bot.arbitration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The bunker episode anchor ({@link BunkerAnchor}): one episode digs exactly its depth from the siege start,
 * preemption at the sealed spot never re-opens it, knockback inside the tolerance is not displacement, a real
 * relocation resets the episode, and resuming clears the dig watchdog.
 */
class BunkerAnchorTest {

    @Test
    void anEpisodeDigsItsDepthOnceAndPreemptionNeverReopensIt() {
        final int depthWanted = 2;
        final int startY = 64, x = 0, z = 0;
        BunkerAnchor a = new BunkerAnchor();
        int y = startY;

        // One episode: dig straight down depthWanted, then seal (mirrors tick()'s anchor flow).
        for (int t = 0; t < 100 && !a.sealed; t++) {
            if (a.displacedFrom(x, y, z)) a.reset();
            a.beginIfIdle(x, y, z);
            if (a.sealed) break;
            int depth = a.depth(y);
            a.noteDepth(depth);
            if (depth < depthWanted) y -= 1;      // block below broke → bot drops a level
            else a.sealed = true;                 // deep enough → roof sealed
        }
        assertEquals(startY, a.startY, "episode must anchor at the siege start Y");
        assertEquals(depthWanted, startY - y, "one episode must dig exactly its depth");
        final int sealedY = y;

        // 50 preemptions at the sealed spot. Each cycle is the real contract: onInterrupt→onPreempt() (no
        // reset) + onResume→resume() + a tick at the SAME position. The anchor must not move and the pocket
        // must not re-dig.
        for (int cycle = 0; cycle < 50; cycle++) {
            a.onPreempt();
            a.resume();
            if (a.displacedFrom(x, y, z)) a.reset();
            a.beginIfIdle(x, y, z);
            assertTrue(a.sealed && a.startY == startY && y == sealedY,
                    "ratchet re-opened at preempt cycle " + cycle + ": sealed=" + a.sealed + " startY=" + a.startY + " y=" + y);
        }

        assertFalse(a.displacedFrom(x + BunkerAnchor.DRIFT_TOL, sealedY, z)
                        || a.displacedFrom(x, sealedY, z - BunkerAnchor.DRIFT_TOL),
                "in-tolerance knockback wrongly flagged as displacement (would ratchet)");
        assertTrue(a.displacedFrom(x + BunkerAnchor.DRIFT_TOL + 1, sealedY, z),
                "far horizontal displacement (respawn) must reset the episode");
        assertTrue(a.displacedFrom(x, startY + 2, z), "rising above the start Y (teleport/knock-up) must reset the episode");

        a.digTicks = 999;
        a.resume();
        assertEquals(0, a.digTicks, "onResume must clear the dig watchdog");
    }
}
