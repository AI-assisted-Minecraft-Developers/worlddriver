package net.magicterra.worlddriver.api.methods.script;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.api.Spec;

/** Scripts, the skills saved from them, and the boss playbooks they run. */
public interface ScriptApi {

    /** A caller that goes through {@code api}'s registry, checked as a request is. */
    static ScriptApi of(DriverApi api) { return ScriptApiBinding.calls(api); }

    @Spec(value = ScriptContracts.class, spec = "EVAL")
    CompletionStage<ScriptContracts.Outcome> eval(ScriptContracts.EvalParams p, CallContext ctx);

    @Spec(value = ScriptContracts.class, spec = "SKILL")
    CompletionStage<ScriptContracts.SkillReply> skill(ScriptContracts.SkillOp op, CallContext ctx);

    @Spec(value = ScriptContracts.class, spec = "PLAYBOOK")
    CompletionStage<ScriptContracts.PlaybookReply> playbook(ScriptContracts.PlaybookOp op, CallContext ctx);
}
