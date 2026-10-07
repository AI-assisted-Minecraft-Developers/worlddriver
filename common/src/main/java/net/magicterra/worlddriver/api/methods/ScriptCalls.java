package net.magicterra.worlddriver.api.methods;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.Api;
import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.DriverApi;

/** Scripts, the skills saved from them, and the boss playbooks they run. */
public interface ScriptCalls {

    /** A caller that goes through {@code api}'s registry, checked as a request is. */
    static ScriptCalls of(DriverApi api) { return ScriptCallsBinding.calls(api); }

    @Api(value = ScriptContracts.class, spec = "EVAL")
    CompletionStage<ScriptContracts.Outcome> eval(ScriptContracts.EvalParams p, CallContext ctx);

    @Api(value = ScriptContracts.class, spec = "SKILL")
    CompletionStage<ScriptContracts.SkillReply> skill(ScriptContracts.SkillOp op, CallContext ctx);

    @Api(value = ScriptContracts.class, spec = "PLAYBOOK")
    CompletionStage<ScriptContracts.PlaybookReply> playbook(ScriptContracts.PlaybookOp op, CallContext ctx);
}
