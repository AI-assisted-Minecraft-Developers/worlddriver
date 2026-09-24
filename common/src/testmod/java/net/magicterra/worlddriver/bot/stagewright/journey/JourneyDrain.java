package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.List;

import net.magicterra.stagewright.scene.SceneContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The water PORTAL_LIT borrows, and where it goes when it is let go.
 *
 * <p>Split out of {@link JourneyPortalRung} the same mechanical way that rung was split out of
 * {@code WorldDriverJourneyScenes}: the file crossed its 3000-line budget, and the alternative —
 * shaving the comments that carry the measurements — trades the only thing those files are good
 * for. Nothing changed in the move except that {@code stairBottom} is package-private now; it has
 * since moved on to {@link JourneyStairwell}, which is where the flight it names is cut and walked.
 *
 * <p>These four readings belong together because they are one story told at two moments. The cast
 * pours a source into an open mould; the mould's floor row IS the flight's bottom row; so the
 * water runs down the stairs and stands in the cell the next return has to land in.
 * {@link #stairFootStory} is that fact seen from the return, {@link #drainTheAlcove} is it seen
 * from the pour, and both must look at the stair foot: the bottom step is not a corridor cell, so
 * a drain that checked only the corridor would certify "alcove drained" with the bottom step under
 * water.
 */
final class JourneyDrain {

    private JourneyDrain() {}

    /**
     * The cells a body ends the descent in — the bottom step, its head room, and the clearance the
     * climb back out jumps through.
     *
     * <p>The same three cells {@code digStairsDown} cuts for every step, named here because two
     * different things ask about them: the return's own flood row and {@link #drainTheAlcove}'s
     * wait.
     */
    static List<BlockPos> stairFootCells() {
        if (JourneyStairwell.stairBottom == null) return List.of();
        BlockPos b = JourneyStairwell.stairBottom;
        return List.of(b, b.above(), b.above(2));
    }

    /**
     * Is the foot of the flight standing in the cast's own water? Recorded on every return.
     *
     * <p><b>The water this rung pours has somewhere to go, and it goes down the stairs.</b> The
     * alcove's floor row IS the flight's bottom row, so a source placed in the mould floods the
     * corridor and then runs out of it along the one open route there is. Measured on the ladder run
     * of 2026-08-19: {@code cast8.landing} reported the stair bottom 2, 56, 19 as flowing water and
     * the cell above it, 2, 57, 19, as flowing water too — the bot came down the flight and landed
     * in a puddle, which is where the climb that follows reports {@code afloat} and "cannot build
     * the tower higher".
     *
     * <p><b>Why this is a reading and not a repair.</b> Two verbs could touch these cells and both
     * are wrong. Mining is a no-op — a pick does not remove water, which is why {@link JourneyStairs}
     * excludes flooding from its faults in the first place. Placing DOES remove it, and placing here
     * fills the bottom step: the same audit would then report the step blocked, which is exactly the
     * failure the unwedge's tower already caused once. So what is left is to wait for it (flowing
     * water with no source runs out on its own — {@link #drainTheAlcove} waits on these cells too)
     * and to say so when it has not.
     */
    static String stairFootStory(ServerLevel level) {
        BlockPos bottom = JourneyStairwell.stairBottom;
        if (bottom == null) return "no stair bottom coordinate yet";
        String wet = JourneyForge.firstFluid(level, stairFootCells());
        if (wet == null) return "no fluid in the three cells at the stair bottom " + bottom.toShortString();
        return "⚠ water pooled at the stair bottom: " + wet + " (stair bottom " + bottom.toShortString()
                + "; water from the mould ran down the stairs and collected here). It cannot be dug"
                + " (digging water is a no-op) and cannot be filled (filling it would wall off the"
                + " last step), so the only option is to wait for it to recede";
    }

    /** How long to let the alcove empty after the water is taken back, and how many such legs.
     *  Water without a source is gone in under a second, so five legs of forty ticks is generous —
     *  it is sized to be long enough that "still wet" means the SOURCE is still there. */
    private static final int DRAIN_TICKS = 40;
    private static final int DRAIN_LEGS = 5;

    /**
     * How far around the alcove and the stair foot to look for whatever is still feeding them.
     *
     * <p>Three, because the sentence above already did this reasoning and stopped one step short:
     * "sized to be long enough that <b>still wet means the SOURCE is still there</b>". It has been
     * true since it was written, and the timeout branch must act on it: printing "flowing, will
     * recede on its own without a source" without looking for the source the argument says must
     * exist is a promise nobody checks. j51's {@code drain.7} showed the cost: {@code drain.0}
     * through {@code drain.6} cleared the stair foot every time, then one cast later the row
     * reported fluid still present at 2,56,20 after 200 ticks, flowing and expected to recede on
     * its own, and the return that followed could not reach the mould at all.
     *
     * <p><b>Eight, because a radius of three cannot see its own subject.</b> "A feeder further than
     * three cells is not flooding this corridor" is false about vanilla water: a source spreads to
     * horizontal level 7, so a feeder up to <b>seven</b> cells away reaches this corridor, and a fall
     * resets the level so it can reach further still. Ladder j54 makes this concrete: {@code drain.7}
     * timed out and a radius-three scan reported no water source within 3 cells of the alcove and
     * the stair bottom, which reads exactly like a finding ("so it really is just still receding")
     * while being a <b>false negative by construction</b>: at radius three it could not have found a
     * feeder at five even if one were standing there. A reading that cannot fail is not evidence.
     *
     * <p>The real worry about a wide scan — that it drowns one clear answer in twelve useless ones
     * — is handled where it belongs, in {@link JourneyForge#sourcesAround}: it dedups, sorts
     * highest-y first so the upstream end reads first, and caps the printed list. Narrowing the
     * search to keep the output short would trade the answer for the formatting.
     */
    private static final int DRAIN_UPSTREAM = 8;

    /** The legs the caller starts with. Here rather than at the call site so the wait's length and
     *  the sentences that quote it cannot drift apart. */
    static int legs() {
        return DRAIN_LEGS;
    }

    /**
     * Wait for the water the cast borrowed to run out of the alcove.
     *
     * <p>Taking the bucket back is not the same as the alcove being dry, and the gap between those
     * two is a whole cell. One source placed in an open mould floods everything below it: measured,
     * the rung recovered its bucket, opened the next frame cell one tick later and read
     * {@code opened.1=-10,51,23=water} — a cell it had just cut out of solid rock. Pouring into that
     * is the mistake the "both cells must be air" gate exists to stop, so it stopped, and the finding
     * read as a mining failure.
     *
     * <p>Flowing water with no source disappears on its own, so this is a wait and not a repair. If
     * it is still wet after all the legs, something is still feeding it — a different failure, and
     * since j51 this names the feeder rather than letting the next cell report it second-hand.
     */
    static void drainTheAlcove(SceneContext ctx, JourneyRig rig, int i, int legs, Runnable then) {
        String wet = JourneyForge.firstFluid(ctx.level(), List.copyOf(JourneyPortalRung.forgeCorridor));
        // THE FOOT OF THE STAIRS IS DOWNHILL OF THE MOULD, so it is the last thing to dry and the
        // first thing the next return lands in. It is not a corridor cell, so checking only the
        // corridor would certify "alcove drained" with the bottom step under water. See
        // stairFootStory: waiting is the only legal answer here, so the wait is what is widened.
        String foot = JourneyForge.firstFluid(ctx.level(), stairFootCells());
        if ((wet == null && foot == null) || legs <= 0) {
            // The row must not name "the source was never picked up" as the cause: every recover in
            // the run reports CONSUME, and since firstFluid states source-or-flowing, the run that
            // lit the portal read "flowing, will recede on its own without a source" for drain.6
            // through drain.9. Nothing is feeding the alcove; the water is simply still on its way
            // out after 200 ticks, in a seven-tall room whose floor the tidy has just unplugged.
            // That is a wait to lengthen or a floor to leave alone, not a bucket to chase.
            rig.evidence("drain." + i, wet == null ? "alcove drained"
                    : "fluid still present after waiting " + (DRAIN_LEGS * DRAIN_TICKS) + " ticks: " + wet
                      + "; it will flow into the next cell once that is dug. The parenthesis says"
                      + " whether it is a source; flowing water has simply not finished receding");
            // Its own row, unconditionally — a drain that waited for the stairwell and a drain that
            // never looked at it must not read alike.
            rig.evidence("drain." + i + ".stairFoot", foot == null
                    ? "the three cells at the stair bottom are drained"
                            + (JourneyStairwell.stairBottom == null ? " (no stair bottom coordinate yet)"
                            : " (" + JourneyStairwell.stairBottom.toShortString() + ")")
                    : "fluid still present at the stair bottom after waiting " + (DRAIN_LEGS * DRAIN_TICKS)
                      + " ticks: " + foot + "; the next descent will land in water and the tower cannot"
                      + " be built (see cast*.stairFoot / climb.*.afloat)");
            // ONLY WHEN THE WAIT RAN OUT, and only then. A drain that cleared has nothing upstream
            // worth naming, and this scan is the expensive one. When it did NOT clear, the rows
            // above say "flowing, will recede on its own without a source" about water that has just
            // failed to recede for `DRAIN_LEGS * DRAIN_TICKS` ticks, a promise the reading that makes
            // it never checked. So the reading that checks it goes here, next to the sentence it
            // adjudicates.
            if (wet != null || foot != null) {
                java.util.LinkedHashSet<BlockPos> near =
                        new java.util.LinkedHashSet<>(JourneyPortalRung.forgeCorridor);
                near.addAll(stairFootCells());
                String up = JourneyForge.sourcesAround(ctx.level(), near, DRAIN_UPSTREAM);
                rig.evidence("drain." + i + ".upstream", up == null
                        ? "no water source within " + DRAIN_UPSTREAM + " cells of the alcove and the"
                          + " stair bottom, so the water really is still receding; the next attempt"
                          + " must either wait longer, or this cell is not on the drainage path at all"
                        : "water sources still feeding it (highest first): " + up
                          + "; so \"will recede on its own without a source\" is false on this run."
                          + " Water was poured many times into a small enclosed room, a flowing cell"
                          + " between two sources becomes a new source, and each recovery collects"
                          + " only the one cell it aims at");
            }
            then.run();
            return;
        }
        // Hold for the full DRAIN_TICKS, not half of it. Every sentence above quotes
        // `DRAIN_LEGS * DRAIN_TICKS` = 200, so a shorter hold would make a timed-out drain report
        // twice the wait it actually took. j54's `drain.7` shows why that matters: it timed out with
        // no water source within 8 cells of the alcove and the stair bottom (pure recession, nothing
        // feeding it), and the run then poured the next source onto the un-receded flow, which
        // pinned the stair foot for good (`cast8#12.climb.0.washedOffFed`). When the upstream scan
        // finds no source, a longer wait is the whole remedy, so the wait must be as long as it says.
        rig.settle(new HoldStill(DRAIN_TICKS), DRAIN_TICKS * 2,
                () -> drainTheAlcove(ctx, rig, i, legs - 1, then));
    }
}
