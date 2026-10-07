package net.magicterra.worlddriver.api.methods.script;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.registry.Spec;

/** Scripts, the skills saved from them, and the boss playbooks they run. */
public interface ScriptApi {

    /** A caller that goes through {@code registry}, checked as a request is. */
    static ScriptApi of(ApiRegistry registry) { return ScriptApiBinding.calls(registry); }

    @Spec(value = ScriptContracts.class, spec = "EVAL")
    CompletionStage<ScriptContracts.Outcome> eval(ScriptContracts.EvalParams p, CallContext ctx);

    @Spec(value = ScriptContracts.class, spec = "SKILL")
    CompletionStage<ScriptContracts.SkillReply> skill(ScriptContracts.SkillOp op, CallContext ctx);

    @Spec(value = ScriptContracts.class, spec = "PLAYBOOK")
    CompletionStage<ScriptContracts.PlaybookReply> playbook(ScriptContracts.PlaybookOp op, CallContext ctx);
}
