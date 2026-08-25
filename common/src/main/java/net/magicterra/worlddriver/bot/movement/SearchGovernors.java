package net.magicterra.worlddriver.bot.movement;

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
    int futileSearches;                                 // consecutive futile completions
    int searchBackoffTicks;                             // no new search kickoff while >0
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

    void reset() {
        deadZoneRepeats = 0;
        deadZoneFoot = null;
        deadZoneNode = null;
        futileBestDist = Double.POSITIVE_INFINITY;
        futileFoot = null;
        futileSearches = 0;
        searchBackoffTicks = 0;
        quickCooldown = 0;
        noPathWaitTicks = 0;
    }
}
