package net.magicterra.worlddriver.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ContextFactory;
import dev.latvian.mods.rhino.JavaScriptException;
import dev.latvian.mods.rhino.RhinoException;
import dev.latvian.mods.rhino.ScriptRuntime;
import dev.latvian.mods.rhino.ScriptableObject;
import dev.latvian.mods.rhino.Undefined;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.magicterra.worlddriver.api.call.ApiTimers;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.call.RunContext;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.Issue;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.Outcome;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.ScriptError;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.JsonValue;

/**
 * Runs scripts: {@code mc.script.eval}, a skill, a playbook. Each run gets a fresh scope (nothing leaks between runs)
 * on a worker from a cached pool, off the game threads, so the calls it makes can wait for them. The optional
 * {@link ScriptClassFilter} applies only when opted in (-Dworlddriver.sandbox=on; off by default: scripts are a
 * first-party capability, and running the caller's code is what this class is for).
 *
 * <p>A run that its caller is not already inside is a new {@link RunContext}, which this class ends: stopped when its
 * budget runs out or it is cancelled, which cancels the actions it started; finished when the script returns or throws,
 * which leaves them running. A script evaluated from inside another run (a nested {@code mc.script.eval}) shares that
 * run and its deadline, and leaves ending it to its owner.
 *
 * <p>The budget is checked between script instructions, so a script blocked in a call is not interrupted by it; the
 * call itself carries the run's deadline. A watchdog a moment past the budget answers anyway.
 */
public final class ScriptEvaluator {
    private static final int INSTRUCTION_THRESHOLD = 10_000;
    /** How long past its budget a run blocked outside the engine is given before it is answered for. */
    private static final long WATCHDOG_GRACE_MS = 250;
    /** Encoding a result can run the script's own getters, so it is budgeted too. */
    private static final long ENVELOPE_BUDGET_MS = 1000;
    /**
     * Runs alive at once, across the JVM, nested ones included. Each holds a thread of its own until it returns, and a
     * run answers its caller before that, so no request quota bounds them: without this, one connection sending eval
     * after eval would keep opening threads.
     */
    public static final int MAX_RUNS = 32;

    private final ApiRegistry api;
    private final ExecutorService executor;
    private final Semaphore runs = new Semaphore(MAX_RUNS);

    public ScriptEvaluator(ApiRegistry api) {
        this.api = api;
        this.executor = Executors.newCachedThreadPool(
                Thread.ofPlatform().name("agent-script-eval-", 1).daemon().factory());
    }

    /**
     * Run {@code source} for at most {@code budgetMs}, and no later than {@code caller}'s deadline: inside
     * {@code caller}'s run when it has one, else as a new run named after {@code kind} ({@code eval-7}).
     */
    public CompletionStage<Outcome> run(String source, int budgetMs, CallContext caller, String kind) {
        if (caller.run().isPresent()) return start(source, budgetMs, caller, false);
        return start(source, budgetMs, caller.inRun(RunContext.start(kind)), true);
    }

    /** Run {@code source} as {@code ctx}'s run, which this ends: stopped by its budget, else finished. */
    public CompletionStage<Outcome> runOwned(String source, int budgetMs, CallContext ctx) {
        return start(source, budgetMs, ctx, true);
    }

    /**
     * Refused with {@code server_busy} before anything runs when {@link #MAX_RUNS} runs are alive; a run this would
     * have owned is ended at once. A nested run is refused the same way rather than waiting, since the runs holding
     * every slot may be the ones waiting for it.
     */
    private CompletionStage<Outcome> start(String source, int budgetMs, CallContext caller, boolean owns) {
        RunContext run = caller.run().orElseThrow();
        if (!runs.tryAcquire()) {
            if (owns) run.stop();
            throw ApiException.of(
                    ApiError.Code.SERVER_BUSY,
                    "server busy: " + MAX_RUNS
                            + " scripts are already running; wait for one to end before starting another");
        }
        long start = System.nanoTime();
        long own = start + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        long deadline = own - caller.deadlineNanos() < 0 ? own : caller.deadlineNanos();
        CallContext ctx = new CallContext(
                deadline, caller.requestCancelled(), Optional.of(run), caller.waits(), true, caller.threadWaitMs());
        BooleanSupplier stopped =
                () -> run.stopped().isCancelled() || caller.requestCancelled().isCancelled();

        CompletableFuture<Outcome> out = new CompletableFuture<>();
        // The slot goes back when the thread does, not when the caller is answered: a run past its
        // budget still holds its thread until the interrupt reaches it.
        Future<?> worker;
        try {
            worker = executor.submit(() -> {
                try {
                    out.complete(runOnce(source, ctx, stopped, start));
                } finally {
                    runs.release();
                }
            });
        } catch (RuntimeException e) {
            runs.release();
            throw e;
        }
        long waitMs = TimeUnit.NANOSECONDS.toMillis(deadline - start) + WATCHDOG_GRACE_MS;
        // The timer only hands the work on: stopping a run cancels actions, which is no job for the timer thread.
        ApiTimers.schedule(
                () -> executor.execute(() -> {
                    if (out.isDone()) return;
                    worker.cancel(true);
                    out.complete(
                            failed(run, ScriptContracts.SCRIPT_TIMEOUT, "script timeout (" + budgetMs + " ms)", start));
                }),
                waitMs);
        return out.whenComplete((o, t) -> {
            if (!owns) return;
            String code =
                    o == null || o.error().isEmpty() ? null : o.error().get().code();
            if (t != null
                    || ScriptContracts.SCRIPT_TIMEOUT.equals(code)
                    || ScriptContracts.SCRIPT_STOPPED.equals(code)) {
                run.stop();
            } else {
                run.finish();
            }
        });
    }

    /**
     * Parse-only syntax check (compiles, never executes): the gate the skill library runs before it saves a skill. Null
     * when {@code source} parses, else the parser's message.
     */
    public String checkSyntax(String source) {
        ContextFactory factory = new ScriptContextFactory() {
            @Override
            protected Context createContext() {
                return new ObservedContext(this, Long.MAX_VALUE, () -> false);
            }
        };
        Context cx = factory.enter();
        try {
            cx.compileString(source == null ? "" : source, "<skill>", 1, null);
            return null;
        } catch (RuntimeException e) {
            return (e.getMessage() != null) ? e.getMessage() : e.toString();
        }
    }

    private Outcome runOnce(String source, CallContext ctx, BooleanSupplier stopped, long start) {
        String runId = ctx.run().orElseThrow().runId();
        ContextFactory factory = new ScriptContextFactory() {
            @Override
            protected Context createContext() {
                return new ObservedContext(this, ctx.deadlineNanos(), stopped);
            }
        };
        Context cx = factory.enter();
        cx.setInstructionObserverThreshold(INSTRUCTION_THRESHOLD);

        ScriptableObject scope = cx.initStandardObjects();
        ScriptPrelude.install(cx, scope, new ScriptCalls(api, () -> ctx));

        // Evaluated directly rather than through JS eval(), so its declarations stay in this scope; the
        // value of its last expression is the result.
        Object value = null;
        Object thrown = null;
        ScriptError error = null;
        try {
            value = cx.evaluateString(scope, source, "<eval>", 1, null);
            if (value instanceof Undefined) value = null;
        } catch (Throwable t) {
            if (stopped.getAsBoolean()) {
                error = scriptError(ScriptContracts.SCRIPT_STOPPED, "script stopped", t);
            } else if (System.nanoTime() - ctx.deadlineNanos() > 0) {
                error = scriptError(
                        ScriptContracts.SCRIPT_TIMEOUT,
                        "script timeout (" + TimeUnit.NANOSECONDS.toMillis(ctx.deadlineNanos() - start) + " ms)",
                        t);
            } else {
                if (t instanceof JavaScriptException js) thrown = js.getValue();
                error = scriptError(ScriptContracts.SCRIPT_ERROR, message(t), t);
            }
        }

        // JSON.stringify turns the script's own objects and arrays into JSON without walking them in Java; a
        // DriverApiError the script let through is read the same way, so its code and issues survive. The
        // budget was the script's, and may be spent; this step gets a small one of its own, since stringify
        // still runs the script's own getters.
        ((ObservedContext) cx).finishWithin(ENVELOPE_BUDGET_MS);
        ScriptableObject.putProperty(scope, "__userResult", value == null ? null : cx.javaToJS(value, scope), cx);
        ScriptableObject.putProperty(scope, "__thrown", thrown, cx);
        JsonObject envelope;
        try {
            Object json = cx.evaluateString(
                    scope,
                    "JSON.stringify({result: (typeof __userResult === 'undefined' ? null : __userResult), log: __log, "
                            + "api: (__thrown instanceof DriverApiError) ? {code: __thrown.code, message: __thrown.message, "
                            + "issues: __thrown.issues, execution: __thrown.execution} : null});",
                    "<eval-end>",
                    1,
                    null);
            envelope = JsonParser.parseString(ScriptRuntime.toString(cx, json)).getAsJsonObject();
        } catch (Throwable t) {
            return new Outcome(
                    runId,
                    Optional.empty(),
                    Optional.of(
                            scriptError(ScriptContracts.SCRIPT_ERROR, "the result is not JSON: " + message(t), null)),
                    List.of(),
                    elapsedMs(start));
        }
        if (envelope.get("api") instanceof JsonObject api) error = apiError(api, error);
        List<String> log = new ArrayList<>();
        if (envelope.get("log") instanceof JsonArray l) for (JsonElement o : l) log.add(text(o));
        JsonElement returned = envelope.get("result");
        Optional<JsonValue> result = error != null || returned == null || returned.isJsonNull()
                ? Optional.empty()
                : Optional.of(JsonValue.of(returned));
        return new Outcome(runId, result, Optional.ofNullable(error), log, elapsedMs(start));
    }

    /** The error a Driver call reported, at the line the script let it through. */
    private static ScriptError apiError(JsonObject api, ScriptError thrown) {
        List<Issue> issues = new ArrayList<>();
        if (api.get("issues") instanceof JsonArray l) {
            for (JsonElement o : l) {
                if (o instanceof JsonObject m) {
                    issues.add(new Issue(text(m.get("path")), text(m.get("code")), text(m.get("message"))));
                }
            }
        }
        return new ScriptError(
                text(api.get("code")),
                text(api.get("message")),
                issues,
                Optional.of(text(api.get("execution"))),
                thrown == null ? Optional.empty() : thrown.line());
    }

    /** A JSON value as a script wrote it into a message: a string as itself, anything else as JSON. */
    private static String text(JsonElement v) {
        if (v == null || v.isJsonNull()) return "null";
        return v.isJsonPrimitive() ? v.getAsString() : v.toString();
    }

    private static ScriptError scriptError(String code, String message, Throwable at) {
        Optional<Integer> line =
                at instanceof RhinoException r && r.lineNumber() > 0 ? Optional.of(r.lineNumber()) : Optional.empty();
        return new ScriptError(code, message, List.of(), Optional.empty(), line);
    }

    private static String message(Throwable t) {
        if (t instanceof RhinoException r && r.details() != null) return r.details();
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    private static Outcome failed(RunContext run, String code, String message, long start) {
        return new Outcome(
                run.runId(),
                Optional.empty(),
                Optional.of(new ScriptError(code, message, List.of(), Optional.empty(), Optional.empty())),
                List.of(),
                elapsedMs(start));
    }

    /** The outcome of a run whose stage failed rather than answering one. */
    static Outcome failed(RunContext run, Throwable t) {
        String message = t == null ? "the run ended without an outcome" : message(t);
        return new Outcome(
                run.runId(),
                Optional.empty(),
                Optional.of(new ScriptError(
                        ScriptContracts.SCRIPT_ERROR, message, List.of(), Optional.empty(), Optional.empty())),
                List.of(),
                0);
    }

    private static long elapsedMs(long t0) {
        return (System.nanoTime() - t0) / 1_000_000L;
    }

    /**
     * A script context that also stops the script at the run's deadline or stop signal, checked through Rhino's
     * instruction-count hook.
     */
    private static final class ObservedContext extends ScriptContextFactory.ScriptContext {
        private long deadlineNanos;
        private BooleanSupplier stopped;

        ObservedContext(ContextFactory factory, long deadlineNanos, BooleanSupplier stopped) {
            super(factory);
            this.deadlineNanos = deadlineNanos;
            this.stopped = stopped;
        }

        /** The script is over: what runs now (encoding its result) has {@code ms} and ignores the stop signal. */
        void finishWithin(long ms) {
            deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms);
            stopped = () -> false;
        }

        @Override
        protected void observeInstructionCount(int instructionCount) {
            if (stopped.getAsBoolean()) throw new RuntimeException("script stopped");
            if (System.nanoTime() - deadlineNanos > 0) throw new RuntimeException("script timeout");
        }
    }
}
