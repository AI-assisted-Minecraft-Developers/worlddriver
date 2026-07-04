package net.magicterra.agent.bot.pathfinder;

import net.magicterra.agent.bot.Goal;
import net.minecraft.core.BlockPos;

/**
 * A composable per-edge cost contribution added to the A* g-cost when a move
 * enters {@code to} from {@code from}. The A0 refactor extracts the eight
 * hardcoded taxes in {@link PathFinder.Search} behind this interface without
 * changing behavior; later phases (the LLM navigation intent layer) add and
 * remove modifiers per intent (avoid / leash / preferY / water taxes ...).
 *
 * <p><b>Admissibility contract:</b> implementations MUST return {@code >= 0}, or
 * the A* heuristic stops being an underestimate and optimality/termination
 * guarantees break.
 *
 * <p>{@code goal} and {@code world} are supplied so an implementation need not
 * close over a {@link PathFinder.Search}; the A0 adapters ignore them and call
 * the existing {@code Search} tax methods, preserving byte-identical results.
 */
@FunctionalInterface
public interface CostModifier {
    double extraCost(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world);
}
