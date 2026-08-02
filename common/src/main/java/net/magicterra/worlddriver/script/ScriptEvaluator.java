package net.magicterra.worlddriver.script;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ContextFactory;
import dev.latvian.mods.rhino.ScriptRuntime;
import dev.latvian.mods.rhino.ScriptableObject;
import dev.latvian.mods.rhino.Undefined;
import dev.latvian.mods.rhino.util.ClassVisibilityContext;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.rpc.JsonCodec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ad-hoc Rhino evaluator behind the {@code mc.script.eval} MCP tool.
 *
 * Each call gets a fresh scope (no state leaks between calls), runs on a
 * worker thread from a cached pool (off the server thread, so DriverApi
 * routes that bounce through {@code server.execute()} don't deadlock), and
 * is bounded by a wall-clock deadline enforced via Rhino's instruction-count
 * observer. The optional {@link ScriptClassFilter} class filter applies only when opted in (-Dworlddriver.sandbox=on; off by default — scripts are first-party capability).
 *
 * The script body runs through {@code eval()} so its last expression is the
 * completion value; the wrapper captures result/error/log and JSON-encodes
 * the payload so it can travel back through JsonCodec unchanged.
 */
public final class ScriptEvaluator {
    private static final int DEFAULT_TIMEOUT_MS = 3000;
    private static final int MAX_TIMEOUT_MS = 30_000;
    /** Cap for {@link #evaluateOnThread} — boss playbooks run minutes, not the 30 s
     *  ad-hoc {@code mc.script.eval} budget. Same script context, longer deadline. */
    private static final int PLAYBOOK_MAX_TIMEOUT_MS = 20 * 60_000;
    private static final int MAX_SOURCE_CHARS = 64 * 1024;
    private static final int INSTRUCTION_THRESHOLD = 10_000;
    private static final String PRELUDE_RESOURCE = "/data/worlddriver/scripts/prelude.js";
    private static final String PRELUDE = loadPrelude();

    private final DriverApi api;
    private final ExecutorService executor;
    private final AtomicLong seq = new AtomicLong();

    private static String loadPrelude() {
        try (InputStream in = ScriptEvaluator.class.getResourceAsStream(PRELUDE_RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing classpath resource " + PRELUDE_RESOURCE);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load " + PRELUDE_RESOURCE, e);
        }
    }

    public ScriptEvaluator(DriverApi api) {
        this.api = api;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "agent-script-eval-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Evaluate a JS snippet. Returns a Map with keys:
     *   result : the completion value (last expression), JSON-decoded; null on error
     *   error  : a string error message, or null on success
     *   log    : array of strings captured via console.log/console.error
     *   ms     : wall-clock milliseconds spent
     */
    public Map<String, Object> evaluate(String source, int requestedTimeoutMs) {
        if (source == null) source = "";
        if (source.length() > MAX_SOURCE_CHARS) {
            return errorEnvelope("script source too large: " + source.length() + " > " + MAX_SOURCE_CHARS, 0L);
        }
        int budget = (requestedTimeoutMs <= 0)
                ? DEFAULT_TIMEOUT_MS
                : Math.min(requestedTimeoutMs, MAX_TIMEOUT_MS);
        long t0 = System.nanoTime();
        long deadline = t0 + TimeUnit.MILLISECONDS.toNanos(budget);

        final String src = source;
        Future<String> f = executor.submit(() -> runOnce(src, deadline, budget));
        String json;
        try {
            json = f.get(budget + 250L, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            return errorEnvelope("script timeout (" + budget + " ms)", elapsedMs(t0));
        } catch (ExecutionException e) {
            Throwable cause = (e.getCause() != null) ? e.getCause() : e;
            String msg = (cause.getMessage() != null) ? cause.getMessage() : cause.toString();
            return errorEnvelope(msg, elapsedMs(t0));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorEnvelope("interrupted", elapsedMs(t0));
        }

        return decodeEnvelope(json, t0);
    }

    /**
     * Evaluate a long-running script (a boss playbook) on the CALLING thread —
     * the {@link PlaybookRunner}'s background worker — with a generous budget and a
     * cooperative {@code abort} flag, reusing the exact same Rhino script context as
     * {@link #evaluate}. The abort/deadline are enforced via Rhino's instruction
     * observer, so they bite at the next script instruction (typically the next
     * loop turn). Returns the same {result, error, log, ms} envelope.
     */
    public Map<String, Object> evaluateOnThread(String source, int requestedTimeoutMs,
                                                java.util.function.BooleanSupplier abort) {
        if (source == null) source = "";
        if (source.length() > MAX_SOURCE_CHARS) {
            return errorEnvelope("script source too large: " + source.length() + " > " + MAX_SOURCE_CHARS, 0L);
        }
        int budget = (requestedTimeoutMs <= 0)
                ? DEFAULT_TIMEOUT_MS
                : Math.min(requestedTimeoutMs, PLAYBOOK_MAX_TIMEOUT_MS);
        long t0 = System.nanoTime();
        long deadline = t0 + TimeUnit.MILLISECONDS.toNanos(budget);
        java.util.function.BooleanSupplier abortOrFalse = (abort != null) ? abort : () -> false;
        String json;
        try {
            json = runOnce(source, deadline, budget, abortOrFalse);
        } catch (Throwable t) {
            String msg = (t.getMessage() != null) ? t.getMessage() : t.toString();
            return errorEnvelope(msg, elapsedMs(t0));
        }
        return decodeEnvelope(json, t0);
    }

    /**
     * Parse-only syntax check (compiles, never executes) — the cheap "does it
     * even parse" gate the skill library runs before persisting a new skill
     * (Phase H / Voyager). Returns {@code null} if the source is syntactically
     * valid, else the parser's error message. Uses the same script context
     * (no deadline — compilation doesn't run instructions).
     */
    public String checkSyntax(String source) {
        if (source == null) source = "";
        ContextFactory factory = new ContextFactory() {
            @Override
            protected Context createContext() {
                return new FilteredObservedContext(this, Long.MAX_VALUE, 0, () -> false);
            }
        };
        Context cx = factory.enter();
        try {
            cx.compileString(source, "<skill>", 1, null);
            return null;
        } catch (RuntimeException e) {
            return (e.getMessage() != null) ? e.getMessage() : e.toString();
        }
    }

    private Map<String, Object> decodeEnvelope(String json, long t0) {
        Map<String, Object> out;
        try {
            Object decoded = JsonCodec.decode(json);
            if (!(decoded instanceof Map<?, ?> m)) {
                return errorEnvelope("eval payload not an object", elapsedMs(t0));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> casted = (Map<String, Object>) m;
            out = new LinkedHashMap<>(casted);
        } catch (Exception parseErr) {
            return errorEnvelope("eval payload parse error: " + parseErr.getMessage(), elapsedMs(t0));
        }
        out.put("ms", elapsedMs(t0));
        return out;
    }

    private String runOnce(String source, long deadlineNanos, int budgetMs) {
        return runOnce(source, deadlineNanos, budgetMs, () -> false);
    }

    private String runOnce(String source, long deadlineNanos, int budgetMs,
                           java.util.function.BooleanSupplier abort) {
        ContextFactory factory = new ContextFactory() {
            @Override
            protected Context createContext() {
                return new FilteredObservedContext(this, deadlineNanos, budgetMs, abort);
            }
        };

        Context cx = factory.enter();
        cx.setInstructionObserverThreshold(INSTRUCTION_THRESHOLD);

        ScriptableObject scope = cx.initStandardObjects();
        ScriptableObject.putProperty(scope, "__api", cx.javaToJS(api, scope), cx);

        cx.evaluateString(scope, PRELUDE, "<eval-prelude>", 1, null);

        // Evaluate the user source directly — its last expression becomes the
        // result. Doing this with a separate evaluateString (rather than wrapping
        // in JS eval()) keeps user var/let declarations confined to this scope
        // and avoids Rhino's eval-time scope-promotion behavior.
        Object userResult;
        String userError = null;
        try {
            userResult = cx.evaluateString(scope, source, "<eval>", 1, null);
            if (userResult instanceof Undefined) userResult = null;
        } catch (Throwable t) {
            userResult = null;
            userError = (t.getMessage() != null) ? t.getMessage() : t.getClass().getSimpleName();
        }

        // Push the captured values back into the scope, then ask JS to
        // JSON.stringify the whole envelope. This lets NativeObject / NativeArray
        // results round-trip cleanly without us having to walk them in Java.
        ScriptableObject.putProperty(scope, "__userResult",
                userResult == null ? null : cx.javaToJS(userResult, scope), cx);
        ScriptableObject.putProperty(scope, "__userError", userError, cx);

        // Use typeof rather than strict equality. Java-wrapped strings
        // (especially the literal "undefined") trip Rhino's === undefined check
        // in ways that don't match standard JS semantics; typeof is robust.
        Object payload = cx.evaluateString(scope,
            "JSON.stringify({result: (typeof __userResult === 'undefined' ? null : __userResult), " +
            "error: __userError, log: __log});",
            "<eval-end>", 1, null);
        return ScriptRuntime.toString(cx, payload);
    }

    private static long elapsedMs(long t0) {
        return (System.nanoTime() - t0) / 1_000_000L;
    }

    private static Map<String, Object> errorEnvelope(String message, long ms) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("result", null);
        m.put("error", message);
        m.put("log", List.of());
        m.put("ms", ms);
        return m;
    }

    /**
     * Context that combines the optional {@link ScriptClassFilter} class filter (off by default)
     * with a wall-clock deadline enforced via Rhino's instruction-count hook.
     */
    private static final class FilteredObservedContext extends Context {
        private final long deadlineNanos;
        private final int budgetMs;
        private final java.util.function.BooleanSupplier abort;

        FilteredObservedContext(ContextFactory factory, long deadlineNanos, int budgetMs,
                                java.util.function.BooleanSupplier abort) {
            super(factory);
            this.deadlineNanos = deadlineNanos;
            this.budgetMs = budgetMs;
            this.abort = abort;
        }

        @Override
        public boolean visibleToScripts(String fullClassName, ClassVisibilityContext type) {
            return ScriptClassFilter.isAllowed(fullClassName, type);
        }

        @Override
        protected void observeInstructionCount(int instructionCount) {
            if (abort != null && abort.getAsBoolean()) {
                throw new RuntimeException("script aborted");
            }
            if (System.nanoTime() > deadlineNanos) {
                throw new RuntimeException("script timeout (" + budgetMs + " ms)");
            }
        }
    }
}
