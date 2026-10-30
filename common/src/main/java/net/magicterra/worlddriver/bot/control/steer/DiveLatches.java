package net.magicterra.worlddriver.bot.control.steer;

/** Latches that keep a dive going: a forced dive under the cap, and a hold on an active descent across repaths. */
public final class DiveLatches {
    public int latch; // ticks left forcing a dive-under-cap (set on a blocked submerged descent; holds the dive through

    // the sink so it doesn't flip-flop)
    public int
            hold; // ticks left holding an ACTIVE descent (diving) across repaths — a mid-sink repath re-plans from the
    // buoyancy point with a dy=1 first hop, which alone never re-arms diving, so the bot pops back up
    // (round45 water-well live)

    /** Clears both dive latches. */
    public void reset() {
        latch = 0;
        hold = 0;
    }
}
