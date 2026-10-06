package net.magicterra.worlddriver.application;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.methods.ScriptContracts.EvalParams;
import net.magicterra.worlddriver.api.methods.ScriptContracts.Outcome;
import net.magicterra.worlddriver.api.methods.ScriptContracts.PlaybookOp;
import net.magicterra.worlddriver.api.methods.ScriptContracts.PlaybookReply;
import net.magicterra.worlddriver.api.methods.ScriptContracts.SkillOp;
import net.magicterra.worlddriver.api.methods.ScriptContracts.SkillReply;

/**
 * What runs scripts. Bound at startup by the platform bootstrap, so the application does not load the
 * script engine; until it is bound the three methods are {@code capability_unavailable}.
 */
public interface ScriptHost {
    CompletionStage<Outcome> eval(EvalParams params, CallContext ctx);

    CompletionStage<SkillReply> skill(SkillOp op, CallContext ctx);

    PlaybookReply playbook(PlaybookOp op, CallContext ctx);
}
