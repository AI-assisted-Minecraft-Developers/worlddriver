package net.magicterra.agent.bot.pathfinder;

/** A move-type category that a {@link CapabilityProfile} can forbid per-intent.
 *  {@code NONE} = ungated (the vast majority of moves). A2a wires only PARKOUR;
 *  A2b will add PLACE/SWIM/DIG. */
public enum Capability { NONE, PARKOUR }
