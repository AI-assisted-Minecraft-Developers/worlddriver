package net.magicterra.worlddriver.bot.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** A crop harvested but not replanted is already in the bag, so it must not read as left in the field. */
class FarmVerdictTest {

    @Test
    void aCleanSweepIsNotAFailure() {
        assertNull(FarmProcess.incomplete(0, 0));
    }

    @Test
    void cropsLeftInTheFieldAreNotHarvested() {
        assertEquals("incomplete: 3 crops not harvested", FarmProcess.incomplete(3, 0));
    }

    @Test
    void failedReplantsAreReportedAsSuch() {
        assertEquals("incomplete: 12 harvested crops not replanted", FarmProcess.incomplete(0, 12));
    }

    @Test
    void bothShortfallsAreNamed() {
        assertEquals("incomplete: 2 crops not harvested, 5 not replanted", FarmProcess.incomplete(2, 5));
    }
}
