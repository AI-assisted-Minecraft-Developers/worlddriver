package net.magicterra.worlddriver.api;

import java.util.Objects;

/**
 * One script run (an {@code mc.script.eval}, a skill, a playbook) as the API sees it: the identity its
 * child actions are owned by, and the token that says the run was stopped or ran out of budget.
 */
public record RunContext(String runId, CancellationToken stopped) {
    public RunContext {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(stopped, "stopped");
    }
}
