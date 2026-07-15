package net.magicterra.agent.bot.movement;

/** Result of one {@link Movement#updateState} tick. SUCCESS is a clean pointer-advance; both
 *  failure codes fold into the existing fellOffPath re-route but carry different telemetry —
 *  UNREACHABLE = "could not close the gap within budget" (the task#82 case), FAILED = "edge is
 *  malformed / the world changed under us". */
public enum MovementStatus {
    PREP,        // still aligning/positioning; not yet actuating the core maneuver
    RUNNING,     // actuating (breaking/jumping/rising); hold the step pointer
    SUCCESS,     // arrived on the destination stand cell → caller does step++
    UNREACHABLE, // bounded timeout with no actuation progress → caller re-routes (blacklist node)
    FAILED       // hard error (edge/world inconsistency) → caller re-routes
}
