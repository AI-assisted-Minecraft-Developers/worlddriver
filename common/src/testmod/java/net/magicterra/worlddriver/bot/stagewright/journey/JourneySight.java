package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Does the pour line hold from every position a bot standing in that cell can actually be in?
 *
 * <h2>The eye the chooser used is one no bot ever has</h2>
 *
 * <p>{@code JourneyPour#standToAimAt} — <b>that file, not {@code JourneyPortalRung}</b>, which this
 * paragraph named for a while and which has never had such a member; the rung reaches this family
 * through {@code JourneyPour.standLevelWith}/{@code raiseTo}/{@code aimThatLandsIn}, and names
 * {@code standToPour}/{@code standToFill} only in prose — weighed each candidate by clipping from
 * {@code (foot.x + 0.5, foot.y + float + eyeHeight, foot.z + 0.5)} — the exact centre of the cell,
 * at a height guessed by adding a whole block when the cell holds fluid. A real bot is at neither.
 * A player's box is 0.6 wide, so its centre rests anywhere in {@code [0.2, 0.8]} of its own cell —
 * the walker leaves it wherever the last path edge ended, which {@link JourneyRamp}'s own approach
 * note already measured from the other side (exact position of the bot: 6.60/57.00/17.78) — and a bot in
 * one block of water floats a third of a block, not a whole one.
 *
 * <p>Measured, the south rehearsal of 2026-08-17, cell eight of the mould. The chooser accepted
 * {@code -10,56,32} and said so:
 *
 * <pre>
 * cast8.stand.3 = -10, 56, 32 aiming at -9, 60, 35 (near face of the backing) veto counts {…}
 * cast8.picks.3 = -9, 58, 33 Block{minecraft:cobblestone} face=west → lands in -10, 58, 33
 *                 (intended pour -9, 60, 34, …, bot at -10, 56, 32, eye -9.15/57.98/32.57 …)
 * </pre>
 *
 * <p>The eye it was chosen for is {@code -9.50/58.62/32.50}; the eye that fired is
 * {@code -9.15/57.98/32.57}. The shot is a diagonal ({@code dx=1, dz=3}), so a third of a block of
 * x is a whole column of crossing: from the centre the line enters the {@code x=-9} rank beyond the
 * step at {@code -9,58,33}, and from where the bot actually stood it enters straight into it. The
 * pour's own {@code .picks} gate caught it, as it is built to — but by then the cell had already
 * cost the rung the decision that matters, because {@code standLevelWith} asks this same chooser
 * whether a raise is needed at all, and a "yes, there is a spot" skips the raise. The east arm,
 * which has no such spot, raises and pours the same cell 5 times in 5.
 *
 * <h2>So a stand is graded, not accepted</h2>
 *
 * <p>{@link #ANYWHERE} means the line survives every corner of the bot's own footprint and both
 * heights a bot in fluid can sit at; {@link #CENTRE_ONLY} means it holds from the ideal point and
 * from nowhere else, which is a coin toss the walker gets to call. The caller keeps the second as a
 * place to WALK to — refusing it outright would leave a bot with nowhere to go, and the {@code
 * .picks} gate still refuses to spend the bucket — but it is no answer to the question "is a raise
 * needed", where the cost of a wrong yes is the cell.
 *
 * <p>Note what this does NOT do: it does not widen anything, and it is not a tolerance. It asks the
 * existing question more times, from the positions the bot is entitled to occupy, and a shot that
 * is axis-aligned — the shape this rung's every successful cast has — is unaffected by construction,
 * because sliding the eye along its own axis does not move which cells the line crosses.
 *
 * <h2>The second kind of no-go list: cells that must stay clear of a LINE</h2>
 *
 * <p>{@link JourneyStairs#needsOpen} is the first kind — cells the descent flight needs to stay
 * WALKABLE, which {@link JourneyRamp#fillable} refuses to build into. A block that cannot stop a
 * bot can still stop a ray, so the mould needs the other kind too, and rung 12 lost a run to not
 * having it (ladder run of 2026-08-20, cast 8 of an {@code east} mould at {@code 4,56,19}):
 *
 * <pre>
 * cell.8.step       = 3, 58, 19 placed as a step for 4, 60, 19 → can stand now (cobblestone)
 * wet.8.ramp.flight = 4 steps: 2, 56, 17 → 3, 57, 17 → 3, 58, 18 → 3, 59, 19
 * cast8.picks.1     = 3, 59, 19 Block{minecraft:cobblestone} face=west → lands in 2, 59, 19
 *                     (intended pour 4, 60, 19, aiming at 5, 60, 19, bot at 2, 58, 19, eye 2.04/59.62/19.48)
 * cast8.clear3      = no clearable block on the pour line (3, 59, 19=Block{minecraft:cobblestone}(inside the alcove) …)
 * </pre>
 *
 * <p>Read in order: a step was placed at {@code 3,58,19} <b>for this very cell</b>, so that the bot
 * could stand in {@code 3,59,19} and shoot the backing along the axis. The WET half of the same cell
 * then needed to be one row higher, its flight filled {@code 3,59,19} to get there — and the LAVA
 * half came back to find its own stand solid, its line blocked by that same block, and
 * {@code clearPourLine} exempting it because {@link JourneyRamp#isStep} said the rung had put it
 * there on purpose. The rung built the blocker and then excused it.
 *
 * <p><b>Refusing the fill is not available here, and that is measured rather than assumed</b> — see
 * {@code wd.pourLineHasNoOtherWayUp}. The wet cell is one row above the frame cell, so the stand it
 * needs is one row above the frame cell's stand, so its flight's landing rests on exactly the cell
 * the frame cell wants to stand IN. {@link JourneyRamp#plan} is given the reservation and prefers a
 * route around it, but in this alcove there is no route around it: the reserved cell IS the landing's
 * own support. So the reservation is redeemed instead of enforced — the step is BORROWED, and
 * {@link #blockersOnTheLine} hands it back the moment the pour that reserved it asks.
 */
final class JourneySight {

    private JourneySight() {}

    /** The line does not hold even from the ideal point — the veto is in the caller's map. */
    static final int REFUSED = 0;

    /** It holds from the centre of the cell and not from the whole of it. */
    static final int CENTRE_ONLY = 1;

    /** It holds from every position the bot can rest in. */
    static final int ANYWHERE = 2;

    /** How far a standing bot's centre can sit from the centre of its own cell. A player's box is
     *  0.6 wide, so [0.2, 0.8] is the whole of it — the same half-width {@link JourneyRamp} reads
     *  back off {@code getBoundingBox()} when it asks whether the bot is in the way of a step. */
    private static final double OFF_CENTRE = 0.3;

    /**
     * How well a bot standing in {@code foot} can put fluid into {@code target} by aiming at
     * {@code backing} — {@link #REFUSED}, {@link #CENTRE_ONLY} or {@link #ANYWHERE}.
     *
     * <p>The centre pass writes the caller's veto map in the words it has always used, so a refusal
     * reads the same as it did before. The corner passes add one row of their own, naming the cell
     * that stops the shot, because "the geometry is fine and the bot's own footwork is not" is a
     * different search from "there is nothing to aim at down here".
     *
     * <p>Takes the BOT rather than the rig, and that is not cosmetic: everything here is geometry
     * plus one eye height, so an isolated arena can ask the production question of a staged mould
     * with its own avatar. A predicate that could only be reached through {@link JourneyRig} could
     * only be tested by a forty-minute ladder run.
     */
    static int pourGrade(ServerLevel level, ServerPlayer body, BlockPos foot, boolean afloat,
                         BlockPos backing, BlockPos target, Map<String, Integer> why) {
        String centre = pourLine(level, body, foot, 0.5, 0.5, afloat ? 1 : 0, backing, target);
        if (centre != null) {
            why.merge(centre, 1, Integer::sum);
            return REFUSED;
        }
        for (double x : new double[] {0.5 - OFF_CENTRE, 0.5 + OFF_CENTRE})
            for (double z : new double[] {0.5 - OFF_CENTRE, 0.5 + OFF_CENTRE})
                // BOTH HEIGHTS when the cell holds fluid, because how far a bot floats is exactly
                // what nobody here knows: one block of water lifts it about a third of a block
                // (measured bot y=56.29 over a floor at 56), deeper water lifts it a whole one
                // (measured `water2.stand=-9,56,37` against the bot at -9,57,37). A line that needs
                // one of the two to be true is not a line this rung may plan on.
                for (int lift = 0; lift <= (afloat ? 1 : 0); lift++) {
                    String edge = pourLine(level, body, foot, x, z, lift, backing, target);
                    if (edge == null) continue;
                    why.merge("holds only from the cell centre (standing " + OFF_CENTRE
                                    + " blocks off centre: " + edge + ")",
                            1, Integer::sum);
                    return CENTRE_ONLY;
                }
        return ANYWHERE;
    }

    /**
     * The eye a bucket line should be clipped from, for a bot that would stand at {@code foot}.
     *
     * <p>Two cases, and only one of them is a guess. When {@code foot} is the cell the bot is
     * ALREADY standing in, its eye is knowable to the centimetre, so ask it. A player's box is
     * 0.6 wide, so its centre rests anywhere in {@code [0.2, 0.8]} of its own cell, which puts the
     * real eye up to {@code hypot(0.2, 0.2) = 0.283} away from the centre this file's other callers
     * assume — measured {@code 0.22} on the seat that passed validation and then fired into rock,
     * with the blocking face named ({@code -5,62,55 grass_block face=up, 1.04 blocks}). When {@code foot}
     * is any OTHER cell — one the bot has not walked to — no such reading exists and the centre is
     * the only honest estimate available.
     *
     * <p>Note what this deliberately is NOT: an envelope. It clips exactly as many rays as before,
     * from a better origin for one cell, so it costs nothing. {@link #pourLine}'s corner sampling is
     * the envelope, and it answers the opposite question — whether a line survives EVERY position
     * the bot might end up in. An earlier attempt conflated the two, gave every candidate five
     * eyes, and was reverted.
     */
    static Vec3 eyeFor(ServerPlayer body, BlockPos foot) {
        return body.blockPosition().equals(foot)
                ? body.getEyePosition()
                : new Vec3(foot.getX() + 0.5, foot.getY() + body.getEyeHeight(), foot.getZ() + 0.5);
    }

    /** Null when the line holds from this one eye; otherwise the veto that stopped it. The clip is
     *  the one a filled bucket runs — {@code Fluid.NONE}, because that is what a non-empty bucket
     *  uses — and it is the same call the chooser has always made, only from more places. */
    private static String pourLine(ServerLevel level, ServerPlayer body, BlockPos foot, double x,
                                   double z, int lift, BlockPos backing, BlockPos target) {
        var eye = new Vec3(foot.getX() + x, foot.getY() + lift + body.getEyeHeight(),
                foot.getZ() + z);
        var aim = Vec3.atCenterOf(backing);
        if (eye.distanceTo(aim) > JourneyFill.BUCKET_REACH) return "out of reach: " + backing.toShortString();
        var hit = level.clip(new ClipContext(eye, aim, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, body));
        if (hit.getType() != HitResult.Type.BLOCK) return "the ray hit no block";
        // JourneyPourLineScenes rebuilds this exact key and looks it up with containsKey, so the
        // wording must change in both files at once.
        if (!hit.getBlockPos().equals(backing))
            return "the ray stopped at " + hit.getBlockPos().toShortString() + " "
                    + level.getBlockState(hit.getBlockPos()).getBlock();
        if (!backing.relative(hit.getDirection()).equals(target))
            return "hit the " + hit.getDirection() + " face of " + backing.toShortString();
        return null;
    }

    // ------------------------------------------------ the sight-line reservation ----

    /** The mould whose casts still have lines to protect, or null when there is none. Bare
     *  coordinates, like {@link JourneyStairs#cells}, so it is cleared with the corridor it belongs
     *  to rather than left to be inherited by the next hole. */
    private static BlockPos mouldBase;
    private static Direction mouldAway;

    /** Register the mould this reservation is about. Called where {@code forgeCorridor} is
     *  replaced — one lifetime, one alcove. */
    static void mould(BlockPos base, Direction away) {
        mouldBase = base == null ? null : base.immutable();
        mouldAway = away;
    }

    /** No mould at all. The isolated scenes stage one and must not leave it behind. */
    static void forgetMould() { mould(null, null); }

    /**
     * Is this cell on the pour line of a ring cell that has NOT been cast yet?
     *
     * <p>"Not cast yet" is read off the WORLD rather than kept as an index, for the same reason
     * {@link JourneyRamp}'s footing scan reads the world: a cell that was cast and then lost is
     * still a cell this rung has to pour into, and an index would say it was done. Obsidian in a
     * ring cell is the one state that means the line is spent.
     *
     * <p>Cheap enough to ask per placement — ten ring cells times the {@link
     * JourneyPortalRung#POUR_LINE} window is at most 120 comparisons and no block reads until a
     * ring cell matches.
     */
    static boolean onALineToCome(ServerLevel level, BlockPos cell) {
        return lineOwner(level, cell) != null;
    }

    /** Which pending cast needs this cell clear, or null — the named half of
     *  {@link #onALineToCome}, so a row can say WHICH pour it is borrowing from. */
    static BlockPos lineOwner(ServerLevel level, BlockPos cell) {
        if (mouldBase == null || mouldAway == null) return null;
        for (int[] c : JourneyForge.RING) {
            BlockPos ring = JourneyForge.frameCell(mouldBase, mouldAway, c[0], c[1]);
            if (level.getBlockState(ring).is(Blocks.OBSIDIAN)) continue;
            if (onTheLineOf(ring, mouldAway, cell)) return ring;
        }
        return null;
    }

    /** The window {@link JourneyPortalRung#clearPourLine} sweeps, as a predicate: {@code depth}
     *  cells back along the pour's own axis, one row below the target for the feet and two above it
     *  for the head a floating bot has. */
    private static boolean onTheLineOf(BlockPos target, Direction away, BlockPos cell) {
        for (int k = 1; k <= JourneyPortalRung.POUR_LINE; k++)
            for (int dy = -1; dy <= 2; dy++)
                if (target.relative(away.getOpposite(), k).above(dy).equals(cell)) return true;
        return false;
    }

    /**
     * What is standing in this pour's line that the rung may take back.
     *
     * <p>Moved out of {@link JourneyPortalRung#clearPourLine} so an arena can ask the production
     * question without a rig, and given the clause that run cost: <b>a flight step on the line goes
     * back unless the bot is resting on it.</b>
     *
     * <p>The exemption it replaces was unconditional, and its reasoning was sound as far as it went
     * — breaking the floor a pour is standing on is the same mistake as the clear that mined the
     * frame it was pouring into. What it missed is that the step blocking a line is usually not the
     * step holding the bot up: on the run above the bot stood at {@code 2,58,19} and the blocker
     * was {@code 3,59,19}, a column over and a row up. So the question is asked of the bot's own
     * footprint instead of of the block's provenance, and a step that is genuinely load-bearing is
     * still refused — {@code wd.pourLineWillNotMineTheStepUnderItsOwnFeet} is that half.
     *
     * <p>Nothing outside {@code corridor} is ever touched: one cell below the bottom frame row is
     * the mould's own floor, and answering a blocked ray by breaking it would drain every cast.
     * Fluid is skipped because the alcove floods with the rung's own water and a puddle is not a
     * wall — {@code mine} on water is a no-op that spends the whole budget.
     */
    static List<BlockPos> blockersOnTheLine(ServerLevel level, Set<BlockPos> corridor,
                                            BlockPos target, Direction away, int depth,
                                            ServerPlayer body) {
        List<BlockPos> blocked = new ArrayList<>();
        for (int k = 1; k <= depth; k++)
            for (int dy = -1; dy <= 2; dy++) {
                BlockPos c = target.relative(away.getOpposite(), k).above(dy);
                if (!corridor.contains(c)) continue;
                if (level.getBlockState(c).isAir()) continue;
                if (!level.getFluidState(c).isEmpty()) continue;   // the rung's own water, not a wall
                if (JourneyRamp.isStep(c) && restsOn(body, c)) continue;
                blocked.add(c.immutable());
            }
        return blocked;
    }

    /**
     * Is the bot in this cell, or standing on it?
     *
     * <p>All four corners of the footprint, because a 0.6-wide box a fifth of a cell off centre
     * rests on the cell next door — the same reason {@link JourneyShaft#supportUnder} looks there,
     * and the same measurement (exact position of the bot: 6.60/57.00/17.78) that
     * {@link JourneyRamp#approach} was written around. Asked of the BOX rather than of
     * {@code blockPosition()} for exactly that: the cell a bot rounds to is not the whole of what
     * it is resting on.
     */
    static boolean restsOn(ServerPlayer body, BlockPos c) {
        if (body == null) return false;
        AABB box = body.getBoundingBox();
        if (box.intersects(new AABB(c))) return true;
        if (c.getY() != Mth.floor(box.minY) - 1) return false;
        return c.getX() >= Mth.floor(box.minX) && c.getX() <= Mth.floor(box.maxX)
                && c.getZ() >= Mth.floor(box.minZ) && c.getZ() <= Mth.floor(box.maxZ);
    }
}
