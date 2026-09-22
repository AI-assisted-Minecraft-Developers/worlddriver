package net.magicterra.worlddriver.bot.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import net.magicterra.worlddriver.api.ServerThreadHop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A client-thread hop that timed out must not leave its task queued to run later: the caller was
 * told it failed, and a retry would apply it twice. Same contract as the server-thread hop.
 */
class ClientHopTest {

    /** A client that has not drained its task queue yet: tasks run only when drained. */
    private static final class ParkedClient implements Executor {
        final Queue<Runnable> queued = new ArrayDeque<>();
        @Override public synchronized void execute(Runnable r) { queued.add(r); }
        synchronized void drain() { Runnable r; while ((r = queued.poll()) != null) r.run(); }
    }

    @Test
    @Timeout(10)
    void aTaskStillQueuedAtTheTimeoutIsWithdrawnAndNeverRuns() {
        ParkedClient client = new ParkedClient();
        AtomicInteger runs = new AtomicInteger();

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> ClientHop.call(client, () -> false, 50, runs::incrementAndGet));
        assertInstanceOf(ServerThreadHop.NotExecutedException.class, e,
                "a timed-out queued task must report not-executed, got " + e);
        assertTrue(e.getMessage().contains("client thread"), e.getMessage());

        client.drain();
        assertEquals(0, runs.get(), "the withdrawn task ran after its caller was told it had not");
    }

    @Test
    void onTheClientThreadTheTaskRunsInline() {
        assertEquals(7, ClientHop.call(r -> { throw new AssertionError("must not queue"); }, () -> true, 50, () -> 7));
    }

    @Test
    void aTaskErrorReachesTheCallerUnwrapped() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ClientHop.call(Runnable::run, () -> false, 1000, () -> { throw new IllegalArgumentException("bad param"); }));
        assertEquals("bad param", e.getMessage());
    }
}
