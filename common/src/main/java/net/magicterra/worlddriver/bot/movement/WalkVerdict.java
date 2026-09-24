package net.magicterra.worlddriver.bot.movement;

import java.util.Locale;

/**
 * Whether a walk that stopped did what it was asked. The walker ends its give-ups (churn, frontier,
 * a consumed best-effort path) with {@link Walker.Step#ARRIVED}, so the step alone reads them as
 * success; the goal test at the foot is what tells them apart.
 */
public final class WalkVerdict {

    /** {@code endReason} of an arrival at the standable cell nearest an unstandable target, which is
     *  as near as that target can be reached. */
    public static final String GOAL_SNAPPED = "goal-snapped";

    private WalkVerdict() {}

    /**
     * Why the walk fell short, or null when it did not.
     *
     * @param finalDist the goal's {@code estimate} at the foot, in path-cost units of 10 per block;
     *                  negative when there was no foot to measure from
     */
    public static String shortfall(Walker.Step s, String error, boolean goalReached, String endReason,
                                   double finalDist, boolean openGoal) {
        if (s == Walker.Step.FAILED) return error != null ? error : "walk failed";
        if (s != Walker.Step.ARRIVED || goalReached || openGoal || GOAL_SNAPPED.equals(endReason)) return null;
        if (finalDist < 0) return "goal not reached (" + endReason + ")";
        return String.format(Locale.ROOT, "goal not reached (%s, about %.1f blocks short)",
                endReason, finalDist / 10.0);
    }
}
