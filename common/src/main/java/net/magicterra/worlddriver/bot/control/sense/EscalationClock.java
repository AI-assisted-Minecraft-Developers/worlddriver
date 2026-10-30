package net.magicterra.worlddriver.bot.control.sense;

/** A sticky timer that keeps boxed-churn escalation armed for a few ticks after it is triggered. */
public final class EscalationClock {
    public long tick; // monotonic per-tick counter (drives the sticky boxed-escalation timer)

    public long
            untilTick; // escalation armed until this tick (sticky so a few net-progress windows mid-climb don't drop

    // it)
    public boolean wasArmed; // armed on the previous tick, so the debug line marks the edge only

    /** True while the escalation timer has not yet lapsed. */
    public boolean armed() {
        return tick < untilTick;
    }

    /** Arms the escalation for {@code stickyTicks} ticks from now. */
    public void arm(long stickyTicks) {
        untilTick = tick + stickyTicks;
    }

    /** Ends the escalation at once. */
    public void disarm() {
        untilTick = 0;
    }
}
