package net.magicterra.worlddriver.debug;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The receding-horizon and soft-commit early stops ({@link HorizonArena}) on a long flat corridor: each
 * makes the first search cheaper and the first segment shorter, and the chain of segments still reaches
 * the corridor's end.
 */
class HorizonArenaTest {
    private static final int MIN_REACH = HorizonArena.CORRIDOR_LEN - 20;

    @Test
    void horizonCommitsShortSegmentsThatStillCoverTheCorridor() {
        HorizonArena.Result off = HorizonArena.run(0);
        HorizonArena.Result on = HorizonArena.run(48);
        String both = "off=" + off + " on=" + on;
        assertTrue(on.firstExpanded < off.firstExpanded, "horizon=48 should expand fewer nodes than horizon=0; " + both);
        assertTrue(on.firstEndX >= 40 && on.firstEndX <= 90,
                "horizon=48 first segment should end ~48 blocks out, got x=" + on.firstEndX);
        assertTrue(off.firstEndX > on.firstEndX, "horizon=0 should commit a longer segment than horizon=48; " + both);
        assertTrue(on.chainEndX >= MIN_REACH,
                "horizon=48 chain should reach the corridor end (~" + HorizonArena.CORRIDOR_LEN + "), got x=" + on.chainEndX);
    }

    @Test
    void softCommitStopsNearItsBudgetAndStillCoversTheCorridor() {
        HorizonArena.Result off = HorizonArena.run(0);
        HorizonArena.Result soft = HorizonArena.run(0, 150);
        assertTrue(soft.firstExpanded < off.firstExpanded,
                "softCommit=150 should expand fewer nodes than the full grind; off=" + off + " soft=" + soft);
        assertTrue(soft.firstExpanded <= 400,
                "softCommit=150 first search should stop near the soft budget, expanded=" + soft.firstExpanded);
        assertTrue(soft.chainEndX >= MIN_REACH, "softCommit chain should still reach the corridor end, got x=" + soft.chainEndX);
    }
}
