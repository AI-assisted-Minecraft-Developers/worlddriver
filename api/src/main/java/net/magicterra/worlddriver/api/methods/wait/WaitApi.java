package net.magicterra.worlddriver.api.methods.wait;

import java.util.concurrent.CompletionStage;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.registry.Spec;

/**
 * The {@code mc.wait.*} methods. The three waits may hold their caller, so on a game thread they are refused unless
 * they run in the background; {@link #result} reads how a background wait or a body task ended.
 */
public interface WaitApi {

    /** A caller that goes through {@code registry}, checked as a request is. */
    static WaitStubApi of(ApiRegistry registry) {
        return WaitApiBinding.calls(registry);
    }

    @Spec(value = WaitContracts.class, spec = "EVENT")
    CompletionStage<WaitContracts.Outcome> event(WaitContracts.EventParams p, CallContext ctx);

    @Spec(value = WaitContracts.class, spec = "WORLD_READY")
    CompletionStage<WaitContracts.Outcome> worldReady(WaitContracts.WorldReadyParams p, CallContext ctx);

    @Spec(value = WaitContracts.class, spec = "CONDITION")
    CompletionStage<WaitContracts.Outcome> condition(WaitContracts.ConditionParams p, CallContext ctx);

    @Spec(value = WaitContracts.class, spec = "RESULT")
    CompletionStage<WaitContracts.Reply> result(WaitContracts.Query query, CallContext ctx);
}
