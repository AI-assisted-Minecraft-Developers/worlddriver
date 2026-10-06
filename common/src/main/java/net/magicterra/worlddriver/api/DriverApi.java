package net.magicterra.worlddriver.api;

import net.magicterra.worlddriver.api.contract.ApiError;
import net.magicterra.worlddriver.api.contract.ApiException;
import net.magicterra.worlddriver.api.contract.MethodSpec;
import net.magicterra.worlddriver.model.DriverEvent;
import net.magicterra.worlddriver.protocol.JsonCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The single validated dispatch point shared by every transport.
 * The bootstrap supplies an application implementation; this package knows no
 * transport implementation or concrete business handler.
 *
 * <p>Methods live in the {@link ApiRegistry}: one entry binds a method's types to its handler.
 * {@link #routes} holds the methods not yet moved onto typed contracts; {@link #dispatch} serves
 * both, and a name can be in only one of them.
 */
public abstract class DriverApi {
    /** Methods still answered by an untyped handler; validated by the injected {@link ParamsValidator}. */
    protected final Map<String, Function<Map<String, Object>, Object>> routes = new ConcurrentHashMap<>();
    private final ApiRegistry registry = new ApiRegistry();
    private volatile ParamsValidator paramsValidator;

    public final ApiRegistry registry() { return registry; }

    /**
     * Run {@code method} for an entry point. {@code paramsTree} is the decoded JSON of the request's
     * parameters, null when it sent none. The stage completes with the encoded result tree, or
     * exceptionally with an {@link ApiException}.
     */
    public final CompletionStage<Object> dispatch(String method, Object paramsTree, CallContext context) {
        if (method != null && registry.method(method) != null) return registry.dispatch(method, paramsTree, context);
        Function<Map<String, Object>, Object> fn = method == null ? null : routes.get(method);
        if (fn == null) return registry.dispatch(method, paramsTree, context);   // reports method_not_found
        try {
            if (paramsTree != null && !(paramsTree instanceof Map<?, ?>)) {
                throw ApiException.invalidParam("", net.magicterra.worlddriver.api.contract.ApiIssue.Code.TYPE_MISMATCH,
                        "expected an object, got " + net.magicterra.worlddriver.api.contract.DecodeContext.describe(paramsTree));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> p = paramsTree == null ? Map.of() : (Map<String, Object>) paramsTree;
            ParamsValidator v = paramsValidator;
            if (v != null) v.validate(method, p);
            Object result = fn.apply(p);
            // One adaptation for every entry point, so the three stay byte-identical.
            return CompletableFuture.completedFuture(JsonCodec.decode(WireValues.encode(result)));
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(ApiRegistry.asApi(t));
        }
    }

    /**
     * Run {@code method} and wait for its result on this thread: for code inside this JVM that holds
     * a Map (wait conditions, watchers, replay). Throws the call's {@link ApiException}. On a game thread
     * a call that would wait is refused before it starts, as {@link #invokeJson} refuses it.
     */
    public final Object route(String method, Map<String, Object> params) {
        CallContext ctx = GameThreads.onGameThread() ? CallContext.standalone().onGameThread() : CallContext.standalone();
        return await(dispatch(method, params, ctx), CallContext.SERVER_CAP_MS);
    }

    /** Wait at most {@code budgetMs} for {@code stage}, unwrapping its failure into the {@link ApiException}. */
    public static <T> T await(CompletionStage<T> stage, long budgetMs) {
        try {
            return stage.toCompletableFuture().get(Math.max(1, budgetMs), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw ApiRegistry.asApi(e.getCause() == null ? e : e.getCause());
        } catch (TimeoutException e) {
            stage.toCompletableFuture().cancel(false);
            throw new ApiException(new ApiError(ApiError.Code.OUTCOME_UNKNOWN,
                    "no result within " + budgetMs + "ms; the call may still take effect", List.of(),
                    ApiError.Execution.UNKNOWN));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(new ApiError(ApiError.Code.OUTCOME_UNKNOWN,
                    "interrupted while waiting; the call may still take effect", List.of(), ApiError.Execution.UNKNOWN));
        }
    }

    /**
     * Register an additional untyped route after construction. Used by optional, strippable
     * subsystems so core never compile-depends on them; the name still needs a declared schema
     * ({@code ToolCatalog}), checked at boot by {@link #requireSchemasFor} and at dispatch by the validator.
     */
    public final void addRoute(String method, Function<Map<String, Object>, Object> handler) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(handler, "handler");
        if (registry.method(method) != null) {
            throw new IllegalArgumentException(method + " is already a typed method");
        }
        routes.put(method, handler);
    }

    /**
     * The in-JVM script entry: parameters as JSON text ({@code null} for none), answer as an envelope
     * {@code {"result":…}} or {@code {"error":ApiError}} so a script sees the error's code and issues
     * rather than a Java exception's message.
     *
     * <p>On a game thread the call may not wait: a call that would is refused before it starts,
     * because that thread is what it would be waiting for.
     */
    public final String invokeJson(String method, String paramsJson) {
        return invokeJson(method, paramsJson, CallContext.standalone());
    }

    /** {@link #invokeJson(String, String)} as part of {@code caller}: its run, its deadline, its cancellation. */
    public final String invokeJson(String method, String paramsJson, CallContext caller) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        try {
            Object params = paramsJson == null ? null : JsonCodec.decode(paramsJson);
            boolean game = GameThreads.onGameThread();
            CallContext ctx = game ? caller.onGameThread() : caller;
            CompletionStage<Object> stage = dispatch(method, params, ctx);
            CompletableFuture<Object> f = stage.toCompletableFuture();
            if (game && !f.isDone()) {
                f.cancel(false);
                throw new ApiException(new ApiError(ApiError.Code.INTERNAL_ERROR, method
                        + " did not finish synchronously on a game thread; it may still take effect", List.of(),
                        ApiError.Execution.UNKNOWN));
            }
            envelope.put("result", await(f, Math.max(1, ctx.remainingMillis())));
        } catch (JsonCodec.JsonSyntaxException bad) {
            envelope.put("error", ApiErrors.toTree(ApiError.of(ApiError.Code.INVALID_PARAMS, "params are not JSON: " + bad.getMessage())));
        } catch (Throwable t) {
            envelope.put("error", ApiErrors.toTree(ApiRegistry.asApi(t).error()));
        }
        return WireValues.encode(envelope);
    }

    /** Every callable method name: typed ones first, in registration order, then the untyped ones. */
    public final Set<String> methods() {
        Set<String> out = new LinkedHashSet<>(registry.snapshot().names());
        out.addAll(new TreeSet<>(routes.keySet()));
        return java.util.Collections.unmodifiableSet(out);
    }

    /** The spec of a typed method, or null for an untyped or unknown one. */
    public final MethodSpec<?, ?> spec(String method) {
        ApiMethod<?, ?> m = registry.method(method);
        return m == null ? null : m.spec();
    }

    /**
     * Boot-time invariant: every untyped route MUST have a declared MCP {@code ToolSchema}; typed
     * methods carry their own. The declared names come in from the bootstrap so this layer does not
     * import the MCP catalog.
     *
     * @throws IllegalStateException if any route lacks a declared schema
     */
    public final void requireSchemasFor(Set<String> declaredToolNames) {
        Set<String> missing = new TreeSet<>(routes.keySet());
        missing.removeAll(declaredToolNames);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                "worlddriver: " + missing.size() + " route(s) have no MCP ToolSchema — declare each "
                + "in ToolCatalog (a normal schema, or a hidden one for RPC-only verbs): " + missing);
        }
    }

    /** Pre-dispatch validation of the untyped routes, injected by the bootstrap from the MCP catalog. */
    public final void setParamsValidator(ParamsValidator validator) {
        this.paramsValidator = validator;
    }

    /** Untyped route names, for the catalog and the boot invariant. */
    public final List<String> untypedMethods() { return new ArrayList<>(new TreeSet<>(routes.keySet())); }

    public abstract void attachServer(MinecraftServer server);
    public abstract void detachServer();
    public abstract void clearEvents();
    public abstract void emitExternal(String type, BlockPos pos, Object data);
    public abstract void addEventListener(Consumer<DriverEvent> listener);
    public abstract void removeEventListener(Consumer<DriverEvent> listener);
    public abstract int restoreCellsOnServer(List<WorldCell> cells);
    public abstract Object query(QueryParams params);
}
