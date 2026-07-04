package net.magicterra.agent.bot.process;

import net.magicterra.agent.bot.Goal;

/**
 * The declarative unit of navigation the {@link IntentProcess} interprets. Phase
 * A1 holds only the target {@link Goal}; later phases of the LLM navigation intent
 * layer grow this with a cost-modifier stack, a capability profile, hard
 * constraints, terminators, and a watch policy (see the design doc). The
 * mutable-goal {@code amend} operation arrives in phase B alongside its verb, so
 * A1 keeps the target immutable here.
 */
public final class Intent {
    private final Goal target;

    public Intent(Goal target) {
        if (target == null) throw new IllegalArgumentException("intent target is null");
        this.target = target;
    }

    /** The A* goal this intent currently converges on. */
    public Goal target() {
        return target;
    }
}
