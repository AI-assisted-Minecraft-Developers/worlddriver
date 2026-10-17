package net.magicterra.worlddriver.bot.control;

import net.magicterra.worlddriver.bot.rules.SearchProfile;

/**
 * What a walker is for, fixed when it is made.
 *
 * @param owner          the chain or verb it moves for ("mine", "retreat", "goto"); threaded into
 *                       every search it launches so the log's search lines say whose they are.
 *                       Telemetry only — never read by planning or steering
 * @param profile        the bias, capability and constraints its searches use until a
 *                       {@link Locomotor#replan(SearchProfile)} changes them
 * @param searchMaxNodes deep-search node budget, or −1 for the configured one
 * @param searchMaxMs    deep-search time budget, or −1 for the configured one
 */
public record WalkerSettings(String owner, SearchProfile profile, int searchMaxNodes, long searchMaxMs) {

    /**
     * A walker nobody named. The default still says WHAT it is: a thread dump reading
     * {@code owner=?} once cost a round of an investigation, because "?" reads as "not a walker's"
     * when it means "a walker nobody named". Scenes that drive a walker directly get this.
     */
    public static final WalkerSettings DEFAULT = new WalkerSettings("walker.unnamed", SearchProfile.NONE, -1, -1);

    public WalkerSettings {
        if (owner == null || owner.isEmpty()) owner = DEFAULT.owner();
        if (profile == null) profile = SearchProfile.NONE;
    }

    /** Default settings for a walker that moves for {@code owner}. */
    public static WalkerSettings named(String owner) {
        return new WalkerSettings(owner, SearchProfile.NONE, -1, -1);
    }

    public WalkerSettings withProfile(SearchProfile profile) {
        return new WalkerSettings(owner, profile, searchMaxNodes, searchMaxMs);
    }

    /**
     * A smaller deep-search budget, for an owner whose goals are always near. An ore stand embedded
     * in a wall is proven unreachable after a few thousand expansions, and grinding the full budget
     * per candidate is waste — sliced, it also chops the client's frame rate for seconds after each
     * mined block. Long-haul navigation keeps the configured budget.
     */
    public WalkerSettings withSearchBudget(int nodes, long ms) {
        return new WalkerSettings(owner, profile, nodes, ms);
    }
}
