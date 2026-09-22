package net.magicterra.worlddriver.mcp;

import net.magicterra.worlddriver.mcp.McpServer.SseSubscriber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pushing to a stalled SSE client must not block the caller.
 *
 * <p>{@code McpServer.onEvent} runs on DriverApi's single event-dispatch thread, which
 * every listener shares — the WebSocket transport included. It used to write the SSE
 * socket inline, so one client whose TCP receive window had filled blocked that thread
 * inside {@code os.write} and with it every other subscriber, the WebSocket push
 * channel, and the (unbounded) dispatch queue. Netty's asynchronous writes keep the
 * WebSocket side from blocking (its own growth bound is {@code RpcBackpressureTest}); the
 * MCP side had no equivalent.
 *
 * <p>These tests wedge the stream directly rather than trying to fill a real receive
 * window, which is why {@code SseSubscriber} is package-private.
 *
 * <p>Every test here carries a {@link Timeout}: what they assert is that a call
 * RETURNS, so the pre-fix failure mode is not a wrong value but an unbounded park.
 * Verified by reverting the fix — without these annotations the build hangs instead
 * of going red, which in CI is worse than a failure because nothing ever reports.
 */
class SseBackpressureTest {

    /** An OutputStream whose first write parks until released. */
    private static final class StalledStream extends OutputStream {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch entered = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean();

        @Override public void write(int b) { throw new UnsupportedOperationException(); }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }
    }

    @Test
    @Timeout(30)
    void offerNeverBlocksOnAStalledConsumer() throws Exception {
        StalledStream stream = new StalledStream();
        SseSubscriber sub = new SseSubscriber(stream);
        Thread pump = new Thread(() -> {
            try { sub.pumpUntilClosed(); } catch (InterruptedException ignored) { }
        }, "test-sse-pump");
        pump.setDaemon(true);
        pump.start();

        // Fill the outbox and keep going well past its capacity. The pump is stuck in
        // write() the whole time, so nothing is ever drained.
        sub.offer("data: first\n\n");
        assertTrue(stream.entered.await(5, TimeUnit.SECONDS), "pump never reached the write");

        long start = System.nanoTime();
        for (int i = 0; i < SseSubscriber.OUTBOX_CAP * 3; i++) sub.offer("data: " + i + "\n\n");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        // The real assertion is "this returned at all" — before the fix the first call
        // would have parked forever on the same latch the pump is parked on. The time
        // bound is deliberately loose so a slow CI box cannot make it flaky.
        assertTrue(elapsedMs < 5_000,
                "offering past capacity took " + elapsedMs + "ms — the caller is being blocked");
        assertFalse(sub.alive, "a consumer this far behind must have its stream closed");

        stream.release.countDown();
        pump.join(5_000);
    }

    @Test
    @Timeout(30)
    void aHealthyConsumerReceivesWhatWasOffered() throws Exception {
        // Same path, nothing stalled: the frames must actually come out the other end,
        // in order. Guards against "fixed the blocking by dropping everything".
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        SseSubscriber sub = new SseSubscriber(sink);
        Thread pump = new Thread(() -> {
            try { sub.pumpUntilClosed(); } catch (InterruptedException ignored) { }
        }, "test-sse-pump-ok");
        pump.setDaemon(true);
        pump.start();

        for (int i = 0; i < 20; i++) sub.offer("data: " + i + "\n\n");

        String written = "";
        for (int i = 0; i < 100 && !written.contains("data: 19"); i++) {
            Thread.sleep(20);
            synchronized (sub) { written = sink.toString("UTF-8"); }
        }
        assertTrue(written.contains("data: 0\n\n"), "first frame missing: " + written);
        assertTrue(written.contains("data: 19\n\n"), "last frame missing: " + written);
        assertTrue(written.indexOf("data: 0\n\n") < written.indexOf("data: 19\n\n"), "out of order");
        assertTrue(sub.alive, "a healthy consumer must stay open");

        sub.die();
        pump.join(5_000);
    }

    @Test
    @Timeout(30)
    void deadSubscriberAcceptsNoMoreFrames() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        SseSubscriber sub = new SseSubscriber(sink);
        sub.die();
        sub.offer("data: after-death\n\n");
        assertFalse(sub.alive);
        assertTrue(sink.toString("UTF-8").isEmpty(), "wrote to a closed stream");
    }
}
