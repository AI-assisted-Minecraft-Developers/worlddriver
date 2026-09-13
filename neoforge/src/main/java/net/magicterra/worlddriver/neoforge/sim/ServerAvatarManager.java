package net.magicterra.worlddriver.neoforge.sim;

/**
 * NeoForge shim: static-delegation onto the common
 * {@link net.magicterra.worlddriver.bot.sim.ServerAvatarManager}, which is the SINGLE registry.
 *
 * <p><b>One caller, inside this loader.</b> {@code /worlddriver server}'s {@link #register}/
 * {@link #activeCount}/{@link #clear}. The per-server-tick {@code tickAll()} used to be the
 * second caller, from {@code WorldDriverNeoForge}; it moved to the common
 * {@code WorldDriverEvents} with the rest of the event handlers and calls the common class
 * directly. The dogfood scenes import {@code bot.sim.ServerAvatarManager} directly and never
 * reach this class either — which is the whole reason a reader must not treat this shim as
 * load-bearing for the suites. {@link #unregister} has no caller at all; it is kept only so the
 * pair stays symmetric.
 */
public final class ServerAvatarManager {
    private ServerAvatarManager() {}

    public static void register(ServerWorldDriver d) { net.magicterra.worlddriver.bot.sim.ServerAvatarManager.register(d); }
    public static void unregister(ServerWorldDriver d) { net.magicterra.worlddriver.bot.sim.ServerAvatarManager.unregister(d); }
    public static int activeCount() { return net.magicterra.worlddriver.bot.sim.ServerAvatarManager.activeCount(); }
    public static void clear() { net.magicterra.worlddriver.bot.sim.ServerAvatarManager.clear(); }
}
