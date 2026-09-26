package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;

/**
 * The fire that was already there when a corridor route segment was planned — the field the death of rung 14
 * needs and no run has ever recorded.
 *
 * <h2>The reading this exists to disambiguate</h2>
 *
 * The ladder that first reached the Nether died on rung 14 with ten of its eighteen hit points burnt
 * off. Read the damage trace in write order (field names rendered in English):
 *
 * <pre>
 * t21 −1.0→13.0 @71, 43, 70 below=netherrack in=fire burning 160t
 * t31 −1.0→12.0 @71, 43, 69 below=netherrack in=air  burning 170t
 * </pre>
 *
 * <p><b>The trace's "in" field is {@code blockPosition}, so the bot's CENTRE was in a fire
 * block.</b> That is not something a 0.6-wide box clips into from a neighbouring cell — the box
 * explains why the burn kept climbing after the bot had left ("in=air" with the counter still
 * rising is {@code BaseFireBlock#entityInside} reading the box, not the cell), but it does not
 * explain how the bot got in.
 *
 * <p>And the planner cannot have routed it there. {@code BotUtil.isHazardState} answers true for
 * {@link BlockTags#FIRE}, every real {@code WorldView} delegates to it, and
 * {@code WorldView.canStandAt} refuses a foot cell that is a hazard. So there are exactly two
 * families left, and they call for opposite repairs:
 *
 * <ul>
 *   <li><b>(a) The fire was there at plan time.</b> The search refused it as a node, so the bot
 *       reached it by LEAVING the plan — cutting a corner, or knocked off by the unattributed
 *       {@code −1.0 @70,43,69} one row earlier. The repair is in the executor, or a tax on the
 *       cells beside fire so a corner cut has no fire to cut into.</li>
 *   <li><b>(b) The fire appeared after the plan.</b> A ghast fireball PLACES fire blocks, so
 *       "a ghast did it" is not a third family, it is this one. The repair is a replan trigger, not
 *       a cost.</li>
 * </ul>
 *
 * <p>One reading already rules out a third candidate: the bot's first burn stamp is exactly
 * {@code 160t}, the value vanilla assigns on entering a fire block, so it was not ignited at range.
 *
 * <h2>The answer, from the first run that carried this instrument (2026-08-28)</h2>
 *
 * <b>(b).</b> That run took four {@code inFire} blows at {@code 66,43,66}, {@code 70,43,69} twice and
 * {@code 71,43,69}. Every cell a 0.6-wide box at those positions can overlap — x±1, z±1, y 43–44 —
 * lies inside the census box of segment 3 ({@code x 60–72 / y 42–44 / z 65–70}) or of segment 4
 * ({@code x 69–73 / y 41–44 / z 67–86}), both of which reported 0 fire cells over 234 and 400
 * scanned cells. So the repair is a replan trigger, not a cost — and taxing the cells beside fire,
 * which is what (a) would have asked for, would have been work aimed at a world that did not exist.
 *
 * <p><b>A census only answers this if it precedes the blow, so the blows have to be attributed to a
 * segment — "whichever segment owns them, some census precedes them" is not an argument.</b> It
 * reads like one, but it is false in the direction that
 * matters: had {@code 66,43,66} belonged to segment 2, segment 3's census would have been written
 * AFTER it, and segment 2's own box ({@code x 31–63}) does not reach x=66 — the blow would have had
 * no census at all. The attribution, from two independent fields:
 *
 * <ul>
 *   <li><b>Position.</b> {@code wp2.at} is {@code 61,43,66} and {@code wp2.track.direct}'s furthest
 *       east sample is {@code 57,43,55}: segment 2 was never at x=66. Segment 3 runs
 *       {@code 61,43,66 → 71,43,69}, so {@code 66,43,66} is on it, and the other three sit at
 *       segment 4's start cell.</li>
 *   <li><b>The clock.</b> {@code death.blow}'s {@code @N} is a SEGMENT tick, and {@code hp.trace}'s
 *       write order runs {@code t161 → t28 → t12}: it resets twice, so those three blows are in
 *       three different segments. Combined with the positions that fixes {@code t28} on segment 3
 *       and {@code t12/t52/t114} on segment 4 — and each segment's census is written from
 *       {@code .from}, which is the PREVIOUS segment's arrival, i.e. before the bot has moved on
 *       this segment.</li>
 * </ul>
 *
 * <p>Two cautions that came with the answer, both about what it does NOT say:
 *
 * <ul>
 *   <li><b>Which side of the audit this settles is fire, not the corridor.</b> That run still failed
 *       rung 14, and it failed by walking segment 5 for 2 403 ticks and dying of accumulated fall damage.
 *       Fire cost it four points of twenty.</li>
 *   <li><b>Nothing here names the igniter.</b> {@code inFire} in the Nether can only come from
 *       {@link BlockTags#FIRE} — lava is {@code lava} and magma is {@code hotFloor} — so the damage
 *       type is unambiguous about WHAT hurt the bot and silent about WHO lit it. A ghast is one
 *       candidate; fire spreading across netherrack, which never burns out, is another.</li>
 * </ul>
 *
 * <p><b>And the trace field that looks like the fire signal is not it.</b> All four of those blows
 * appear in {@code hp.trace} as "in=air" with no "burning" stamp: the bot's CENTRE was in air
 * while its BOX was in the fire, and it was never set alight. A reader keyed on "in=fire" — which
 * is how the trace read on the death above — sees none of them. The "in" field answers "which
 * block is the bot's centre in"; only the damage TYPE in {@code death.blow} answers "did fire hurt
 * it".
 *
 * <h2>What this is NOT</h2>
 *
 * <b>It scans the axis-aligned BOX spanned by the two endpoints, not the route the bot walks.</b>
 * A path is free to bulge outside it and a cell inside it is not necessarily on the way. That is a
 * bounded question deliberately — a census that tried to follow the path would have to run after the
 * search, and the whole point is to have the reading from BEFORE the segment is walked. A reader comparing
 * a burn coordinate against this list gets "was there fire in this neighbourhood at plan time",
 * which is precisely the (a)/(b) discriminator and nothing more. Naming the box in the row keeps
 * that honest; a bare "0 fire" would read as "no fire on the route", which this cannot say.
 *
 * <p>Every row prints the CELLS, never a yes/no. A predicate cannot be checked against a burn
 * coordinate, and this suite has repeatedly paid for an instrument that printed its own conclusion.
 * When there are more cells than {@link #NAMED_CELLS} the row says how many it left out — the
 * sibling {@code death.blow} keeps eight blows and drops the rest with no such clause, which is why
 * a fourteen-blow death reads as an eight-blow one.
 */
final class JourneyFireCensus {

    private JourneyFireCensus() { }

    /** How many fire cells the row names before it starts counting instead. Twelve is enough to see
     *  a patch's shape; past that the count is the useful reading. */
    private static final int NAMED_CELLS = 12;

    /**
     * Every {@link BlockTags#FIRE} cell in the box spanned by {@code from} and {@code to}, grown by
     * {@code pad} on all six sides, in scan order.
     *
     * <p>The tag rather than a block reference, for the same reason {@code isHazardState} uses it:
     * soul fire is fire, and so is anything a datapack adds. Matching {@code Blocks.FIRE} alone
     * would make a soul-sand fortress read as fireless.
     */
    static List<BlockPos> fireCells(ServerLevel level, BlockPos from, BlockPos to, int pad) {
        List<BlockPos> found = new ArrayList<>();
        int x0 = Math.min(from.getX(), to.getX()) - pad, x1 = Math.max(from.getX(), to.getX()) + pad;
        int y0 = Math.min(from.getY(), to.getY()) - pad, y1 = Math.max(from.getY(), to.getY()) + pad;
        int z0 = Math.min(from.getZ(), to.getZ()) - pad, z1 = Math.max(from.getZ(), to.getZ()) + pad;
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (level.getBlockState(p).is(BlockTags.FIRE)) found.add(p);
                }
        return found;
    }

    /** How many cells {@link #fireCells} looks at, so a zero can be told from a scan that never ran. */
    static int scanned(BlockPos from, BlockPos to, int pad) {
        long w = Math.abs(from.getX() - to.getX()) + 1L + 2 * pad;
        long h = Math.abs(from.getY() - to.getY()) + 1L + 2 * pad;
        long d = Math.abs(from.getZ() - to.getZ()) + 1L + 2 * pad;
        return (int) Math.min(Integer.MAX_VALUE, w * h * d);
    }

    /** The evidence row: the box, how much of it was looked at, and the cells themselves. */
    static String line(ServerLevel level, BlockPos from, BlockPos to, int pad) {
        List<BlockPos> fire = fireCells(level, from, to, pad);
        StringBuilder out = new StringBuilder();
        out.append(from.toShortString()).append(" → ").append(to.toShortString())
                .append(" bounding box (padded ").append(pad).append(" blocks on each side, ")
                .append(scanned(from, to, pad)).append(" cells in total) contains ").append(fire.size())
                .append(" fire cells");
        if (fire.isEmpty()) {
            out.append(": this means \"no fire in the box at plan time\", not \"no fire on the"
                    + " route\"; the route can bulge outside the box");
            return out.toString();
        }
        out.append(": ");
        for (int i = 0; i < Math.min(NAMED_CELLS, fire.size()); i++)
            out.append(i == 0 ? "" : "; ").append(fire.get(i).toShortString());
        if (fire.size() > NAMED_CELLS)
            out.append(", plus ").append(fire.size() - NAMED_CELLS).append(" not listed");
        out.append(". ⚠️ This is the bounding box, not the path walked: a cell in the box is not"
                + " necessarily on the route, and the route may bulge outside the box");
        return out.toString();
    }
}
