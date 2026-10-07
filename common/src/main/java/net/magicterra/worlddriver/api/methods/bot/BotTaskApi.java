package net.magicterra.worlddriver.api.methods.bot;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.api.Spec;
import net.magicterra.worlddriver.api.methods.shared.Responses.ActionResponse;
import net.magicterra.worlddriver.api.methods.wait.WaitApi;

/**
 * The {@code mc.bot.*} verbs that run a task on a body. Each answers when the task is accepted or
 * refused, or later with {@code awaitMs}; read its end with {@link WaitApi#result}.
 */
public interface BotTaskApi {

    /** A caller that goes through {@code api}'s registry, checked as a request is. */
    static BotTaskApi of(DriverApi api) { return BotTaskApiBinding.calls(api); }

    @Spec(GotoContracts.class)
    CompletionStage<ActionResponse<GotoContracts.Result>> goTo(GotoContracts.Params p, CallContext ctx);

    @Spec(ClearAreaContracts.class)
    CompletionStage<ActionResponse<ClearAreaContracts.Summary>> clearArea(ClearAreaContracts.Params p, CallContext ctx);

    @Spec(value = GatherContracts.class, spec = "MINE")
    CompletionStage<ActionResponse<GatherContracts.Mined>> mine(GatherContracts.MineParams p, CallContext ctx);

    @Spec(value = GatherContracts.class, spec = "CRAFT")
    CompletionStage<ActionResponse<GatherContracts.Crafted>> craft(GatherContracts.CraftParams p, CallContext ctx);

    @Spec(value = GatherContracts.class, spec = "SMELT")
    CompletionStage<ActionResponse<GatherContracts.Smelted>> smelt(GatherContracts.SmeltParams p, CallContext ctx);

    @Spec(value = GatherContracts.class, spec = "FARM")
    CompletionStage<ActionResponse<GatherContracts.Farmed>> farm(GatherContracts.FarmParams p, CallContext ctx);

    @Spec(value = BuildContracts.class, spec = "BUILD")
    CompletionStage<ActionResponse<BuildContracts.Built>> build(BuildContracts.BuildParams p, CallContext ctx);

    @Spec(value = BuildContracts.class, spec = "CONSTRUCT")
    CompletionStage<ActionResponse<BuildContracts.Constructed>> construct(BuildContracts.ConstructParams p, CallContext ctx);

    @Spec(value = BuildContracts.class, spec = "BUNKER")
    CompletionStage<ActionResponse<BuildContracts.Bunkered>> bunker(BuildContracts.BunkerParams p, CallContext ctx);

    @Spec(value = BuildContracts.class, spec = "ESCAPE")
    CompletionStage<ActionResponse<BuildContracts.Escaped>> escape(BuildContracts.EscapeParams p, CallContext ctx);

    @Spec(value = MoveContracts.class, spec = "FOLLOW")
    CompletionStage<ActionResponse<MoveContracts.Followed>> follow(MoveContracts.FollowParams p, CallContext ctx);

    @Spec(value = MoveContracts.class, spec = "EXPLORE")
    CompletionStage<ActionResponse<MoveContracts.Explored>> explore(MoveContracts.ExploreParams p, CallContext ctx);

    @Spec(value = MoveContracts.class, spec = "RUN_AWAY")
    CompletionStage<ActionResponse<MoveContracts.RanAway>> runAway(MoveContracts.RunAwayParams p, CallContext ctx);

    @Spec(value = MoveContracts.class, spec = "SLEEP")
    CompletionStage<ActionResponse<MoveContracts.Slept>> sleep(MoveContracts.SleepParams p, CallContext ctx);

    @Spec(value = MoveContracts.class, spec = "ELYTRA_FLY")
    CompletionStage<ActionResponse<MoveContracts.Flown>> elytraFly(MoveContracts.ElytraParams p, CallContext ctx);

    @Spec(CombatContracts.class)
    CompletionStage<ActionResponse<CombatContracts.Fought>> combat(CombatContracts.Params p, CallContext ctx);
}
