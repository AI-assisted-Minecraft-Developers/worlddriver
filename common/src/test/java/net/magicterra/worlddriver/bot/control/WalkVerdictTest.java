package net.magicterra.worlddriver.bot.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** The walker's give-ups end through the same branch as an arrival, so a walk's verdict has to come
 *  from the goal test. */
class WalkVerdictTest {

    @Test
    void anArrivalAtTheGoalIsReached() {
        WalkResult r = WalkVerdict.of(false, null, true, "arrived", 0.0, false, false);
        assertEquals(Locomotor.Status.REACHED, r.status());
        assertNull(r.reason());
    }

    @Test
    void aGiveUpIsMeasuredInBlocks() {
        WalkResult r = WalkVerdict.of(false, null, false, "churn-giveup", 65.0, false, false);
        assertEquals(Locomotor.Status.GAVE_UP, r.status());
        assertEquals("goal not reached (churn-giveup, about 6.5 blocks short)", r.reason());
    }

    @Test
    void anArrivalAtTheSnappedCellIsReached() {
        assertEquals(Locomotor.Status.REACHED,
                WalkVerdict.of(false, null, false, WalkVerdict.GOAL_SNAPPED, 0.0, true, false).status());
    }

    @Test
    void anOpenGoalIsReachedWhenTheWalkStops() {
        assertEquals(Locomotor.Status.REACHED,
                WalkVerdict.of(false, null, false, "best-effort-consumed", -350.0, false, true).status());
    }

    @Test
    void aGiveUpWithNoFootOmitsTheDistance() {
        assertEquals("goal not reached (path-consumed)",
                WalkVerdict.of(false, null, false, "path-consumed", -1, false, false).reason());
    }

    @Test
    void aFailedWalkReportsTheWalkersReason() {
        WalkResult r = WalkVerdict.of(true, "no route progress", false, "failed:no route progress", -1, false, false);
        assertEquals(Locomotor.Status.FAILED, r.status());
        assertEquals("no route progress", r.reason());
    }

    @Test
    void aFailedWalkWithNoReasonStillFails() {
        WalkResult r = WalkVerdict.of(true, null, false, null, -1, false, true);
        assertEquals(Locomotor.Status.FAILED, r.status());
        assertEquals("walk failed", r.reason());
    }
}
