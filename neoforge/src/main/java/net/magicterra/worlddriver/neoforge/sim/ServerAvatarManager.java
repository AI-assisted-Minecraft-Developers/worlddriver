package net.magicterra.worlddriver.neoforge.sim;

/**
 * NeoForge shim: static-delegation onto the common
 * {@link net.magicterra.worlddriver.bot.sim.ServerAvatarManager}, which is the SINGLE registry.
 *
 * <p><b>Two callers, both inside this loader.</b> {@code WorldDriverNeoForge}'s per-server-tick
 * {@link #tickAll()} and {@code /agentserver}'s {@link #register}/{@link #activeCount}/
 * {@link #clear}. This javadoc used to add「and the dogfood scenes」to that list: the scenes
 * import {@code bot.sim.ServerAvatarManager} directly and never reach this class — which is the
 * whole reason a reader must not treat this shim as load-bearing for the suites.
 * {@link #unregister} has no caller at all; it is kept only so the pair stays symmetric.
 */
public final class ServerAvatarManager {
    private ServerAvatarManager() {}

    public static void register(ServerWorldDriver d) { net.magicterra.worlddriver.bot.sim.ServerAvatarManager.register(d); }
    public static void unregister(ServerWorldDriver d) { net.magicterra.worlddriver.bot.sim.ServerAvatarManager.unregister(d); }
    public static int activeCount() { return net.magicterra.worlddriver.bot.sim.ServerAvatarManager.activeCount(); }
    public static void clear() { net.magicterra.worlddriver.bot.sim.ServerAvatarManager.clear(); }

    /** Advance every registered driver one tick; drop finished/crashed ones. */
    public static void tickAll() { net.magicterra.worlddriver.bot.sim.ServerAvatarManager.tickAll(); }
}
