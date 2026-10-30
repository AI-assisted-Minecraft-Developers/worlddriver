package net.magicterra.worlddriver.bot.control;

import java.util.Locale;

/**
 * Whether a walk that stopped did what it was asked. The navigator's give-ups (churn, frontier, a consumed best-effort
 * path) end through the same branch as an arrival; the goal test at the foot is what tells
 * {@link Locomotor.Status#REACHED} from {@link Locomotor.Status#GAVE_UP}.
 */
public final class WalkVerdict {

    /**
     * {@code endReason} of an arrival at the standable cell nearest an unstandable target, which is as near as that
     * target can be reached.
     */
    public static final String GOAL_SNAPPED = "goal-snapped";

    private WalkVerdict() {}

    /** The {@code endReason} of a walk whose route ran out. Pure, so a scene can check it alone. */
    public static String classifyArrival(boolean bestEffort, boolean reachedFoot, boolean snapped) {
        if (snapped && reachedFoot) {
            return GOAL_SNAPPED;
        }
        if (reachedFoot) {
            return "arrived";
        }
        return bestEffort ? "best-effort-consumed" : "path-consumed";
    }

    /**
     * How a walk ended.
     *
     * @param failed the walk failed rather than ran out of route
     * @param error why it failed; ignored for a walk that did not
     * @param finalDist the goal's {@code estimate} at the foot, in path-cost units of 10 per block; negative when there
     *     was no foot to measure from
     * @param openGoal the goal has no cell to reach (an exploration), so ending is reaching it
     */
    public static WalkResult of(
            boolean failed,
            String error,
            boolean goalReached,
            String endReason,
            double finalDist,
            boolean goalSnapped,
            boolean openGoal) {
        if (failed) {
            return new WalkResult(
                    Locomotor.Status.FAILED,
                    error != null ? error : "walk failed",
                    endReason,
                    goalReached,
                    finalDist,
                    goalSnapped);
        }
        if (goalReached || openGoal || GOAL_SNAPPED.equals(endReason)) {
            return new WalkResult(Locomotor.Status.REACHED, null, endReason, goalReached, finalDist, goalSnapped);
        }
        String shortBy = finalDist < 0
                ? "goal not reached (" + endReason + ")"
                : String.format(
                        Locale.ROOT, "goal not reached (%s, about %.1f blocks short)", endReason, finalDist / 10.0);
        return new WalkResult(Locomotor.Status.GAVE_UP, shortBy, endReason, goalReached, finalDist, goalSnapped);
    }
}
