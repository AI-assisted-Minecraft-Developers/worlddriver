package net.magicterra.worlddriver.api.methods;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.Api;
import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.DriverApi;

/** The path-debug methods, {@code mc.debug.*}; registered only where the path-debug subsystem is. */
public interface DebugCalls {

    /** A caller that goes through {@code api}'s registry, checked as a request is. */
    static DebugCalls of(DriverApi api) { return DebugCallsBinding.calls(api); }

    @Api(value = DebugContracts.class, spec = "PATH_CHART")
    CompletionStage<DebugContracts.Chart> pathChart(DebugContracts.ChartParams p, CallContext ctx);

    @Api(value = DebugContracts.class, spec = "PLAN")
    CompletionStage<DebugContracts.PlanReply> plan(DebugContracts.PlanParams p, CallContext ctx);

    @Api(value = DebugContracts.class, spec = "REPLAY")
    CompletionStage<DebugContracts.ReplayReply> replay(DebugContracts.ReplayParams p, CallContext ctx);
}
