package net.magicterra.worlddriver.bot;

import java.util.concurrent.CopyOnWriteArrayList;

import net.magicterra.worlddriver.bot.planning.PathFinder;

/**
 * Phase 2 registry: the server-tick driver of every active
 * {@link Bot}. The platform calls {@link #tickAll()} once per
 * server tick (from {@code ServerTickEvent.Post}); a driver that has finished
 * (reached/failed) is dropped automatically, and a driver that throws is
 * removed so a single faulty agent can never wedge the whole server tick.
 *
 * <p>This is the seam that makes agent driving headless: the same Walker loop
 * the arenas exercise now runs autonomously on the dedicated-server tick, with
 * no {@code LocalPlayer} / client involved.
 *
 * <p>MIGRATION (P1.6 Task 1): this is the SINGLE registry, shared by every loader, so the
 * {@code /worlddriver server} command ({@link BotCommand}) and the dogfood scenes land in
 * the same list that {@code WorldDriverEvents}' server-tick handler drives.
 *
 * <p>The list holds {@link Bot}s rather than {@link PlayerBot}s, so a driver over a
 * controlled entity that is not a player rides the same tick and the same crash guard.
 */
public final class BotTicker {
    private BotTicker() {}

    private static final CopyOnWriteArrayList<Bot> ACTIVE = new CopyOnWriteArrayList<>();

    // The server tick loads this class before any driver can search, so the planner's stall rows
    // count drivers from the first one on.
    static { PathFinder.driverCount = BotTicker::activeCount; }

    public static void register(Bot d) { ACTIVE.addIfAbsent(d); }
    public static void unregister(Bot d) { ACTIVE.remove(d); }
    /** Whether {@code d} is still on the tick list; {@link #tickAll} drops a finished driver itself. */
    public static boolean isRegistered(Bot d) { return ACTIVE.contains(d); }
    public static int activeCount() { return ACTIVE.size(); }
    public static void clear() { ACTIVE.clear(); }

    /** Advance every registered driver one tick; drop finished/crashed ones. */
    public static void tickAll() {
        for (Bot d : ACTIVE) {
            try {
                d.tick();
                if (d.finished()) ACTIVE.remove(d);
            } catch (Throwable t) {
                net.magicterra.worlddriver.config.Mod.LOG.error("[BotTicker] driver crashed, removing", t);
                ACTIVE.remove(d);   // a crashed driver must not wedge the server tick
                try { d.crashed(t); }
                catch (Throwable again) {
                    net.magicterra.worlddriver.config.Mod.LOG.error("[BotTicker] ending the crashed driver's task failed", again);
                }
            }
        }
    }
}
