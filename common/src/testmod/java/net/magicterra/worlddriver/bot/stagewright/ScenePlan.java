package net.magicterra.worlddriver.bot.stagewright;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.minecraft.core.BlockPos;

/**
 * The two shapes a scene may hand {@link Walker#adoptForTest}, each built by a factory that says
 * which one it is.
 *
 * <p><b>Why this class exists.</b> Both shapes are a {@code List<BlockPos>} plus a
 * {@code List<Move.Edge>}, and one of them deliberately omits the start node — so a plan that is
 * MALFORMED and a plan that is a deliberately mis-anchored TEST INPUT are indistinguishable by
 * inspection. {@code wd.surfacePillarPointerNeedsItsSupport} spent three gate runs executing zero
 * ticks of its own subject because it handed over the first shape while meaning the second's
 * freedom; {@code wd.deepWaterFloatBeeline} has handed over the second shape correctly all along.
 * Naming the factories puts the difference in the source instead of in the reader's head.
 *
 * <p><b>The rule this class is the standing reminder of.</b> A scanner matches SHAPE; what decides
 * which family a site belongs to is WHAT THE CALL SITE WANTS IT TO DO, and that is not in the
 * shape. All six {@code adoptForTest} sites in this source set satisfy {@code plan[0] != foot}.
 * In one it was the defect; in five it is the question being asked. "N places look alike" is a
 * reason to START investigating, never a reason to merge them — read the call sites first. The
 * same rule saved {@code BotUtil.stepAwayCardinal}, whose two callers' openness tests disagree on
 * thin blocks, so folding them onto one predicate would have loosened both.
 */
public record ScenePlan(List<BlockPos> nodes, List<Move.Edge> edges) {

    /**
     * A plan in the shape every real {@code PathFinder.Result} has, for a scene that goes on to
     * TICK the walker — i.e. the 5-arg {@link Walker#adoptForTest} seam.
     *
     * <p>Callers pass only the nodes they want WALKED. This prepends the start node and the
     * {@code null} lead edge, because {@code PathFinder.build}'s javadoc says the start has no
     * incoming edge and {@code adoptPath} relies on it: it sets {@code step = 1} unconditionally,
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
                        + " — the walker drives edges.get(i) to reach path.get(i)");
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

    /**
     * A continuation segment that deliberately does NOT start at the foot, for a scene that only
     * reads {@code adoptPath}'s VERDICT — i.e. the 4-arg {@link Walker#adoptForTest} seam, whose
     * javadoc explains why the two arities differ.
     *
     * <p>This is the input the segment anchor-gate exists to judge, so the missing start node is
     * the question, not a defect. {@code wd.deepWaterFloatBeeline} asks it five ways and expects a
     * REJECT in three of them; give those arms a start node and the gate has nothing left to
     * reject, so three assertions stop being about anything.
     *
     * <p>Do not tick a walker that adopted one of these. A segment adopted this way is best-effort
     * — the walker is entitled to run a continuation search and replace it, edges and all.
     */
    public static ScenePlan misanchoredSegment(List<BlockPos> nodes, List<Move.Edge> edges) {
        if (nodes.size() != edges.size())
            throw new IllegalArgumentException("misanchoredSegment: " + nodes.size() + " nodes but "
                    + edges.size() + " edges");
        if (edges.isEmpty() || edges.get(0) == null)
            throw new IllegalArgumentException("misanchoredSegment: edges[0] must be a real edge — a "
                    + "null lead edge is the mark of a plan that starts at the foot, which is the "
                    + "one thing this shape is not");
        return new ScenePlan(List.copyOf(nodes), List.copyOf(edges));
    }
}
