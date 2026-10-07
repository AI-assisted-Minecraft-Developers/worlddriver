package net.magicterra.worlddriver.api.action;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import net.magicterra.worlddriver.api.call.RunContext;
import net.magicterra.worlddriver.api.contract.ApiError;
import net.magicterra.worlddriver.api.contract.ApiException;
import net.magicterra.worlddriver.api.methods.shared.Responses.ActionSnapshot;
import net.magicterra.worlddriver.api.methods.shared.Responses.CancelReason;
import net.magicterra.worlddriver.api.methods.shared.Responses.RefusalReason;

/**
 * Every action this JVM has accepted and not yet forgotten. Running actions are never evicted; the
 * admission cap is what bounds them. Ended ones are kept for {@link #KEEP_ENDED} entries or
 * {@link #KEEP_ENDED_MS}, whichever runs out first, so a caller that reconnects can still read how an
 * action ended. Nothing survives a restart.
 *
 * <p>It also keeps which script run holds which body: a run's first action on a body makes the body
 * the run's until the run has ended and none of its actions on the body is still running. A start
 * from anyone else is refused as {@code busy} in that time.
 *
 * <p>All state is guarded by this object's monitor; nothing is called out while holding it.
 */
public final class ActionRegistry {
    public static final int MAX_ACTIVE = 256;
    public static final int KEEP_ENDED = 128;
    public static final long KEEP_ENDED_MS = 600_000L;

    /** The one registry the API and the executors share. */
    public static final ActionRegistry GLOBAL = new ActionRegistry();

    private final AtomicLong seq = new AtomicLong();
    private final String epoch = Long.toString(System.currentTimeMillis() & 0xFFFFFFFL, 36);
    private final Map<String, ActionHandle> active = new LinkedHashMap<>();
    private final ArrayDeque<ActionHandle> ended = new ArrayDeque<>();
    private final Map<String, ActionHandle> endedById = new HashMap<>();
    private final Map<String, RunContext> owners = new HashMap<>();
    private final Map<String, RunContext> watchedRuns = new HashMap<>();

    /** The outcome of {@link #admit}: a handle, or why there is none. */
    public sealed interface Admission permits Admitted, Denied {}
    public record Admitted(ActionHandle handle) implements Admission {}
    public record Denied(RefusalReason reason, String message) implements Admission {}

    /**
     * Accept an action on {@code body} for {@code run} (empty: no script run), whose success will report
     * what {@code result} reads then; null for work that ends itself with an explicit result. Call it on
     * the thread that is about to install the process, after everything that can refuse the request has
     * passed, and install at once: a handle that is admitted and never installed stays running until
     * something cancels it.
     */
    public Admission admit(String body, ActionHandle.Scope scope, Optional<RunContext> run, Supplier<?> result) {
        RunContext caller = requireRunning(run);
        ActionHandle handle;
        boolean watch;
        synchronized (this) {
            Denied busy = busy(body, caller);
            if (busy != null) return busy;
            if (active.size() >= MAX_ACTIVE) {
                return new Denied(RefusalReason.ACTION_LIMIT, MAX_ACTIVE + " actions are running; wait for one to end or cancel one");
            }
            String id = "a" + epoch + "-" + seq.incrementAndGet();
            handle = new ActionHandle(this, id, body, scope, caller == null ? null : caller.runId(), result);
            active.put(id, handle);
            watch = own(body, caller);
        }
        if (watch) watch(caller);
        return new Admitted(handle);
    }

    /**
     * For a write that ends within the call (holding an item, one click): null when {@code body} is free for
     * {@code run}, which then holds it as a first write does; otherwise why not.
     */
    public Denied claim(String body, Optional<RunContext> run) {
        RunContext caller = requireRunning(run);
        boolean watch;
        synchronized (this) {
            Denied busy = busy(body, caller);
            if (busy != null) return busy;
            watch = own(body, caller);
        }
        if (watch) watch(caller);
        return null;
    }

    private static RunContext requireRunning(Optional<RunContext> run) {
        RunContext caller = run.orElse(null);
        if (caller != null && caller.stopped().isCancelled()) {
            throw new ApiException(new ApiError(ApiError.Code.CAPABILITY_UNAVAILABLE, caller + " was stopped and starts nothing more",
                    List.of(), ApiError.Execution.NOT_STARTED));
        }
        return caller;
    }

    /** Monitor held. */
    private Denied busy(String body, RunContext caller) {
        RunContext owner = owners.get(body);
        if (owner != null && !holds(owner, body)) {
            owners.remove(body);
            owner = null;
        }
        return owner != null && owner != caller
                ? new Denied(RefusalReason.BUSY, body + " is held by " + owner + " until its actions end") : null;
    }

    /** Monitor held: {@code caller} holds {@code body}; true when its run is new here and needs {@link #watch}. */
    private boolean own(String body, RunContext caller) {
        if (caller == null) return false;
        owners.put(body, caller);
        return watchedRuns.putIfAbsent(caller.runId(), caller) == null;
    }

    private void watch(RunContext caller) {
        caller.stopped().onCancel(() -> endRun(caller.runId(), CancelReason.REQUESTED));
        caller.ended().onCancel(() -> forgetRunIfIdle(caller));
    }

    /** Whether {@code run} still holds {@code body}: it has not ended, or one of its actions there runs. Monitor held. */
    private boolean holds(RunContext run, String body) {
        if (!run.ended().isCancelled()) return true;
        for (ActionHandle h : active.values()) {
            if (body.equals(h.body()) && run.runId().equals(h.runId())) return true;
        }
        return false;
    }

    private synchronized void forgetRunIfIdle(RunContext run) {
        for (ActionHandle h : active.values()) if (run.runId().equals(h.runId())) return;
        watchedRuns.remove(run.runId());
        owners.values().removeIf(r -> r == run);
    }

    /** Called by a handle once, as it ends. */
    void ended(ActionHandle h) {
        synchronized (this) {
            active.remove(h.id());
            ended.addLast(h);
            endedById.put(h.id(), h);
            prune();
            if (h.runId() != null) {
                RunContext run = watchedRuns.get(h.runId());
                if (run != null && run.ended().isCancelled()) {
                    boolean idle = true;
                    for (ActionHandle a : active.values()) if (h.runId().equals(a.runId())) { idle = false; break; }
                    if (idle) {
                        watchedRuns.remove(run.runId());
                        owners.values().removeIf(r -> r == run);
                    }
                }
            }
        }
    }

    private void prune() {
        long cutoff = System.currentTimeMillis() - KEEP_ENDED_MS;
        while (!ended.isEmpty() && (ended.size() > KEEP_ENDED || ended.peekFirst().endedAtMs() < cutoff)) {
            endedById.remove(ended.removeFirst().id());
        }
    }

    /** The action, running or kept after its ending, or {@code action_not_found}. */
    public ActionSnapshot<Object> snapshot(String id) {
        ActionHandle h = find(id);
        if (h == null) {
            throw ApiException.of(ApiError.Code.ACTION_NOT_FOUND, "no action " + id + " (unknown, or ended more than "
                    + KEEP_ENDED_MS / 60_000 + " minutes or " + KEEP_ENDED + " endings ago)");
        }
        return h.snapshot();
    }

    public synchronized ActionHandle find(String id) {
        prune();
        ActionHandle h = active.get(id);
        return h != null ? h : endedById.get(id);
    }

    public synchronized int activeCount() { return active.size(); }

    /** End every running action of {@code scope} with {@code reason}: its world or connection is gone. */
    public void cancelScope(ActionHandle.Scope scope, CancelReason reason) {
        generations[scope.ordinal()].incrementAndGet();
        for (ActionHandle h : running(a -> a.scope() == scope)) h.requestCancel(reason);
    }

    /** End every running action, whatever its scope: the local world is closing. */
    public void cancelAll(CancelReason reason) {
        for (AtomicLong g : generations) g.incrementAndGet();
        for (ActionHandle h : running(a -> true)) h.requestCancel(reason);
    }

    private final AtomicLong[] generations = {new AtomicLong(), new AtomicLong()};

    /**
     * Which world or connection {@code scope} is on, as a number that changes whenever it closes. A
     * request reads it when it arrives and the game thread compares before it starts anything, so a
     * request queued behind a world change cannot act on the next world's body.
     */
    public long generation(ActionHandle.Scope scope) { return generations[scope.ordinal()].get(); }

    /** Throw {@code context_changed} unless {@code scope} is still on {@code generation}. */
    public void requireGeneration(ActionHandle.Scope scope, long generation) {
        if (generation(scope) != generation) {
            throw ApiException.of(ApiError.Code.CONTEXT_CHANGED, "the "
                    + (scope == ActionHandle.Scope.CLIENT ? "client's connection" : "server's world")
                    + " changed after this request arrived; nothing was started");
        }
    }

    /** End every running action on {@code body}: it is no longer addressable. */
    public void cancelBody(String body, CancelReason reason) {
        for (ActionHandle h : running(a -> body.equals(a.body()))) h.requestCancel(reason);
    }

    private void endRun(String runId, CancelReason reason) {
        for (ActionHandle h : running(a -> runId.equals(a.runId()))) h.requestCancel(reason);
    }

    private synchronized List<ActionHandle> running(java.util.function.Predicate<ActionHandle> which) {
        List<ActionHandle> out = new ArrayList<>();
        for (ActionHandle h : active.values()) if (which.test(h)) out.add(h);
        return out;
    }
}
