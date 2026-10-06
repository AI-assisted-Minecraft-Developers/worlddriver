package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.api.CallContext;

/**
 * Driver calls from a scene body, which runs on the server thread. self's verbs run on the client
 * thread, and one game thread may not wait for another, so {@code DriverApi.route} refuses them there;
 * {@link #ask} sends the call and continues the scene once the reply lands.
 */
final class SceneCalls {
    private SceneCalls() {}

    /** How long a reply may take, in server ticks. */
    private static final int REPLY_TICKS = 40;

    /**
     * Calls {@code method} through the door every transport shares and hands the reply to {@code then}.
     * A call that failed reaches {@code then} as {@code {error: <the exception>}}.
     */
    static void ask(SceneContext ctx, String tag, String method, Map<String, Object> params, Consumer<Map<?, ?>> then) {
        CompletableFuture<Object> reply = send(method, params);
        ctx.await(reply::isDone).within(REPLY_TICKS).then(() -> {
            Map<?, ?> r = settled(reply);
            ctx.record(tag, String.valueOf(r));
            then.accept(r);
        });
    }

    /**
     * Asks {@code method} again each time the last reply has landed, until a reply satisfies
     * {@code until}, and hands that reply to {@code then}; the scene fails when none does within
     * {@code ticks}. The polling form of {@link #ask}, for a state the client reaches a few ticks later.
     */
    static void askUntil(SceneContext ctx, String tag, String method, Map<String, Object> params,
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

    /** Sends {@code method} without waiting for it: for a cleanup, which may not wait on the client. */
    static CompletableFuture<Object> send(String method, Map<String, Object> params) {
        return WorldDriverCommon.api().dispatch(method, params, CallContext.standalone()).toCompletableFuture();
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
