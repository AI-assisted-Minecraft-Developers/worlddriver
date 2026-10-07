package net.magicterra.worlddriver.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import net.magicterra.worlddriver.api.contract.ApiError;
import net.magicterra.worlddriver.api.contract.ApiException;
import net.magicterra.worlddriver.api.contract.MethodSpec;

/**
 * Every callable method, as one immutable {@link Snapshot} replaced whole by each published batch:
 * a reader sees a batch entirely or not at all, and the name, types, handler and owner of a method
 * always come from the same entry.
 *
 * <p>Namespace policy: {@code mc.*} belongs to the {@code worlddriver} owner, except {@code mc.test.*},
 * which belongs to {@code stagewright}; anyone else registers {@code <modid>.<verb>} outside {@code mc.}.
 * A name taken by another batch is a conflict for the whole new batch: nobody wins by registering last.
 */
public final class ApiRegistry {
    public static final String CORE_OWNER = "worlddriver";
    public static final String TEST_OWNER = "stagewright";

    /** One published state: methods by name in registration order, the batches behind them, and a version. */
    public record Snapshot(long version, Map<String, ApiMethod<?, ?>> methods, Map<String, ApiBatch> batches,
                           Map<String, String> ownerOf) {
        /** The method names, in registration order; an immutable copy, not a view of later states. */
        public List<String> names() { return List.copyOf(methods.keySet()); }
    }

    private volatile Snapshot snapshot = new Snapshot(0, Map.of(), Map.of(), Map.of());
    private final List<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<>();

    /**
     * Encoding a result that a game thread completed must not run on that game thread: an image or a
     * long list is encoded here instead. Bounded; the work per task is one encode.
     */
    private static final ExecutorService ENCODE = new ThreadPoolExecutor(1, 4, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), Thread.ofPlatform().name("worlddriver-encode").daemon().factory());

    public Snapshot snapshot() { return snapshot; }

    /** Told after every publish, on the publishing thread, with the new snapshot. */
    public void addListener(Consumer<Snapshot> listener) { listeners.add(Objects.requireNonNull(listener)); }

    /**
     * Check the whole batch, then publish it in one step. Returns false when this very batch object is
     * already published (a retry), true when it was published now.
     *
     * @throws IllegalArgumentException on a namespace violation, a duplicate inside the batch, a name
     *                                  another batch holds, or a different batch under the same owner and id
     */
    public boolean registerBatch(ApiBatch batch) {
        Objects.requireNonNull(batch, "batch");
        Snapshot published;
        synchronized (this) {
            Snapshot cur = snapshot;
            String key = batch.owner() + "/" + batch.id();
            ApiBatch existing = cur.batches().get(key);
            if (existing == batch) return false;
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
                if (policy != null) problems.add(policy);
                if (seen.contains(name)) problems.add(name + " appears twice in the batch");
                seen.add(name);
                if (methods.containsKey(name)) problems.add(name + " is already registered by " + ownerOf.get(name));
            }
            if (!problems.isEmpty()) {
                throw new IllegalArgumentException("batch " + key + " rejected, nothing published: " + String.join("; ", problems));
            }
            for (ApiMethod<?, ?> m : batch.methods()) {
                methods.put(m.name(), m);
                ownerOf.put(m.name(), key);
            }
            Map<String, ApiBatch> batches = new LinkedHashMap<>(cur.batches());
            batches.put(key, batch);
            published = new Snapshot(cur.version() + 1, Collections.unmodifiableMap(methods),
                    Collections.unmodifiableMap(batches), Collections.unmodifiableMap(ownerOf));
            snapshot = published;
        }
        for (Consumer<Snapshot> l : listeners) {
            try { l.accept(published); } catch (RuntimeException ignored) { /* a listener cannot unpublish */ }
        }
        return true;
    }

    private static String namespaceProblem(String owner, String name) {
        boolean mc = name.startsWith("mc.");
        boolean test = name.startsWith("mc.test.");
        if (CORE_OWNER.equals(owner)) return mc && !test ? null : name + ": the core registers mc.* outside mc.test.*";
        if (TEST_OWNER.equals(owner)) return test ? null : name + ": stagewright registers mc.test.* only";
        if (mc) return name + ": mc.* is reserved for worlddriver (mc.test.* for stagewright); use <modid>.<verb>";
        return null;
    }

    /** The method called {@code name}, or null. */
    public ApiMethod<?, ?> method(String name) {
        return name == null ? null : snapshot.methods().get(name);
    }

    /**
     * Decode {@code paramsTree} with the method's input type, run its handler, and encode the result
     * with its output type. {@code paramsTree} null means the caller sent no parameters, which reads as
     * an empty object; anything else that is not an object is an error, never an empty object.
     * Every failure arrives as an exceptionally completed stage carrying an {@link ApiException}.
     */
    public CompletionStage<Object> dispatch(String method, Object paramsTree, CallContext context) {
        ApiMethod<?, ?> m = method(method);
        if (m == null) {
            return CompletableFuture.failedFuture(ApiException.of(ApiError.Code.METHOD_NOT_FOUND,
                    method == null ? "the request named no method" : "unknown method: " + method));
        }
        return run(m, paramsTree == null ? Map.of() : paramsTree, context);
    }

    private static <P, R> CompletionStage<Object> run(ApiMethod<P, R> m, Object tree, CallContext context) {
        MethodSpec<P, R> spec = m.spec();
        CompletionStage<R> stage;
        try {
            P params = spec.input().decode(tree);
            stage = m.handler().execute(params, context);
            if (stage == null) throw new IllegalStateException(spec.name() + " returned no stage");
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(asApi(t));
        }
        CompletableFuture<R> f = stage.toCompletableFuture();
        // Already done: encode here, on the caller's thread. Not yet: whoever completes it may be a
        // game thread, which must not pay for the encoding.
        if (f.isDone()) return f.handle((r, t) -> finish(spec, r, t));
        return f.handleAsync((r, t) -> finish(spec, r, t), ENCODE);
    }

    private static <R> Object finish(MethodSpec<?, R> spec, R result, Throwable failure) {
        if (failure != null) throw asApi(failure);
        try {
            return spec.output().encode(result);
        } catch (RuntimeException broken) {
            throw new ApiException(new ApiError(ApiError.Code.INTERNAL_ERROR,
                    spec.name() + " produced a result outside its contract: " + broken.getMessage(), List.of(),
                    ApiError.Execution.UNKNOWN), broken);
        }
    }

    /** {@code t} as an {@link ApiException}, translated once by {@link ApiErrors}. */
    public static ApiException asApi(Throwable t) {
        Throwable c = ApiErrors.unwrap(t);
        if (c instanceof ApiException a) return a;
        return new ApiException(ApiErrors.from(c), c);
    }

    /**
     * A Java caller's typed call, which every generated {@link Api} binding makes: the parameters get
     * the same checks a request does, and the result is not encoded. The caller's context is passed
     * through as {@link #dispatch} passes it: the call answers a stage rather than holding its thread,
     * so a game thread may make it, and a hop to the other game thread is queued, not refused.
     */
    @SuppressWarnings("unchecked")
    public <P, R> CompletionStage<R> invoke(MethodSpec<P, R> spec, P params, CallContext context) {
        ApiMethod<?, ?> m = method(spec.name());
        if (m == null || m.spec() != spec) {
            return CompletableFuture.failedFuture(ApiException.of(ApiError.Code.METHOD_NOT_FOUND,
                    spec.name() + " is not registered with this spec"));
        }
        try {
            P checked = spec.input().validate(params);
            CompletionStage<R> stage = ((ApiMethod<P, R>) m).handler().execute(checked, context);
            if (stage == null) throw new IllegalStateException(spec.name() + " returned no stage");
            return stage;
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(asApi(t));
        }
    }
}
