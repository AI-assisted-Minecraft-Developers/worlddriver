package net.magicterra.worlddriver.api;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One script run (an {@code mc.script.eval}, a skill, a playbook) as the API sees it: the identity its
 * child actions are owned by, and two signals. {@link #stopped} means the run was stopped or ran out
 * of budget, which cancels its actions; {@link #ended} means it will start nothing more, whether it
 * returned or was stopped. A run that returned leaves its actions running, and they keep the bodies
 * they hold until they end.
 */
public final class RunContext {
    private static final AtomicLong SEQ = new AtomicLong();

    private final String runId;
    private final CancellationToken.Source stopped = new CancellationToken.Source();
    private final CancellationToken.Source ended = new CancellationToken.Source();

    public RunContext(String runId) {
        this.runId = Objects.requireNonNull(runId, "runId");
    }

    /** A run with a fresh id, {@code <kind>-<n>}. */
    public static RunContext start(String kind) {
        return new RunContext(kind + "-" + SEQ.incrementAndGet());
    }

    public String runId() { return runId; }

    public CancellationToken stopped() { return stopped; }

    public CancellationToken ended() { return ended; }

    /** The run returned: it starts nothing more, and what it started runs on. */
    public void finish() { ended.cancel(); }

    /** The run was stopped: its actions are cancelled, and it starts nothing more. */
    public void stop() {
        stopped.cancel();
        ended.cancel();
    }

    @Override public String toString() { return "run " + runId; }
}
