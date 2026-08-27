package net.magicterra.worlddriver.bot.movement;

import net.minecraft.core.BlockPos;

/** Thin decision-weave machine for the ascent family (stepUp, stairUpBreak, diagUp).
 *
 *  B1 architecture (plan 2026-07-16-executor-b1-thin-machine): the legacy drive in Walker.tickInner
 *  stays the SINGLE actuation source — the delegation branch falls through on PREP/RUNNING/SUCCESS
 *  and this machine emits no inputs of its own. It owns only the per-edge episode lifecycle and the
 *  dig-aware no-progress watchdog (→ UNREACHABLE, the task#82 dead-zone fix: the cur2∈(0.45,4.0),
 *  no-hCol pose that every legacy recovery gate misses — spec §2.2 — finally gets a trigger into
 *  the fellOffPath re-route backbone). Step advancement stays with the legacy advance loop
 *  (Walker ~2265); SUCCESS is telemetry-only.
 *
 *  Progress marks are MONOTONIC high-waters (best Y ever, best gap-close ever), so a
 *  jump-land-slideback bob cannot keep resetting the clock — the same trick crestOrbitTicks uses
 *  against the vertical bob. An active planned dig (ctx.digging) is progress by definition
 *  (bare-hand stone is 150 t+/block, #66). */
public final class AscendMovement implements Movement {
    /** Public alias of the watchdog budget (WalkerConstants is package-private; the gametest seam needs the number). */
    public static final int DEADZONE_GIVEUP = WalkerConstants.ASCEND_DEADZONE_GIVEUP;
    private static final double PROGRESS_EPS_Y = 0.01;     // dy high-water must beat this to count (float-jitter guard)
    private static final double PROGRESS_EPS_GAP = 0.01;   // cur2 low-water must improve by this to count

    // Episode = consecutive delegated ticks on the same stand-cell node + move name. A node or move
    // change (legacy advanced the step, or a repath swapped the edge) starts a fresh episode.
    private BlockPos episodeNode;
    private String episodeMove;
    private double episodeBestY;        // dy-progress high-water mark for this episode
    private double episodeBestCur2;     // horizontal gap-close low-water (squared dist to node center)
    private int ticksSinceProgress;     // dead-zone clock: ticks with no high-water gain and no active dig

    @Override public MovementStatus updateState(MovementContext ctx) {
        if (episodeNode == null || !episodeNode.equals(ctx.node) || !episodeMove.equals(ctx.edge.move)) {
            episodeNode = ctx.node.immutable();
            episodeMove = ctx.edge.move;
            episodeBestY = ctx.p.getY();
            episodeBestCur2 = cur2(ctx);
            ticksSinceProgress = 0;
            return MovementStatus.PREP;
        }
        boolean progressed = false;
        if (ctx.p.getY() > episodeBestY + PROGRESS_EPS_Y) { episodeBestY = ctx.p.getY(); progressed = true; }
        double cur2 = cur2(ctx);
        if (cur2 < episodeBestCur2 - PROGRESS_EPS_GAP) { episodeBestCur2 = cur2; progressed = true; }
        if (progressed || ctx.digging) {
            ticksSinceProgress = 0;
        } else if (++ticksSinceProgress > WalkerConstants.ASCEND_DEADZONE_GIVEUP) {
            episodeNode = null;   // a replanned edge on the same node restarts a fresh clock (re-route may legitimately retry it)
            return MovementStatus.UNREACHABLE;
        }
        return MovementStatus.RUNNING;
    }

    private static double cur2(MovementContext ctx) {
        double dx = (ctx.node.getX() + 0.5) - ctx.p.getX();
        double dz = (ctx.node.getZ() + 0.5) - ctx.p.getZ();
        return dx * dx + dz * dz;
    }
}
