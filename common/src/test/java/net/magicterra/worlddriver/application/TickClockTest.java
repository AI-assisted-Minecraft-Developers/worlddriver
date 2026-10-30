package net.magicterra.worlddriver.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.magicterra.worlddriver.api.methods.system.SystemContracts.TickTimes;
import org.junit.jupiter.api.Test;

class TickClockTest {
    private static final long MS = 1_000_000L;

    private static TickClock fed(long... millis) {
        TickClock c = new TickClock();
        for (long m : millis) {
            c.record(m * MS);
        }
        return c;
    }

    @Test
    void anOddRunHasTheMiddleTickForItsMedian() {
        TickTimes t = fed(1, 9, 2).since(0).orElseThrow();
        assertEquals(3, t.ticks());
        assertEquals(4.0, t.meanMs(), 1e-9);
        assertEquals(2.0, t.medianMs(), 1e-9);
        assertEquals(9.0, t.maxMs(), 1e-9);
    }

    @Test
    void anEvenRunAveragesItsTwoMiddleTicks() {
        TickTimes t = fed(1, 2, 3, 10).since(0).orElseThrow();
        assertEquals(2.5, t.medianMs(), 1e-9);
    }

    @Test
    void onlyTicksAfterTheMarkCount() {
        TickClock c = fed(50, 50);
        long mark = c.tickCount();
        c.record(2 * MS);
        c.record(4 * MS);
        TickTimes t = c.since(mark).orElseThrow();
        assertEquals(2, t.ticks());
        assertEquals(3.0, t.meanMs(), 1e-9);
        assertEquals(4.0, t.maxMs(), 1e-9);
    }

    @Test
    void noTickSinceTheMarkIsEmpty() {
        TickClock c = fed(1, 2);
        assertTrue(c.since(c.tickCount()).isEmpty());
    }

    @Test
    void theRingWrapsAndKeepsOnlyTheLastOnes() {
        TickClock c = new TickClock();
        for (int i = 0; i < TickClock.SIZE + 10; i++) {
            c.record((i < 10 ? 1000 : 1) * MS);
        }
        TickTimes t = c.since(0).orElseThrow();
        assertEquals(TickClock.SIZE, t.ticks());
        assertEquals(1.0, t.maxMs(), 1e-9);
    }

    @Test
    void aResetStartsOver() {
        TickClock c = fed(1, 2, 3);
        c.reset();
        assertEquals(0, c.tickCount());
        assertTrue(c.since(0).isEmpty());
    }
}
