package net.magicterra.worlddriver.application;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.methods.script.ScriptApi;
import net.magicterra.worlddriver.api.methods.script.ScriptApiBinding;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts;
import net.magicterra.worlddriver.api.registry.ApiMethod;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;

/** {@code mc.script.eval}, {@code mc.skill} and {@code mc.bot.playbook}, answered by the bound {@link ScriptHost}. */
final class ScriptService implements ScriptApi {
    private final Supplier<ScriptHost> host;

    ScriptService(Supplier<ScriptHost> host) {
        this.host = host;
    }

    List<ApiMethod<?, ?>> methods() {
        return ScriptApiBinding.methods(this);
    }

    @Override
    public CompletionStage<ScriptContracts.Outcome> eval(ScriptContracts.EvalParams p, CallContext ctx) {
        return host().eval(p, ctx);
    }

    @Override
    public CompletionStage<ScriptContracts.SkillReply> skill(ScriptContracts.SkillOp op, CallContext ctx) {
        return host().skill(op, ctx);
    }

    @Override
    public CompletionStage<ScriptContracts.PlaybookReply> playbook(ScriptContracts.PlaybookOp op, CallContext ctx) {
        return CompletableFuture.completedFuture(host().playbook(op, ctx));
    }

    private ScriptHost host() {
        ScriptHost h = host.get();
        if (h == null) {
            throw ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE, "no script engine is bound in this game");
        }
        return h;
    }
}
