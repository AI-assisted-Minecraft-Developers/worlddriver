package net.magicterra.worlddriver.api.methods;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.Api;
import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.api.methods.ActionContract.ActionResponse;

/**
 * The {@code mc.bot.*} verbs that run a task on a body. Each answers when the task is accepted or
 * refused, or later with {@code awaitMs}; read its end with {@link WaitCalls#result}.
 */
public interface BotTaskCalls {

    /** A caller that goes through {@code api}'s registry, checked as a request is. */
    static BotTaskCalls of(DriverApi api) { return BotTaskCallsBinding.calls(api); }

    @Api(GotoContract.class)
    CompletionStage<ActionResponse<GotoContract.Result>> goTo(GotoContract.Params p, CallContext ctx);

    @Api(ClearAreaContract.class)
    CompletionStage<ActionResponse<ClearAreaContract.Summary>> clearArea(ClearAreaContract.Params p, CallContext ctx);

    @Api(value = GatherContracts.class, spec = "MINE")
    CompletionStage<ActionResponse<GatherContracts.Mined>> mine(GatherContracts.MineParams p, CallContext ctx);

    @Api(value = GatherContracts.class, spec = "CRAFT")
    CompletionStage<ActionResponse<GatherContracts.Crafted>> craft(GatherContracts.CraftParams p, CallContext ctx);

    @Api(value = GatherContracts.class, spec = "SMELT")
    CompletionStage<ActionResponse<GatherContracts.Smelted>> smelt(GatherContracts.SmeltParams p, CallContext ctx);

    @Api(value = GatherContracts.class, spec = "FARM")
    CompletionStage<ActionResponse<GatherContracts.Farmed>> farm(GatherContracts.FarmParams p, CallContext ctx);

    @Api(value = BuildContracts.class, spec = "BUILD")
    CompletionStage<ActionResponse<BuildContracts.Built>> build(BuildContracts.BuildParams p, CallContext ctx);

    @Api(value = BuildContracts.class, spec = "CONSTRUCT")
    CompletionStage<ActionResponse<BuildContracts.Constructed>> construct(BuildContracts.ConstructParams p, CallContext ctx);

    @Api(value = BuildContracts.class, spec = "BUNKER")
    CompletionStage<ActionResponse<BuildContracts.Bunkered>> bunker(BuildContracts.BunkerParams p, CallContext ctx);

    @Api(value = BuildContracts.class, spec = "ESCAPE")
    CompletionStage<ActionResponse<BuildContracts.Escaped>> escape(BuildContracts.EscapeParams p, CallContext ctx);

    @Api(value = MoveContracts.class, spec = "FOLLOW")
    CompletionStage<ActionResponse<MoveContracts.Followed>> follow(MoveContracts.FollowParams p, CallContext ctx);

    @Api(value = MoveContracts.class, spec = "EXPLORE")
    CompletionStage<ActionResponse<MoveContracts.Explored>> explore(MoveContracts.ExploreParams p, CallContext ctx);

    @Api(value = MoveContracts.class, spec = "RUN_AWAY")
    CompletionStage<ActionResponse<MoveContracts.RanAway>> runAway(MoveContracts.RunAwayParams p, CallContext ctx);

    @Api(value = MoveContracts.class, spec = "SLEEP")
    CompletionStage<ActionResponse<MoveContracts.Slept>> sleep(MoveContracts.SleepParams p, CallContext ctx);

    @Api(value = MoveContracts.class, spec = "ELYTRA_FLY")
    CompletionStage<ActionResponse<MoveContracts.Flown>> elytraFly(MoveContracts.ElytraParams p, CallContext ctx);

    @Api(CombatContract.class)
    CompletionStage<ActionResponse<CombatContract.Fought>> combat(CombatContract.Params p, CallContext ctx);
}
