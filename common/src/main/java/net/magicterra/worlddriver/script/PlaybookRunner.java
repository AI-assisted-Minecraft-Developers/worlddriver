package net.magicterra.worlddriver.script;

import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.call.CancellationToken;
import net.magicterra.worlddriver.api.call.RunContext;
import net.magicterra.worlddriver.protocol.JsonCodec;
import net.magicterra.worlddriver.api.contract.ApiError;
import net.magicterra.worlddriver.api.contract.ApiException;
import net.magicterra.worlddriver.api.contract.ApiIssue;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.CancelPlaybook;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.Outcome;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.PlaybookCancelled;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.PlaybookOp;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.PlaybookReply;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.PlaybookStarted;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.PlaybookState;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.PlaybookStatus;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.StartPlaybook;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Runs a named boss playbook (a script under {@code /data/worlddriver/scripts/playbooks/}) in the
 * background, so {@code mc.bot.playbook} answers at once and the fight outlives {@code mc.script.eval}'s
 * budget. One at a time. The playbook is a run of its own, not part of the call that started it: the
 * caller going away does not stop it; {@code cancel} or its budget does, which also cancels the actions
 * it started.
 *
 * <p>It runs through the same {@link ScriptEvaluator} as {@code mc.script.eval}, so under the same class
 * filter, which is off unless {@code -Dworlddriver.sandbox=on}: scripts are a first-party capability and
 * the caller owns the trust boundary, here as there.
 */
public final class PlaybookRunner {
    private static final String DIR = "/data/worlddriver/scripts/playbooks/";

    private final ScriptEvaluator evaluator;

    /** The running or last playbook. */
    private volatile Playbook current;

    private static final class Playbook {
        final String name;
        final RunContext run;
        final long startedAtMs = System.currentTimeMillis();
        /** Set once, when the run ends. */
        volatile Outcome outcome;

        Playbook(String name, RunContext run) {
            this.name = name;
            this.run = run;
        }

        boolean active() { return outcome == null; }
    }

    public PlaybookRunner(ScriptEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    public PlaybookReply handle(PlaybookOp op) {
        return switch (op) {
            case StartPlaybook s -> start(s);
            case PlaybookStatus s -> status();
            case CancelPlaybook c -> cancel();
        };
    }

    private synchronized PlaybookReply start(StartPlaybook s) {
        Playbook cur = current;
        if (cur != null && cur.active()) {
            throw ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE,
                    "playbook " + cur.name + " (" + cur.run.runId() + ") is running; cancel it first");
        }
        String body = load(s.name());
        if (body == null) throw ApiException.invalidParam("/name", ApiIssue.Code.INVALID_VALUE, "no playbook named " + s.name());
        // On the body's first line, so the lines an error names are the playbook's own.
        String full = "var PLAYBOOK = " + JsonCodec.encode(s.args().map(a -> a.tree()).orElse(Map.of())) + "; " + body;
        Playbook p = new Playbook(s.name(), RunContext.start("playbook-" + s.name()));
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(s.budgetMs());
        CallContext own = new CallContext(deadline, CancellationToken.NEVER, Optional.of(p.run), CallContext.SHARED_WAITS, true);
        current = p;
        evaluator.runOwned(full, s.budgetMs(), own).whenComplete((o, t) -> p.outcome = o != null ? o
                : ScriptEvaluator.failed(p.run, t));
        return new PlaybookStarted(s.name(), p.run.runId());
    }

    private PlaybookReply status() {
        Playbook cur = current;
        if (cur == null) return new PlaybookState(false, Optional.empty(), Optional.empty(), Optional.empty(), false, Optional.empty());
        boolean active = cur.active();
        return new PlaybookState(active, Optional.of(cur.name), Optional.of(cur.run.runId()), Optional.of(cur.startedAtMs),
                active && cur.run.stopped().isCancelled(), Optional.ofNullable(cur.outcome));
    }

    private PlaybookReply cancel() {
        Playbook cur = current;
        if (cur == null || !cur.active()) return new PlaybookCancelled(Optional.empty());
        cur.run.stop();
        return new PlaybookCancelled(Optional.of(cur.run.runId()));
    }

    private static String load(String name) {
        try (InputStream in = PlaybookRunner.class.getResourceAsStream(DIR + name + ".js")) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
