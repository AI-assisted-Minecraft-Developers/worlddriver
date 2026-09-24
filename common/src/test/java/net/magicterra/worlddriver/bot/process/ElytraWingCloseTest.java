package net.magicterra.worlddriver.bot.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Landing closes the wing as surely as a crash does, so the verdict has to come from where it closed. */
class ElytraWingCloseTest {

    @Test
    void aTouchDownAfterTheFlareIsAnArrival() {
        assertNull(ElytraProcess.closedShort(true, 18.0, 3.0));
    }

    @Test
    void aCloseWithinTheStopDistanceIsAnArrival() {
        assertNull(ElytraProcess.closedShort(false, 2.5, 3.0));
    }

    @Test
    void aCloseShortOfTheTargetIsAFailure() {
        assertEquals("wing closed 140.0 blocks short of target", ElytraProcess.closedShort(false, 140.0, 3.0));
    }
}
