package net.magicterra.worlddriver.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.registry.Scripting;
import net.magicterra.worlddriver.api.registry.Scripting.ScriptFailure;
import org.junit.jupiter.api.Test;

/** What crosses between Java and a hosted script, and how failures leave it. */
class HostedScriptsTest {
    private final HostedScripts scripts = new HostedScripts(new ApiRegistry());

    @Test
    void valuesCrossAsPlainJava() {
        Object out = scripts.evaluate("values.js", "({n: given.n + 1, s: given.s + '!', list: [1, 'a', null], b: true})",
                Map.of("given", Map.of("n", 41, "s", "hi")));
        assertEquals(Map.of("n", 42.0, "s", "hi!", "list", java.util.Arrays.asList(1.0, "a", null), "b", true), out);
    }

    @Test
    void aScriptsFunctionIsCalledLaterFromAnotherThread() throws Exception {
        List<Scripting.Function> registered = new ArrayList<>();
        Scripting.Function register = args -> { registered.add((Scripting.Function) args[0]); return null; };
        scripts.evaluate("later.js", "var base = 10; register(function (x) { return base + x; });",
                Map.of("register", register));
        Object out = CompletableFuture.supplyAsync(() -> registered.get(0).call(5)).get(10, TimeUnit.SECONDS);
        assertEquals(15.0, out);
    }

    @Test
    void aJavaExceptionReachesTheCallerAsItself() {
        IllegalStateException thrown = new IllegalStateException("from java");
        Scripting.Function boom = args -> { throw thrown; };
        assertSame(thrown, assertThrows(IllegalStateException.class,
                () -> scripts.evaluate("boom.js", "boom()", Map.of("boom", boom))));
        assertSame(thrown, assertThrows(IllegalStateException.class,
                () -> scripts.evaluate("rethrow.js", "try { boom() } catch (e) { throw e }", Map.of("boom", boom))));
        AssertionError failed = new AssertionError("expected");
        Scripting.Function check = args -> { throw failed; };
        assertSame(failed, assertThrows(AssertionError.class,
                () -> scripts.evaluate("check.js", "check()", Map.of("check", check))));
    }

    @Test
    void aScriptErrorSaysWhere() {
        ScriptFailure failure = assertThrows(ScriptFailure.class,
                () -> scripts.evaluate("where.js", "var a = 1;\nthrow new Error('nope');", Map.of()));
        assertEquals("where.js", failure.script());
        assertEquals(2, failure.line());
        assertTrue(failure.getMessage().contains("nope"), failure.getMessage());
    }

    @Test
    void anObjectThatIsNotAPlainValueIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> scripts.evaluate("refused.js", "1", Map.of("thread", Thread.currentThread())));
    }
}
