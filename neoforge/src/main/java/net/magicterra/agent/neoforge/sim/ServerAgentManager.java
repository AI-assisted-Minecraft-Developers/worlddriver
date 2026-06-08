package net.magicterra.agent.neoforge.sim;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Phase 2 registry: the server-tick driver of every active
 * {@link ServerAgentDriver}. The platform calls {@link #tickAll()} once per
 * server tick (from {@code ServerTickEvent.Post}); a driver that has finished
 * (reached/failed) is dropped automatically, and a driver that throws is
 * removed so a single faulty agent can never wedge the whole server tick.
 *
 * <p>This is the seam that makes agent driving headless: the same Walker loop
 * the arenas exercise now runs autonomously on the dedicated-server tick, with
 * no {@code LocalPlayer} / client involved.
 */
public final class ServerAgentManager {
    private ServerAgentManager() {}

    private static final CopyOnWriteArrayList<ServerAgentDriver> ACTIVE = new CopyOnWriteArrayList<>();

    public static void register(ServerAgentDriver d) { ACTIVE.addIfAbsent(d); }
    public static void unregister(ServerAgentDriver d) { ACTIVE.remove(d); }
    public static int activeCount() { return ACTIVE.size(); }
    public static void clear() { ACTIVE.clear(); }

    /** Advance every registered driver one tick; drop finished/crashed ones. */
    public static void tickAll() {
        for (ServerAgentDriver d : ACTIVE) {
            try {
                d.tick();
                if (d.finished()) ACTIVE.remove(d);
            } catch (Throwable t) {
                ACTIVE.remove(d);   // a crashed driver must not wedge the server tick
            }
        }
    }
}
