package net.magicterra.worlddriver.bot;

/**
 * Broker between the driver's services (common) and the client-side {@link BotApi} impl. Mirrors {@code ClientHooks} —
 * keeps the static reference in common so dedicated server JVMs never load bot impl classes.
 *
 * <p>Bind is loose: registration order doesn't matter; the services check availability at call time and returns
 * "unavailable" to {@code mc.bot.*} routes when no impl is registered (e.g. on dedicated server).
 */
public final class BotHooks {
    private static volatile BotApi impl;

    private BotHooks() {}

    /** Installs {@code api} as the client-side impl that the {@code mc.bot.*} routes call into. */
    public static void register(BotApi api) {
        impl = api;
    }

    /** The installed impl, or null when none is registered. */
    public static BotApi impl() {
        return impl;
    }

    public static boolean isAvailable() {
        return impl != null;
    }
}
