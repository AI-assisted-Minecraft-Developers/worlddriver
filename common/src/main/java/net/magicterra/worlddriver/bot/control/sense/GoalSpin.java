package net.magicterra.worlddriver.bot.control.sense;

public final class GoalSpin {
    public double bestDistToGoal = Double.POSITIVE_INFINITY;
    public double bestGoalDist =
            Double.POSITIVE_INFINITY; // anti-spin: best goal-estimate across repaths (5-block margin ignores
    // micro-lunges)
    public int repathsNoProgress; // anti-spin: consecutive in-water repaths that didn't improve goalSpin.bestGoalDist
    public int
            churnResets; // anti-spin: fresh baselines granted after a stall (tolerates water go-arounds; real progress
    // clears it)

    public void resetForNewGoal() {
        bestDistToGoal = Double.POSITIVE_INFINITY;
        bestGoalDist = Double.POSITIVE_INFINITY;
        repathsNoProgress = 0;
    }
}
