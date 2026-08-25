package net.magicterra.worlddriver.bot.movement;

import net.magicterra.worlddriver.bot.Goal;
import net.minecraft.core.BlockPos;

/**
 * Search-stage governors (task#96 step B): the unreachable-goal churn guard (gap #49-③)
 * and its kickoff backoff, owned by {@link WalkerTickSearch} (the backoff is honored by
 * {@link WalkerTickRepath}'s kickoff gates). Reset per journey via {@link #reset()}; the
 * water anti-spin's {@code goalSpin.churnResets} deliberately lives OUTSIDE (it persists
 * across goals — see its field doc).
 *
 * <p>A top-level class rather than a {@code Walker} nested one purely so it can carry its
 * own documentation: Walker.java sits at its 3000-line budget, where every explanatory
 * line competes with code for the same allowance.
 */
final class SearchGovernors {
    double futileBestDist = Double.POSITIVE_INFINITY;  // goalSpin.bestDistToGoal snapshot at last counted search
    BlockPos futileFoot;                                // foot snapshot at last counted search
    /** The goal that was in force when {@link #futileBestDist} was taken — the yardstick, kept so a
     *  MOVING goal cannot hand the body progress it did not make.
     *
     *  <p>A pursuit re-goals as its quarry moves, and every re-goal resets {@code bestDistToGoal},
     *  so the quarry drifting one block closer reads as the body having earned a block. Judging
     *  today's foot with YESTERDAY's goal removes that for free and for every goal shape. The
     *  arithmetic alternative — subtracting the goal's own displacement — was tried and measured
     *  wrong: {@code Goal.Near.estimate} returns {@code 10 *} blocks, so a one-block descent moves
     *  the estimate by 10 while the displacement being subtracted was 1. Two numbers that both look
     *  like distances, one block-valued and one cost-valued. */
    Goal futileGoal;
    int futileSearches;                                 // consecutive futile completions
    int searchBackoffTicks;                             // no new search kickoff while >0
    /** The foot the futile cap latched on, or null when the cap has not been reached.
     *
     *  <p>{@code Walker.terminal()} stores nothing, so the counter IS the terminal: once it is
     *  at the cap, every later tick runs a full A* only to increment past the cap and re-report
     *  the same failure. Worse, the cap branch returns BEFORE the backoff is armed, so that
     *  repeat costs a whole search budget per tick. Unlatching is a property of the BODY's
     *  position, not of the goal being re-issued — "unreachable from here" stops being true
     *  when "here" changes — so a caller re-goaling at a moving quarry must not clear it, and
     *  a body that gets knocked back or falls must. */
    BlockPos futileLatchFoot;
    int quickCooldown;                                  // ticks before the next quick-start stub attempt (a useless stub backs off)
    int noPathWaitTicks;                                // ticks spent holding a "no path" verdict while self-inflicted stuck-penalties decay
    /** Consecutive ascent dead-zones reported from the SAME foot for the SAME node.
     *
     *  <p>The futile-search cap next to it cannot cover this: it is gated on
     *  {@code !res.goalReached()}, and a dead-zone happens when the search SUCCEEDS and the
     *  executor then refuses the edge it produced. Measured on journey rung 20 (2026-08-18): a
     *  body perched on a 0.16 sole beside the void re-routed for 2400 ticks — the whole leg
     *  budget — and every re-route returned the identical {@code diagUp} to the identical node,
     *  while the footing guard, the stride floor-guard and the recovery hop each correctly
     *  refused to move it. Four right answers and no legal move ({@code a-retry-that-changes-nothing}
     *  in the executor rather than in the search). */
    int deadZoneRepeats;
    BlockPos deadZoneFoot;
    BlockPos deadZoneNode;

    /** True while the futile cap has latched and the body has not left the cell it latched on.
     *  Uses the same &gt;2-block predicate the gate itself uses for {@code moved}, so the two
     *  cannot disagree about whether the body went anywhere.
     *
     *  <p>Read by BOTH of {@link WalkerTickRepath}'s kickoff gates: while this holds, no search is
     *  started at all. {@link #searchBackoffTicks} cannot cover it — the cap branch returns before
     *  arming the backoff, so a latched terminal would otherwise cost a full search every tick. */
    boolean futileLatched(BlockPos foot) {
        return futileLatchFoot != null && futileLatchFoot.distSqr(foot) <= 4;
    }

    void reset() {
        deadZoneRepeats = 0;
        deadZoneFoot = null;
        deadZoneNode = null;
        futileBestDist = Double.POSITIVE_INFINITY;
        futileFoot = null;
        futileGoal = null;
        futileLatchFoot = null;
        futileSearches = 0;
        searchBackoffTicks = 0;
        quickCooldown = 0;
        noPathWaitTicks = 0;
    }
}
