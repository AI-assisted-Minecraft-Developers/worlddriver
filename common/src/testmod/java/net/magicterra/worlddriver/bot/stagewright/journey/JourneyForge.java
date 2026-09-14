package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * The shape of the mould rung 12 casts its portal frame in, and the rules that say where it may go.
 *
 * <p>Pure geometry and world-reading: no body, no rig, no processes. It lives apart from the rung
 * that drives it because the rung's file is at its line budget, and because these are the parts a
 * failure is usually diagnosed against — {@code RING}'s order and the shell rule below are each a
 * one-line invariant that cost a run to learn.
 *
 * <h2>The two invariants</h2>
 *
 * <b>1. Every cell is cast onto something.</b> {@link #RING} is the cast order and it encodes a
 * dependency: each cell's floor is either rock nobody has touched or the obsidian cast one step
 * earlier — {@code (-1,2)} stands on {@code (-1,1)}, which is cast before it. That is why
 * {@link #corridor} carves only the space the body walks in and the frame cells are opened one at a
 * time, in {@code castCell}. Hollowing all twelve up front turns every one of those floors into air
 * before the first pour, and every fluid then runs off: measured as {@code frame.cast=0/10} with
 * both buckets reporting {@code CONSUME}.
 *
 * <p><b>2. Nothing the excavation opens may touch a fluid.</b> {@link #blocked} is that rule, and it
 * is the one this class was extracted to add. The rung used to check only the cells it was about to
 * DIG, which is half the question: a mould dug directly under a lava lake has perfectly dry cells
 * right up to the moment the last one is opened, and then the lake comes in through the ceiling.
 * Measured, and it is the whole of run 21's {@code 0/10}: a surface lake at y=63, the forge floor
 * seven below at y=56, an alcove seven tall — so its ceiling was the lake's own floor, and cell zero
 * read {@code -9,56,23 = lava} with the water cell beside it {@code = lava} too.
 */
public final class JourneyForge {

    private JourneyForge() {}

    /** Ring cells of the portal, as (dx, dy) from the frame's bottom-left. Corners left out: ten
     *  blocks is what the ladder can afford, and {@code wd.serverLightsPortal} proves ten lights.
     *  The ORDER is load-bearing — see the class note's first invariant. */
    public static final int[][] RING = {
            {0, 0}, {1, 0}, {-1, 1}, {2, 1}, {-1, 2}, {2, 2}, {-1, 3}, {2, 3}, {0, 4}, {1, 4}};

    /** How tall the alcove is, in cells — the body's floor plus six. */
    public static final int ALCOVE_HEIGHT = 7;

    /** How far below the lava the mould starts out being cut.
     *
     * <p>Seven puts all twelve frame cells in rock at an UNDERGROUND pool whatever its depth, which
     * is what this number was chosen for. It is only a starting point: beside a surface lake seven is
     * not enough for the alcove's ceiling, and {@link #blocked} is what discovers that and
     * {@code carveTheForge} is what digs further. */
    public static final int BELOW_LAVA = 7;

    /** A frame cell at (dx, dy) from {@code base}, in the plane facing {@code away}. */
    public static BlockPos frameCell(BlockPos base, Direction away, int dx, int dy) {
        return base.relative(away.getClockWise(), dx).above(dy);
    }

    /** The interior (or, for the top pair, the notch above) that the water goes into for this cell. */
    public static BlockPos wetCellFor(BlockPos base, Direction away, int dx, int dy) {
        if (dy == 0) return frameCell(base, away, dx, 1);                 // bottom pair: above
        if (dy == 4) return frameCell(base, away, dx, 5);                 // top pair: the notch
        return frameCell(base, away, dx < 0 ? 0 : 1, dy);                 // columns: sideways
    }

    /**
     * Only the corridor — the space the body walks and stands in. <b>Not</b> the frame cells.
     *
     * <p>Carved bottom-up and deepening with {@code push}, so however far out the frame is pushed the
     * body still has a walked path to each cell. Order is not tidiness: the body digs what it can
     * path to, so opening a whole layer before the one above keeps every next cell adjacent to air it
     * can already stand in.
     */
    public static List<BlockPos> corridor(BlockPos at, Direction away, int push) {
        List<BlockPos> cells = new ArrayList<>();
        for (int y = 0; y < ALCOVE_HEIGHT; y++)
            for (int d = 0; d < push; d++)
                for (int w : SIDEWAYS)
                    cells.add(at.relative(away, d).relative(away.getClockWise(), w).above(y));
        return cells;
    }

    /**
     * The width of the alcove, ordered OUTWARD from the shaft the body arrives down.
     *
     * <p>Not cosmetic: {@code Body.canBreak} refuses a block whose six neighbours are all full
     * solid faces, on the correct grounds that no ray from any eye could reach it. Sweeping
     * {@code -2 → 2} therefore asks for the far edge first, while it is still buried in rock — and
     * that cell is silently skipped, which then buries the cell BEHIND it at the next depth. Measured
     * as {@code carve.stuck=2 格挖不动 例：-7,51,21}: both stuck cells were the far edge of the
     * corridor's floor, and nothing else in the whole excavation failed.
     *
     * <p>{@code 0} is the shaft's own column, which is already open when carving starts, so each
     * cell here is adjacent to one already carved.
     */
    private static final int[] SIDEWAYS = {0, -1, 1, -2, 2};

    /** Corridor plus the frame — everything that will be OPEN by the time the last cell is cast.
     *  The frame cells are checked here but must not be pre-carved; see the class note. */
    public static List<BlockPos> cells(BlockPos at, Direction away, int push) {
        List<BlockPos> cells = corridor(at, away, push);
        cells.addAll(aimedCells(at.relative(away, push), away));
        return cells;
    }

    /**
     * The fourteen cells a bucket is ever aimed into: ten ring cells, six interior, two cap notches.
     *
     * <p>One list, because there were three copies of it and they are the definition of what has to
     * have a backing — {@link #cells} counted them as "must be dry", {@link #firstOpenBacking}
     * counted them as "must have something behind", and the rung counts them again to audit those
     * backings during the casting. A copy that drifts turns a checked invariant into an unchecked one
     * silently.
     */
    public static List<BlockPos> aimedCells(BlockPos base, Direction away) {
        List<BlockPos> cells = new ArrayList<>();
        for (int[] c : RING) cells.add(frameCell(base, away, c[0], c[1]));
        for (int ix = 0; ix <= 1; ix++)
            for (int iy = 1; iy <= 3; iy++) cells.add(frameCell(base, away, ix, iy));
        cells.add(frameCell(base, away, 0, 5));
        cells.add(frameCell(base, away, 1, 5));
        return cells;
    }

    /**
     * Every cell that touches the excavation from outside — its walls, its floor, its roof.
     *
     * <p>The mould's containment, in other words, and the thing that has to hold. A fluid anywhere in
     * here drains into the mould the moment the cell beside it is opened, and the rung has no way to
     * tell that apart afterwards from a cast that simply did not work: what it sees is lava sitting
     * in a cell it never poured into.
     *
     * <p>The frame's backing is in this set too, which is the other reason it is worth naming. Every
     * bucket in this rung is aimed at the solid block BEHIND the cell it is filling, so a backing
     * that is fluid is not merely a leak — it is an aim with nothing to stop it.
     */
    public static List<BlockPos> shell(BlockPos at, Direction away, int push) {
        Set<BlockPos> inside = new HashSet<>(cells(at, away, push));
        LinkedHashSet<BlockPos> out = new LinkedHashSet<>();
        for (BlockPos c : inside)
            for (Direction d : Direction.values()) {
                BlockPos n = c.relative(d);
                if (!inside.contains(n)) out.add(n);
            }
        return new ArrayList<>(out);
    }

    /**
     * Every water SOURCE within {@code r} of {@code cells}, highest first — or null when there is
     * none. The question {@link #firstFluid}'s own parenthetical assumes an answer to.
     *
     * <p><b>Why this exists.</b> {@code firstFluid} prints「流动，没源就会自己退」for a flowing
     * cell. That is a claim about the cell's UPSTREAM, and the reading it comes from never looked
     * upstream — it is a local fact carrying a global promise. Ladder j51 is where the promise came
     * due: {@code drain.0} through {@code drain.6} cleared the stair foot every time, and then
     * {@code drain.7 = 等了 200 tick 仍有流体：2,56,20（流动，没源就会自己退）}. Sourceless water
     * does not survive 200 ticks; water that is still being fed survives forever, and the two are
     * the same row.
     *
     * <p>Vanilla makes the feeder easy to acquire without anyone pouring it: flowing water with two
     * or more horizontal source neighbours over a solid floor BECOMES a source. A rung that pours
     * water a dozen times into one small room manufactures them, and the recover only ever picks up
     * the one cell it aimed at.
     *
     * <p>A reading, not a repair — deliberately, and for the reason the stair foot itself is a
     * reading: what to do about a stray source (bucket it, wall it, re-route) depends on where it
     * is, and nothing has known where it is until now.
     */
    public static String sourcesAround(ServerLevel level, java.util.Collection<BlockPos> cells, int r) {
        LinkedHashSet<BlockPos> seen = new LinkedHashSet<>();
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos c : cells)
            for (int dx = -r; dx <= r; dx++)
                for (int dy = -r; dy <= r; dy++)
                    for (int dz = -r; dz <= r; dz++) {
                        BlockPos p = c.offset(dx, dy, dz).immutable();
                        if (!seen.add(p)) continue;
                        if (JourneyTerrain.plainSource(level, p, false)) found.add(p);
                    }
        if (found.isEmpty()) return null;
        // Highest first: the one feeding a puddle is upstream of it, and「上游」here means y.
        // Sorted rather than scan-ordered because the caller hands us a Set whose iteration order
        // is unspecified — an evidence row that reshuffles between runs cannot be diffed.
        found.sort(java.util.Comparator.<BlockPos>comparingInt(BlockPos::getY).reversed()
                .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        StringBuilder sb = new StringBuilder();
        int n = Math.min(found.size(), 12);
        for (int i = 0; i < n; i++) sb.append(i == 0 ? "" : "、").append(found.get(i).toShortString());
        if (found.size() > n) sb.append("…（共 ").append(found.size()).append(" 个，只列最高的 ").append(n).append(" 个）");
        return sb.toString();
    }

    /** The first cell holding fluid, described — or null when they are all dry. */
    public static String firstFluid(ServerLevel level, List<BlockPos> cells) {
        for (BlockPos c : cells)
            if (!level.getFluidState(c).isEmpty())
                // SOURCE OR FLOWING, because that one boolean is the whole diagnosis and the caller
                // that has been printing this for months could not act on it. Flowing water with
                // nothing feeding it runs out on its own and a wait is the right answer; a source
                // sits there forever and wants a bucket or a block. The drain gate says "waited 200
                // ticks and it is still wet" for both, which is a true sentence about two different
                // worlds.
                return c.toShortString() + " = " + level.getBlockState(c).getBlock()
                        + (level.getFluidState(c).isSource() ? "（源块）" : "（流动，没源就会自己退）");
        return null;
    }

    /**
     * Why a mould here would flood, or null when it would not.
     *
     * <p>Two questions, and the second is the one that was missing. "Is there fluid in what I am
     * about to dig" is answered by the ground the shovel goes through; "is there fluid in what will
     * be holding it in" is answered by everything one cell further out, and only the pair of them
     * says the mould will still be a mould once it is finished.
     */
    public static String blocked(ServerLevel level, BlockPos at, Direction away, int push) {
        String inside = firstFluid(level, cells(at, away, push));
        if (inside != null) return "要挖的格子里有流体：" + inside;
        String outside = firstFluid(level, shell(at, away, push));
        if (outside != null) return "模腔外壳（顶/壁/底）上有流体：" + outside
                + " —— 挖开旁边那格它就会灌进来";
        String back = firstOpenBacking(level, at, away, push);
        if (back != null) return "门框背后不是实心的：" + back
                + " —— 每一桶都是瞄着背板浇的，背板是空的，射线就穿过去，"
                + "流体落在更远的一格里（use 照样报 CONSUME）";
        return null;
    }

    /**
     * The first frame cell whose backing is not solid, or null when every one of them is.
     *
     * <p>The invariant nothing was checking, and it is not the same as "the backing is dry". Every
     * bucket in this rung is aimed at the block BEHIND the cell it wants to fill, because a filled
     * bucket clips through fluids and empties into the cell in front of whatever face it lands on.
     * An air cell stops no ray at all — so a mould cut against a cave wall aims through its own back
     * wall, the fluid lands a cell or more beyond, and {@code useItemInHand} still reports
     * {@code CONSUME}. The rung then reads its target, finds it empty, and reports the cast as
     * broken.
     *
     * <p>Checked over the fourteen cells that are ever aimed at: the ten ring cells, the six
     * interior, the two notches — {@link #cells} minus the corridor, which nothing is poured into.
     */
    public static String firstOpenBacking(ServerLevel level, BlockPos at, Direction away, int push) {
        List<BlockPos> open = openBackings(level, at.relative(away, push), away);
        if (open.isEmpty()) return null;
        BlockPos backing = open.get(0);
        return backing.toShortString() + " = " + level.getBlockState(backing).getBlock()
                + "（在 " + backing.relative(away.getOpposite()).toShortString() + " 后面）";
    }

    /**
     * Every backing that is no longer solid — the audit, not just its first line.
     *
     * <p>Asked once after the carve this is a go/no-go, which is what {@link #firstOpenBacking} was
     * written for. Asked again during the casting it is a MEASUREMENT: the backings were all solid
     * when the mould was declared sound and two of them were air by the ninth cast (run 43,
     * {@code -9,59,39} and {@code -9,60,39}, both behind the column the upper cells are dug from), so
     * what a cast needs to know is which ones went and when. A count per cast puts the loss inside one
     * round trip instead of somewhere in ten.
     */
    public static List<BlockPos> openBackings(ServerLevel level, BlockPos base, Direction away) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos cell : aimedCells(base, away)) {
            BlockPos backing = cell.relative(away);
            if (!level.getBlockState(backing).isSolidRender(level, backing)) out.add(backing);
        }
        return out;
    }

    /** How many of the ten ring cells are obsidian. */
    public static int countObsidian(ServerLevel level, BlockPos base, Direction away) {
        int n = 0;
        for (int[] c : RING)
            if (level.getBlockState(frameCell(base, away, c[0], c[1])).getBlock() == Blocks.OBSIDIAN) n++;
        return n;
    }
}
