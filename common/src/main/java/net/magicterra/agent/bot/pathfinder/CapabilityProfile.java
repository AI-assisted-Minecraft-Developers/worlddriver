package net.magicterra.agent.bot.pathfinder;

import java.util.EnumSet;
import java.util.Set;

/** Per-intent gate on which move TYPES a search may use. Immutable. The default
 *  {@link #ALL} (empty forbidden set) allows every move → byte-identical no-op. */
public final class CapabilityProfile {
    /** Allows every capability — the no-op default carried by a plain intent. */
    public static final CapabilityProfile ALL = new CapabilityProfile(EnumSet.noneOf(Capability.class));

    private final Set<Capability> forbidden;

    public CapabilityProfile(Set<Capability> forbidden) {
        this.forbidden = (forbidden == null || forbidden.isEmpty())
                ? EnumSet.noneOf(Capability.class) : EnumSet.copyOf(forbidden);
    }

    /** True unless this move's required capability is forbidden. {@code NONE} is never forbidden. */
    public boolean allows(Capability c) { return c == Capability.NONE || !forbidden.contains(c); }
}
