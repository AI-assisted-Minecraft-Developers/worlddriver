package net.magicterra.worlddriver.bot.stagewright.journey;

import net.magicterra.worlddriver.bot.sim.ServerWorldDriver;
import net.minecraft.core.BlockPos;

import java.util.Locale;

/**
 * What a walk is required to say about itself when it ends.
 *
 * <h2>Why this exists</h2>
 *
 * The ladder issues about fifty-six walks. Twenty-two of them go through
 * {@code WorldDriverJourneyScenes.walkToColumn}, which asks — every time, on the arrival path as
 * well as the failure path — where the bot actually stopped and what the walker said about why.
 * The other thirty-four asked nothing, and simply ran their next step.
 *
 * <p>That is not a tidiness problem, it is a diagnosis problem, and this suite has already paid for
 * it more than once. {@code IntentProcess} reports its goal reached for a PARTIAL path, so a walk
 * that comes back "done" says nothing about whether the bot is where the walk was aiming: measured
 * on the iron rung, a walk ended cleanly with the bot <b>88 blocks</b> from its column, and the
 * rung reported "cannot reach the shaft entry" for what was really "stopped halfway and nobody
 * checked". A walk with no reading turns every failure downstream of it into a guess: an aim that
 * missed and an aim that was taken from thirty blocks away produce the same evidence, and the
 * search then goes looking at the wrong mechanism. A false "yes" costs far more than an honest
 * "no".
 *
 * <h2>What it is not</h2>
 *
 * <b>Pure evidence. No control flow, no criterion, no retry.</b> This never fails a scene, never
 * skips one, and never changes what a rung does next — it only writes down what already happened,
 * so adding a call to it cannot change the colour of a run. Deciding what to DO about a walk that
 * came up short belongs to the caller (and, where the caller wants attempts and a midpoint retry,
 * to {@code WorldDriverJourneyScenes.walkToColumn}, which is a different thing and stays separate).
 *
 * <h2>Never a bare {@code 0} and never a {@code null}</h2>
 *
 * Every clause either carries a measurement or says in words why it has none. {@code 0} and
 * {@code null} are the two readings this suite has repeatedly mistaken for answers when they meant
 * "never computed", so where a value is unavailable this writes {@code unavailable/<reason>}
 * instead.
 */
final class JourneyLeg {
    private JourneyLeg() {}

    /**
     * Write one row saying where a walk ended, how far that is from where it was sent, and what the
     * walker itself said about the ending.
     *
     * <p>Call it as the first statement of a walk's continuation. The key is
     * {@code <what>.leg}; a rung that records two walks under one {@code what} does not lose either
     * — {@link JourneyRig#evidence} puts the second on {@code <what>.leg#2} and warns — but a
     * distinct {@code what} per walk is what makes the rows readable.
     *
     * @param goal where the walk was aiming, or {@code null} when the walk's goal is not a point
     *             (a {@code Goal.YLevel} climb, a follow). The distance clause then says so rather
     *             than inventing a number.
     */
    static void record(JourneyRig rig, String what, BlockPos goal) {
        BlockPos at = rig.player().blockPosition();
        rig.evidence(what + ".leg", "stopped at " + at.toShortString() + ", " + gap(at, goal)
                + "; " + walkerEnd(rig));
    }

    /**
     * How far the bot ended from where it was sent — horizontally and vertically, separately.
     *
     * <p>Separately on purpose. A single 3-D distance hides exactly the case that matters: a bot
     * standing on the rim above its goal and a bot standing beside it at the right height read the
     * same, and only one of them can do the next step. The horizontal number is the one a walk is
     * judged on; the height difference is the one that says "reached the column but did not descend
     * to the target cell".
     */
    private static String gap(BlockPos at, BlockPos goal) {
        if (goal == null) return "distance to goal unavailable/the goal of this walk is not a point,"
                + " so there is no coordinate to compare";
        double flat = Math.hypot(at.getX() - goal.getX(), at.getZ() - goal.getZ());
        return String.format(Locale.ROOT, "from %s: horizontal %.1f blocks, height difference %+d",
                goal.toShortString(), flat, at.getY() - goal.getY());
    }

    /**
     * What the walker said about the walk it just ended.
     *
     * <h2>{@code end=null} is a reading, and printing it as "null" throws it away</h2>
     *
     * {@code IntentProcess.attach} nulls {@code endReason}, and the only writes to it are on the
     * terminal exits: {@code tick} returns early on {@code Step.WALKING} and stamps
     * {@code walker.lastEndReason} only once the step is no longer WALKING (plus
     * {@code crossedOut}'s {@code DIMENSION_CHANGED}). So {@code endReason == null} does not mean
     * "not found"; it means exactly "the process was still walking when this walk was stopped",
     * i.e. something OUTSIDE the process ended it, which in this suite is the {@code settle} budget
     * running out.
     *
     * <p>That distinction is the whole point of the row. A walk that ended
     * {@code failed:no route progress …} is a search that ran and lost to the terrain; a walk that
     * ended still walking is a search that was never allowed to finish, and the two want opposite
     * responses (re-route vs. more budget). Printed as {@code end=null} they read alike, and read
     * like "no information", which is how {@code 0}/{@code null} has repeatedly been mistaken in
     * this suite for an answer rather than for an unasked question.
     *
     * <p><b>Measured</b>, rung 14 of the run of 2026-08-20 (`journey14BlazeRod`, 24 hops): all eight
     * hops with {@code end=null} had spent exactly their 900-tick budget, and both hops with a real
     * {@code endReason} had stopped early (546t, 200t). Ten of ten, in the direction the code says.
     *
     * <p>{@code lastError}'s {@code null} is separately ambiguous — {@code BotState}'s own javadoc
     * says "null if last run ok or in-progress", so "none" here means "no error reported" and does
     * <b>not</b> prove the walk is over. Read it beside {@code end=}.
     */
    static String walkerEnd(JourneyRig rig) {
        return walkerEnd(rig.body());
    }

    /**
     * Did this walk end because the SEARCH gave up, as opposed to running out of tick budget?
     *
     * <h2>The two endings need different answers, and the corridor proved it costs a bot</h2>
     *
     * A walk that timed out was still walking when the clock stopped: the route it was on may have
     * been fine and merely long, so re-aiming somewhere else is a genuinely different question and
     * can work. A walk that ended {@code no path} / {@code goal unreachable} has already had the
     * search exhaust itself; asking it to reach a cell six blocks FURTHER is the same question with
     * a worse answer, and the bot pays for the attempt by wandering off a bridge it built.
     *
     * <p>Three walks, and the split is exactly along this line:
     *
     * <pre>
     * wp2  (real ladder)  direct end=unavailable (budget spent, still walking) → detour arrived, 1 block short
     * wp5  (real ladder)  direct end=failed:no route progress…unreachable     → detour stopped at 59,5,90, lava
     * wp11 (rehearsal)    direct end=failed:no path (expanded=100000)         → detour stopped at 62,3,86, lava
     * </pre>
     *
     * The detour's one measured win followed a timeout; both measured lava deaths followed a search
     * failure, and neither recovered anything before dying. Gating on the reason keeps the win.
     *
     * <h2>Unknown deliberately means "let it try"</h2>
     *
     * {@code endReason == null} is the timeout case (see {@link #walkerEnd}: the field is only
     * written on a terminating step), and an ending this method does not recognise is treated the
     * same way. The conservative direction for a gate that SUPPRESSES a fallback is to let the
     * fallback run — a mis-read that blocks a working detour turns a passing walk red, while a
     * mis-read that allows a doomed one costs ticks this file already knows how to see.
     */
    static boolean searchGaveUp(JourneyRig rig) {
        String end = rig.slotEnd("goto");
        if (end == null) return false;
        String e = end.toLowerCase(Locale.ROOT);
        return e.contains("no path") || e.contains("unreachable") || e.contains("no route progress");
    }

    /**
     * The same row for a bot that is driven directly rather than through a {@link JourneyRig}.
     *
     * <p>The A/B scenes in {@code JourneyPortalEntryScenes} run two drivers side by side and have no
     * rig, so they read {@code botState()} off the driver. They need this reading more than the
     * rungs do, not less: an arm whose walk was cut off by {@code LEDGE_TICKS} and an arm whose
     * search genuinely failed are exactly the confusion an A/B is there to rule out, and an arm
     * that ran out of budget is not evidence about the subject at all.
     */
    static String walkerEnd(ServerWorldDriver driver) {
        var goto_ = driver.botState().mc_goto;
        String end = goto_.endReason;
        String err = goto_.lastError;
        return "end=" + (end == null
                        ? "unavailable/the process was still walking when the budget ran out (endReason"
                          + " is written only on a terminal step; unset means no terminal step was reached)"
                        : end)
                + " err=" + (err == null ? "none" : err);
    }
}
