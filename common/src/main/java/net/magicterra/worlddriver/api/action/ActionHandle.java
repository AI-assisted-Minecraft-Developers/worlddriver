package net.magicterra.worlddriver.api.action;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import net.magicterra.worlddriver.api.methods.ActionContract.ActionSnapshot;
import net.magicterra.worlddriver.api.methods.ActionContract.CancelReason;
import net.magicterra.worlddriver.api.methods.ActionContract.Cancelled;
import net.magicterra.worlddriver.api.methods.ActionContract.Failed;
import net.magicterra.worlddriver.api.methods.ActionContract.FailureReason;
import net.magicterra.worlddriver.api.methods.ActionContract.Running;
import net.magicterra.worlddriver.api.methods.ActionContract.Succeeded;
import net.magicterra.worlddriver.api.methods.ActionContract.Superseded;

/**
 * One accepted action: its identity, and its state, which moves from running to one terminal state
 * exactly once. The executor that holds the process (the client's user task chain, a server driver's
 * held process) writes the ending; everyone else only reads. The first ending wins and later ones are
 * ignored, which is what lets an ending with a precise cause (the body died) come before the generic
 * cancel that follows it.
 */
public final class ActionHandle {

    /** Where the action's body lives, which decides which ending closes it. */
    public enum Scope {
        /** This client's own player: ends with the client's connection. */
        CLIENT,
        /** A bot of the local server: ends with that server. */
        SERVER
    }

    private final ActionRegistry registry;
    private final String id;
    private final String body;
    private final Scope scope;
    private final String runId;
    private final Object result;
    private final AtomicReference<ActionSnapshot<Object>> state;
    private final CompletableFuture<ActionSnapshot<Object>> ended = new CompletableFuture<>();
    private volatile long endedAtMs;

    ActionHandle(ActionRegistry registry, String id, String body, Scope scope, String runId, Object result) {
        this.registry = registry;
        this.id = id;
        this.body = body;
        this.scope = scope;
        this.runId = runId;
        this.result = Objects.requireNonNull(result, "result");
        this.state = new AtomicReference<>(new Running<>(id, body));
    }

    public String id() { return id; }

    /** The body ref the action was started on, as given; never resolved again. */
    public String body() { return body; }

    public Scope scope() { return scope; }

    /** The run that owns it, or null. */
    public String runId() { return runId; }

    public ActionSnapshot<Object> snapshot() { return state.get(); }

    public boolean running() { return !state.get().terminal(); }

    /** Completes with the terminal snapshot, on whatever thread ended the action. */
    public CompletionStage<ActionSnapshot<Object>> ended() { return ended.minimalCompletionStage(); }

    long endedAtMs() { return endedAtMs; }

    /** The executor confirmed the goal: the result is the summary the action was started with. */
    public boolean succeed() { return succeed(result); }

    /** The executor confirmed the goal and reports {@code result}, which must be of the started summary's kind. */
    public boolean succeed(Object result) { return end(new Succeeded<>(id, body, result)); }

    /** The process gave up, threw, or the body died. */
    public boolean fail(String message) {
        return end(new Failed<>(id, body, FailureReason.PROCESS_FAILED, message == null ? "failed" : message));
    }

    /** The executor stopped the process for {@code reason}. */
    public boolean cancelled(CancelReason reason) { return end(new Cancelled<>(id, body, reason)); }

    /**
     * Someone other than the executor ends the action: the record says {@code reason} at once, and the
     * executor's stopper, if it bound one, is asked to stop the process on its own thread. The
     * record does not wait for that: the stop may never come (the world is gone), and the action has
     * ended for the caller either way.
     */
    public boolean requestCancel(CancelReason reason) {
        if (!cancelled(reason)) return false;
        java.util.function.Consumer<CancelReason> s = stopper;
        if (s != null) {
            try { s.accept(reason); } catch (RuntimeException ignored) { /* the record already says how it ended */ }
        }
        return true;
    }

    private volatile java.util.function.Consumer<CancelReason> stopper;

    /**
     * How {@link #requestCancel} stops the process: called at most once, on the requesting thread, so
     * it must hop to the executor's thread itself and stop the process only if it still holds this action.
     */
    public void bindStopper(java.util.function.Consumer<CancelReason> stopper) { this.stopper = stopper; }

    /** Another explicit action took the same body's slot. */
    public boolean supersede(String replacementActionId) {
        return end(new Superseded<>(id, body, replacementActionId));
    }

    private boolean end(ActionSnapshot<Object> terminal) {
        ActionSnapshot<Object> now = state.get();
        if (now.terminal() || !state.compareAndSet(now, terminal)) return false;
        endedAtMs = System.currentTimeMillis();
        registry.ended(this);
        ended.complete(terminal);
        return true;
    }

    @Override public String toString() { return "action " + id + " on " + body + ": " + state.get(); }
}
