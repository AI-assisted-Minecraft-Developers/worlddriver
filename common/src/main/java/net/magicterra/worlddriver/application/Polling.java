package net.magicterra.worlddriver.application;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.magicterra.worlddriver.api.call.ApiTimers;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.call.CancellationToken;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.DecodeContext;
import net.magicterra.worlddriver.api.schema.JsonValue;
import net.magicterra.worlddriver.api.schema.MethodSpec;

/**
 * Repeated reads for the waits and the event watchers. A wait between two polls holds no thread: the timer hands each
 * poll to a small pool, and the poll gives its thread back as soon as its read answers. Only methods described as
 * {@link MethodSpec#pollable() pollable} may be polled, which keeps a write from being repeated and a wait or script
 * from being nested inside one.
 */
final class Polling {

    /** Every poll is a short read; a slow one queues the next rather than starting another thread. */
    private static final ExecutorService POLLS = new ThreadPoolExecutor(
            4,
            4,
            30,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            Thread.ofPlatform().name("worlddriver-poll").daemon().factory());

    static {
        ((ThreadPoolExecutor) POLLS).allowCoreThreadTimeOut(true);
    }

    private Polling() {}

    /** A pollable method and the params it was checked with. */
    record Target(String method, JsonElement params) {
        /** One read, given up after {@code budgetMs}; a pollable read has no effect to leave half done. */
        CompletionStage<JsonElement> read(ApiRegistry api, long budgetMs) {
            CallContext ctx = CallContext.withBudget(budgetMs, CancellationToken.NEVER, CallContext.SHARED_WAITS);
            return api.dispatch(method, params, ctx).toCompletableFuture().orTimeout(budgetMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * {@code invoke} with {@code params}, checked before anything starts: the method must be pollable (reported at
     * {@code /invoke}) and the params must satisfy its own contract (at {@code /params/…}).
     */
    static Target target(ApiRegistry api, String invoke, Optional<JsonValue> params) {
        MethodSpec<?, ?> spec = api.spec(invoke);
        if (spec == null || !spec.pollable()) {
            String why = spec != null
                    ? invoke + " is not pollable: it writes, waits or runs a script"
                    : api.methods().contains(invoke)
                            ? invoke + " is not pollable: it has no typed contract yet"
                            : "unknown method " + invoke;
            throw ApiException.invalidParam(
                    "/invoke",
                    ApiIssue.Code.INVALID_VALUE,
                    why + "; only a bounded read such as mc.bot.status or mc.observe.cursor may be polled");
        }
        JsonElement tree = params.map(JsonValue::json).orElse(new JsonObject());
        DecodeContext at = DecodeContext.root().field("params");
        spec.input().decode(tree, at);
        if (!at.issues().isEmpty()) {
            throw ApiException.invalidParams(at.issues());
        }
        return new Target(invoke, tree);
    }

    /** {@code path} walked into a result, as JSON; empty when it leads nowhere. */
    static Optional<JsonValue> field(JsonElement result, Optional<String> path) {
        JsonElement v = ApiSupport.walkPath(result, path.orElse(null));
        return v == null ? Optional.empty() : Optional.of(JsonValue.of(v));
    }

    static void execute(Runnable task) {
        POLLS.execute(task);
    }

    /**
     * Probe now and then every {@code pollMs} until it answers, or until {@code budgetMs} has passed, when
     * {@code timedOut} answers instead; a cancelled request ends it the same way. A probe that fails ends the wait with
     * its error.
     */
    static <R> CompletableFuture<R> until(
            long budgetMs,
            long pollMs,
            CancellationToken cancelled,
            Supplier<CompletionStage<Optional<R>>> probe,
            Supplier<R> timedOut) {
        Loop<R> loop = new Loop<>(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs), pollMs, probe, timedOut);
        AutoCloseable gone = cancelled.onCancel(loop::giveUp);
        loop.out.whenComplete((r, t) -> {
            ScheduledFuture<?> timer = loop.timer;
            if (timer != null) {
                timer.cancel(false);
            }
            try {
                gone.close();
            } catch (Exception ignored) {
                /* nothing to release */
            }
        });
        execute(loop::attempt);
        return loop.out;
    }

    private static final class Loop<R> {
        final CompletableFuture<R> out = new CompletableFuture<>();

        final long deadlineNanos;

        final long pollMs;

        final Supplier<CompletionStage<Optional<R>>> probe;

        final Supplier<R> timedOut;

        volatile ScheduledFuture<?> timer;

        Loop(long deadlineNanos, long pollMs, Supplier<CompletionStage<Optional<R>>> probe, Supplier<R> timedOut) {
            this.deadlineNanos = deadlineNanos;
            this.pollMs = pollMs;
            this.probe = probe;
            this.timedOut = timedOut;
        }

        void giveUp() {
            if (!out.isDone()) {
                out.complete(timedOut.get());
            }
        }

        void attempt() {
            if (out.isDone()) {
                return;
            }
            CompletionStage<Optional<R>> stage;
            try {
                stage = probe.get();
            } catch (Throwable t) {
                out.completeExceptionally(t);
                return;
            }
            stage.whenComplete((answer, failure) -> {
                if (failure != null) {
                    out.completeExceptionally(failure);
                } else if (answer.isPresent()) {
                    out.complete(answer.get());
                } else {
                    long leftMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
                    if (leftMs <= 0) {
                        giveUp();
                    } else if (!out.isDone()) {
                        timer = ApiTimers.schedule(() -> execute(this::attempt), Math.min(pollMs, leftMs));
                    }
                }
            });
        }
    }
}
