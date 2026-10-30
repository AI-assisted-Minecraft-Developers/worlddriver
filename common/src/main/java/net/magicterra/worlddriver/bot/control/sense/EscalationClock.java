package net.magicterra.worlddriver.bot.control.sense;

public final class EscalationClock {
    public long tick; // monotonic per-tick counter (drives the sticky boxed-escalation timer)

    public long
            untilTick; // escalation armed until this tick (sticky so a few net-progress windows mid-climb don't drop

    // it)
    public boolean wasArmed; // armed on the previous tick, so the debug line marks the edge only

    public boolean armed() {
        return tick < untilTick;
    }

    public void arm(long stickyTicks) {
        untilTick = tick + stickyTicks;
    }

    public void disarm() {
        untilTick = 0;
    }
}
