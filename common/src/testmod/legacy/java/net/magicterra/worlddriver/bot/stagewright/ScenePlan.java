package net.magicterra.worlddriver.bot.stagewright;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.magicterra.worlddriver.api.methods.debug.DebugContracts.PlanEdge;
import net.minecraft.core.BlockPos;

/**
 * Plans a scene hands {@code mc.debug.walkPlan}: one edge per node, and a factory that checks the shape of a plan
 * the navigator adopts as its own.
 *
 * <p><b>Why the factory exists.</b> A plan that omits the start node is indistinguishable by inspection from a
 * deliberately mis-anchored test input. {@code wd.surfacePillarPointerNeedsItsSupport} spent three gate runs
 * executing zero ticks of its own subject because it handed over a plan without the start node. The mis-anchored
 * shape is now asked only of the adoption's verdict, in {@code DeepWaterFloatBeelineTest}, so the one shape left
 * here is the one a walking scene needs.
 *
 * <p><b>The rule this class is the standing reminder of.</b> A scanner matches SHAPE; what decides
 * which family a site belongs to is WHAT THE CALL SITE WANTS IT TO DO, and that is not in the
 * shape. "N places look alike" is a reason to START investigating, never a reason to merge them —
 * read the call sites first. The same rule saved {@code BotUtil.stepAwayCardinal}, whose two callers'
 * openness tests disagree on thin blocks, so folding them onto one predicate would have loosened both.
 */
public final class ScenePlan {
    private ScenePlan() {}

    /** The edge entering {@code to} by {@code move}, costing 10, breaking and placing nothing. */
    public static PlanEdge edge(BlockPos to, String move) {
        return edge(to, move, List.of());
    }

    /** The edge entering {@code to} by {@code move}, costing 10 and placing {@code places}. */
    public static PlanEdge edge(BlockPos to, String move, List<BlockPos> places) {
        return new PlanEdge(Targets.pos(to), Optional.of(move), 10, List.of(), places.stream().map(Targets::pos).toList(),
                Map.of());
    }

    /**
     * A plan in the shape every real search result has, for {@code mode: 'follow'}.
     *
     * <p>Callers pass only the edges they want WALKED. This prepends the start node with no move, because a
     * search's start has no incoming edge and the adoption relies on it: it sets {@code step = 1}
     * unconditionally, meaning "the bot already stands on node 0". Hand it a plan without that node and the
     * pointer starts one past the only edge there was — a plan that is finished the instant it is adopted,
     * which reports as a clean ARRIVED and executes nothing.
     *
     * @param foot  where the bot actually stands; becomes node 0
     * @param edges the edges to walk, in order, none entering {@code foot}
     */
    public static List<PlanEdge> syntheticPlan(BlockPos foot, List<PlanEdge> edges) {
        if (edges.isEmpty())
            throw new IllegalArgumentException("syntheticPlan: no nodes to walk — the bot is already there");
        if (edges.get(0).to().equals(Targets.pos(foot)))
            throw new IllegalArgumentException("syntheticPlan: edges[0] enters the foot cell " + foot
                    + " — pass only the cells to WALK; the start node is this factory's job");
        for (int i = 0; i < edges.size(); i++) {
            if (edges.get(i).move().isEmpty())
                throw new IllegalArgumentException("syntheticPlan: edges[" + i + "] has no move — only the "
                        + "LEAD edge has none, and this factory adds it");
        }
        List<PlanEdge> full = new ArrayList<>(edges.size() + 1);
        full.add(new PlanEdge(Targets.pos(foot), Optional.empty(), 0, List.of(), List.of(), Map.of()));
        full.addAll(edges);
        return Collections.unmodifiableList(full);
    }
}
