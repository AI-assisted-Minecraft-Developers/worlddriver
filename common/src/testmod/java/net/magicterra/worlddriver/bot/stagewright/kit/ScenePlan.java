package net.magicterra.worlddriver.bot.stagewright.kit;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts.PlanEdge;
import net.minecraft.core.BlockPos;

/**
 * Edges of the plans a scene hands {@code mc.debug.walkPlan}, one per node.
 *
 * <p>A plan a scene adopts in {@code mode: 'follow'} must start with the node the bot stands on, carrying no move, the
 * way every real search result does: the adoption sets {@code step = 1} unconditionally, so a plan without that node is
 * finished the instant it is adopted, reports a clean ARRIVED and executes nothing.
 *
 * <p><b>The rule this class is the standing reminder of.</b> A scanner matches SHAPE; what decides which family a site
 * belongs to is WHAT THE CALL SITE WANTS IT TO DO, and that is not in the shape. "N places look alike" is a reason to
 * START investigating, never a reason to merge them — read the call sites first. The same rule saved
 * {@code BotUtil.stepAwayCardinal}, whose two callers' openness tests disagree on thin blocks, so folding them onto one
 * predicate would have loosened both.
 */
public final class ScenePlan {
    private ScenePlan() {}

    /** The edge entering {@code to} by {@code move}, costing 10, breaking and placing nothing. */
    public static PlanEdge edge(BlockPos to, String move) {
        return edge(to, move, List.of());
    }

    /** The edge entering {@code to} by {@code move}, costing 10 and placing {@code places}. */
    public static PlanEdge edge(BlockPos to, String move, List<BlockPos> places) {
        return new PlanEdge(
                Targets.pos(to),
                Optional.of(move),
                10,
                List.of(),
                places.stream().map(Targets::pos).toList(),
                Map.of());
    }
}
