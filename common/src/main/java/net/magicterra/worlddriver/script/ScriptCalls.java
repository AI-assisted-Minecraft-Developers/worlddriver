package net.magicterra.worlddriver.script;

import java.util.function.Supplier;

import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.registry.ApiRegistry;

/**
 * The {@code __api} global behind {@code Driver.invoke}: every call a scope makes goes out as part of
 * the context it was given, so a script run's calls carry its run, its deadline and its cancellation.
 */
public final class ScriptCalls {
    private final ApiRegistry api;
    private final Supplier<CallContext> context;

    ScriptCalls(ApiRegistry api, Supplier<CallContext> context) {
        this.api = api;
        this.context = context;
    }

    /** Calls standing on their own, each with the server's full budget: the validation harness's. */
    static ScriptCalls standalone(ApiRegistry api) {
        return new ScriptCalls(api, CallContext::standalone);
    }

    /** The answer envelope {@link ApiRegistry#invokeJson(String, String, CallContext)} gives. */
    public String invokeJson(String method, String paramsJson) {
        return api.invokeJson(method, paramsJson, context.get());
    }
}
