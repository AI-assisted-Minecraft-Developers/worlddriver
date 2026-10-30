package net.magicterra.worlddriver.api.methods.debug;

import java.util.concurrent.CompletionStage;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.registry.Spec;

/**
 * The debug methods, {@code mc.debug.*}: the path debugging ({@code pathChart}, {@code plan}, {@code walkPlan},
 * {@code replay}) and the readings of a server-side bot's insides ({@code navigator}, {@code body}, {@code cells}).
 * Registered only where the debug subsystem is.
 */
public interface DebugApi {

    /** A caller that goes through {@code registry}, checked as a request is. */
    static DebugStubApi of(ApiRegistry registry) {
        return DebugApiBinding.calls(registry);
    }

    /**
     * {@code mc.debug.pathChart}: renders the current goto's pathfinding chart to a PNG and answers its path. The
     * {@code pathDebug} setting must be on before the goto starts.
     */
    @Spec(value = DebugContracts.class, spec = "PATH_CHART")
    CompletionStage<DebugContracts.Chart> pathChart(DebugContracts.ChartParams p, CallContext ctx);

    /** {@code mc.debug.plan}: one read-only A* search, or a simulated segment chain, from a start to a goto target. */
    @Spec(value = DebugContracts.class, spec = "PLAN")
    CompletionStage<DebugContracts.PlanReply> plan(DebugContracts.PlanParams p, CallContext ctx);

    /** {@code mc.debug.walkPlan}: walks a given plan on a server-side bot as its task; answers once it has started. */
    @Spec(value = DebugContracts.class, spec = "WALK_PLAN")
    CompletionStage<DebugContracts.WalkingPlan> walkPlan(DebugContracts.WalkPlanParams p, CallContext ctx);

    /** {@code mc.debug.replay}: replays a recorded goto archive; answers once the run is installed. */
    @Spec(value = DebugContracts.class, spec = "REPLAY")
    CompletionStage<DebugContracts.ReplayReply> replay(DebugContracts.ReplayParams p, CallContext ctx);

    /** {@code mc.debug.navigator}: a server-side bot's navigator as it stands, with its counters and traces. */
    @Spec(value = BotDebugContracts.class, spec = "NAVIGATOR")
    CompletionStage<BotDebugContracts.NavigatorReading> navigator(BotDebugContracts.NavigatorParams p, CallContext ctx);

    /** {@code mc.debug.body}: a bot's body as the driver judges it: its footing, its hands and its world view. */
    @Spec(value = BotDebugContracts.class, spec = "BODY")
    CompletionStage<BotDebugContracts.BodyReading> body(BotDebugContracts.BodyParams p, CallContext ctx);

    /** {@code mc.debug.cells}: cells as the planner's world view classifies them, a bot's or the server level's. */
    @Spec(value = BotDebugContracts.class, spec = "CELLS")
    CompletionStage<BotDebugContracts.CellsReading> cells(BotDebugContracts.CellsParams p, CallContext ctx);
}
