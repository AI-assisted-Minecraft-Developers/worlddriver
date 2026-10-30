package net.magicterra.worlddriver.bot.control;

import net.magicterra.worlddriver.bot.rules.SearchProfile;

/**
 * What a navigator is for, fixed when it is made.
 *
 * @param owner the chain or verb it moves for ("mine", "retreat", "goto"); threaded into every search it launches so
 *     the log's search lines say whose they are. Telemetry only — never read by planning or steering
 * @param profile the bias, capability and constraints its searches use until a {@link Locomotor#replan(SearchProfile)}
 *     changes them
 * @param searchMaxNodes deep-search node budget, or −1 for the configured one
 * @param searchMaxMs deep-search time budget, or −1 for the configured one
 */
public record NavigatorSettings(String owner, SearchProfile profile, int searchMaxNodes, long searchMaxMs) {

    /**
     * A navigator nobody named. The default still says WHAT it is: a thread dump reading {@code owner=?} once cost a
     * round of an investigation, because "?" reads as "not a navigator's" when it means "a navigator nobody named".
     * Scenes that drive a navigator directly get this.
     */
    public static final NavigatorSettings DEFAULT =
            new NavigatorSettings("navigator.unnamed", SearchProfile.NONE, -1, -1);

    public NavigatorSettings {
        if (owner == null || owner.isEmpty()) {
            owner = DEFAULT.owner();
        }
        if (profile == null) {
            profile = SearchProfile.NONE;
        }
    }

    /** Default settings for a navigator that moves for {@code owner}. */
    public static NavigatorSettings named(String owner) {
        return new NavigatorSettings(owner, SearchProfile.NONE, -1, -1);
    }

    public NavigatorSettings withProfile(SearchProfile profile) {
        return new NavigatorSettings(owner, profile, searchMaxNodes, searchMaxMs);
    }

    /**
     * A smaller deep-search budget, for an owner whose goals are always near. An ore stand embedded in a wall is proven
     * unreachable after a few thousand expansions, and grinding the full budget per candidate is waste — sliced, it
     * also chops the client's frame rate for seconds after each mined block. Long-haul navigation keeps the configured
     * budget.
     */
    public NavigatorSettings withSearchBudget(int nodes, long ms) {
        return new NavigatorSettings(owner, profile, nodes, ms);
    }
}
