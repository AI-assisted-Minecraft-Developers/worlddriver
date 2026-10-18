package net.magicterra.worlddriver.api.methods.debug;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.registry.Spec;

/**
 * The debug methods, {@code mc.debug.*}: the path debugging ({@code pathChart}, {@code plan}, {@code replay}) and the
 * readings of a server-side bot's insides ({@code navigator}, {@code body}, {@code cells}). Registered only where the
 * debug subsystem is.
 */
public interface DebugApi {

    /** A caller that goes through {@code registry}, checked as a request is. */
    static DebugStubApi of(ApiRegistry registry) { return DebugApiBinding.calls(registry); }

    @Spec(value = DebugContracts.class, spec = "PATH_CHART")
    CompletionStage<DebugContracts.Chart> pathChart(DebugContracts.ChartParams p, CallContext ctx);

    @Spec(value = DebugContracts.class, spec = "PLAN")
    CompletionStage<DebugContracts.PlanReply> plan(DebugContracts.PlanParams p, CallContext ctx);

    @Spec(value = DebugContracts.class, spec = "REPLAY")
    CompletionStage<DebugContracts.ReplayReply> replay(DebugContracts.ReplayParams p, CallContext ctx);

    @Spec(value = BotDebugContracts.class, spec = "NAVIGATOR")
    CompletionStage<BotDebugContracts.NavigatorReading> navigator(BotDebugContracts.NavigatorParams p, CallContext ctx);

    @Spec(value = BotDebugContracts.class, spec = "BODY")
    CompletionStage<BotDebugContracts.BodyReading> body(BotDebugContracts.BodyParams p, CallContext ctx);

    @Spec(value = BotDebugContracts.class, spec = "CELLS")
    CompletionStage<BotDebugContracts.CellsReading> cells(BotDebugContracts.CellsParams p, CallContext ctx);
}
