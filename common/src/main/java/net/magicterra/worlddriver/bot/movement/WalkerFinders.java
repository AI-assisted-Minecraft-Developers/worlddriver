package net.magicterra.worlddriver.bot.movement;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.pathfinder.PathFinder;
import net.magicterra.worlddriver.bot.pathfinder.PathTuning;
import net.magicterra.worlddriver.bot.pathfinder.SearchScope;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.world.entity.player.Player;

/**
 * Where a {@link Walker} builds its {@link PathFinder}s — the deep search and the quick start —
 * so every launch site gets the same budget, tuning, owner and scope source. Split out of
 * {@code Walker} for its line budget, the way the {@code WalkerTick*} phases were.
 */
final class WalkerFinders {
    private WalkerFinders() {}

    /** The deep-search finder: the per-Walker budget override when set, else the global one. */
    static PathFinder deep(Walker wk, WorldView world) {
        PathFinder pf = ((wk.searchMaxNodes > 0 && wk.searchMaxMs > 0)
                ? new PathFinder(world, wk.searchMaxNodes, wk.searchMaxMs, wk.profile)
                : new PathFinder(world, wk.profile))
                // THIS Walker's churn clock, not a global every body writes. Read live, because the
                // escalation is a sticky TIMER and a time-sliced search outlives it: a search that
                // starts escalated must pick the re-capped horizon back up when the clock lapses, or
                // it grinds on easy terrain instead of stopping early.
                .withTuning(PathTuning.escalatedWhen(wk.escal::armed));
        return scoped(wk, pf).withOwner(wk.owner);
    }

    /** The quick-start finder: a small node cap, one call. */
    static PathFinder quick(Walker wk, WorldView world, long maxMs) {
        return scoped(wk, new PathFinder(world, BotConfig.pathfinderQuickNodes, maxMs, wk.profile)).withOwner(wk.owner);
    }

    /**
     * The scope source: the Walker is the one construction point that has a body, so it is where
     * the per-search entity snapshot is wired. Gathered per Search (the finder calls it from the
     * Search constructor), on the body's own level, with the body itself excluded. Both bodies
     * alike — a {@link Player}, never a LocalPlayer, so the server never links client types. No
     * body yet (a scene driving the walker before its first tick) → no source, an empty scope.
     */
    private static PathFinder scoped(Walker wk, PathFinder pf) {
        Player b = wk.body;
        if (b != null) pf.withScopeSource((s, g, p) -> SearchScope.gather(b.level(), b.getId(), s, g, p));
        return pf;
    }
}
