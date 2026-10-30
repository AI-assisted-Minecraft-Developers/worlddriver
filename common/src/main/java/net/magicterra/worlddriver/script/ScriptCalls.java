package net.magicterra.worlddriver.script;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.registry.ApiErrors;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.thread.GameThreads;

/**
 * The {@code __api} global behind {@code Driver.invoke}: every call a scope makes goes out as part of the context it
 * was given, so a script run's calls carry its run, its deadline and its cancellation.
 */
public final class ScriptCalls {
    private final ApiRegistry api;
    private final Supplier<CallContext> context;

    ScriptCalls(ApiRegistry api, Supplier<CallContext> context) {
        this.api = api;
        this.context = context;
    }

    /** Calls standing on their own, each with the server's full budget: the validation harness's and StageWright's. */
    public static ScriptCalls standalone(ApiRegistry api) {
        return new ScriptCalls(api, CallContext::standalone);
    }

    /**
     * Parameters as JSON text ({@code null} for none), answer as an envelope {@code {"result":…}} or
     * {@code {"error":ApiError}} so a script sees the error's code and issues rather than a Java exception's message.
     *
     * <p>On a game thread the call may not wait: a call that would is refused before it starts, because that thread is
     * what it would be waiting for.
     */
    public String invokeJson(String method, String paramsJson) {
        JsonObject envelope = new JsonObject();
        try {
            JsonElement params = paramsJson == null ? null : JsonParser.parseString(paramsJson);
            boolean game = GameThreads.onGameThread();
            CallContext caller = context.get();
            CallContext ctx = game ? caller.onGameThread() : caller;
            CompletableFuture<JsonElement> f = api.dispatch(method, params, ctx).toCompletableFuture();
            if (game && !f.isDone()) {
                f.cancel(false);
                throw new ApiException(new ApiError(
                        ApiError.Code.INTERNAL_ERROR,
                        method + " did not finish synchronously on a game thread; it may still take effect",
                        List.of(),
                        ApiError.Execution.UNKNOWN));
            }
            envelope.add("result", ApiRegistry.await(f, Math.max(1, ctx.remainingMillis())));
        } catch (JsonParseException bad) {
            envelope.add(
                    "error",
                    ApiErrors.toTree(
                            ApiError.of(ApiError.Code.INVALID_PARAMS, "params are not JSON: " + bad.getMessage())));
        } catch (Throwable t) {
            envelope.add("error", ApiErrors.toTree(ApiRegistry.asApi(t).error()));
        }
        return envelope.toString();
    }
}
