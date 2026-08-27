package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;

/**
 * The fire that was already there when a corridor leg was planned — the field the death of rung 14
 * needs and no run has ever recorded.
 *
 * <h2>The reading this exists to disambiguate</h2>
 *
 * The ladder that first reached the Nether died on rung 14 with ten of its eighteen hit points burnt
 * off. Read the damage trace in write order:
 *
 * <pre>
 * t21 −1.0→13.0 @71, 43, 70 脚下=netherrack 身处=fire 着火160t
 * t31 −1.0→12.0 @71, 43, 69 脚下=netherrack 身处=air  着火170t
 * </pre>
 *
 * <p><b>{@code 身处} is {@code blockPosition}, so the body's CENTRE was in a fire block.</b> That is
 * not something a 0.6-wide box clips into from a neighbouring cell — the box explains why the burn
 * kept climbing after the body had left ({@code 身处=air} with the counter still rising is
 * {@code BaseFireBlock#entityInside} reading the box, not the cell), but it does not explain how the
 * body got in.
 *
 * <p>And the planner cannot have routed it there. {@code BotUtil.isHazardState} answers true for
 * {@link BlockTags#FIRE}, every real {@code WorldView} delegates to it, and
 * {@code WorldView.canStandAt} refuses a foot cell that is a hazard. So there are exactly two
 * families left, and they call for opposite repairs:
 *
 * <ul>
 *   <li><b>(a) The fire was there at plan time.</b> The search refused it as a node, so the body
 *       reached it by LEAVING the plan — cutting a corner, or knocked off by the unattributed
 *       {@code −1.0 @70,43,69} one row earlier. The repair is in the executor, or a tax on the
 *       cells beside fire so a corner cut has no fire to cut into.</li>
 *   <li><b>(b) The fire appeared after the plan.</b> A ghast fireball PLACES fire blocks, so
 *       「a ghast did it」is not a third family, it is this one. The repair is a replan trigger, not
 *       a cost.</li>
 * </ul>
 *
 * <p>One reading already rules out a third candidate: the body's first burn stamp is exactly
 * {@code 160t}, the value vanilla assigns on entering a fire block, so it was not ignited at range.
 *
 * <h2>What this is NOT</h2>
 *
 * <b>It scans the axis-aligned BOX spanned by the two endpoints, not the route the body walks.</b>
 * A path is free to bulge outside it and a cell inside it is not necessarily on the way. That is a
 * bounded question deliberately — a census that tried to follow the path would have to run after the
 * search, and the whole point is to have the reading from BEFORE the leg moves. A reader comparing
 * a burn coordinate against this list gets 「was there fire in this neighbourhood at plan time」,
 * which is precisely the (a)/(b) discriminator and nothing more. Naming the box in the row keeps
 * that honest; a bare 「0 fire」 would read as 「no fire on the route」, which this cannot say.
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
                .append(" 的外接盒（各面外扩 ").append(pad).append(" 格，共 ")
                .append(scanned(from, to, pad)).append(" 格）里有 ").append(fire.size())
                .append(" 个火格");
        if (fire.isEmpty()) {
            out.append(" —— 这是「规划这一刻盒子里没有火」，不是「路上没有火」：路可以鼓出盒外");
            return out.toString();
        }
        out.append("：");
        for (int i = 0; i < Math.min(NAMED_CELLS, fire.size()); i++)
            out.append(i == 0 ? "" : "、").append(fire.get(i).toShortString());
        if (fire.size() > NAMED_CELLS)
            out.append("，另有 ").append(fire.size() - NAMED_CELLS).append(" 个没列出");
        out.append("。⚠️ 这是外接盒不是走过的路 —— 盒里的格未必在路上，路也可能鼓出盒外");
        return out.toString();
    }
}
