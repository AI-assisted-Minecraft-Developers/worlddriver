package net.magicterra.agent.neoforge;

import net.magicterra.agent.AgentDriverCommon;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.gametest.framework.TestFunction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Suite-integrity manifest (task#85 stopgap): one JSONL file per GameTest run,
 * written into the GameTestServer working directory (neoforge/run-gametest/).
 *
 * Records:
 *   {"type":"registered","name":..,"batch":..,"required":..}
 *     — every TestFunction in GameTestRegistry, dumped once at server starting.
 *   {"type":"enter","name":..}
 *     — appended by AgentGameTestSupport.gtOnlySkips(), the first line of every
 *       test body. A registered test with no enter record was silently swallowed
 *       by the vanilla scheduler (#85); scripts/gt_reconcile.py turns that into
 *       a hard failure.
 *
 * enter() runs on the SERVER THREAD at every test body's first line, and a
 * controller A/B measured that synchronous open/append/close there perturbs
 * wall-clock timing enough to flip the byte-level-deterministic
 * descentYawArena. enter() is therefore lock-free and IO-free: it only offers
 * the name onto a {@link ConcurrentLinkedQueue}. A dedicated daemon writer
 * thread ("gt-manifest-writer", started by reset()) drains the queue off the
 * server thread and does the actual file append. A JVM shutdown hook drains
 * any remainder on normal server stop so a run's tail is never lost. If the
 * writer thread's append itself fails, it throws loudly there (that thread's
 * job is exactly that append) rather than silently dropping records — and any
 * record that never makes it to disk is still caught downstream, since the
 * reconciler treats a missing enter line as a hard (fail-RED) swallow.
 *
 * Name matching is case-insensitive downstream: vanilla testName() is the
 * lowercased method name, guard strings are hand-written mixed case.
 * Test names are Java identifiers — no JSON escaping needed.
 */
final class GameTestManifest {
    private static final Path FILE = Path.of("testkit-manifest.jsonl");
    private static final Object LOCK = new Object();
    private static volatile boolean armed = false;

    private static final ConcurrentLinkedQueue<String> PENDING = new ConcurrentLinkedQueue<>();
    private static volatile boolean writerStarted = false;

    private GameTestManifest() {}

    /** Truncate the manifest, dump every registered test, and start the async enter-writer.
     *  GameTestServer runs only — live/integrated servers never call this, so enter()
     *  stays a no-op there. */
    static void reset() {
        synchronized (LOCK) {
            try {
                Collection<TestFunction> all = GameTestRegistry.getAllTestFunctions();
                StringBuilder sb = new StringBuilder();
                for (TestFunction fn : all) {
                    sb.append("{\"type\":\"registered\",\"name\":\"").append(fn.testName())
                      .append("\",\"batch\":\"").append(fn.batchName())
                      .append("\",\"required\":").append(fn.required()).append("}\n");
                }
                Files.writeString(FILE, sb.toString(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
                armed = true;
                AgentDriverCommon.LOG.info("[{}] gametest manifest armed: {} registered tests -> {}",
                        AgentDriverCommon.MOD_ID, all.size(), FILE.toAbsolutePath());
            } catch (IOException e) {
                // A broken manifest must never read as green — fail the run loudly.
                throw new UncheckedIOException("cannot write gametest manifest", e);
            }
            if (!writerStarted) {
                writerStarted = true;
                Thread writer = new Thread(GameTestManifest::drainLoop, "gt-manifest-writer");
                writer.setDaemon(true);
                writer.start();
                Runtime.getRuntime().addShutdownHook(
                        new Thread(GameTestManifest::drainPending, "gt-manifest-writer-shutdown"));
            }
        }
    }

    /** Offer an enter record for the async writer. No-op unless reset() armed this run.
     *  Lock-free, IO-free — see class javadoc: this runs on the server thread and must
     *  never block on IO. */
    static void enter(String name) {
        if (!armed) return;
        PENDING.offer(name);
    }

    /** Runs on the "gt-manifest-writer" daemon thread: poll-and-append forever. */
    private static void drainLoop() {
        while (true) {
            drainPending();
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                drainPending();
                return;
            }
        }
    }

    /** Append every currently-queued enter record. Called by the writer loop and by
     *  the shutdown hook — both can run this concurrently near process exit (the
     *  writer thread mid-sleep while the shutdown hook fires), so the whole body is
     *  serialized on LOCK rather than relying on POSIX O_APPEND atomicity alone.
     *
     *  <p>Mid-batch loss path: names are removed from PENDING as soon as they're
     *  polled into the local buffer. If {@code Files.writeString} then throws, that
     *  batch's names are already gone from the queue and are never retried — they
     *  are lost. This is accepted, not accidental: a lost enter record reads
     *  downstream as "registered but never entered," which the reconciler already
     *  treats as a hard RED swallow (the fail-safe direction), so a lost record can
     *  only make the run fail loud, never falsely pass. */
    private static void drainPending() {
        synchronized (LOCK) {
            String name;
            StringBuilder sb = null;
            while ((name = PENDING.poll()) != null) {
                if (sb == null) sb = new StringBuilder();
                sb.append("{\"type\":\"enter\",\"name\":\"").append(name).append("\"}\n");
            }
            if (sb == null) return;
            try {
                Files.writeString(FILE, sb.toString(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot append gametest manifest", e);
            }
        }
    }
}
