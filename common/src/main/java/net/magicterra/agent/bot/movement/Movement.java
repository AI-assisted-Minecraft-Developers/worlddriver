package net.magicterra.agent.bot.movement;

/** A single migrated move that advances itself one tick against {@link MovementContext},
 *  emitting inputs via the context and returning its own status. The Baritone MovementState
 *  form: the move OWNS its PREP→BREAK→ASCEND→CONFIRM sequence and its bounded timeout→cancel. */
public interface Movement {
    /** Advance this move one tick against ctx; emit inputs via ctx; return status. */
    MovementStatus updateState(MovementContext ctx);
}
