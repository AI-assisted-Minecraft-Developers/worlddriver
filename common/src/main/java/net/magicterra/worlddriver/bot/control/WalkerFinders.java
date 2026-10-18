package net.magicterra.worlddriver.bot.control;

import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.bot.planning.PathFinder;
import net.magicterra.worlddriver.bot.planning.PathTuning;
import net.magicterra.worlddriver.bot.rules.SearchScope;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.minecraft.world.entity.LivingEntity;

/**
 * Where a {@link Walker} builds its {@link PathFinder}s — the deep search and the quick start —
 * so every launch site gets the same budget, tuning, owner and scope source. Split out of
 * {@code Walker} for its line budget, the way the {@code WalkerTick*} phases were.
 */
public final class WalkerFinders {
    private WalkerFinders() {}

    /** The deep-search finder: the per-Walker budget override when set, else the global one. */
    static PathFinder deep(Walker wk, WorldView world) {
        PathFinder pf = ((wk.searchMaxNodes > 0 && wk.searchMaxMs > 0)
                ? new PathFinder(world, wk.searchMaxNodes, wk.searchMaxMs, wk.profile)
                : new PathFinder(world, wk.profile))
                // THIS Walker's churn clock, not a global every bot writes. Read live, because the
                // escalation is a sticky TIMER and a time-sliced search outlives it: a search that
                // starts escalated must pick the re-capped horizon back up when the clock lapses, or
                // it grinds on easy terrain instead of stopping early.
                .withTuning(PathTuning.escalatedWhen(wk.stallDetect.escal::armed));
        return scoped(wk, pf).withOwner(wk.owner);
    }

    /** The quick-start finder: a small node cap, one call, on the same escalation as the deep one. */
    public static PathFinder quick(Walker wk, WorldView world, long maxMs) {
        PathFinder pf = new PathFinder(world, ControlConfig.pathfinderQuickNodes, maxMs, wk.profile)
                .withTuning(PathTuning.escalatedWhen(wk.stallDetect.escal::armed));
        return scoped(wk, pf).withOwner(wk.owner);
    }

    /**
     * The scope source: the Walker is the one construction point that has a bot, so it is where
     * the per-search entity snapshot is wired. Gathered per Search (the finder calls it from the
     * Search constructor), on the bot's own level, with the bot itself excluded. Both bots
     * alike — a {@link Player}, never a LocalPlayer, so the server never links client types. No
     * bot yet (a scene driving the walker before its first tick) → no source, an empty scope.
     */
    private static PathFinder scoped(Walker wk, PathFinder pf) {
        LivingEntity b = wk.body;
        if (b != null) pf.withScopeSource((s, g, p) -> SearchScope.gather(b.level(), b.getId(), s, g, p));
        return pf;
    }
}
