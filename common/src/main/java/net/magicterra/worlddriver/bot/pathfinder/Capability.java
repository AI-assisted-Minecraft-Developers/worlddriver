package net.magicterra.worlddriver.bot.pathfinder;

/** A move-type category that a {@link CapabilityProfile} can forbid (or, for an
 *  opt-in category, permit) per-intent. {@code NONE} = ungated (the vast majority
 *  of moves). A2a wires only PARKOUR (forbid-set); A5 adds DIVE as the first
 *  OPT-IN category (see {@link CapabilityProfile#allowsOptIn}) — a move gated on
 *  DIVE is pruned from EVERY search unless the intent explicitly opts in. */
public enum Capability { NONE, PARKOUR, DIVE }
