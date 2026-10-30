package net.magicterra.worlddriver.api.methods.observe;

import java.util.concurrent.CompletionStage;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.methods.event.EventApi;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.registry.Spec;

/** The {@code mc.observe.*} reads of the player and its surroundings; the event stream is {@link EventApi}. */
public interface ObserveApi {

    /** A caller that goes through {@code registry}, checked as a request is. */
    static ObserveStubApi of(ApiRegistry registry) {
        return ObserveApiBinding.calls(registry);
    }

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
