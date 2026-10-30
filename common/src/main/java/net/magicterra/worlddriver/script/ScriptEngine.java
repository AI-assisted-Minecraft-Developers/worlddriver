package net.magicterra.worlddriver.script;

import java.nio.file.Path;
import java.util.concurrent.CompletionStage;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.EvalParams;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.Outcome;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.PlaybookOp;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.PlaybookReply;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.SkillOp;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.SkillReply;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.application.ScriptHost;

/** The script engine behind {@code mc.script.eval}, {@code mc.skill} and {@code mc.bot.playbook}. */
public final class ScriptEngine implements ScriptHost {
    private final ScriptEvaluator evaluator;

    private final SkillLibrary skills;

    private final PlaybookRunner playbooks;

    /** {@code skillsDir}: where saved skills live. */
    public ScriptEngine(ApiRegistry api, Path skillsDir) {
        this.evaluator = new ScriptEvaluator(api);
        this.skills = new SkillLibrary(evaluator, skillsDir);
        this.playbooks = new PlaybookRunner(evaluator);
    }

    @Override
    public CompletionStage<Outcome> eval(EvalParams params, CallContext ctx) {
        return evaluator.run(params.source(), params.timeoutMs(), ctx, "eval");
    }

    @Override
    public CompletionStage<SkillReply> skill(SkillOp op, CallContext ctx) {
        return skills.handle(op, ctx);
    }

    @Override
    public PlaybookReply playbook(PlaybookOp op, CallContext ctx) {
        return playbooks.handle(op);
    }
}
