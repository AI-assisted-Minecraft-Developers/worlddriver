package net.magicterra.worlddriver.api.methods;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.Api;
import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.DriverApi;

/**
 * The {@code mc.wait.*} methods. The three waits may hold their caller, so on a game thread they are
 * refused unless they run in the background; {@link #result} reads how a background wait or a body
 * task ended.
 */
public interface WaitCalls {

    /** A caller that goes through {@code api}'s registry, checked as a request is. */
    static WaitCalls of(DriverApi api) { return WaitCallsBinding.calls(api); }

    @Api(value = WaitContracts.class, spec = "EVENT")
    CompletionStage<WaitContracts.Outcome> event(WaitContracts.EventParams p, CallContext ctx);

    @Api(value = WaitContracts.class, spec = "WORLD_READY")
    CompletionStage<WaitContracts.Outcome> worldReady(WaitContracts.WorldReadyParams p, CallContext ctx);

    @Api(value = WaitContracts.class, spec = "CONDITION")
    CompletionStage<WaitContracts.Outcome> condition(WaitContracts.ConditionParams p, CallContext ctx);

    @Api(WaitResultContract.class)
    CompletionStage<WaitResultContract.Reply> result(WaitResultContract.Query query, CallContext ctx);
}
