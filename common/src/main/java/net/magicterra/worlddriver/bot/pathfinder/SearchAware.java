package net.magicterra.worlddriver.bot.pathfinder;

/**
 * A {@link Constraint} or {@link CostModifier} that wants to know where the entities are for THIS
 * search. {@link PathFinder.Search} calls {@link #beginSearch} once per search, after the cost
 * stack is complete, with the {@link SearchScope} the finder's scope source gathered — one call per
 * object even when the same object sits in both the constraint and the modifier list.
 *
 * <p>{@link #scanRadius()} is how far out from the start–goal box this component needs entities
 * scanned; the scope source takes the largest over the profile. A component that returns 0
 * still receives a scope, but contributes nothing to the scan box.
 */
public interface SearchAware {
    void beginSearch(SearchScope scope);

    default int scanRadius() { return 0; }
}
