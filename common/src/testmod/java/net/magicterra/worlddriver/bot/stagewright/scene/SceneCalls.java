package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.contract.ApiError;
import net.magicterra.worlddriver.api.contract.ApiException;
import net.magicterra.worlddriver.api.registry.ApiRegistry;

/**
 * Driver calls from a scene body, which runs on the server thread. self's verbs run on the client
 * thread, and one game thread may not wait for another, so a scene never waits on a call: {@link #ask}
 * sends it and continues the scene once the reply lands.
 *
 * <p>Calls go through the typed interfaces ({@code BotTaskApi.of(api())} and the like), which take
 * the same path a request does. The by-name forms are for the scenes whose subject is the request
 * itself: a method name, a parameter the contract refuses, a reply's JSON shape.
 */
final class SceneCalls {
    private SceneCalls() {}

    /** How long a reply may take, in server ticks. */
    private static final int REPLY_TICKS = 40;

    static ApiRegistry api() { return WorldDriverCommon.registry(); }

    /** Continues with {@code call}'s reply; a call that fails fails the scene. */
    static <R> void ask(SceneContext ctx, String tag, CompletionStage<R> call, Consumer<R> then) {
        CompletableFuture<R> reply = call.toCompletableFuture();
        ctx.await(reply::isDone).within(REPLY_TICKS).then(() -> {
            ApiError error = errorOf(reply);
            ctx.record(tag, error == null ? String.valueOf(reply.join()) : "error " + error);
            if (error != null) {
                ctx.fail(tag + ": the call failed: " + error);
                return;
            }
            then.accept(reply.join());
        });
    }

    /** Continues with how {@code call} failed; a call that succeeds fails the scene. */
    static void askFailure(SceneContext ctx, String tag, CompletionStage<?> call, Consumer<ApiError> then) {
        CompletableFuture<?> reply = call.toCompletableFuture();
        ctx.await(reply::isDone).within(REPLY_TICKS).then(() -> {
            ApiError error = errorOf(reply);
            ctx.record(tag, error == null ? String.valueOf(reply.join()) : "error " + error);
            if (error == null) {
                ctx.fail(tag + ": the call was expected to fail and answered " + reply.join());
                return;
            }
            then.accept(error);
        });
    }

    /**
     * Makes {@code call} again each time the last reply has landed, until a reply satisfies {@code until},
     * and continues with that reply; the scene fails when none does within {@code ticks}. For a state the
     * client reaches a few ticks later. A failed reply counts as not yet.
     */
    static <R> void askUntil(SceneContext ctx, String tag, Supplier<CompletionStage<R>> call,
                             Predicate<R> until, int ticks, Consumer<R> then) {
        AtomicReference<CompletableFuture<R>> inFlight = new AtomicReference<>();
        AtomicReference<R> hit = new AtomicReference<>();
        ctx.await(() -> {
            CompletableFuture<R> f = inFlight.get();
            if (f != null && !f.isDone()) return false;
            if (f != null && errorOf(f) == null && until.test(f.join())) {
                hit.set(f.join());
                return true;
            }
            inFlight.set(call.get().toCompletableFuture());
            return false;
        }).within(ticks).then(() -> {
            ctx.record(tag, String.valueOf(hit.get()));
            then.accept(hit.get());
        });
    }

    /** {@code reply}'s failure as the API reports it, or null when it succeeded. */
    private static ApiError errorOf(CompletableFuture<?> reply) {
        if (!reply.isCompletedExceptionally()) return null;
        Throwable t = reply.exceptionNow();
        ApiException a = ApiException.find(t);
        return a != null ? a.error() : ApiError.of(ApiError.Code.INTERNAL_ERROR, String.valueOf(t));
    }

    // ---------------------------------------------------------------- by name, for the request itself

    /**
     * Calls {@code method} by name and hands the reply tree to {@code then}; a call that failed reaches
     * {@code then} as {@code {error: <the exception>}}.
     */
    static void askByName(SceneContext ctx, String tag, String method, Map<String, Object> params, Consumer<Map<?, ?>> then) {
        CompletableFuture<Object> reply = send(method, params);
        ctx.await(reply::isDone).within(REPLY_TICKS).then(() -> {
            Map<?, ?> r = settled(reply);
            ctx.record(tag, String.valueOf(r));
            then.accept(r);
        });
    }

    /** {@link #askUntil} by name, for a method no typed interface binds. */
    static void askUntilByName(SceneContext ctx, String tag, String method, Map<String, Object> params,
                               Predicate<Map<?, ?>> until, int ticks, Consumer<Map<?, ?>> then) {
        AtomicReference<CompletableFuture<Object>> inFlight = new AtomicReference<>();
        AtomicReference<Map<?, ?>> hit = new AtomicReference<>();
        ctx.await(() -> {
            CompletableFuture<Object> f = inFlight.get();
            if (f != null && !f.isDone()) return false;
            if (f != null && until.test(settled(f))) {
                hit.set(settled(f));
                return true;
            }
            inFlight.set(send(method, params));
            return false;
        }).within(ticks).then(() -> {
            ctx.record(tag, String.valueOf(hit.get()));
            then.accept(hit.get());
        });
    }

    /** Sends {@code method} by name without waiting for it. */
    static CompletableFuture<Object> send(String method, Map<String, Object> params) {
        return api().dispatch(method, params, CallContext.standalone()).toCompletableFuture();
    }

    /** A landed reply as a map; a failure as {@code {error: <the exception>}}. */
    private static Map<?, ?> settled(CompletableFuture<Object> reply) {
        if (reply.isCompletedExceptionally()) {
            Throwable t = reply.exceptionNow();
            return Map.of("error", t instanceof CompletionException c && c.getCause() != null ? c.getCause() : t);
        }
        return reply.join() instanceof Map<?, ?> m ? m : Map.of();
    }
}
