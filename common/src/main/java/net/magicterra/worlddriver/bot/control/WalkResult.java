package net.magicterra.worlddriver.bot.control;

/**
 * How a walk ended.
 *
 * @param status      {@link Locomotor.Status#REACHED}, {@link Locomotor.Status#GAVE_UP} or
 *                    {@link Locomotor.Status#FAILED}; {@link Locomotor.Status#MOVING} only in {@link #NONE}
 * @param reason      why it failed, or how far short it gave up; null when it reached the goal
 * @param endReason   the branch that ended it, e.g. {@code arrived}, {@code path-consumed},
 *                    {@code frontier-giveup}, {@code failed:<reason>}
 * @param goalReached whether the goal's test passes at the feet; false for a snapped goal, whose
 *                    requested cell was not the one walked to
 * @param finalDist   the goal's estimate at the feet, in path-cost units of 10 per block; negative
 *                    when there was no body to measure from
 * @param goalSnapped whether an unstandable target was replaced by the nearest standable cell
 */
public record WalkResult(Locomotor.Status status, String reason, String endReason, boolean goalReached,
                         double finalDist, boolean goalSnapped) {

    /** No walk has ended since the last {@link Locomotor#start}. */
    public static final WalkResult NONE = new WalkResult(Locomotor.Status.MOVING, null, null, false, -1, false);
}
