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
 * Name matching is case-insensitive downstream: vanilla testName() is the
 * lowercased method name, guard strings are hand-written mixed case.
 * Test names are Java identifiers — no JSON escaping needed.
 */
final class GameTestManifest {
    private static final Path FILE = Path.of("testkit-manifest.jsonl");
    private static final Object LOCK = new Object();
    private static volatile boolean armed = false;

    private GameTestManifest() {}

    /** Truncate the manifest and dump every registered test. GameTestServer runs only —
     *  live/integrated servers never call this, so enter() stays a no-op there. */
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
        }
    }

    /** Append an enter record. No-op unless reset() armed this run. */
    static void enter(String name) {
        if (!armed) return;
        synchronized (LOCK) {
            try {
                Files.writeString(FILE, "{\"type\":\"enter\",\"name\":\"" + name + "\"}\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot append gametest manifest", e);
            }
        }
    }
}
