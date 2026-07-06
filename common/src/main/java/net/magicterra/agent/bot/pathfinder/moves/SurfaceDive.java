package net.magicterra.agent.bot.pathfinder.moves;

import net.magicterra.agent.bot.pathfinder.Capability;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/**
 * Swim straight down in water, starting from the SURFACE (the complement of
 * {@link SwimDown}, which only continues a descent already begun underwater).
 *
 * <p><b>Why this is a separate, opt-in move.</b> The 2026-06-20 buoyancy-ratchet
 * disaster showed that an UNPLANNED surface dive is a trap: a bot floating at
 * the surface has no reason to dive on its own, and if {@code SwimDown} were
 * ever allowed to fire from a floating foot, A* would happily route a shallow
 * bank climb-out THROUGH a deep dive (cheaper on paper), the executor would
 * over-sink past the intended shallow target to the riverbed, the climb-out
 * would never land, and each replan re-dived deeper — a live-observed
 * y61→y32 ratchet into a boxed, futile-dug deadlock. That is why
 * {@link SwimDown#valid} hard-requires {@code isSubmergedFoot(from)}.
 *
 * <p>But a DELIBERATE, goal-directed dive — "go back down to the underwater
 * base" — is a different animal: the goal cell itself is deep underwater, so
 * there is no shallow alternative to lose to a spurious dive, and the caller
 * has explicitly asked for it. Gating this move on the opt-in
 * {@link Capability#DIVE} (see {@link net.magicterra.agent.bot.pathfinder.CapabilityProfile#allowsOptIn})
 * means it is pruned from every search by default — {@code SwimDown} keeps
 * sole ownership of "is already submerged" — and only fires when a goto
 * explicitly requests {@code dive:true}.
 *
 * <p>The executor needs no new machinery for this: the Walker's dive actuation
 * (pitch-down + active sink + suppressed swim-up jump) is keyed on the edge
 * NAME via {@code move.startsWith("swimDown")} (see Walker.java), not on
 * whether the foot was already submerged when the edge was chosen — so naming
 * this move {@code "swimDownSurface"} inherits every one of those exemptions
 * for free.
 */
public final class SurfaceDive extends Move {
    public SurfaceDive() { super(0, -1, 0, 30); }
    public boolean valid(WorldView w, BlockPos from) {
        // The surface complement of SwimDown: same water-above/water-below
        // shape, but ONLY from a foot that is NOT already submerged (SwimDown
        // owns the already-submerged continuation; the two are mutually
        // exclusive so a search with DIVE opted in never double-counts a step).
        return w.isWater(from) && w.isWater(apply(from)) && !w.isSubmergedFoot(from);
    }
    public Capability optInCapability() { return Capability.DIVE; }
    public String name() { return "swimDownSurface"; }
}
