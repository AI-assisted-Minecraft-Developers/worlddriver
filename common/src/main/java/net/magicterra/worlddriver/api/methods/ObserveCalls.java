package net.magicterra.worlddriver.api.methods;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.Api;
import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.DriverApi;

/** The {@code mc.observe.*} reads of the player and its surroundings; the event stream is {@link EventCalls}. */
public interface ObserveCalls {

    /** A caller that goes through {@code api}'s registry, checked as a request is. */
    static ObserveCalls of(DriverApi api) { return ObserveCallsBinding.calls(api); }

    @Api(PlayerContract.class)
    CompletionStage<PlayerContract.PlayerReading> player(PlayerContract.PlayerParams p, CallContext ctx);

    @Api(value = ObserveContracts.class, spec = "THREATS")
    CompletionStage<ObserveContracts.Threats> threats(ObserveContracts.ThreatsParams p, CallContext ctx);

    @Api(value = ObserveContracts.class, spec = "BOSS_SPEC")
    CompletionStage<ObserveContracts.BossReading> boss(ObserveContracts.BossParams p, CallContext ctx);

    @Api(value = ObserveContracts.class, spec = "SCENE")
    CompletionStage<ObserveContracts.Scene> scene(ObserveContracts.SceneParams p, CallContext ctx);

    @Api(value = ObserveContracts.class, spec = "MAP")
    CompletionStage<ObserveContracts.AsciiMap> map(ObserveContracts.MapParams p, CallContext ctx);
}
