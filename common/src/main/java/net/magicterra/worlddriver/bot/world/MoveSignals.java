package net.magicterra.worlddriver.bot.world;

/**
 * What one bot's movement said about itself this tick, for the parts of the same bot that run around it: the search's
 * cost model, the swim backstop, the water-bucket clutch.
 *
 * <p>One per {@link WorldView}, so one per bot. These were static flags, and every bot in the JVM wrote the same ones:
 * a server bot's navigator digging switched off the client's swim backstop, and a server bot stepping off a ledge armed
 * the client's clutch. The bot clears them near the start of each tick, once its clutch has taken last tick's plan and
 * before its scheduler runs; the writers set them again while they hold.
 */
public final class MoveSignals {

    /**
     * A flee process ticked: the search prices water and ledges higher, so a fleeing bot does not dive or jump off a
     * cliff to get away.
     */
    public boolean fleeing;

    /**
     * The navigator held a block-break. The swim backstop yields to the dig while air is healthy, instead of bobbing
     * the bot and resetting the break progress.
     */
    public boolean digging;

    /**
     * The navigator's surface sprint-swim holds the eyes under on purpose. The swim backstop yields to it while air is
     * healthy, as it does to a dig.
     */
    public boolean cruising;

    private boolean clutchPlanned;
    private int clutchX;
    private int clutchZ;

    /**
     * The navigator is stepping off a lip it planned to clutch a water bucket under, landing on column {@code (x, z)}.
     * The bot's clutch takes this at the top of its next tick.
     */
    public void planClutch(int x, int z) {
        clutchPlanned = true;
        clutchX = x;
        clutchZ = z;
    }

    public boolean clutchPlanned() {
        return clutchPlanned;
    }

    public int clutchX() {
        return clutchX;
    }

    public int clutchZ() {
        return clutchZ;
    }

    /** Start of a bot tick: nothing has been said yet. */
    public void clear() {
        fleeing = false;
        digging = false;
        cruising = false;
        clutchPlanned = false;
    }
}
