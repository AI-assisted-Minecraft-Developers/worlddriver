package net.magicterra.worlddriver.api.methods.bot;

import java.util.concurrent.CompletionStage;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.methods.shared.Responses.ActionResponse;
import net.magicterra.worlddriver.api.methods.wait.WaitApi;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.registry.Spec;

/**
 * The {@code mc.bot.*} verbs that run a task on a body. Each answers when the task is accepted or refused, or later
 * with {@code awaitMs}; read its end with {@link WaitApi#result}.
 */
public interface BotTaskApi {

    /** A caller that goes through {@code registry}, checked as a request is. */
    static BotTaskStubApi of(ApiRegistry registry) {
        return BotTaskApiBinding.calls(registry);
    }

    /** {@code mc.bot.goto}: walk or fly to a target, preview the route, or price a drawn line. */
    @Spec(GotoContracts.class)
    CompletionStage<ActionResponse<GotoContracts.Result>> goTo(GotoContracts.Params p, CallContext ctx);

    /** {@code mc.bot.clearArea}: clear, fill or replace the cells of a box or a tunnel. */
    @Spec(ClearAreaContracts.class)
    CompletionStage<ActionResponse<ClearAreaContracts.Summary>> clearArea(ClearAreaContracts.Params p, CallContext ctx);

    /** {@code mc.bot.mine}: break matching blocks and collect the drops, or break one given cell. */
    @Spec(value = GatherContracts.class, spec = "MINE")
    CompletionStage<ActionResponse<GatherContracts.Mined>> mine(GatherContracts.MineParams p, CallContext ctx);

    /** {@code mc.bot.craft}: craft an item, resolving its whole recipe tree against the inventory. */
    @Spec(value = GatherContracts.class, spec = "CRAFT")
    CompletionStage<ActionResponse<GatherContracts.Crafted>> craft(GatherContracts.CraftParams p, CallContext ctx);

    /** {@code mc.bot.smelt}: smelt an ingredient in a furnace within reach or placed from the hotbar. */
    @Spec(value = GatherContracts.class, spec = "SMELT")
    CompletionStage<ActionResponse<GatherContracts.Smelted>> smelt(GatherContracts.SmeltParams p, CallContext ctx);

    /** {@code mc.bot.farm}: harvest the mature crops in a box and replant each from its own drop. */
    @Spec(value = GatherContracts.class, spec = "FARM")
    CompletionStage<ActionResponse<GatherContracts.Farmed>> farm(GatherContracts.FarmParams p, CallContext ctx);

    /** {@code mc.bot.build}: place a schematic's blocks bottom-up. */
    @Spec(value = BuildContracts.class, spec = "BUILD")
    CompletionStage<ActionResponse<BuildContracts.Built>> build(BuildContracts.BuildParams p, CallContext ctx);

    /** {@code mc.bot.construct}: pillar up or bridge forward with blocks from the inventory. */
    @Spec(value = BuildContracts.class, spec = "CONSTRUCT")
    CompletionStage<ActionResponse<BuildContracts.Constructed>> construct(
            BuildContracts.ConstructParams p, CallContext ctx);

    /** {@code mc.bot.bunker}: dig straight down and seal the roof, a pocket no mob reaches. */
    @Spec(value = BuildContracts.class, spec = "BUNKER")
    CompletionStage<ActionResponse<BuildContracts.Bunkered>> bunker(BuildContracts.BunkerParams p, CallContext ctx);

    /** {@code mc.bot.escape}: carve a staircase out of a pit or shaft without pathfinding. */
    @Spec(value = BuildContracts.class, spec = "ESCAPE")
    CompletionStage<ActionResponse<BuildContracts.Escaped>> escape(BuildContracts.EscapeParams p, CallContext ctx);

    /** {@code mc.bot.follow}: follow an entity or a player at a standoff distance. */
    @Spec(value = MoveContracts.class, spec = "FOLLOW")
    CompletionStage<ActionResponse<MoveContracts.Followed>> follow(MoveContracts.FollowParams p, CallContext ctx);

    /** {@code mc.bot.explore}: walk to unvisited chunk centres in a spiral around a point. */
    @Spec(value = MoveContracts.class, spec = "EXPLORE")
    CompletionStage<ActionResponse<MoveContracts.Explored>> explore(MoveContracts.ExploreParams p, CallContext ctx);

    /** {@code mc.bot.runAway}: walk to a reachable point at least {@code minDist} blocks from a given one. */
    @Spec(value = MoveContracts.class, spec = "RUN_AWAY")
    CompletionStage<ActionResponse<MoveContracts.RanAway>> runAway(MoveContracts.RunAwayParams p, CallContext ctx);

    /** {@code mc.bot.sleep}: walk to the nearest bed and sleep in it, if the game allows. */
    @Spec(value = MoveContracts.class, spec = "SLEEP")
    CompletionStage<ActionResponse<MoveContracts.Slept>> sleep(MoveContracts.SleepParams p, CallContext ctx);

    /** {@code mc.bot.elytraFly}: glide with a worn elytra; the body must already be airborne. */
    @Spec(value = MoveContracts.class, spec = "ELYTRA_FLY")
    CompletionStage<ActionResponse<MoveContracts.Flown>> elytraFly(MoveContracts.ElytraParams p, CallContext ctx);

    /** {@code mc.bot.combat}: fight hostiles until the target dies or the area is clear. */
    @Spec(CombatContracts.class)
    CompletionStage<ActionResponse<CombatContracts.Fought>> combat(CombatContracts.Params p, CallContext ctx);
}
