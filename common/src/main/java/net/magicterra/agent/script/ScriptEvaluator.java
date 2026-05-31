package net.magicterra.agent.script;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ContextFactory;
import dev.latvian.mods.rhino.ScriptRuntime;
import dev.latvian.mods.rhino.ScriptableObject;
import dev.latvian.mods.rhino.Undefined;
import dev.latvian.mods.rhino.util.ClassVisibilityContext;
import net.magicterra.agent.api.AgentApi;
import net.magicterra.agent.rpc.JsonCodec;

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
 * worker thread from a cached pool (off the server thread, so AgentApi
 * routes that bounce through {@code server.execute()} don't deadlock), and
 * is bounded by a wall-clock deadline enforced via Rhino's instruction-count
 * observer. The standard {@link AgentClassFilter} sandbox applies.
 *
 * The script body runs through {@code eval()} so its last expression is the
 * completion value; the wrapper captures result/error/log and JSON-encodes
 * the payload so it can travel back through JsonCodec unchanged.
 */
public final class ScriptEvaluator {
    private static final int DEFAULT_TIMEOUT_MS = 3000;
    private static final int MAX_TIMEOUT_MS = 30_000;
    private static final int MAX_SOURCE_CHARS = 64 * 1024;
    private static final int INSTRUCTION_THRESHOLD = 10_000;
    private static final String PRELUDE_RESOURCE = "/data/agent_driver/scripts/prelude.js";
    private static final String PRELUDE = loadPrelude();

    private final AgentApi api;
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

    public ScriptEvaluator(AgentApi api) {
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
        ContextFactory factory = new ContextFactory() {
            @Override
            protected Context createContext() {
                return new FilteredObservedContext(this, deadlineNanos, budgetMs);
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
     * Context that combines the standard {@link AgentClassFilter} sandbox
     * with a wall-clock deadline enforced via Rhino's instruction-count hook.
     */
    private static final class FilteredObservedContext extends Context {
        private final long deadlineNanos;
        private final int budgetMs;

        FilteredObservedContext(ContextFactory factory, long deadlineNanos, int budgetMs) {
            super(factory);
            this.deadlineNanos = deadlineNanos;
            this.budgetMs = budgetMs;
        }

        @Override
        public boolean visibleToScripts(String fullClassName, ClassVisibilityContext type) {
            return AgentClassFilter.isAllowed(fullClassName, type);
        }

        @Override
        protected void observeInstructionCount(int instructionCount) {
            if (System.nanoTime() > deadlineNanos) {
                throw new RuntimeException("script timeout (" + budgetMs + " ms)");
            }
        }
    }
}
