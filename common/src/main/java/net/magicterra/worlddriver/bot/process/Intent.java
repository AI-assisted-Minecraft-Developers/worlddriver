package net.magicterra.worlddriver.bot.process;

import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.pathfinder.CapabilityProfile;
import net.magicterra.worlddriver.bot.pathfinder.Constraint;
import net.magicterra.worlddriver.bot.pathfinder.CostModifier;
import net.magicterra.worlddriver.bot.pathfinder.SearchProfile;

import java.util.List;

/**
 * The declarative unit of navigation the {@link IntentProcess} interprets. Holds
 * the target {@link Goal} and a per-intent {@code bias} — a list of
 * {@link CostModifier}s appended to the pathfinder's cost stack for THIS intent
 * (avoid a region, prefer a Y band, leash to an anchor), plus a
 * {@link CapabilityProfile} and a list of {@link Constraint}s. A4a threaded the
 * (empty) bias through; A2a threads the full {@link SearchProfile}. Later
 * phases add terminators and the mutable-goal {@code amend} operation.
 */
public final class Intent {
    private final Goal target;
    private final List<CostModifier> bias;
    private final CapabilityProfile capability;
    private final List<Constraint> constraints;
    private final EntityLeash entityLeash;

    public Intent(Goal target) {
        this(target, List.of(), CapabilityProfile.ALL, List.of(), null);
    }

    public Intent(Goal target, List<CostModifier> bias) {
        this(target, bias, CapabilityProfile.ALL, List.of(), null);
    }

    public Intent(Goal target, List<CostModifier> bias, CapabilityProfile capability, List<Constraint> constraints) {
        this(target, bias, capability, constraints, null);
    }

    public Intent(Goal target, List<CostModifier> bias, CapabilityProfile capability, List<Constraint> constraints,
                  EntityLeash entityLeash) {
        if (target == null) throw new IllegalArgumentException("intent target is null");
        this.target = target;
        this.bias = (bias == null) ? List.of() : List.copyOf(bias);
        this.capability = (capability == null) ? CapabilityProfile.ALL : capability;
        this.constraints = (constraints == null) ? List.of() : List.copyOf(constraints);
        this.entityLeash = entityLeash;
    }

    /** The A* goal this intent currently converges on. */
    public Goal target() {
        return target;
    }

    /** Per-intent cost modifiers appended to the pathfinder stack. Empty = plain navigation. */
    public List<CostModifier> bias() {
        return bias;
    }

    /** The mobility envelope this intent's pathfinder search must respect. */
    public CapabilityProfile capability() {
        return capability;
    }

    /** Hard constraints this intent's pathfinder search must satisfy. */
    public List<Constraint> constraints() {
        return constraints;
    }

    /** The full {@link SearchProfile} — bias, capability, and constraints — for this intent. */
    public SearchProfile searchProfile() {
        return new SearchProfile(bias, capability, constraints);
    }

    /** Optional dynamic entity-anchor leash (A3a); {@code null} = no leash, byte-identical
     *  to pre-A3a behavior. Resolved per re-solve by {@link IntentProcess}. */
    public EntityLeash entityLeash() {
        return entityLeash;
    }
}
