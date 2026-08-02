package net.magicterra.worlddriver.bot.sim;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Phase 2 registry: the server-tick driver of every active
 * {@link ServerWorldDriver}. The platform calls {@link #tickAll()} once per
 * server tick (from {@code ServerTickEvent.Post}); a driver that has finished
 * (reached/failed) is dropped automatically, and a driver that throws is
 * removed so a single faulty agent can never wedge the whole server tick.
 *
 * <p>This is the seam that makes agent driving headless: the same Walker loop
 * the arenas exercise now runs autonomously on the dedicated-server tick, with
 * no {@code LocalPlayer} / client involved.
 *
 * <p>MIGRATION (P1.6 Task 1): this is now the SINGLE registry, shared by every
 * loader. The NeoForge {@code net.magicterra.worlddriver.neoforge.sim.ServerAvatarManager}
 * of the same simple name is a static-delegation shim onto this class, so
 * {@code WorldDriverNeoForge}'s per-server-tick {@code tickAll()} and the common
 * dogfood scenes land in the same list.
 */
public final class ServerAvatarManager {
    private ServerAvatarManager() {}

    private static final CopyOnWriteArrayList<ServerWorldDriver> ACTIVE = new CopyOnWriteArrayList<>();

    public static void register(ServerWorldDriver d) { ACTIVE.addIfAbsent(d); }
    public static void unregister(ServerWorldDriver d) { ACTIVE.remove(d); }
    public static int activeCount() { return ACTIVE.size(); }
    public static void clear() { ACTIVE.clear(); }

    /** Advance every registered driver one tick; drop finished/crashed ones. */
    public static void tickAll() {
        for (ServerWorldDriver d : ACTIVE) {
            try {
                d.tick();
                if (d.finished()) ACTIVE.remove(d);
            } catch (Throwable t) {
                net.magicterra.worlddriver.WorldDriverCommon.LOG.error("[ServerAvatarManager] driver crashed, removing", t);
                ACTIVE.remove(d);   // a crashed driver must not wedge the server tick
            }
        }
    }
}
