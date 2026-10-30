package net.magicterra.worlddriver.bot.control;

/**
 * How much of the walk a change clears. A new goal clears all of it. A replan drops the plan and what was measured
 * against it — a resume mid climb-out would otherwise carry an armed stall into the next tick and fire a spurious
 * takeover — but keeps how this pursuit has been going: the anti-spin and churn baselines, the search governors bar the
 * quick cooldown, the wedge count. A running controller is stopped only for a new goal; after a replan one that
 * depended on the old plan stops itself.
 */
public enum ResetScope {
    GOAL,
    REPLAN
}
