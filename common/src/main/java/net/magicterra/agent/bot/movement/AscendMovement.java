package net.magicterra.agent.bot.movement;

/** Per-move state machine for the ascent/climb family (stepUp, stairUpBreak, diagUp). Owns its own
 *  PREP→BREAK→ASCEND→CONFIRM sequence, input emission, and bounded timeout→cancel. See spec §4.
 *  Unit 1: empty stub (flag stays OFF; never invoked). Filled in Units 2–5. */
public final class AscendMovement implements Movement {
    @Override public MovementStatus updateState(MovementContext ctx) {
        return MovementStatus.PREP;   // stub — real transitions land in Units 2–5
    }
}
