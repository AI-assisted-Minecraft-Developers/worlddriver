package net.magicterra.agent.bot.pathfinder;

import java.util.List;

/** The per-intent inputs to a search, bundled so {@link PathFinder}/Walker thread
 *  ONE value instead of three parallel params. {@link #NONE} = plain search,
 *  byte-identical to pre-A2a. All fields defensively copied / defaulted. */
public record SearchProfile(List<CostModifier> bias, CapabilityProfile capability, List<Constraint> constraints) {
    public static final SearchProfile NONE =
            new SearchProfile(List.of(), CapabilityProfile.ALL, List.of());

    public SearchProfile {
        bias = (bias == null) ? List.of() : List.copyOf(bias);
        capability = (capability == null) ? CapabilityProfile.ALL : capability;
        constraints = (constraints == null) ? List.of() : List.copyOf(constraints);
    }
}
