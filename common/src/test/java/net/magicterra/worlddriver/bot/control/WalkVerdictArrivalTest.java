package net.magicterra.worlddriver.bot.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The arrival exits must be told apart (gap#68-R2a). Call sites pass specific labels such as goal-snapped
 * or frontier-giveup themselves; {@link WalkVerdict#classifyArrival} covers the generic exit states.
 */
class WalkVerdictArrivalTest {

    @Test
    void eachArrivalExitHasItsOwnLabel() {
        assertEquals("arrived", WalkVerdict.classifyArrival(false, true, false), "full path + reached = arrived");
        assertEquals("arrived", WalkVerdict.classifyArrival(true, true, false), "best-effort + reached = arrived");
        assertEquals("path-consumed", WalkVerdict.classifyArrival(false, false, false), "full path + NOT reached = path-consumed");
        assertEquals("best-effort-consumed", WalkVerdict.classifyArrival(true, false, false),
                "best-effort + NOT reached = best-effort-consumed");
        assertEquals("goal-snapped", WalkVerdict.classifyArrival(false, true, true), "snapped goal reached = goal-snapped");
    }
}
