package net.magicterra.worlddriver.bot.control.route;

public final class StepProgress {
    public double bestStepDist = Double.POSITIVE_INFINITY; // closest approach² to the current node (drives the progress-based stuckTicks)
    public int stuckStep = -1;                         // path index bestStepDist tracks; a step change starts a fresh progress window
    public int stuckStepHigh = -1;                     // walkerStuckStepMonotonic HIGH-WATER mark: the stall clock only resets when step exceeds this (C33-J2: an oscillating pointer 5↔6 cleared the clock on every "advance" because the retreat re-base LOWERED stuckStep — the high-water mark never goes down within one path)
    public int noStepProgressTicks;                    // jitter-immune ticks on the SAME step (resets only when step advances/path changes) → wedge detector
    public int noProgressStep = -1;                    // path index noStepProgressTicks tracks (independent of bridge/progress resets)
    public double noProgressBestD2 = Double.POSITIVE_INFINITY; // closest-ever approach² to the tracked step; monotonic, so a bob can't reset the wedge timer but a slow water cruise along a long string-pulled edge does
    public int crestOrbitTicks;                        // BOB-IMMUNE dwell on a dry stepUp/diagUp CREST step: resets ONLY on step-advance/path-change, never on the 3D new-low the vertical bob fakes on dry land → lets walkerStepUpCrestReach fire on a WIDE bob orbit where noStepProgressTicks keeps zeroing
    public int crestOrbitStep = -1;                    // path index crestOrbitTicks tracks
    public int rawStepDwellTicks;                      // PURELY step-tracked dwell (resets ONLY on step-change/path-change, NEVER on a 3D new-low) → jitter-immune wedge gate for the steep-ascent slide-back recovery (walkerAscentRamJitterImmune)
    public int ramRecoverLastFireDwell = -1;           // rawStepDwellTicks at the last walkerAscentRamJitterImmune fold (-1 = none this episode); a RAM_RECOVER_DEBOUNCE gap caps the fold rate AND re-attempts a stubborn same-step ram; cleared on step/path change
    public void restartWindows() {
        bestStepDist = Double.POSITIVE_INFINITY;
        stuckStep = -1;
        stuckStepHigh = -1;
        noStepProgressTicks = 0;
        noProgressStep = -1;
    }
}
