package net.magicterra.worlddriver.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Runs a task on the server thread and waits a bounded time for it.
 *
 * <p>A wait that runs out cannot simply report failure: the task is still queued, and a caller
 * told "failed" who retries would have it applied twice once the tick frees up. So each call
 * races the server thread for the task. The server thread moves it QUEUED to RUNNING before
 * running it; a waiter that times out moves it QUEUED to ABANDONED. Exactly one side wins, which
 * turns the timeout into one of two definite answers: {@link NotExecutedException} (the task was
 * withdrawn and will never run, a retry is safe) or {@link OutcomeUnknownException} (it is
 * already running and cannot be withdrawn, observe before retrying).
 */
public final class ServerThreadHop {
    /** JSON-RPC server-error code for {@link NotExecutedException}. */
    public static final int CODE_NOT_EXECUTED = -32001;
    /** JSON-RPC server-error code for {@link OutcomeUnknownException}. */
    public static final int CODE_OUTCOME_UNKNOWN = -32002;

    private static final int QUEUED = 0, RUNNING = 1, ABANDONED = 2;

    private final Executor executor;
    private final BooleanSupplier onTargetThread;
    private final long timeoutMs;

    public ServerThreadHop(Executor executor, BooleanSupplier onTargetThread, long timeoutMs) {
        this.executor = executor;
        this.onTargetThread = onTargetThread;
        this.timeoutMs = timeoutMs;
    }

    public <T> T call(Supplier<T> task) {
        if (onTargetThread.getAsBoolean()) return task.get();
        AtomicInteger state = new AtomicInteger(QUEUED);
        CompletableFuture<T> f = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                if (!state.compareAndSet(QUEUED, RUNNING)) return;
                try { f.complete(task.get()); }
                catch (Throwable e) { f.completeExceptionally(e); }
            });
        } catch (RejectedExecutionException e) {
            throw new NotExecutedException("server thread refused the task (" + e.getMessage()
                    + "); it will not run and is safe to retry");
        }
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return giveUp(state, f, "within " + timeoutMs + "ms (server busy or paused)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return giveUp(state, f, "before the waiting thread was interrupted");
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
    }

    private static <T> T giveUp(AtomicInteger state, CompletableFuture<T> f, String when) {
        if (state.compareAndSet(QUEUED, ABANDONED)) {
            throw new NotExecutedException("server thread did not start the task " + when
                    + "; it was withdrawn and will not run, so it is safe to retry");
        }
        // Lost the race: the task started, and may have finished since the wait ran out.
        if (f.isDone()) {
            try { return f.get(); }
            catch (ExecutionException e) { throw unwrap(e); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        throw new OutcomeUnknownException("server thread started the task but it did not finish "
                + when + "; it is still running and may yet apply, so observe the world before retrying");
    }

    private static RuntimeException unwrap(ExecutionException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        return cause instanceof RuntimeException re ? re : new RuntimeException(cause);
    }

    /** The hop timeout somewhere in {@code t}'s cause chain, or null: routes may wrap it. */
    public static HopTimeoutException find(Throwable t) {
        for (int depth = 0; t != null && depth < 16; depth++, t = t.getCause()) {
            if (t instanceof HopTimeoutException h) return h;
        }
        return null;
    }

    /** A hop that gave up waiting. {@link #code()} is the JSON-RPC code the transports report. */
    public abstract static class HopTimeoutException extends RuntimeException {
        HopTimeoutException(String message) { super(message); }
        public abstract int code();
    }

    /** The task never ran and never will: safe to retry. */
    public static final class NotExecutedException extends HopTimeoutException {
        public NotExecutedException(String message) { super(message); }
        @Override public int code() { return CODE_NOT_EXECUTED; }
    }

    /** The task was running when the wait ran out: it may or may not have applied. */
    public static final class OutcomeUnknownException extends HopTimeoutException {
        public OutcomeUnknownException(String message) { super(message); }
        @Override public int code() { return CODE_OUTCOME_UNKNOWN; }
    }
}
