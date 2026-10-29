package net.magicterra.worlddriver.api.call;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;

/**
 * What one call carries besides its parameters, passed explicitly from parent to child call: never
 * through a ThreadLocal, which does not follow work across executors.
 *
 * @param deadlineNanos     a {@link System#nanoTime} instant in THIS JVM; across processes a remaining
 *                          duration travels instead and the receiver rebuilds the instant
 * @param requestCancelled  this one request is no longer wanted (its client went away, its own budget ran out)
 * @param run               the script run the call belongs to, if any
 * @param waits             the quota a call that suspends draws from
 * @param blockingAllowed   false on a game thread: the call must finish synchronously or refuse before acting
 * @param threadWaitMs      how long a hop onto a game thread waits for that thread to start the task, still bounded
 *                          by the deadline; 0 for the hop's own limit (8 s on the server thread), which is what every
 *                          transport uses. Set by a caller inside this JVM that would rather wait than be told
 *                          {@code not_executed} ({@link #withThreadWait}, {@code ApiRegistry.withThreadWait})
 */
public record CallContext(long deadlineNanos, CancellationToken requestCancelled, Optional<RunContext> run,
                          WaitPermits waits, boolean blockingAllowed, long threadWaitMs) {

    /** A context with the hop's own thread wait. */
    public CallContext(long deadlineNanos, CancellationToken requestCancelled, Optional<RunContext> run,
                       WaitPermits waits, boolean blockingAllowed) {
        this(deadlineNanos, requestCancelled, run, waits, blockingAllowed, 0);
    }

    /** The longest any call may be kept waiting by this server: the awaitMs ceiling. */
    public static final long SERVER_CAP_MS = 600_000L;

    /** Shared by callers that bring no quota of their own; transports bring theirs. */
    public static final WaitPermits SHARED_WAITS = WaitPermits.bounded(64);

    public CallContext {
        Objects.requireNonNull(requestCancelled, "requestCancelled");
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(waits, "waits");
        if (threadWaitMs < 0) throw new IllegalArgumentException("threadWaitMs is 0 or more, got " + threadWaitMs);
        threadWaitMs = Math.min(threadWaitMs, SERVER_CAP_MS);
    }

    /** A call with the server's own cap as its budget, nothing to cancel it and the shared wait quota. */
    public static CallContext standalone() {
        return withBudget(SERVER_CAP_MS, CancellationToken.NEVER, SHARED_WAITS);
    }

    /** A call allowed {@code budgetMs} from now, capped at {@link #SERVER_CAP_MS}. */
    public static CallContext withBudget(long budgetMs, CancellationToken cancelled, WaitPermits waits) {
        long ms = Math.max(0, Math.min(budgetMs, SERVER_CAP_MS));
        return new CallContext(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms), cancelled, Optional.empty(),
                waits, true);
    }

    public long remainingNanos() { return deadlineNanos - System.nanoTime(); }

    public long remainingMillis() { return Math.max(0, TimeUnit.NANOSECONDS.toMillis(remainingNanos())); }

    public boolean expired() { return remainingNanos() <= 0; }

    /** This call inside {@code run}. */
    public CallContext inRun(RunContext run) {
        return new CallContext(deadlineNanos, requestCancelled, Optional.of(run), waits, blockingAllowed, threadWaitMs);
    }

    /** This call made from a game thread, which it may not hold. */
    public CallContext onGameThread() {
        return new CallContext(deadlineNanos, requestCancelled, run, waits, false, threadWaitMs);
    }

    /**
     * This call waiting up to {@code ms} for a game thread to start each task it hops there, instead of the hop's
     * own limit; the deadline still bounds it. 0 goes back to the hop's own limit.
     */
    public CallContext withThreadWait(long ms) {
        return new CallContext(deadlineNanos, requestCancelled, run, waits, blockingAllowed, ms);
    }

    /** A child call: the same run, request and thread wait, a deadline no later than this one's. */
    public CallContext child(long budgetMs) {
        long d = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, budgetMs));
        // nanoTime instants compare by their difference, which survives wrap-around.
        return new CallContext(d - deadlineNanos < 0 ? d : deadlineNanos, requestCancelled, run, waits, blockingAllowed,
                threadWaitMs);
    }

    /**
     * Throw {@code capability_unavailable} unless this call may wait; {@code what} names the wait.
     * A service calls this before it does anything, so a refusal never follows an effect.
     */
    public void requireBlockingAllowed(String what) {
        if (!blockingAllowed) {
            throw new ApiException(new ApiError(ApiError.Code.CAPABILITY_UNAVAILABLE, what
                    + " cannot wait on a game thread: it would wait for work only that thread can do. "
                    + "Call it from a worker, or do not wait (awaitMs 0, background:true)", List.of(),
                    ApiError.Execution.NOT_STARTED));
        }
    }
}
