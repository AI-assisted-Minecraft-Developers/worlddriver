package net.magicterra.worlddriver.application;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import net.magicterra.worlddriver.api.ApiMethod;
import net.magicterra.worlddriver.api.contract.ApiError;
import net.magicterra.worlddriver.api.contract.ApiException;
import net.magicterra.worlddriver.api.methods.ScriptContracts;

/** {@code mc.script.eval}, {@code mc.skill} and {@code mc.bot.playbook}, answered by the bound {@link ScriptHost}. */
final class ScriptMethods {
    private final Supplier<ScriptHost> host;

    ScriptMethods(Supplier<ScriptHost> host) { this.host = host; }

    List<ApiMethod<?, ?>> methods() {
        return List.of(
                new ApiMethod<>(ScriptContracts.EVAL, (p, ctx) -> host().eval(p, ctx)),
                new ApiMethod<>(ScriptContracts.SKILL, (op, ctx) -> host().skill(op, ctx)),
                new ApiMethod<>(ScriptContracts.PLAYBOOK, (op, ctx) -> CompletableFuture.completedFuture(host().playbook(op, ctx))));
    }

    private ScriptHost host() {
        ScriptHost h = host.get();
        if (h == null) throw ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE, "no script engine is bound in this game");
        return h;
    }
}
