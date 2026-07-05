package net.magicterra.agent.bot.process;

/** Spec of a DYNAMIC leash anchor: tether the route near a moving entity.
 *  {@code entity} is a player/entity name (no colon) or an entity type id
 *  (with colon, e.g. "minecraft:armor_stand"). {@code hard} picks
 *  LeashHardRadius (prune) vs LeashAnchor (soft cost). Resolved per re-solve
 *  by {@link IntentProcess} — never inside the immutable search. */
public record EntityLeash(String entity, double radius, double weight, boolean hard) {}
