package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Does the pour line hold from every position a body standing in that cell can actually be in?
 *
 * <h2>The eye the chooser used is one no body ever has</h2>
 *
 * <p>{@code JourneyPortalRung#standToAimAt} weighed each candidate by clipping from
 * {@code (foot.x + 0.5, foot.y + float + eyeHeight, foot.z + 0.5)} — the exact centre of the cell,
 * at a height guessed by adding a whole block when the cell holds fluid. A real body is at neither.
 * A player's box is 0.6 wide, so its centre rests anywhere in {@code [0.2, 0.8]} of its own cell —
 * the walker leaves it wherever the last path edge ended, which {@link JourneyRamp}'s own approach
 * note already measured from the other side ({@code 身体精确位置 6.60/57.00/17.78}) — and a body in
 * one block of water floats a third of a block, not a whole one.
 *
 * <p>Measured, the south rehearsal of 2026-08-17, cell eight of the mould. The chooser accepted
 * {@code -10,56,32} and said so:
 *
 * <pre>
 * cast8.stand.3 = -10, 56, 32 瞄 -9, 60, 35（背板近面） 否决计数 {…}
 * cast8.picks.3 = -9, 58, 33 Block{minecraft:cobblestone} face=west → 落进 -10, 58, 33
 *                 （想浇 -9, 60, 34，…，身体 -10, 56, 32，眼睛 -9.15/57.98/32.57 …）
 * </pre>
 *
 * <p>The eye it was chosen for is {@code -9.50/58.62/32.50}; the eye that fired is
 * {@code -9.15/57.98/32.57}. The shot is a diagonal ({@code dx=1, dz=3}), so a third of a block of
 * x is a whole column of crossing: from the centre the line enters the {@code x=-9} rank beyond the
 * step at {@code -9,58,33}, and from where the body actually stood it enters straight into it. The
 * pour's own {@code .picks} gate caught it, as it is built to — but by then the cell had already
 * cost the rung the decision that matters, because {@code standLevelWith} asks this same chooser
 * whether a raise is needed at all, and a "yes, there is a spot" skips the raise. The east arm,
 * which has no such spot, raises and pours the same cell 5 times in 5.
 *
 * <h2>So a stand is graded, not accepted</h2>
 *
 * <p>{@link #ANYWHERE} means the line survives every corner of the body's own footprint and both
 * heights a body in fluid can sit at; {@link #CENTRE_ONLY} means it holds from the ideal point and
 * from nowhere else, which is a coin toss the walker gets to call. The caller keeps the second as a
 * place to WALK to — refusing it outright would leave a body with nowhere to go, and the {@code
 * .picks} gate still refuses to spend the bucket — but it is no answer to the question "is a raise
 * needed", where the cost of a wrong yes is the cell.
 *
 * <p>Note what this does NOT do: it does not widen anything, and it is not a tolerance. It asks the
 * existing question more times, from the positions the body is entitled to occupy, and a shot that
 * is axis-aligned — the shape this rung's every successful cast has — is unaffected by construction,
 * because sliding the eye along its own axis does not move which cells the line crosses.
 */
final class JourneySight {

    private JourneySight() {}

    /** The line does not hold even from the ideal point — the veto is in the caller's map. */
    static final int REFUSED = 0;

    /** It holds from the centre of the cell and not from the whole of it. */
    static final int CENTRE_ONLY = 1;

    /** It holds from every position the body can rest in. */
    static final int ANYWHERE = 2;

    /** How far a standing body's centre can sit from the centre of its own cell. A player's box is
     *  0.6 wide, so [0.2, 0.8] is the whole of it — the same half-width {@link JourneyRamp} reads
     *  back off {@code getBoundingBox()} when it asks whether the body is in the way of a step. */
    private static final double OFF_CENTRE = 0.3;

    /**
     * How well a body standing in {@code foot} can put fluid into {@code target} by aiming at
     * {@code backing} — {@link #REFUSED}, {@link #CENTRE_ONLY} or {@link #ANYWHERE}.
     *
     * <p>The centre pass writes the caller's veto map in the words it has always used, so a refusal
     * reads the same as it did before. The corner passes add one row of their own, naming the cell
     * that stops the shot, because "the geometry is fine and the body's own footwork is not" is a
     * different search from "there is nothing to aim at down here".
     */
    static int pourGrade(ServerLevel level, JourneyRig rig, BlockPos foot, boolean afloat,
                         BlockPos backing, BlockPos target, Map<String, Integer> why) {
        String centre = pourLine(level, rig, foot, 0.5, 0.5, afloat ? 1 : 0, backing, target);
        if (centre != null) {
            why.merge(centre, 1, Integer::sum);
            return REFUSED;
        }
        for (double x : new double[] {0.5 - OFF_CENTRE, 0.5 + OFF_CENTRE})
            for (double z : new double[] {0.5 - OFF_CENTRE, 0.5 + OFF_CENTRE})
                // BOTH HEIGHTS when the cell holds fluid, because how far a body floats is exactly
                // what nobody here knows: one block of water lifts it about a third of a block
                // (measured `身体 y=56.29` over a floor at 56), deeper water lifts it a whole one
                // (measured `water2.stand=-9,56,37` against `身体 -9,57,37`). A line that needs one
                // of the two to be true is not a line this rung may plan on.
                for (int lift = 0; lift <= (afloat ? 1 : 0); lift++) {
                    String edge = pourLine(level, rig, foot, x, z, lift, backing, target);
                    if (edge == null) continue;
                    why.merge("只有正对格心才成立（走位偏 " + OFF_CENTRE + " 格就 " + edge + "）",
                            1, Integer::sum);
                    return CENTRE_ONLY;
                }
        return ANYWHERE;
    }

    /** Null when the line holds from this one eye; otherwise the veto that stopped it. The clip is
     *  the one a filled bucket runs — {@code Fluid.NONE}, because that is what a non-empty bucket
     *  uses — and it is the same call the chooser has always made, only from more places. */
    private static String pourLine(ServerLevel level, JourneyRig rig, BlockPos foot, double x,
                                   double z, int lift, BlockPos backing, BlockPos target) {
        var eye = new Vec3(foot.getX() + x, foot.getY() + lift + rig.player().getEyeHeight(),
                foot.getZ() + z);
        var aim = Vec3.atCenterOf(backing);
        if (eye.distanceTo(aim) > JourneyFill.BUCKET_REACH) return "够不着 " + backing.toShortString();
        var hit = level.clip(new ClipContext(eye, aim, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, rig.player()));
        if (hit.getType() != HitResult.Type.BLOCK) return "射线没打到方块";
        if (!hit.getBlockPos().equals(backing))
            return "射线停在 " + hit.getBlockPos().toShortString() + " "
                    + level.getBlockState(hit.getBlockPos()).getBlock();
        if (!backing.relative(hit.getDirection()).equals(target))
            return "打中 " + backing.toShortString() + " 的 " + hit.getDirection() + " 面";
        return null;
    }
}
