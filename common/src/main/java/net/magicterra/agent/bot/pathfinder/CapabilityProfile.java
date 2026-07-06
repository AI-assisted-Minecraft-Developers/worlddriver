package net.magicterra.agent.bot.pathfinder;

import java.util.EnumSet;
import java.util.Set;

/** Per-intent gate on which move TYPES a search may use. Immutable. The default
 *  {@link #ALL} (empty forbidden set, empty opt-in set) allows every ungated
 *  move and excludes every opt-in move → byte-identical no-op. */
public final class CapabilityProfile {
    /** Allows every capability — the no-op default carried by a plain intent. */
    public static final CapabilityProfile ALL = new CapabilityProfile(EnumSet.noneOf(Capability.class));

    private final Set<Capability> forbidden;
    /** A5: OPT-IN categories (e.g. DIVE) — the complement of {@code forbidden}.
     *  A move tagged with an opt-in capability is pruned from every search UNLESS
     *  its category is in this set; empty by default so {@link #ALL} (and any
     *  profile built via the forbid-only ctor) never fires an opt-in move. */
    private final Set<Capability> optIn;

    public CapabilityProfile(Set<Capability> forbidden) {
        this(forbidden, EnumSet.noneOf(Capability.class));
    }

    public CapabilityProfile(Set<Capability> forbidden, Set<Capability> optIn) {
        this.forbidden = (forbidden == null || forbidden.isEmpty())
                ? EnumSet.noneOf(Capability.class) : EnumSet.copyOf(forbidden);
        this.optIn = (optIn == null || optIn.isEmpty())
                ? EnumSet.noneOf(Capability.class) : EnumSet.copyOf(optIn);
    }

    /** True unless this move's required capability is forbidden. {@code NONE} is never forbidden. */
    public boolean allows(Capability c) { return c == Capability.NONE || !forbidden.contains(c); }

    /** True only if {@code c} was explicitly opted into by this profile. Used for
     *  opt-in-only move categories (A5 DIVE) that must stay pruned everywhere a
     *  plain intent doesn't ask for them, even though they're never "forbidden". */
    public boolean allowsOptIn(Capability c) { return optIn.contains(c); }
}
