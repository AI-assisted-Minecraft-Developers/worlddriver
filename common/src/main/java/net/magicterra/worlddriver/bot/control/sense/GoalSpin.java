package net.magicterra.worlddriver.bot.control.sense;

/** Anti-spin bookkeeping for one bot's goal: the best distance reached, and repaths that failed to improve it. */
public final class GoalSpin {
    public double bestDistToGoal = Double.POSITIVE_INFINITY;

    public double bestGoalDist =
            Double.POSITIVE_INFINITY; // anti-spin: best goal-estimate across repaths (5-block margin ignores

    // micro-lunges)
    public int repathsNoProgress; // anti-spin: consecutive in-water repaths that didn't improve goalSpin.bestGoalDist

    public int
            churnResets; // anti-spin: fresh baselines granted after a stall (tolerates water go-arounds; real progress
    // clears it)

    /** Clears the spin bookkeeping when the bot gets a new goal. */
    public void resetForNewGoal() {
        bestDistToGoal = Double.POSITIVE_INFINITY;
        bestGoalDist = Double.POSITIVE_INFINITY;
        repathsNoProgress = 0;
    }
}
