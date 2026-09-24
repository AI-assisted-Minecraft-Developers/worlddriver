package net.magicterra.worlddriver.bot.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** The walker ends its give-ups with ARRIVED, so a walk's verdict has to come from the goal test. */
class WalkVerdictTest {

    @Test
    void anArrivalAtTheGoalIsNotAFailure() {
        assertNull(WalkVerdict.shortfall(Walker.Step.ARRIVED, null, true, "arrived", 0.0, false));
    }

    @Test
    void aGiveUpThatEndsInArrivedIsAFailureMeasuredInBlocks() {
        assertEquals("goal not reached (churn-giveup, about 6.5 blocks short)",
                WalkVerdict.shortfall(Walker.Step.ARRIVED, null, false, "churn-giveup", 65.0, false));
    }

    @Test
    void anArrivalAtTheSnappedCellIsNotAFailure() {
        assertNull(WalkVerdict.shortfall(Walker.Step.ARRIVED, null, false, WalkVerdict.GOAL_SNAPPED, 0.0, false));
    }

    @Test
    void anOpenGoalIsDoneWhenTheWalkStops() {
        assertNull(WalkVerdict.shortfall(Walker.Step.ARRIVED, null, false, "best-effort-consumed", -350.0, true));
    }

    @Test
    void aGiveUpWithNoFootOmitsTheDistance() {
        assertEquals("goal not reached (path-consumed)",
                WalkVerdict.shortfall(Walker.Step.ARRIVED, null, false, "path-consumed", -1, false));
    }

    @Test
    void aFailedWalkReportsTheWalkersReason() {
        assertEquals("no route progress",
                WalkVerdict.shortfall(Walker.Step.FAILED, "no route progress", false, null, -1, false));
    }

    @Test
    void aFailedWalkWithNoReasonStillFails() {
        assertEquals("walk failed", WalkVerdict.shortfall(Walker.Step.FAILED, null, false, null, -1, true));
    }
}
