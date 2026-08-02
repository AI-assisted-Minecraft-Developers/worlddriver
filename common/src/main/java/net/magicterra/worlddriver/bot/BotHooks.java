package net.magicterra.worlddriver.bot;

/**
 * Broker between {@code DriverApi} (common) and the client-side {@link BotApi}
 * impl. Mirrors {@code ClientHooks} — keeps the static reference in common so
 * dedicated server JVMs never load bot impl classes.
 *
 * Bind is loose: registration order doesn't matter; {@code DriverApi} checks
 * availability at call time and returns "unavailable" to {@code mc.bot.*}
 * routes when no impl is registered (e.g. on dedicated server).
 */
public final class BotHooks {
    private static volatile BotApi impl;

    private BotHooks() {}

    public static void register(BotApi api) { impl = api; }

    public static BotApi impl() { return impl; }

    public static boolean isAvailable() { return impl != null; }
}
