package net.magicterra.worlddriver.api.registry;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.thread.GameThreads;

/**
 * Every callable method, as one immutable {@link Snapshot} replaced whole by each published batch: a reader sees a
 * batch entirely or not at all, and the name, types, handler and owner of a method always come from the same entry. It
 * is the single validated dispatch point every transport shares, and knows no transport implementation or concrete
 * business handler.
 *
 * <p>Namespace policy: {@code mc.*} belongs to the {@code worlddriver} owner, except {@code mc.test.*}, which belongs
 * to {@code stagewright}; anyone else registers {@code <modid>.<verb>} outside {@code mc.}. A name taken by another
 * batch is a conflict for the whole new batch: nobody wins by registering last. A hidden spec is left out of the MCP
 * tool list but stays callable by name on every transport.
 */
public final class ApiRegistry {
    public static final String CORE_OWNER = "worlddriver";

    public static final String TEST_OWNER = "stagewright";

    /**
     * Encoding a result that a game thread completed must not run on that game thread: an image or a long list is
     * encoded here instead. Bounded; the work per task is one encode.
     */
    private static final ExecutorService ENCODE = new ThreadPoolExecutor(
            1,
            4,
            30,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            Thread.ofPlatform().name("worlddriver-encode").daemon().factory());

    private final Published state;

    /** What {@link #withThreadWait} gave this view, or 0 for the registry itself: each hop's own limit. */
    private final long threadWaitMs;

    /** One published state: methods by name in registration order, the batches behind them, and a version. */
    public record Snapshot(
            long version,
            Map<String, ApiMethod<?, ?>> methods,
            Map<String, ApiBatch> batches,
            Map<String, String> ownerOf) {
        /** The method names, in registration order; an immutable copy, not a view of later states. */
        public List<String> names() {
            return List.copyOf(methods.keySet());
        }
    }

    /** What is published, shared by a registry and every {@link #withThreadWait} view of it. */
    private static final class Published {
        volatile Snapshot snapshot = new Snapshot(0, Map.of(), Map.of(), Map.of());

        final List<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<>();
    }

    /** An empty registry, with no methods published yet. */
    public ApiRegistry() {
        this(new Published(), 0);
    }

    private ApiRegistry(Published state, long threadWaitMs) {
        this.state = state;
        this.threadWaitMs = threadWaitMs;
    }

    /**
     * The same methods, for a caller inside this JVM that will wait up to {@code ms} for a game thread to start each
     * call's task, where a call otherwise gives up after that thread's own limit (8 s for the server thread) and
     * answers {@code not_executed}. Every call through the view, a {@link #route}, a {@link #dispatch} or a generated
     * caller ({@code XxxBlockingApi.of(view)}), gets a context carrying {@link CallContext#withThreadWait} unless its
     * context already names a wait; the call's deadline still bounds it. Publishing and listening go to the registry
     * itself. The transports never use a view, so a client on the wire keeps the thread's own limit.
     *
     * @param ms at least 1; {@link CallContext#SERVER_CAP_MS} at most is honoured
     */
    public ApiRegistry withThreadWait(long ms) {
        if (ms < 1) {
            throw new IllegalArgumentException("a thread wait is at least 1 ms, got " + ms);
        }
        return new ApiRegistry(state, ms);
    }

    /** {@code ctx} with this view's thread wait, unless it already names one. */
    private CallContext adjust(CallContext ctx) {
        return threadWaitMs > 0 && ctx.threadWaitMs() == 0 ? ctx.withThreadWait(threadWaitMs) : ctx;
    }

    /** The published state now; a caller that keeps this object reads one batch-consistent view of it. */
    public Snapshot snapshot() {
        return state.snapshot;
    }

    /** Told after every publish, on the publishing thread, with the new snapshot. */
    public void addListener(Consumer<Snapshot> listener) {
        state.listeners.add(Objects.requireNonNull(listener));
    }

    /**
     * Check the whole batch, then publish it in one step. Returns false when this very batch object is already
     * published (a retry), true when it was published now.
     *
     * @throws IllegalArgumentException on a namespace violation, a duplicate inside the batch, a name another batch
     *     holds, or a different batch under the same owner and id
     */
    public boolean registerBatch(ApiBatch batch) {
        Objects.requireNonNull(batch, "batch");
        Snapshot published;
        synchronized (state) {
            Snapshot cur = state.snapshot;
            String key = batch.owner() + "/" + batch.id();
            ApiBatch existing = cur.batches().get(key);
            if (existing == batch) {
                return false;
            }
            if (existing != null) {
                throw new IllegalArgumentException("batch " + key + " is already published with different content; "
                        + "retry the batch object built at initialisation, or publish under a new id");
            }
            Map<String, ApiMethod<?, ?>> methods = new LinkedHashMap<>(cur.methods());
            Map<String, String> ownerOf = new LinkedHashMap<>(cur.ownerOf());
            List<String> problems = new ArrayList<>();
            List<String> seen = new ArrayList<>();
            for (ApiMethod<?, ?> m : batch.methods()) {
                String name = m.name();
                String policy = namespaceProblem(batch.owner(), name);
                if (policy != null) {
                    problems.add(policy);
                }
                if (seen.contains(name)) {
                    problems.add(name + " appears twice in the batch");
                }
                seen.add(name);
                if (methods.containsKey(name)) {
                    problems.add(name + " is already registered by " + ownerOf.get(name));
                }
            }
            if (!problems.isEmpty()) {
                throw new IllegalArgumentException(
                        "batch " + key + " rejected, nothing published: " + String.join("; ", problems));
            }
            for (ApiMethod<?, ?> m : batch.methods()) {
                methods.put(m.name(), m);
                ownerOf.put(m.name(), key);
            }
            Map<String, ApiBatch> batches = new LinkedHashMap<>(cur.batches());
            batches.put(key, batch);
            published = new Snapshot(
                    cur.version() + 1,
                    Collections.unmodifiableMap(methods),
                    Collections.unmodifiableMap(batches),
                    Collections.unmodifiableMap(ownerOf));
            state.snapshot = published;
        }
        for (Consumer<Snapshot> l : state.listeners) {
            try {
                l.accept(published);
            } catch (RuntimeException ignored) {
                /* a listener cannot unpublish */
            }
        }
        return true;
    }

    private static String namespaceProblem(String owner, String name) {
        boolean mc = name.startsWith("mc.");
        boolean test = name.startsWith("mc.test.");
        if (CORE_OWNER.equals(owner)) {
            return mc && !test ? null : name + ": the core registers mc.* outside mc.test.*";
        }
        if (TEST_OWNER.equals(owner)) {
            return test ? null : name + ": stagewright registers mc.test.* only";
        }
        if (mc) {
            return name + ": mc.* is reserved for worlddriver (mc.test.* for stagewright); use <modid>.<verb>";
        }
        return null;
    }

    /** The method called {@code name}, or null. */
    public ApiMethod<?, ?> method(String name) {
        return name == null ? null : state.snapshot.methods().get(name);
    }

    /** Every callable method name, in registration order. */
    public Set<String> methods() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(state.snapshot.names()));
    }

    /** The spec of {@code method}, or null for an unknown one. */
    public MethodSpec<?, ?> spec(String method) {
        ApiMethod<?, ?> m = method(method);
        return m == null ? null : m.spec();
    }

    /**
     * Decode {@code paramsTree} with the method's input type, run its handler, and encode the result with its output
     * type. {@code paramsTree} null means the caller sent no parameters, which reads as an empty object; anything else
     * that is not an object is an error, never an empty object. Every failure arrives as an exceptionally completed
     * stage carrying an {@link ApiException}.
     */
    public CompletionStage<JsonElement> dispatch(String method, JsonElement paramsTree, CallContext context) {
        ApiMethod<?, ?> m = method(method);
        if (m == null) {
            return CompletableFuture.failedFuture(ApiException.of(
                    ApiError.Code.METHOD_NOT_FOUND,
                    method == null ? "the request named no method" : "unknown method: " + method));
        }
        return run(m, paramsTree == null ? new JsonObject() : paramsTree, adjust(context));
    }

    private static <P, R> CompletionStage<JsonElement> run(ApiMethod<P, R> m, JsonElement tree, CallContext context) {
        MethodSpec<P, R> spec = m.spec();
        CompletionStage<R> stage;
        try {
            P params = spec.input().decode(tree);
            stage = m.handler().execute(params, context);
            if (stage == null) {
                throw new IllegalStateException(spec.name() + " returned no stage");
            }
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(asApi(t));
        }
        CompletableFuture<R> f = stage.toCompletableFuture();
        // Already done: encode here, on the caller's thread. Not yet: whoever completes it may be a
        // game thread, which must not pay for the encoding.
        if (f.isDone()) {
            return f.handle((r, t) -> finish(spec, r, t));
        }
        return f.handleAsync((r, t) -> finish(spec, r, t), ENCODE);
    }

    private static <R> JsonElement finish(MethodSpec<?, R> spec, R result, Throwable failure) {
        if (failure != null) {
            throw asApi(failure);
        }
        try {
            return spec.output().encode(result);
        } catch (RuntimeException broken) {
            throw new ApiException(
                    new ApiError(
                            ApiError.Code.INTERNAL_ERROR,
                            spec.name() + " produced a result outside its contract: " + broken.getMessage(),
                            List.of(),
                            ApiError.Execution.UNKNOWN),
                    broken);
        }
    }

    /**
     * Run {@code method} and wait for its result on this thread: for code inside this JVM that holds the parameters as
     * JSON rather than as the method's types. Throws the call's {@link ApiException}. On a game thread a call that
     * would wait is refused before it starts.
     */
    public JsonElement route(String method, JsonObject params) {
        CallContext ctx =
                GameThreads.onGameThread() ? CallContext.standalone().onGameThread() : CallContext.standalone();
        return await(dispatch(method, params, ctx), CallContext.SERVER_CAP_MS);
    }

    /** Wait at most {@code budgetMs} for {@code stage}, unwrapping its failure into the {@link ApiException}. */
    public static <T> T await(CompletionStage<T> stage, long budgetMs) {
        try {
            return stage.toCompletableFuture().get(Math.max(1, budgetMs), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw asApi(e);
        } catch (TimeoutException e) {
            stage.toCompletableFuture().cancel(false);
            throw new ApiException(
                    new ApiError(
                            ApiError.Code.OUTCOME_UNKNOWN,
                            "no result within " + budgetMs + "ms; the call may still take effect",
                            List.of(),
                            ApiError.Execution.UNKNOWN),
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(
                    new ApiError(
                            ApiError.Code.OUTCOME_UNKNOWN,
                            "interrupted while waiting; the call may still take effect",
                            List.of(),
                            ApiError.Execution.UNKNOWN),
                    e);
        }
    }

    /** {@code t} as an {@link ApiException}, translated once by {@link ApiErrors}. */
    public static ApiException asApi(Throwable t) {
        Throwable c = ApiErrors.unwrap(t);
        if (c instanceof ApiException a) {
            return a;
        }
        return new ApiException(ApiErrors.from(c), c);
    }

    /**
     * A Java caller's typed call, which every generated {@link Spec} binding makes: the parameters get the same checks
     * a request does, and the result is not encoded. The caller's context is passed through as {@link #dispatch} passes
     * it: the call answers a stage rather than holding its thread, so a game thread may make it, and a hop to the other
     * game thread is queued, not refused.
     */
    @SuppressWarnings("unchecked")
    public <P, R> CompletionStage<R> invoke(MethodSpec<P, R> spec, P params, CallContext context) {
        ApiMethod<?, ?> m = method(spec.name());
        if (m == null || m.spec() != spec) {
            return CompletableFuture.failedFuture(
                    ApiException.of(ApiError.Code.METHOD_NOT_FOUND, spec.name() + " is not registered with this spec"));
        }
        try {
            P checked = spec.input().validate(params);
            CompletionStage<R> stage = ((ApiMethod<P, R>) m).handler().execute(checked, adjust(context));
            if (stage == null) {
                throw new IllegalStateException(spec.name() + " returned no stage");
            }
            return stage;
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(asApi(t));
        }
    }
}
