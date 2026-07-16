package net.magicterra.agent.bot.movement;

import net.minecraft.core.BlockPos;

/** Thin decision-weave machine for the ascent family (stepUp, stairUpBreak, diagUp).
 *
 *  B1 architecture (plan 2026-07-16-executor-b1-thin-machine): the legacy drive in Walker.tickInner
 *  stays the SINGLE actuation source — the delegation branch falls through on PREP/RUNNING/SUCCESS
 *  and this machine emits no inputs of its own. It owns only the per-edge episode lifecycle and the
 *  dig-aware no-progress watchdog (→ UNREACHABLE, the task#82 dead-zone fix). Step advancement
 *  stays with the legacy advance loop (Walker ~2265); SUCCESS is telemetry-only.
 *
 *  B1-1: episode tracker only — the watchdog is UNARMED (never returns a terminal), so flag-ON is a
 *  behavior no-op. B1-2 arms the dead-zone clock (no dy gain AND no active dig for N ticks). */
public final class AscendMovement implements Movement {
    // Episode = consecutive delegated ticks on the same stand-cell node + move name. A node or move
    // change (legacy advanced the step, or a repath swapped the edge) starts a fresh episode.
    private BlockPos episodeNode;
    private String episodeMove;
    private int episodeTicks;
    private double episodeBestY;        // dy-progress high-water mark for this episode
    private int ticksSinceProgress;     // dead-zone clock: ticks with no dy gain (B1-2 adds: and no active dig)

    @Override public MovementStatus updateState(MovementContext ctx) {
        if (episodeNode == null || !episodeNode.equals(ctx.node) || !episodeMove.equals(ctx.edge.move)) {
            episodeNode = ctx.node.immutable();
            episodeMove = ctx.edge.move;
            episodeTicks = 0;
            episodeBestY = ctx.p.getY();
            ticksSinceProgress = 0;
            return MovementStatus.PREP;
        }
        episodeTicks++;
        if (ctx.p.getY() > episodeBestY + 1e-4) {
            episodeBestY = ctx.p.getY();
            ticksSinceProgress = 0;
        } else {
            ticksSinceProgress++;
        }
        // B1-2 arms here: ticksSinceProgress (gated on no active dig) past a bound → UNREACHABLE.
        return MovementStatus.RUNNING;
    }
}
