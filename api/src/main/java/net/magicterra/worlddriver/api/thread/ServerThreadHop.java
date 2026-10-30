package net.magicterra.worlddriver.api.thread;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.magicterra.worlddriver.api.call.ApiTimers;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;

/**
 * Runs a task on the server thread and waits a bounded time for it. The client-thread hop ({@code ClientHop}) is the
 * same state machine over the client's executor.
 *
 * <p>A wait that runs out cannot simply report failure: the task is still queued, and a caller told "failed" who
 * retries would have it applied twice once the tick frees up. So each call races the server thread for the task. The
 * server thread moves it QUEUED to RUNNING before running it; a waiter that times out moves it QUEUED to ABANDONED.
 * Exactly one side wins, which turns the timeout into one of two definite answers: {@link NotExecutedException} (the
 * task was withdrawn and will never run, a retry is safe) or {@link OutcomeUnknownException} (it is already running and
 * cannot be withdrawn, observe before retrying).
 */
public final class ServerThreadHop {
    private static final int QUEUED = 0;
    private static final int RUNNING = 1;
    private static final int ABANDONED = 2;

    private final Executor executor;
    private final BooleanSupplier onTargetThread;
    private final long timeoutMs;
    private final String threadName;

    public ServerThreadHop(Executor executor, BooleanSupplier onTargetThread, long timeoutMs) {
        this(executor, onTargetThread, timeoutMs, "server thread");
    }

    /** A hop onto another game thread; {@code threadName} ("client thread") is what the errors call it. */
    public ServerThreadHop(Executor executor, BooleanSupplier onTargetThread, long timeoutMs, String threadName) {
        this.executor = executor;
        this.onTargetThread = onTargetThread;
        this.timeoutMs = timeoutMs;
        this.threadName = threadName;
    }

    public <T> T call(Supplier<T> task) {
        if (onTargetThread.getAsBoolean()) {
            return task.get();
        }
        AtomicInteger state = new AtomicInteger(QUEUED);
        CompletableFuture<T> f = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                if (!state.compareAndSet(QUEUED, RUNNING)) {
                    return;
                }
                try {
                    f.complete(task.get());
                } catch (Throwable e) {
                    f.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            throw new NotExecutedException(
                    threadName + " refused the task (" + e.getMessage() + "); it will not run and is safe to retry");
        }
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return giveUp(state, f, "within " + timeoutMs + "ms (busy, loading or paused)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return giveUp(state, f, "before the waiting thread was interrupted");
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
    }

    /**
     * {@link #call} without holding the calling thread: the task is queued and the returned stage completes when it
     * ran, or with the same withdraw-or-unknown answer when the hop timeout or {@code ctx}'s deadline comes first. On
     * the target thread the task runs at once. On the OTHER game thread nothing is queued and
     * {@code capability_unavailable} is thrown: that thread could only consume a result that was already there, and a
     * task queued for it would run after its caller had been told it failed.
     */
    public <T> CompletableFuture<T> submit(Supplier<T> task, CallContext ctx) {
        if (onTargetThread.getAsBoolean()) {
            try {
                return CompletableFuture.completedFuture(task.get());
            } catch (Throwable e) {
                return CompletableFuture.failedFuture(e);
            }
        }
        if (!ctx.blockingAllowed()) {
            throw new ApiException(new ApiError(
                    ApiError.Code.CAPABILITY_UNAVAILABLE,
                    "this call needs the " + threadName + ", and a game thread cannot wait for another one; "
                            + "call it from a worker",
                    List.of(),
                    ApiError.Execution.NOT_STARTED));
        }
        AtomicInteger state = new AtomicInteger(QUEUED);
        CompletableFuture<T> f = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                if (!state.compareAndSet(QUEUED, RUNNING)) {
                    return;
                }
                try {
                    f.complete(task.get());
                } catch (Throwable e) {
                    f.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(new NotExecutedException(
                    threadName + " refused the task (" + e.getMessage() + "); it will not run and is safe to retry"));
        }
        // The caller's own thread wait when it set one (an in-JVM caller that would rather wait), else this hop's.
        long limit = ctx.threadWaitMs() > 0 ? ctx.threadWaitMs() : timeoutMs;
        long waitMs = Math.max(1, Math.min(limit, ctx.remainingMillis()));
        ScheduledFuture<?> timer = ApiTimers.schedule(
                () -> {
                    if (f.isDone()) {
                        return;
                    }
                    if (state.compareAndSet(QUEUED, ABANDONED)) {
                        f.completeExceptionally(
                                new NotExecutedException(
                                        threadName + " did not start the task within " + waitMs
                                                + "ms (busy, loading or paused); it was withdrawn and will not run, so it is safe to retry"));
                    } else {
                        f.completeExceptionally(
                                new OutcomeUnknownException(
                                        threadName + " started the task but it did not finish within " + waitMs
                                                + "ms; it is still running and may yet apply, so observe the world before retrying"));
                    }
                },
                waitMs);
        f.whenComplete((v, t) -> timer.cancel(false));
        return f;
    }

    private <T> T giveUp(AtomicInteger state, CompletableFuture<T> f, String when) {
        if (state.compareAndSet(QUEUED, ABANDONED)) {
            throw new NotExecutedException(threadName + " did not start the task " + when
                    + "; it was withdrawn and will not run, so it is safe to retry");
        }
        // Lost the race: the task started, and may have finished since the wait ran out.
        if (f.isDone()) {
            try {
                return f.get();
            } catch (ExecutionException e) {
                throw unwrap(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        throw new OutcomeUnknownException(threadName + " started the task but it did not finish " + when
                + "; it is still running and may yet apply, so observe the world before retrying");
    }

    private static RuntimeException unwrap(ExecutionException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        return cause instanceof RuntimeException re ? re : new RuntimeException(cause);
    }

    /** The hop timeout somewhere in {@code t}'s cause chain, or null: routes may wrap it. */
    public static HopTimeoutException find(Throwable t) {
        for (int depth = 0; t != null && depth < 16; depth++, t = t.getCause()) {
            if (t instanceof HopTimeoutException h) {
                return h;
            }
        }
        return null;
    }

    /** A hop that gave up waiting. {@link #code()} is the error every entry point reports it as. */
    public abstract static class HopTimeoutException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        HopTimeoutException(String message) {
            super(message);
        }

        public abstract ApiError.Code code();
    }

    /** The task never ran and never will: safe to retry. */
    public static final class NotExecutedException extends HopTimeoutException {
        private static final long serialVersionUID = 1L;

        public NotExecutedException(String message) {
            super(message);
        }

        @Override
        public ApiError.Code code() {
            return ApiError.Code.NOT_EXECUTED;
        }
    }

    /** The task was running when the wait ran out: it may or may not have applied. */
    public static final class OutcomeUnknownException extends HopTimeoutException {
        private static final long serialVersionUID = 1L;

        public OutcomeUnknownException(String message) {
            super(message);
        }

        @Override
        public ApiError.Code code() {
            return ApiError.Code.OUTCOME_UNKNOWN;
        }
    }
}
