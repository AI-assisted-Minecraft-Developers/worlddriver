package net.magicterra.agent.bot.process;

import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.pathfinder.CostModifier;

import java.util.List;

/**
 * The declarative unit of navigation the {@link IntentProcess} interprets. Holds
 * the target {@link Goal} and a per-intent {@code bias} — a list of
 * {@link CostModifier}s appended to the pathfinder's cost stack for THIS intent
 * (avoid a region, prefer a Y band, leash to an anchor). A4a threads the (empty)
 * bias through; A4b adds the modifiers and the verb args that build them. Later
 * phases add a capability profile, hard constraints, terminators, and the
 * mutable-goal {@code amend} operation.
 */
public final class Intent {
    private final Goal target;
    private final List<CostModifier> bias;

    public Intent(Goal target) {
        this(target, List.of());
    }

    public Intent(Goal target, List<CostModifier> bias) {
        if (target == null) throw new IllegalArgumentException("intent target is null");
        this.target = target;
        this.bias = (bias == null) ? List.of() : List.copyOf(bias);
    }

    /** The A* goal this intent currently converges on. */
    public Goal target() {
        return target;
    }

    /** Per-intent cost modifiers appended to the pathfinder stack. Empty = plain navigation. */
    public List<CostModifier> bias() {
        return bias;
    }
}
