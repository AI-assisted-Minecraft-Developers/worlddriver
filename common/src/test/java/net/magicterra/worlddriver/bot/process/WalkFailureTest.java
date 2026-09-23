package net.magicterra.worlddriver.bot.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.magicterra.worlddriver.bot.movement.Walker;
import org.junit.jupiter.api.Test;

/** The walker ends its give-ups with ARRIVED, so a walk's verdict has to come from the goal test. */
class WalkFailureTest {

    @Test
    void anArrivalAtTheGoalIsNotAFailure() {
        assertNull(IntentProcess.walkFailure(Walker.Step.ARRIVED, null, true, "arrived", 0.0));
    }

    @Test
    void aGiveUpThatEndsInArrivedIsAFailure() {
        assertEquals("goal not reached (churn-giveup, 6.5 blocks short)",
                IntentProcess.walkFailure(Walker.Step.ARRIVED, null, false, "churn-giveup", 6.5));
    }

    @Test
    void aFailedWalkReportsTheWalkersReason() {
        assertEquals("no route progress",
                IntentProcess.walkFailure(Walker.Step.FAILED, "no route progress", false, null, -1));
    }

    @Test
    void aFailedWalkWithNoReasonStillFails() {
        assertEquals("walk failed", IntentProcess.walkFailure(Walker.Step.FAILED, null, false, null, -1));
    }
}
