package net.magicterra.worlddriver.bot.control.route;

/**
 * Counts the ticks the bot has stayed within two blocks of an anchor point; the anchor moves when the bot leaves it.
 */
public final class PhysicalStall {
    /** The anchor's X, or NaN before the first reading. */
    public double anchorX = Double.NaN;

    /** The anchor's Z. */
    public double anchorZ;

    /** Ticks spent near the current anchor. */
    public int stallTicks;
}
