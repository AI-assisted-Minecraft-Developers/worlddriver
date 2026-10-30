package net.magicterra.worlddriver.application;

import java.util.concurrent.CompletionStage;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.EvalParams;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.Outcome;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.PlaybookOp;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.PlaybookReply;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.SkillOp;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.SkillReply;

/**
 * What runs scripts. Bound at startup by the platform bootstrap, so the application does not load the script engine;
 * until it is bound the three methods are {@code capability_unavailable}.
 */
public interface ScriptHost {
    CompletionStage<Outcome> eval(EvalParams params, CallContext ctx);

    CompletionStage<SkillReply> skill(SkillOp op, CallContext ctx);

    PlaybookReply playbook(PlaybookOp op, CallContext ctx);
}
