package net.magicterra.worlddriver.bot.process;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A quarry pacing in a pen the bot cannot enter keeps the walker circling the pen: every lap moves the
 * bot, so the walker's futile-search count never climbs, and the follow ran for as long as the caller
 * left it. The follow now judges the chase itself: no standoff reached and no block gained on the
 * closest approach for the give-up window ends it.
 */
class FollowGiveUpTest {

    private static final int WINDOW = 600;

    @Test
    void circlingAPenAtTheSameDistanceGivesUpAfterTheWindow() {
        FollowProcess.Chase chase = new FollowProcess.Chase(WINDOW);
        boolean gaveUp = false;
        int t = 0;
        while (!gaveUp && t < 5 * WINDOW) {
            // the lap swings the distance between 2 and 6 blocks and never closes the standoff
            gaveUp = chase.tick(false, 2 + 4 * Math.abs(Math.sin(t / 40.0)));
            t++;
        }
        assertTrue(gaveUp, "a chase that never gains must end");
        assertTrue(t <= WINDOW + 60, "within the window of its last gain, took " + t + " ticks");
    }

    @Test
    void reachingTheStandoffKeepsTheChaseAlive() {
        FollowProcess.Chase chase = new FollowProcess.Chase(WINDOW);
        for (int t = 0; t < 10 * WINDOW; t++) {
            boolean arrived = t % 200 == 0;   // the quarry wanders off and the bot catches up again
            assertFalse(chase.tick(arrived, arrived ? 2 : 8), "gave up at tick " + t);
        }
    }

    @Test
    void closingInOnAFarTargetKeepsTheChaseAlive() {
        FollowProcess.Chase chase = new FollowProcess.Chase(WINDOW);
        for (int t = 0; t < 10 * WINDOW; t++) {
            assertFalse(chase.tick(false, 200 - t * 0.03), "gave up at tick " + t);
        }
    }

    @Test
    void aZeroWindowNeverGivesUp() {
        FollowProcess.Chase chase = new FollowProcess.Chase(0);
        for (int t = 0; t < 10 * WINDOW; t++) assertFalse(chase.tick(false, 5));
    }

    @Test
    void aNewQuarryStartsAFreshWindow() {
        FollowProcess.Chase chase = new FollowProcess.Chase(WINDOW);
        for (int t = 0; t < WINDOW - 1; t++) chase.tick(false, 5);
        chase.restart();
        for (int t = 0; t < WINDOW - 1; t++) assertFalse(chase.tick(false, 5), "gave up at tick " + t);
    }
}
