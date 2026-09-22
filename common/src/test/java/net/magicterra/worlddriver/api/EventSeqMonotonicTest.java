package net.magicterra.worlddriver.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The transports outlive a world: a client holding cursor N across a reload keeps asking for
 * {@code seq > N}. A counter that restarts at 1 answers that with silence until N new events have
 * been emitted, so dropping the buffer must never rewind the seq.
 */
class EventSeqMonotonicTest {

    @Test
    void detachingTheServerClearsTheRingButKeepsTheSeq() {
        DriverApi api = new DriverApi();
        api.emit("test.a", null, null);
        api.emit("test.b", null, null);
        long cursor = api.emit("test.c", null, null);

        api.detachServer();

        synchronized (api.eventsLock) {
            assertTrue(api.events.isEmpty(), "the old world's events are dropped");
        }
        long next = api.emit("test.d", null, null);
        assertEquals(cursor + 1, next, "the first event after a detach continues the old seq");
    }

    @Test
    void clearingTheRingKeepsTheSeq() {
        DriverApi api = new DriverApi();
        long cursor = api.emit("test.a", null, null);

        api.clearEvents();

        synchronized (api.eventsLock) {
            assertTrue(api.events.isEmpty());
        }
        assertEquals(cursor + 1, api.emit("test.b", null, null));
    }
}
