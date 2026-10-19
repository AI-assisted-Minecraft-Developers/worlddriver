package net.magicterra.worlddriver.bot.stagewright;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.magicterra.worlddriver.bot.rules.Move;
import net.minecraft.core.BlockPos;

/**
 * A plan a scene hands {@code Navigator.adoptForTest} before it TICKS the navigator, built by a factory that
 * checks its shape.
 *
 * <p><b>Why this class exists.</b> A plan is a {@code List<BlockPos>} plus a {@code List<Move.Edge>}, and a
 * plan that omits the start node is indistinguishable by inspection from a deliberately mis-anchored test
 * input. {@code wd.surfacePillarPointerNeedsItsSupport} spent three gate runs executing zero ticks of its own
 * subject because it handed over a plan without the start node. The mis-anchored shape is now asked only of
 * the adoption's verdict, in {@code DeepWaterFloatBeelineTest}, so the one shape left here is the one a
 * ticking scene needs.
 *
 * <p><b>The rule this class is the standing reminder of.</b> A scanner matches SHAPE; what decides
 * which family a site belongs to is WHAT THE CALL SITE WANTS IT TO DO, and that is not in the
 * shape. "N places look alike" is a reason to START investigating, never a reason to merge them —
 * read the call sites first. The same rule saved {@code BotUtil.stepAwayCardinal}, whose two callers'
 * openness tests disagree on thin blocks, so folding them onto one predicate would have loosened both.
 */
public record ScenePlan(List<BlockPos> nodes, List<Move.Edge> edges) {

    /**
     * A plan in the shape every real {@code PathFinder.Result} has, for a scene that goes on to
     * TICK the navigator — i.e. the 5-arg {@code Navigator.adoptForTest} seam.
     *
     * <p>Callers pass only the nodes they want WALKED. This prepends the start node and the
     * {@code null} lead edge, because {@code PathFinder.build}'s javadoc says the start has no
     * incoming edge and the adoption relies on it: it sets {@code step = 1} unconditionally,
     * meaning "the bot already stands on node 0". Hand it a plan without that node and the
     * pointer starts one past the only edge there was — a plan that is finished the instant it is
     * adopted, which reports as a clean ARRIVED and executes nothing.
     *
     * @param foot  where the bot actually stands; becomes node 0
     * @param nodes the cells to walk, in order, NOT including {@code foot}
     * @param edges one edge per entry in {@code nodes}; {@code edges.get(i)} must enter
     *              {@code nodes.get(i)}, and none may be null
     */
    public static ScenePlan syntheticPlan(BlockPos foot, List<BlockPos> nodes, List<Move.Edge> edges) {
        if (nodes.isEmpty())
            throw new IllegalArgumentException("syntheticPlan: no nodes to walk — the bot is already there");
        if (nodes.size() != edges.size())
            throw new IllegalArgumentException("syntheticPlan: " + nodes.size() + " nodes but "
                    + edges.size() + " edges — one edge ENTERS each node, so the counts must match");
        if (foot.equals(nodes.get(0)))
            throw new IllegalArgumentException("syntheticPlan: nodes[0] is the foot cell " + foot
                    + " — pass only the cells to WALK; the start node is this factory's job");
        for (int i = 0; i < edges.size(); i++) {
            Move.Edge e = edges.get(i);
            if (e == null)
                throw new IllegalArgumentException("syntheticPlan: edges[" + i + "] is null — only the "
                        + "LEAD edge is null, and this factory adds it");
            if (!e.to.equals(nodes.get(i)))
                throw new IllegalArgumentException("syntheticPlan: edges[" + i + "].to=" + e.to
                        + " does not enter nodes[" + i + "]=" + nodes.get(i)
                        + " — the navigator drives edges.get(i) to reach path.get(i)");
        }
        List<BlockPos> fullNodes = new ArrayList<>(nodes.size() + 1);
        fullNodes.add(foot);
        fullNodes.addAll(nodes);
        List<Move.Edge> fullEdges = new ArrayList<>(edges.size() + 1);
        fullEdges.add(null);                       // the start has no incoming edge
        fullEdges.addAll(edges);
        return new ScenePlan(Collections.unmodifiableList(fullNodes),
                Collections.unmodifiableList(fullEdges));
    }
}
