package net.magicterra.worlddriver.bot.pathfinder;

import net.magicterra.worlddriver.bot.Goal;
import net.minecraft.core.BlockPos;

/**
 * A composable per-edge cost contribution added to the A* g-cost when a move
 * enters {@code to} from {@code from}. The A0 refactor extracts the eight
 * hardcoded taxes in {@link PathFinder.Search} behind this interface without
 * changing behavior; later phases (the LLM navigation intent layer) add and
 * remove modifiers per intent (avoid / leash / preferY / water taxes ...).
 *
 * <p><b>Admissibility contract:</b> implementations MUST return {@code >= 0}.
 * These are edge (g-cost) contributions, not heuristic terms; a negative one
 * would lower the true remaining cost below the fixed Euclidean heuristic's
 * estimate, making {@code h} no longer an underestimate and breaking A*
 * optimality/termination.
 *
 * <p>{@code goal} and {@code world} are supplied so an implementation need not
 * close over a {@link PathFinder.Search}; the A0 adapters ignore them and call
 * the existing {@code Search} tax methods, preserving byte-identical results.
 */
@FunctionalInterface
public interface CostModifier {
    double extraCost(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world);
}
