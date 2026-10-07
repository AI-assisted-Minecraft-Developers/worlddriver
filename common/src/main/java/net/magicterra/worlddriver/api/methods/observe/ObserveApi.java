package net.magicterra.worlddriver.api.methods.observe;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.api.Spec;
import net.magicterra.worlddriver.api.methods.event.EventApi;

/** The {@code mc.observe.*} reads of the player and its surroundings; the event stream is {@link EventApi}. */
public interface ObserveApi {

    /** A caller that goes through {@code api}'s registry, checked as a request is. */
    static ObserveApi of(DriverApi api) { return ObserveApiBinding.calls(api); }

    @Spec(value = ObserveContracts.class, spec = "PLAYER")
    CompletionStage<ObserveContracts.PlayerReading> player(ObserveContracts.PlayerParams p, CallContext ctx);

    @Spec(value = ObserveContracts.class, spec = "THREATS")
    CompletionStage<ObserveContracts.Threats> threats(ObserveContracts.ThreatsParams p, CallContext ctx);

    @Spec(value = ObserveContracts.class, spec = "BOSS_SPEC")
    CompletionStage<ObserveContracts.BossReading> boss(ObserveContracts.BossParams p, CallContext ctx);

    @Spec(value = ObserveContracts.class, spec = "SCENE")
    CompletionStage<ObserveContracts.Scene> scene(ObserveContracts.SceneParams p, CallContext ctx);

    @Spec(value = ObserveContracts.class, spec = "MAP")
    CompletionStage<ObserveContracts.AsciiMap> map(ObserveContracts.MapParams p, CallContext ctx);
}
