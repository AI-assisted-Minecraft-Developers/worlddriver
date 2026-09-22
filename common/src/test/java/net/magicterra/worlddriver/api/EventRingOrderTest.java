package net.magicterra.worlddriver.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import net.magicterra.worlddriver.model.DriverEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The event ring's order is its contract: readers walk it front to back and advance their cursor
 * to the last seq they were handed, so an event appended behind a higher seq is never returned to
 * anyone who read in between. Emitters run on the server tick, the client tick, RPC handlers and
 * watcher threads at once.
 */
class EventRingOrderTest {

    private static final int THREADS = 8;
    private static final int PER_THREAD = 20_000;

    @Test
    @Timeout(60)
    void appendOrderIsSeqOrderUnderConcurrentEmitters() throws Exception {
        DriverApi api = new DriverApi();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicReference<String> disorder = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            Thread th = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < PER_THREAD; i++) api.emit("test.tick", null, null);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "test-emitter-" + t);
            th.setDaemon(true);
            threads.add(th);
            th.start();
        }
        // A reader sampling the ring while it fills, which is what a long-polling cursor does;
        // the final window alone could miss a reordering that has already rolled off.
        Thread reader = new Thread(() -> {
            while (done.getCount() > 0 && disorder.get() == null) {
                synchronized (api.eventsLock) {
                    long prev = Long.MIN_VALUE;
                    for (DriverEvent e : api.events) {
                        if (e.seq <= prev) {
                            disorder.compareAndSet(null, "seq " + e.seq + " appended after seq " + prev);
                            break;
                        }
                        prev = e.seq;
                    }
                }
                Thread.onSpinWait();
            }
        }, "test-ring-reader");
        reader.setDaemon(true);
        reader.start();
        start.countDown();
        assertTrue(done.await(50, TimeUnit.SECONDS), "emitters did not finish");
        reader.join(5_000);
        if (disorder.get() != null) fail("ring order is not seq order: " + disorder.get());

        synchronized (api.eventsLock) {
            assertEquals(DriverApi.EVENT_BUFFER_CAP, api.events.size());
            assertEquals((long) THREADS * PER_THREAD, api.events.peekLast().seq,
                    "the newest retained event carries the last seq handed out");
        }
    }
}
