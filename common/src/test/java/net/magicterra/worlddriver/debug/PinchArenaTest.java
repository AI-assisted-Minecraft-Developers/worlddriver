package net.magicterra.worlddriver.debug;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The planner's boxed local minimum ({@link PinchArena}): a bot at the foot of an eight-block cliff with the goal on
 * top. At least one of the search budgets has to scale the wall by pillaring up and walk over.
 */
class PinchArenaTest {

    @Test
    void verticalEscapeScalesTheCliffAtSomeBudget() {
        StringBuilder log = new StringBuilder();
        boolean anyReached = false;
        for (int budget : new int[] {150, 300, 600, 2000, 60000}) {
            PinchArena.Result r = PinchArena.run(budget);
            log.append("maxNodes=").append(budget).append(": ").append(r).append('\n');
            anyReached |= r.reached;
        }
        assertTrue(anyReached, "vertical escape failed to scale the cliff at every budget:\n" + log);
    }
}
