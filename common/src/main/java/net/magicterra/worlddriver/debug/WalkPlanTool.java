package net.magicterra.worlddriver.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.magicterra.worlddriver.api.game.Positions;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts.PlanEdge;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts.WalkMode;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts.WalkPlanParams;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts.WalkingPlan;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.bot.Bot;
import net.magicterra.worlddriver.bot.BotRegistry;
import net.magicterra.worlddriver.bot.GotoGoalResolver;
import net.magicterra.worlddriver.bot.RouteParams;
import net.magicterra.worlddriver.bot.ServerBot;
import net.magicterra.worlddriver.bot.behavior.ReplayProcess;
import net.magicterra.worlddriver.bot.control.NavigatorSettings;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.bot.rules.Move;
import net.minecraft.core.BlockPos;

/** {@code mc.debug.walkPlan}: a given plan walked as a server-side bot's task, on the server thread. */
final class WalkPlanTool {
    private WalkPlanTool() {}

    static WalkingPlan onServer(WalkPlanParams p) {
        Bot bot = BotRegistry.get(p.bot());
        if (!(bot instanceof ServerBot)) {
            throw ApiException.invalidParam(
                    "/bot",
                    ApiIssue.Code.INVALID_VALUE,
                    bot == null ? "no bot named " + p.bot() : p.bot() + " is not a server-side bot");
        }
        if (p.plan().isEmpty())
            throw ApiException.invalidParam("/plan", ApiIssue.Code.INVALID_VALUE, "the plan has no nodes");
        List<BlockPos> plan = new ArrayList<>(p.plan().size());
        List<Move.Edge> edges = new ArrayList<>(p.plan().size());
        for (PlanEdge e : p.plan()) {
            BlockPos to = Positions.blockPos(e.to());
            plan.add(to);
            edges.add(
                    e.move().isEmpty()
                            ? null
                            : new Move.Edge(
                                    to,
                                    e.cost(),
                                    cells(e.breaks()),
                                    cells(e.places()),
                                    e.move().get()));
        }
        Goal goal = p.goal()
                .map(t -> {
                    try {
                        return GotoGoalResolver.resolve(t, false, bot.entity(), Map.of(), id -> null);
                    } catch (GotoGoalResolver.Unavailable u) {
                        throw ApiException.invalidParam("/goal", ApiIssue.Code.INVALID_VALUE, u.getMessage());
                    }
                })
                .orElseGet(() -> new Goal.Block(plan.get(plan.size() - 1)));
        BlockPos foot = p.from().map(Positions::blockPos).orElse(plan.get(0));
        NavigatorSettings settings = NavigatorSettings.named("debug.walkPlan");
        if (p.route().isPresent())
            settings = settings.withProfile(RouteParams.parse(p.route().get()).profile());
        bot.start(new ReplayProcess(plan, edges, goal, foot, settings, p.mode() == WalkMode.FOLLOW, p.goalReached()));
        return new WalkingPlan(p.bot(), plan.size());
    }

    private static List<BlockPos> cells(List<BlockPosition> ps) {
        List<BlockPos> out = new ArrayList<>(ps.size());
        for (BlockPosition c : ps) out.add(Positions.blockPos(c));
        return out;
    }
}
