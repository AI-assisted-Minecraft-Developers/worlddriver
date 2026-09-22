package net.magicterra.worlddriver.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * What a caller may conclude from a server-thread hop that timed out. A task still sitting in the
 * queue when the waiter gives up must never run, so "not executed" is true and a retry cannot
 * apply it twice; a task the server thread already started cannot be withdrawn, so that caller is
 * told the outcome is unknown instead.
 */
class ServerThreadHopTest {

    /** A server thread that has not got round to its queue yet: tasks run only when drained. */
    private static final class ParkedExecutor implements Executor {
        final Queue<Runnable> queued = new ArrayDeque<>();
        @Override public synchronized void execute(Runnable r) { queued.add(r); }
        synchronized void drain() { Runnable r; while ((r = queued.poll()) != null) r.run(); }
    }

    /** A server thread that picks every task up at once, on a thread of its own. */
    private static final Executor PROMPT = r -> {
        Thread t = new Thread(r, "test-server-thread");
        t.setDaemon(true);
        t.start();
    };

    @Test
    @Timeout(10)
    void aTaskStillQueuedAtTheTimeoutIsWithdrawnAndNeverRuns() {
        ParkedExecutor server = new ParkedExecutor();
        AtomicInteger runs = new AtomicInteger();
        ServerThreadHop hop = new ServerThreadHop(server, () -> false, 50);

        RuntimeException e = assertThrows(RuntimeException.class, () -> hop.call(runs::incrementAndGet));
        ServerThreadHop.NotExecutedException ne = assertInstanceOf(ServerThreadHop.NotExecutedException.class, e,
                "a timed-out queued task must report not-executed, got " + e);
        assertEquals(ServerThreadHop.CODE_NOT_EXECUTED, ne.code());
        assertTrue(e.getMessage().contains("will not run"), e.getMessage());

        server.drain();   // the tick frees up and reaches the stale task
        assertEquals(0, runs.get(), "the withdrawn task ran after its caller was told it had not");
    }

    @Test
    @Timeout(10)
    void aTaskRunningAtTheTimeoutReportsAnUnknownOutcome() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        ServerThreadHop hop = new ServerThreadHop(PROMPT, () -> false, 200);

        RuntimeException e = assertThrows(RuntimeException.class, () -> hop.call(() -> {
            started.countDown();
            try { release.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            return runs.incrementAndGet();
        }));
        assertTrue(started.await(1, TimeUnit.SECONDS));
        ServerThreadHop.OutcomeUnknownException ou = assertInstanceOf(ServerThreadHop.OutcomeUnknownException.class, e,
                "a task the server thread already started must report outcome-unknown, got " + e);
        assertEquals(ServerThreadHop.CODE_OUTCOME_UNKNOWN, ou.code());
        assertTrue(e.getMessage().contains("observe"), e.getMessage());

        release.countDown();
        for (int i = 0; i < 100 && runs.get() == 0; i++) Thread.sleep(10);
        assertEquals(1, runs.get(), "a started task is not interrupted by the waiter giving up");
    }

    @Test
    @Timeout(10)
    void aPromptTaskReturnsItsValueAndItsOwnException() {
        ServerThreadHop hop = new ServerThreadHop(PROMPT, () -> false, 2_000);
        assertEquals(42, hop.call(() -> 42));

        IllegalArgumentException bad = new IllegalArgumentException("bad param");
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> hop.call(() -> { throw bad; }));
        assertSame(bad, thrown, "the task's own exception reaches the caller unwrapped");
    }

    @Test
    void onTheServerThreadTheTaskRunsInline() {
        Executor refuses = r -> { throw new AssertionError("must not queue a task from the server thread"); };
        ServerThreadHop hop = new ServerThreadHop(refuses, () -> true, 1);
        assertEquals("inline", hop.call(() -> "inline"));
    }

    @Test
    void theTwoOutcomesCarryDistinctCodesAndAreFoundThroughWrappers() {
        assertNotEquals(ServerThreadHop.CODE_NOT_EXECUTED, ServerThreadHop.CODE_OUTCOME_UNKNOWN);
        ServerThreadHop.NotExecutedException ne = new ServerThreadHop.NotExecutedException("x");
        assertSame(ne, ServerThreadHop.find(ne));
        assertSame(ne, ServerThreadHop.find(new RuntimeException(new IllegalStateException(ne))));
        assertEquals(null, ServerThreadHop.find(new RuntimeException("unrelated")));
        assertEquals(null, ServerThreadHop.find(null));
    }
}
