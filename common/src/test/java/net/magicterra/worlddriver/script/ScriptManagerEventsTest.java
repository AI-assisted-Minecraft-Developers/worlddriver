package net.magicterra.worlddriver.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.test.ScriptTest;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * 09_events.js through {@link ScriptManager}, the loader of the operator's scripts: the {@code ScriptEvents} attach
 * and tick lifecycle exists only in the scope it builds, not in a hosted script test's. One dynamic test per check
 * the script makes, and the list of them pinned, so a check that stops running is noticed.
 */
@Isolated("ScriptTest and ScriptEvents keep one list for the JVM")
class ScriptManagerEventsTest {

    private static final List<String> CHECKS = List.of(
            "09_events: before attach, onAttach callback has not yet fired",
            "09_events: tickListenerCount reflects registration",
            "09_events: onAttach callback ran (post-attach assert)",
            "09_events: late-registered onAttach fires immediately",
            "09_events: tickCount is a non-negative number");

    @TempDir
    Path dir;

    @TestFactory
    Stream<DynamicTest> eachCheckOfTheScriptPasses() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("09_events.js")) {
            assertNotNull(in, "09_events.js");
            Files.copy(in, dir.resolve("09_events.js"));
        }
        ScriptTest.clear();
        int loaded;
        List<ScriptTest.Result> results;
        try {
            loaded = new ScriptManager(new ApiRegistry(), dir, null).loadAll();
            results = ScriptTest.snapshot();
        } finally {
            ScriptTest.clear();
            ScriptEvents.clear();
        }
        assertEquals(1, loaded, "the script loads");
        assertEquals(CHECKS, results.stream().map(r -> r.name).toList(), "the checks the script makes, in order");
        return results.stream().map(r -> DynamicTest.dynamicTest(r.name,
                () -> assertTrue(r.passed, () -> String.valueOf(r.error))));
    }
}
