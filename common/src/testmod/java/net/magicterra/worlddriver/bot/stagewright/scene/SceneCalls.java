package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

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
        CompletableFuture<Object> reply = WorldDriverCommon.api().dispatch(method, params, CallContext.standalone()).toCompletableFuture();
        ctx.await(reply::isDone).within(REPLY_TICKS).then(() -> {
            Object r;
            if (reply.isCompletedExceptionally()) {
                Throwable t = reply.exceptionNow();
                r = Map.of("error", t instanceof CompletionException c && c.getCause() != null ? c.getCause() : t);
            } else {
                r = reply.join();
            }
            ctx.record(tag, String.valueOf(r));
            then.accept(r instanceof Map<?, ?> m ? m : Map.of());
        });
    }
}
