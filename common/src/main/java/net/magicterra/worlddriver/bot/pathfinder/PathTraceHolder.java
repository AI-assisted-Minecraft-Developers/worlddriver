package net.magicterra.worlddriver.bot.pathfinder;

/**
 * Single mutable reference to the active {@link PathTrace}. Defaults to {@link PathTrace#NOOP}
 * so core is inert until the debug package registers a recorder via
 * {@code PathDebugBootstrap.init()}. {@code volatile} because the writer (client init thread)
 * and readers (Walker / pathfinder thread) differ.
 */
public final class PathTraceHolder {
    private PathTraceHolder() {}
    public static volatile PathTrace SINK = PathTrace.NOOP;
}
