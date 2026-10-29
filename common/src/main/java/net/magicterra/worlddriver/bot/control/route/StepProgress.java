package net.magicterra.worlddriver.bot.control.route;

public final class StepProgress {
    public double bestStepDist = Double.POSITIVE_INFINITY; // closest approach² to the current node (drives the progress-based stuckTicks)
    public int stuckStep = -1;                         // path index bestStepDist tracks; a step change starts a fresh progress window
    public int stuckStepHigh = -1;                     // navigatorStuckStepMonotonic HIGH-WATER mark: the stall clock only resets when step exceeds this (C33-J2: an oscillating pointer 5↔6 cleared the clock on every "advance" because the retreat re-base LOWERED stuckStep — the high-water mark never goes down within one path)
    public int noStepProgressTicks;                    // jitter-immune ticks on the SAME step (resets only when step advances/path changes) → wedge detector
    public int noProgressStep = -1;                    // path index noStepProgressTicks tracks (independent of bridge/progress resets)
    public double noProgressBestD2 = Double.POSITIVE_INFINITY; // closest-ever approach² to the tracked step; monotonic, so a bob can't reset the wedge timer but a slow water cruise along a long string-pulled edge does
    public void restartWindows() {
        bestStepDist = Double.POSITIVE_INFINITY;
        stuckStep = -1;
        stuckStepHigh = -1;
        noStepProgressTicks = 0;
        noProgressStep = -1;
    }
}
