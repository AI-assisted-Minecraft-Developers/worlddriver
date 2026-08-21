package net.magicterra.worlddriver.bot.stagewright.journey;

import net.minecraft.core.BlockPos;

import java.util.Locale;

/**
 * What a walking leg is required to say about itself when it ends.
 *
 * <h2>Why this exists</h2>
 *
 * The ladder issues about fifty-six walking legs. Twenty-two of them go through
 * {@code WorldDriverJourneyScenes.walkToColumn}, which asks — every time, on the arrival path as
 * well as the failure path — where the body actually stopped and what the walker said about why.
 * The other thirty-four asked nothing, and simply ran their next step.
 *
 * <p>That is not a tidiness problem, it is a diagnosis problem, and this suite has already paid for
 * it more than once. {@code IntentProcess} reports its goal reached for a PARTIAL path, so a leg
 * coming back「done」says nothing about whether the body is where the leg was aiming: measured on
 * the iron rung, a leg ended cleanly with the body <b>88 blocks</b> from its column, and the rung
 * reported「走不到下井点」for what was really「走到一半就不走了，没人问它」. A leg with no reading
 * turns every failure downstream of it into a guess: an aim that missed and an aim that was taken
 * from thirty blocks away produce the same evidence, and the search then goes looking at the wrong
 * mechanism. A false「可以」costs far more than an honest「不行」.
 *
 * <h2>What it is not</h2>
 *
 * <b>Pure evidence. No control flow, no criterion, no retry.</b> This never fails a scene, never
 * skips one, and never changes what a rung does next — it only writes down what already happened,
 * so adding a call to it cannot change the colour of a run. Deciding what to DO about a leg that
 * came up short belongs to the caller (and, where the caller wants attempts and a midpoint retry,
 * to {@code WorldDriverJourneyScenes.walkToColumn}, which is a different thing and stays separate).
 *
 * <h2>Never a bare {@code 0} and never a {@code null}</h2>
 *
 * Every clause either carries a measurement or says in words why it has none. {@code 0} and
 * {@code null} are the two readings this suite has repeatedly mistaken for answers when they meant
 *「没算过」, so where a value is unavailable this writes {@code unavailable/<原因>} instead.
 */
final class JourneyLeg {
    private JourneyLeg() {}

    /**
     * Write one row saying where a leg ended, how far that is from where it was sent, and what the
     * walker itself said about the ending.
     *
     * <p>Call it as the first statement of a leg's continuation. The key is
     * {@code <what>.leg}; a rung that records two legs under one {@code what} does not lose either
     * — {@link JourneyRig#evidence} puts the second on {@code <what>.leg#2} and warns — but a
     * distinct {@code what} per leg is what makes the rows readable.
     *
     * @param goal where the leg was aiming, or {@code null} when the leg's goal is not a point
     *             (a {@code Goal.YLevel} climb, a follow). The distance clause then says so rather
     *             than inventing a number.
     */
    static void record(JourneyRig rig, String what, BlockPos goal) {
        BlockPos at = rig.player().blockPosition();
        rig.evidence(what + ".leg", "停在 " + at.toShortString() + "，" + gap(at, goal)
                + "；" + walkerEnd(rig));
    }

    /**
     * How far the body ended from where it was sent — horizontally and vertically, separately.
     *
     * <p>Separately on purpose. A single 3-D distance hides exactly the case that matters: a body
     * standing on the rim above its goal and a body standing beside it at the right height read the
     * same, and only one of them can do the next step. The horizontal number is the one a walking
     * leg is judged on; the height difference is the one that says「到了这一柱，但没下到那一格」.
     */
    private static String gap(BlockPos at, BlockPos goal) {
        if (goal == null) return "距目标 unavailable/这一腿的目标不是一个点，没有可比的坐标";
        double flat = Math.hypot(at.getX() - goal.getX(), at.getZ() - goal.getZ());
        return String.format(Locale.ROOT, "距 %s 水平 %.1f 格、高差 %+d",
                goal.toShortString(), flat, at.getY() - goal.getY());
    }

    /**
     * What the walker said about the leg it just ended.
     *
     * <h2>{@code end=null} is a reading, and printing it as「null」threw it away</h2>
     *
     * {@code IntentProcess.attach} nulls {@code endReason}, and the only writes to it are on the
     * terminal exits: {@code tick} returns early on {@code Step.WALKING} and stamps
     * {@code walker.lastEndReason} only once the step is no longer WALKING (plus
     * {@code crossedOut}'s {@code DIMENSION_CHANGED}). So {@code endReason == null} is not「没查到」
     * — it is exactly「这一腿被叫停的时候，进程还在走」, i.e. something OUTSIDE the process ended
     * it, which in this suite is the {@code settle} budget running out.
     *
     * <p>That distinction is the whole point of the row. A leg that ended
     * {@code failed:no route progress …} is a search that ran and lost to the terrain; a leg that
     * ended still-walking is a search that was never allowed to finish, and the two want opposite
     * responses (re-route vs. more budget). Printed as {@code end=null} they read alike, and read
     * like「没有信息」— which is how {@code 0}/{@code null} has repeatedly been mistaken in this
     * suite for an answer rather than for an unasked question.
     *
     * <p><b>Measured</b>, rung 14 of the run of 2026-08-20 (`journey14BlazeRod`, 24 hops): all eight
     * hops with {@code end=null} had spent exactly their 900-tick budget, and both hops with a real
     * {@code endReason} had stopped early (546t, 200t). Ten of ten, in the direction the code says.
     *
     * <p>{@code lastError}'s {@code null} is separately ambiguous — {@code BotState}'s own javadoc
     * says「null if last run ok or in-progress」— so「无」here means「没有报错」and does <b>not</b>
     * prove the leg is over. Read it beside {@code end=}.
     */
    static String walkerEnd(JourneyRig rig) {
        var goto_ = rig.body().botState().mc_goto;
        String end = goto_.endReason;
        String err = goto_.lastError;
        return "end=" + (end == null
                        ? "unavailable/预算用完时进程还在走（endReason 只在终止步写，没写=没走到终止步）"
                        : end)
                + " err=" + (err == null ? "无" : err);
    }
}
