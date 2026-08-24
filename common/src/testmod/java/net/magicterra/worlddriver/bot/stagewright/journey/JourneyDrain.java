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
 * for. Nothing changed in the move except that {@code stairBottom} is package-private now.
 *
 * <p>These four readings belong together because they are one story told at two moments. The cast
 * pours a source into an open mould; the mould's floor row IS the flight's bottom row; so the
 * water runs down the stairs and stands in the cell the next return has to land in.
 * {@link #stairFootStory} is that fact seen from the return, {@link #drainTheAlcove} is it seen
 * from the pour, and they used to disagree — the drain certified「壁龛已排干」with the bottom step
 * under water, because the bottom step is not a corridor cell.
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
        if (JourneyPortalRung.stairBottom == null) return List.of();
        BlockPos b = JourneyPortalRung.stairBottom;
        return List.of(b, b.above(), b.above(2));
    }

    /**
     * Is the foot of the flight standing in the cast's own water? Recorded on every return.
     *
     * <p><b>The water this rung pours has somewhere to go, and it goes down the stairs.</b> The
     * alcove's floor row IS the flight's bottom row, so a source placed in the mould floods the
     * corridor and then runs out of it along the one open route there is. Measured on the ladder run
     * of 2026-08-19: {@code cast8.landing = … 楼梯底 2, 56, 19=water（流动 flowing_water），其上
     * 2, 57, 19=water（流动 flowing_water）} — the body came down the flight and landed in a puddle,
     * which is where the climb that follows reports {@code afloat} and「垒不高」.
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
        BlockPos bottom = JourneyPortalRung.stairBottom;
        if (bottom == null) return "还没有楼梯底坐标";
        String wet = JourneyForge.firstFluid(level, stairFootCells());
        if (wet == null) return "楼梯底 " + bottom.toShortString() + " 那三格没有流体";
        return "⚠ 楼梯底积水：" + wet + "（楼梯底 " + bottom.toShortString()
                + "，模腔的水顺着楼梯流下来积在这里）—— 不能挖（挖水是 no-op），"
                + "也不能垫（垫上就是把最后一级砌死），只能等它退";
    }

    /** How long to let the alcove empty after the water is taken back, and how many such legs.
     *  Water without a source is gone in under a second, so five legs of forty ticks is generous —
     *  it is sized to be long enough that "still wet" means the SOURCE is still there. */
    private static final int DRAIN_TICKS = 40;
    private static final int DRAIN_LEGS = 5;

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
     * is the mistake the "两格都必须是空气" gate exists to stop, so it stopped, and the finding read
     * as a mining failure.
     *
     * <p>Flowing water with no source disappears on its own, so this is a wait and not a repair. If
     * it is still wet after all the legs, the source was never picked up — a different failure, and
     * this says so rather than letting the next cell report it second-hand.
     */
    static void drainTheAlcove(SceneContext ctx, JourneyRig rig, int i, int legs, Runnable then) {
        String wet = JourneyForge.firstFluid(ctx.level(), List.copyOf(JourneyPortalRung.forgeCorridor));
        // THE FOOT OF THE STAIRS IS DOWNHILL OF THE MOULD, so it is the last thing to dry and the
        // first thing the next return lands in — and this gate used to certify「壁龛已排干」with the
        // bottom step under water, because the bottom step is not a corridor cell. See
        // stairFootStory: waiting is the only legal answer here, so the wait is what is widened.
        String foot = JourneyForge.firstFluid(ctx.level(), stairFootCells());
        if ((wet == null && foot == null) || legs <= 0) {
            // THE SENTENCE USED TO NAME A CAUSE THE ROW ITSELF DISPROVES. It said "水源没被收回来"
            // — the source was never picked up — and every recover in the run reports CONSUME. Now
            // that firstFluid states source-or-flowing, the answer is in: on the run that lit the
            // portal, drain.6 through drain.9 all read `（流动，没源就会自己退）`. Nothing is
            // feeding the alcove; the water is simply still on its way out after 200 ticks, in a
            // seven-tall room whose floor the tidy has just unplugged. That is a wait to lengthen or
            // a floor to leave alone, not a bucket to chase.
            rig.evidence("drain." + i, wet == null ? "壁龛已排干"
                    : "等了 " + (DRAIN_LEGS * DRAIN_TICKS) + " tick 仍有流体：" + wet
                      + " —— 挖开下一格它会灌进去；是不是源块见括号，流动的只是还没退完");
            // Its own row, unconditionally — a drain that waited for the stairwell and a drain that
            // never looked at it must not read alike.
            rig.evidence("drain." + i + ".stairFoot", foot == null
                    ? "楼梯底那三格已排干" + (JourneyPortalRung.stairBottom == null ? "（还没有楼梯底坐标）"
                            : "（" + JourneyPortalRung.stairBottom.toShortString() + "）")
                    : "等了 " + (DRAIN_LEGS * DRAIN_TICKS) + " tick 楼梯底仍有流体：" + foot
                      + " —— 下一趟下楼会落进水里，塔垒不起来（见 cast*.stairFoot / climb.*.afloat）");
            then.run();
            return;
        }
        rig.settle(new HoldStill(DRAIN_TICKS / 2), DRAIN_TICKS,
                () -> drainTheAlcove(ctx, rig, i, legs - 1, then));
    }
}
