package net.magicterra.worlddriver.api;

import net.magicterra.worlddriver.api.contract.ApiError;
import net.magicterra.worlddriver.api.contract.ApiException;
import net.magicterra.worlddriver.api.contract.JsonValue;
import net.magicterra.worlddriver.api.contract.MethodSpec;
import net.magicterra.worlddriver.model.DriverEvent;
import net.magicterra.worlddriver.protocol.JsonCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * The single validated dispatch point shared by every transport.
 * The bootstrap supplies an application implementation; this package knows no
 * transport implementation or concrete business handler.
 *
 * <p>Every method lives in the {@link ApiRegistry}: one entry binds a method's typed contract to its
 * handler, and owners publish theirs as {@link ApiBatch}es under the registry's namespace policy (the
 * core's {@code mc.*}, StageWright's {@code mc.test.*}, a mod's {@code <modid>.*}). A hidden spec is left
 * out of the MCP tool list but stays callable by name on every transport.
 */
public abstract class DriverApi {
    private final ApiRegistry registry = new ApiRegistry();

    public final ApiRegistry registry() { return registry; }

    /**
     * Run {@code method} for an entry point. {@code paramsTree} is the decoded JSON of the request's
     * parameters, null when it sent none. The stage completes with the encoded result tree, or
     * exceptionally with an {@link ApiException}.
     */
    public final CompletionStage<Object> dispatch(String method, Object paramsTree, CallContext context) {
        return registry.dispatch(method, paramsTree, context);
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

    /** Every callable method name, in registration order. */
    public final Set<String> methods() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(registry.snapshot().names()));
    }

    /** The spec of {@code method}, or null for an unknown one. */
    public final MethodSpec<?, ?> spec(String method) {
        ApiMethod<?, ?> m = registry.method(method);
        return m == null ? null : m.spec();
    }

    public abstract void attachServer(MinecraftServer server);
    public abstract void detachServer();
    public abstract void clearEvents();
    /** Append an event; {@code data} is its payload as a value tree, {@link JsonValue#NULL} for none. */
    public abstract void emitExternal(String type, BlockPos pos, JsonValue data);
    public abstract void addEventListener(Consumer<DriverEvent> listener);
    public abstract void removeEventListener(Consumer<DriverEvent> listener);
    public abstract int restoreCellsOnServer(List<WorldCell> cells);
}
