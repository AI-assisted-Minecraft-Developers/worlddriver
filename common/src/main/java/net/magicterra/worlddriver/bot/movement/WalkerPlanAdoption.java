package net.magicterra.worlddriver.bot.movement;

import net.magicterra.worlddriver.bot.pathfinder.PathFinder;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/**
 * Hands a previewed route to a walker: {@code mc.bot.goto} with {@code planId}. The one public
 * door to the package-private {@link Walker#adoptPath}, which the {@code bot.process} package
 * cannot reach and which {@code Walker} — at its line budget — does not grow a twin of.
 *
 * <p>The RAW A* result is fed, never the string-pulled route a preview showed: {@code adoptPath}
 * straightens on its own, cuts a best-effort tail, and fast-forwards past the prefix the body has
 * already walked (anchoring on the nearest prefix node absorbs the drift since the preview). A
 * pre-straightened route would be straightened twice. Its own gate — the nearest prefix node
 * within 4 cells on dry ground, 8 in water — is the only distance rule; this design adds no
 * second knob for the same question. {@code dropStalePrefix} inside it cuts the route at the
 * first edge the world no longer accepts, so a preview whose first stretch has been built over
 * comes back empty and is refused, which is the "fall back to a normal search" case.
 */
public final class WalkerPlanAdoption {
    private WalkerPlanAdoption() {}

    /**
     * @param foot where the body stands now; the route is anchored to its nearest prefix node
     * @return false when the walker refused the route (mis-anchored or stale); the caller then
     *         lets the walker search as usual
     */
    public static boolean adopt(Walker walker, PathFinder.Result raw, WorldView world, BlockPos foot) {
        if (walker == null || raw == null || raw.path().isEmpty()) return false;
        return walker.adoptPath(raw, world, foot);
    }
}
