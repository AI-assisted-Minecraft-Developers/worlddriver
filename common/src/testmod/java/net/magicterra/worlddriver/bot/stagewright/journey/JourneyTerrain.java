package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.magicterra.worlddriver.bot.pathfinder.CostModifier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Reading the ground the journey's mining rungs work in.
 *
 * <p>Split out of the rung file, which is at its line budget, and split along a seam that is real
 * rather than convenient: <b>nothing here touches the body</b>. Every method is a question about the
 * world, answered from a {@link ServerLevel} and a coordinate, and that is what makes them safe to
 * call from RECON — before there is a body at all — as well as from the rung that will act on the
 * answer. The one exception, {@link #shallowWaterNear}, takes a rig only to learn where to centre.
 *
 * <p><b>Reading terrain is not staging.</b> The ladder's premise is a caller who knows this seed, and
 * these are how it knows: no method here changes a block, spawns anything, or moves the body.
 * {@code JourneyLedger.stagingCalls()} stays empty across every one of them.
 */
public final class JourneyTerrain {

    private JourneyTerrain() {}

    /** Lava SOURCE cells within {@code r} of {@code around}, ordered by how far the BODY has to walk
     *  to each. Two centres because they are two different questions: where the pool is, and which
     *  of its cells is cheapest to spend next. */
    public static List<BlockPos> lavaSourcesNear(ServerLevel level, BlockPos around, int r,
                                                  BlockPos walkFrom) {
        List<BlockPos> out = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++)
            for (int dy = -6; dy <= 4; dy++)
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos c = around.offset(dx, dy, dz);
                    if (level.getFluidState(c).isSource() && level.getBlockState(c).is(Blocks.LAVA))
                        out.add(c.immutable());
                }
        out.sort(java.util.Comparator.comparingDouble(a -> a.distSqr(walkFrom)));
        return out;
    }

    /**
     * How many lava SOURCE cells sit in a {@code (2r+1) × (2h+1) × (2r+1)} box around
     * {@code centre} — the count that decides whether a pool is a lake or a pocket.
     *
     * <p>Not {@link #lavaSourcesNear}, whose vertical band is fixed at {@code [-6,4]} and whose
     * order is a walking cost: this one takes its own {@code h} and answers a question about the
     * pool rather than about the next cell to spend. Not {@link #plainSource} either — that adds a
     * {@code FluidTags.LAVA} check and is therefore strictly stricter, so swapping it in here would
     * quietly move a threshold that two callers already agree on.
     *
     * <p>The survey and the rehearsal each had a copy of this loop under a different name
     * ({@code countSourcesAround}, {@code lavaSourcesAround}), both called with the same
     * {@code (8, 4)}. One quantity computed twice is how the two get to disagree about what counts
     * as a lake while both keep reporting a number.
     */
    public static int countLavaSources(ServerLevel level, BlockPos centre, int r, int h) {
        int n = 0;
        for (int dx = -r; dx <= r; dx++)
            for (int dy = -h; dy <= h; dy++)
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos c = centre.offset(dx, dy, dz);
                    if (level.getFluidState(c).isSource() && level.getBlockState(c).is(Blocks.LAVA)) n++;
                }
        return n;
    }

    /**
     * Where the lava is down the WHOLE column, in 8-block bands.
     *
     * <p>Every probe above scans {@code dy ∈ [-6,4]} — eleven blocks around the body. So their
     * answer, however wide the radius, is <b>"none at this depth"</b> and never "none here": a
     * lava sea eighty blocks lower reddens exactly the same row, and reading it as absence sends
     * the next round at the radius, which is not the dimension that is wrong. Widening x/z was
     * already tried here (16 → 40 → 48) and bought nothing, which is only informative once this
     * report says whether the column is empty too.
     *
     * <p>Runs only on the failure path, so its cost buys the next run's landmark.
     */
    public static String lavaColumnReport(ServerLevel level, BlockPos here, int r) {
        java.util.TreeMap<Integer, Integer> bands = new java.util.TreeMap<>();
        int floor = level.getMinBuildHeight() + 1;
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                for (int y = floor; y < 64; y++) {
                    BlockPos c = new BlockPos(here.getX() + dx, y, here.getZ() + dz);
                    if (level.getFluidState(c).isSource() && level.getBlockState(c).is(Blocks.LAVA))
                        bands.merge(Math.floorDiv(y, 8) * 8, 1, Integer::sum);
                }
        if (bands.isEmpty())
            return "no source block in the full column within radius " + r + " (y=" + floor
                    + "..63); there really is no lava lake here";
        StringBuilder sb = new StringBuilder("full column within radius " + r
                + " in 8-block bands (band=source count):");
        bands.descendingMap().forEach((y0, n) -> sb.append(" y").append(y0).append("~")
                .append(y0 + 7).append("=").append(n));
        return sb.toString();
    }

    /**
     * A column a shaft may sink beside the lava: solid at the fluid's own level, and lava-free from
     * there to the surface.
     *
     * <p>Checked over the whole 3×3 around the candidate, not the one column, because the walk that
     * puts the body over it lands within about a block and a shaft that starts one cell off is a
     * shaft nobody checked. Rings outward from two cells: one cell would put the tunnel's first
     * break directly into the pool's wall.
     *
     * <p>The ring goes out to eight rather than four because DRYNESS is the strong filter here, and
     * it was the constraint the first version forgot. It picked a column two cells from the pool,
     * stepped correctly off the lava's own column onto it, scaled its attempt cap to the 34-block
     * descent — and then floated: the column was under a swamp pond, so the support under the body
     * was water 122 times running and the rung reported "the block broke but the body did not sink"
     * about a body that was swimming. Every ore landmark on this ladder is surveyed for exactly this
     * and this column is chosen at runtime, so it has to make the same check itself.
     */
    public static BlockPos pickDigColumn(ServerLevel level, BlockPos lava, int surfaceY,
                                          Map<String, Integer> rejected) {
        return pickDigColumn(level, lava, surfaceY, rejected, List.of());
    }

    /**
     * The same, refusing columns a descent has already tried and found wet part way down.
     *
     * <p>{@link #whyNotDiggable} only asks about the two ENDS of a column — deliberately, and the
     * measurement is in its own note: requiring the whole thing dry rejected 280 candidates out of
     * 280 around this seed's pool, because that is what an aquifer is. So the middle is the
     * descent's problem, and when the descent finds water there the only thing that knows the column
     * is bad is the descent. Without this list the next pick rings outward from the same centre and
     * returns the same column, and "try another column" becomes a loop rather than a remedy.
     */
    public static BlockPos pickDigColumn(ServerLevel level, BlockPos lava, int surfaceY,
                                          Map<String, Integer> rejected, List<BlockPos> banned) {
        return pickDigColumn(level, lava, surfaceY, rejected, banned, null);
    }

    /**
     * The same, preferring columns that lie on one SIDE of the lava — the only lever that actually
     * turns the mould.
     *
     * <p><b>Written because the lever that claimed to do this did not.</b> A rehearsal could already
     * stage which side of the lake the BODY starts on ({@code -PforgeAway}), and its evidence row
     * promised "the staircase and the mould will both face this way". It cannot keep that promise,
     * and three directed rehearsals on 2026-08-16 proved it in one line each: {@code east},
     * {@code south} and {@code west} produced three different {@code rehearsal.stand} values and
     * then the SAME {@code shaft.standingOn = -9,21} and the same {@code forge.face}, facing south.
     * The reason is
     * structural rather than incidental — rung 12 opens with {@code walkToColumn(lava)}, which throws
     * the staged stand away, and this method then rings outward from the pool in a fixed scan order
     * and returns the first qualifying column, which for a given pool is the same column every run.
     * The real ladder's mould varies only because rung 11 spends a pool and rung 12 therefore gets a
     * different one.
     *
     * <p>So the side has to be applied HERE. A first pass keeps only columns whose
     * {@link JourneyPortalRung#awayFrom} matches, and a null preference — every ladder climb, always
     * — skips that pass entirely and iterates exactly as it did before. When no column on the
     * requested side qualifies the search falls through to the unrestricted one rather than failing:
     * a seed that cannot offer an orientation should still rehearse, and {@code rejected} carries the
     * count that says which happened.
     */
    public static BlockPos pickDigColumn(ServerLevel level, BlockPos lava, int surfaceY,
                                          Map<String, Integer> rejected, List<BlockPos> banned,
                                          Direction prefer) {
        if (prefer != null) {
            BlockPos onTheSide = scanForDigColumn(level, lava, surfaceY, rejected, banned, prefer);
            if (onTheSide != null) return onTheSide;
            rejected.merge("no qualifying column on this side (" + prefer + "), searching all around",
                    1, Integer::sum);
        }
        return scanForDigColumn(level, lava, surfaceY, rejected, banned, null);
    }

    private static BlockPos scanForDigColumn(ServerLevel level, BlockPos lava, int surfaceY,
                                             Map<String, Integer> rejected, List<BlockPos> banned,
                                             Direction prefer) {
        for (int r = 2; r <= 8; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;   // the ring, not the disc
                    BlockPos c = new BlockPos(lava.getX() + dx, lava.getY(), lava.getZ() + dz);
                    // THE RUNG'S OWN RULE, not a second copy of it: the staircase direction is
                    // awayFrom(lava, start), so filtering candidates through that very call is what
                    // makes "standing on the south side" and "the mould faces south" the same claim
                    // rather than two hopes.
                    if (prefer != null && JourneyPortalRung.awayFrom(lava, c) != prefer) continue;
                    if (sameColumn(banned, c)) {
                        rejected.merge("water found mid-column during the descent, column already replaced",
                                1, Integer::sum);
                        continue;
                    }
                    String why = whyNotDiggable(level, c, surfaceY);
                    if (why == null) return c;
                    rejected.merge(why, 1, Integer::sum);
                }
            }
        }
        return null;
    }

    /** Whether {@code c}'s x/z appears in {@code columns}. Y is ignored on purpose: a column is an
     *  x/z, and the cells a descent reports are at whatever depth it drowned. */
    public static boolean sameColumn(List<BlockPos> columns, BlockPos c) {
        for (BlockPos b : columns) {
            if (b.getX() == c.getX() && b.getZ() == c.getZ()) return true;
        }
        return false;
    }

    /** Whether the column the body is actually standing on will do. */
    public static boolean columnIsSafeToSink(ServerLevel level, BlockPos floor, int surfaceY) {
        return whyNotDiggable(level, floor, surfaceY) == null;
    }

    /**
     * Why this column will not do as a shaft, or null when it will.
     *
     * <p>A reason rather than a boolean, because "nothing within eight blocks qualified" is not a
     * finding — it is a shrug. Which rule did the rejecting is the finding, and it is the difference
     * between relaxing the right constraint and guessing at the next run's cost.
     *
     * <p>The rules are deliberately asymmetric. <b>The shaft is ONE column</b>, so that is the one
     * that has to be dry all the way down and has to have something to land on at the fluid's level.
     * Asking the whole 3×3 for a floor at exactly {@code lava.y} was the first draft and it is not a
     * question a cave answers: a lava pool sits IN a cave, so the cells around it at its own level
     * are pool, cave floor, and cave air in whatever proportion the terrain chose. What the eight
     * neighbours must be is <b>dry at the top</b> — that is the failure this exists to prevent, a
     * body starting the shaft afloat in a pond — and <b>free of lava</b>, because the walk that puts
     * the body over a column is only good to about a block and a shaft one cell off that breaks into
     * the pool is the one outcome the whole rung is arranged to avoid.
     */
    public static String whyNotDiggable(ServerLevel level, BlockPos floor, int surfaceY) {
        // Dry where it MATTERS, not everywhere. Asking for a fluid-free column all the way down was
        // the previous rule and recon measured what it costs: 280 candidates around this seed's pool,
        // 280 rejected, every one of them for "there is fluid somewhere in these thirty-six blocks".
        // Of course there is — that is what an aquifer is. A rule nothing can satisfy is not a strict
        // rule, it is a broken one, and it took a one-minute recon probe to say so instead of a
        // twenty-five-minute run per guess.
        //
        // The two places dryness actually decides the outcome are the ENDS. At the top the body has
        // to stand on ground to start the shaft, or it floats and never falls in. At the bottom it
        // has to land on ground beside the pool rather than in water. What happens in between is the
        // descent's problem, and it now has a guard that reports floating in one line.
        if (!dryBand(level, floor.getX(), floor.getZ(), daylightAt(level, floor), DRY_HEADROOM))
            return "fluid below the shaft mouth";
        if (!dryBand(level, floor.getX(), floor.getZ(), floor.getY() + DRY_LANDING, DRY_LANDING))
            return "fluid above the landing cell";
        if (!level.getBlockState(floor).blocksMotion()) return "no floor to land on at the lava level";
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos c = floor.offset(dx, 0, dz);
                if (!dryUnderfoot(level, c.getX(), c.getZ())) return "a neighbouring column's surface is water";
                for (int y = c.getY() + 1; y <= surfaceY + 1; y++) {
                    if (level.getBlockState(new BlockPos(c.getX(), y, c.getZ())).getBlock() == Blocks.LAVA) {
                        return "a neighbouring column still contains lava";
                    }
                }
            }
        }
        return null;
    }

    /** How many blocks below the mouth of a shaft must be fluid-free — enough that the first courses
     *  are cut in rock and the body is on ground, not afloat, while it learns to fall. */
    public static final int DRY_HEADROOM = 12;

    /** How many blocks above the landing must be fluid-free, so the shaft ends on ground beside the
     *  pool rather than in the water that was sitting on top of it. */
    public static final int DRY_LANDING = 4;

    /** No fluid in {@code depth} cells of one column, counting down from {@code yTop}. */
    public static boolean dryBand(ServerLevel level, int x, int z, int yTop, int depth) {
        for (int y = yTop; y > yTop - depth; y--) {
            if (!level.getBlockState(new BlockPos(x, y, z)).getFluidState().isEmpty()) return false;
        }
        return true;
    }

    /**
     * The height of open sky over a column — where "climb back out" actually means.
     *
     * <p><b>The one door.</b> This expression was written out by hand three times inside this file
     * alone — here, in {@link #dryUnderfoot}, and in {@code daylightY} — with the same heightmap
     * type each time, so today the three agree. That is the state a divergence starts from: the
     * javadoc below is attached to only one of the three, and a reader who lands on either of the
     * others gets a bare {@code getHeightmapPos} call with no reason beside it. They now go through
     * this method, and {@code daylightY} is the {@link JourneyRig}-shaped wrapper around it.
     *
     * <p>Every mining rung records a {@code surfaceY} on arrival and climbs back to it afterwards,
     * and until this existed that number was {@code player().blockPosition().getY()}: <b>wherever
     * the body happened to be standing</b>. That is the surface only if the previous rung left it
     * on the surface, and mining rungs do not.
     *
     * <p>Measured, and it is the whole of a rung failing two rungs later. The portal kit walked to
     * its gravel column from the bottom of the iron rung's shaft, read {@code surfaceY = 43}, dug,
     * and then climbed <i>perfectly</i> back to 47 — {@code exit.rise = 4 block(s)},
     * {@code exit.toY = 47}, goal met. The real surface was around 60. The obsidian rung then began
     * fourteen blocks underground, could not route 84 blocks to the lava, and reported that as a
     * walking failure. A rung that climbs out to a number nobody checked has not climbed out.
     *
     * <p>The heightmap answers the question that was actually being asked, and it does not care
     * where the body is.
     */
    public static int daylightAt(ServerLevel level, BlockPos at) {
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at).getY();
    }

    /** Standing room, not swimming room: no fluid in the few cells a body occupies at this column's
     *  own surface. This is the cell the descent's first course is taken from, and a body floating
     *  in a swamp pond never falls into the hole it just dug. */
    public static boolean dryUnderfoot(ServerLevel level, int x, int z) {
        int surface = daylightAt(level, new BlockPos(x, 0, z));
        for (int y = surface + 2; y >= surface - 2; y--) {
            if (!level.getBlockState(new BlockPos(x, y, z)).getFluidState().isEmpty()) return false;
        }
        return true;
    }

    /** How deep a hole beside a stand still counts as a way into the lake. Eight: the fall this was
     *  written from went from the walking row {@code y=66} to the basin floor at {@code y=59}. The
     *  scan stops at the first solid cell, so on closed ground it costs one block read per
     *  neighbour. */
    public static final int LIP_DEPTH = 8;

    /** The eight cells a body can drift into from a stand — four cardinals and four diagonals, the
     *  same set {@code WalkerGeometry.EDGE_NEIGHBOURS} pins and for the same reason: the drift that
     *  takes a body off a lip is as often sideways along it as forward over it. */
    private static final int[][] EDGE_NEIGHBOURS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /**
     * Is the lake one sideways step from this cell — the reading that makes a stand a trap?
     *
     * <p>The hazard half of {@code WalkerGeometry.lethalDropAdjacent}, written out here rather than
     * called: that class is package-private to {@code bot.movement} and opening it up to a scene is
     * an engine change this finding does not need. It is also deliberately narrower — the rung's
     * hazard is <b>the lake</b> and not any deep hole, because a dry shaft beside a stand costs a
     * climb and this one costs the run.
     *
     * <p>A neighbour counts when its own foot cell AND the cell below it are both open — a floor
     * there is a flat walk or a one-block step down — and the column then falls to lava within
     * {@link #LIP_DEPTH}. Water is a splash, not a drop, exactly as the walker treats it.
     *
     * <p>It is asked of a cell that is EMPTY, so it is a question about the cell rather than about
     * the body in it, which is what makes it usable before any body is standing there. The reading
     * it is a proxy for is {@code soleOnSolid}, and that one needs a body: a dead stop printed
     * {@code Block{minecraft:air}} below the feet at {@code -14,66,21}, which is this predicate's answer taken
     * the expensive way, eleven trips too late.
     *
     * <p>It lives here rather than beside the first caller because it turned out to answer for two:
     * {@code JourneyFill.pinTheFillStation} picks a cell the body visits ten times, and
     * {@link #bankStandNear} picks the cell rung 12's opening walk ENDS on, and both were choosing
     * cells on the same rim.
     */
    public static BlockPos onThePoolsLip(ServerLevel level, BlockPos foot) {
        for (int[] o : EDGE_NEIGHBOURS) {
            BlockPos n = foot.offset(o[0], 0, o[1]);
            if (openToFallThrough(level, n) == null
                    || openToFallThrough(level, n.below()) == null) continue;
            BlockPos c = n.below();
            for (int d = 0; d < LIP_DEPTH; d++) {
                c = c.below();
                if (level.getFluidState(c).is(net.minecraft.tags.FluidTags.LAVA)) return c;
                if (openToFallThrough(level, c) == null) break;
            }
        }
        return null;
    }

    /** The cell itself when nothing in it would hold a body up; null when something would. Water
     *  holds one up for this purpose — a body that lands in it has not fallen into the lake. */
    private static BlockPos openToFallThrough(ServerLevel level, BlockPos c) {
        if (level.getBlockState(c).blocksMotion()) return null;
        return level.getFluidState(c).is(net.minecraft.tags.FluidTags.WATER) ? null : c;
    }

    /**
     * Every cell around the pool that {@link #onThePoolsLip} refuses — the rim, as a set.
     *
     * <p>Because the destination was never the thing that killed this approach: the ROUTE is.
     * Measured on the {@code east} rehearsal of 2026-08-17, with the walk already re-aimed at a
     * chosen bank cell one block from the pool ({@code lava.bank = -8, 66, 19}) rather than at the
     * lake's own column, the walker put the body on the rim anyway and all three legs died there:
     *
     * <pre>
     * [walker] footing guard: sole 0.0362 &lt; 0.18 at -14,66,21 beside a lethal drop → sneak-pin
     * [walker] footing guard: sole 0.0025 &lt; 0.18 at -13,66,20 beside a lethal drop → sneak-pin
     * [walker] footing guard: sole 0.0000 &lt; 0.18 at -13,66,21 beside a lethal drop → sneak-pin
     * lava.goto.1/2/3 = end=failed:no progress for 1200 ticks     FAIL 3826t
     * </pre>
     *
     * <p>Those three cells are consecutive steps of ONE planned route, and the last of them holds a
     * body that can then never move: vanilla's sneak refuses every horizontal move that would take a
     * body off its support, and at {@code sole = 0.0000} there is no support to keep. So the reading
     * that picks stands has to reach the cells BETWEEN them too, and the pathfinder is the only
     * thing that chooses those.
     *
     * <p>Handed to a search as a {@code CostModifier} rather than a {@code Constraint}: a tax leaves
     * the route available when it is the only one, which a prune does not, and "a rule nothing can
     * satisfy is not a strict rule, it is a broken one" is a lesson this file already carries once
     * (see {@link #whyNotDiggable}). Precomputed as a set on the server thread, so the per-edge cost
     * is one hash lookup — the predicate itself reads up to eighty cells and the search expands a
     * hundred thousand nodes.
     */
    public static java.util.Set<BlockPos> poolsLipCells(ServerLevel level, BlockPos lava,
                                                        int radius, int rise) {
        java.util.Set<BlockPos> out = new java.util.HashSet<>();
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++)
                for (int y = lava.getY() + 1; y <= lava.getY() + rise; y++) {
                    BlockPos c = new BlockPos(lava.getX() + dx, y, lava.getZ() + dz);
                    // Only cells a body could be standing in: a solid cell is not a step and the
                    // tax on it would only make the set bigger for nothing.
                    if (level.getBlockState(c).blocksMotion()) continue;
                    if (onThePoolsLip(level, c) != null) out.add(c.immutable());
                }
        return java.util.Set.copyOf(out);
    }

    /** How much a step onto the crater's rim costs the search, in the pathfinder's own units. Three
     *  hundred: a plain walk edge is 10, so this is thirty blocks of detour per rim cell, and the
     *  route that goes round is cheaper up to that; finite, so a pool whose every approach is rim
     *  still has a route — see the {@code CostModifier}-not-{@code Constraint} paragraph on
     *  {@link #poolsLipCells}. */
    public static final double LIP_TAX = 300;

    /** How far around the pool the rim is priced, and how far up. Twelve out covers the whole
     *  crater at the sizes this rung meets; nine up covers the walking rows above a basin floor. */
    public static final int LIP_TAX_RADIUS = 12;
    public static final int LIP_TAX_RISE = 9;

    /**
     * The rim, priced for one search — <b>recomputed, never cached</b>.
     *
     * <p>Every caller on the lava round trip needs this and until 2026-08-24 exactly one of the
     * three had it. The approach ({@code JourneyPortalRung.descendToTheForge}) built the set inline;
     * the walk to the fill station and every leg of the stairwell flight passed {@code List.of()},
     * so the two legs that run <b>after</b> the lake has been opened up were the two that priced it
     * at nothing. Ladder j39 died on the second of those: {@code cast1.return} left the fill station
     * at {@code -8,64,14} for the stairwell mouth five blocks away at {@code -8,66,19}, went north
     * along the crater instead, and took {@code lava −4.0×3; onFire −1.0×2} at {@code -8,66,10}.
     * That the return crosses the lake's own rim was already written down — in the javadoc of the
     * recovery that fires <i>after</i> it goes wrong.
     *
     * <p><b>Recomputed</b> because the rim moves while the rung works, always outward: each fill
     * takes a source out and leaves an air cell, and each cleared aim line breaks a block that was
     * holding the lake in ({@code lava1.clearedLine.2} reported the stone at -9, 63, 17 standing
     * between the eye and -9, 63, 18 and broke it, one cell from a confirmed source). A set built at
     * the approach and reused would price
     * the lake the rung <i>found</i>, not the one it <i>made</i>. The cost is one pass over
     * {@code (2r+1)² × rise} cells per search, milliseconds on the server thread, against a leg that
     * costs the run.
     *
     * <p>Returns an empty list — not a modifier that always answers zero — when the pool has no rim,
     * so a search with nothing to avoid is handed nothing to ask.
     */
    public static RimTax avoidTheRim(ServerLevel level, BlockPos lava) {
        if (lava == null) return new RimTax(List.of(), 0, "no lake coordinate, so this walk is not taxed");
        java.util.Set<BlockPos> rim = poolsLipCells(level, lava, LIP_TAX_RADIUS, LIP_TAX_RISE);
        List<CostModifier> bias = rim.isEmpty() ? List.of()
                : List.of((from, to, edge, goal, world) -> rim.contains(to) ? LIP_TAX : 0.0);
        return new RimTax(bias, rim.size(), rim.size() + " rim cells, each step onto one costs an extra "
                + (int) LIP_TAX + " (a plain walk step is 10, so a detour of up to " + (int) (LIP_TAX / 10)
                + " blocks is cheaper than stepping on it); radius "
                + LIP_TAX_RADIUS + ", y=" + (lava.getY() + 1) + ".." + (lava.getY() + LIP_TAX_RISE));
    }

    /**
     * One rim scan, handed to the search and to the evidence together.
     *
     * <p>A record rather than two calls because the scan is the expensive half and both halves want
     * the same one — and because {@code cells} is a measurement worth keeping per leg, not just per
     * rung: the rim GROWS as the rung works the lake, and a flight-by-flight count is the only thing
     * that shows it. A row that reads 613 → 641 → 688 across one rung's returns is the recompute
     * earning its keep; one that never moves says the snapshot would have done.
     */
    public record RimTax(List<CostModifier> bias, int cells, String story) {}

    /**
     * The nearest column within {@code r} whose surface is dry, or null.
     *
     * <p>Hoisted out of {@code JourneyCast} on 2026-08-24 when a second caller appeared and the two
     * occasions turned out to be one question. The first was a body that surfaced from a lava dive
     * still swimming; the second was the furnace rung failing in three ticks on
     * the error "a crafting table is needed (there is one in the inventory, but no free spot beside
     * the feet to place it)" after the bed rung's walk home stopped three blocks short at
     * {@code 67,63,60} standing on tall_seagrass. Different rungs, different verbs, same answer:
     * <b>go to a column that is dry and stand on it.</b>
     *
     * <p>A dry column is a sufficient condition for "there is somewhere to put a station" rather
     * than a proxy for it: {@link #dryUnderfoot} refuses any column with fluid anywhere in the five
     * rows around its surface, so what is left is solid ground with open air over it.
     *
     * <p>Ranked by squared horizontal distance from the body, and the y it returns is the surface's
     * own — a caller that walks to the column arrives standing on it.
     */
    public static BlockPos nearestDryColumn(ServerLevel level, BlockPos from, int r) {
        BlockPos best = null;
        long bestD2 = Long.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                long d2 = (long) dx * dx + (long) dz * dz;
                if (d2 >= bestD2) continue;
                int x = from.getX() + dx, z = from.getZ() + dz;
                if (!dryUnderfoot(level, x, z)) continue;
                bestD2 = d2;
                best = new BlockPos(x, daylightAt(level, new BlockPos(x, 0, z)), z);
            }
        }
        return best;
    }

    /** How far from the pool an approach may end and still count as having reached it. Eight, the
     *  same figure {@link #pickDigColumn} rings out to and {@code JourneyFill.STATION_REACH} uses:
     *  everything the rung does next is sized off this distance, so a bank further out than the
     *  shaft column could be is a bank the rung would have to walk back in from anyway. */
    public static final int BANK_REACH = 8;

    /** How far above the fluid's own row a bank stand may sit. Eight. The crater's rim on this seed
     *  is three above the lake, so this is slack — what it is really for is the other end of the
     *  heightmap: {@code MOTION_BLOCKING_NO_LEAVES} stops on a LOG, so without a ceiling the
     *  nearest "standable, dry, off the lip" cell to a pool in a forest is the top of a tree. */
    private static final int BANK_RISE = 8;

    /**
     * Where an approach to the pool should END — a cell beside it a body can stand on.
     *
     * <p>Rung 12 opens by walking to {@code XZ(lava.x, lava.z)}: the lake's own centre column, a
     * destination no body can ever occupy. Every archived rehearsal leg says so in one row —
     * {@code lava.gotoEnd.1 = end=failed:…} in <b>six runs of six</b>, not one arrival among them —
     * because the walker plans INTO the crater and then either is pinned on its rim
     * ({@code footing guard: sole 0.0000 … beside a lethal drop}, ending
     * {@code failed:no progress for 1200 ticks} at {@code -13,66,21}) or gets all the way in
     * ({@code failed:no path (expanded=1)} from {@code -12,63,20} at the lava's own row, which is
     * the signature of a start node the pathfinder judges lethal). {@code ARRIVED_WITHIN} then reads
     * the wreck as an arrival — the leg is inside five blocks of a goal it never reached — and the
     * rung carries on from wherever the body came to rest. Both of that arm's failure shapes are
     * downstream of this one line: a body on the lip cannot walk at all, and a body in the pool
     * cannot even be planned for.
     *
     * <p>So the destination becomes a cell that was CHOSEN rather than one that was survived: the
     * column's own daylight cell — one candidate per column, which is what "walk overland to the
     * bank" means — standable, dry, no higher than {@link #BANK_RISE} over the fluid, and with
     * {@link #onThePoolsLip} answering null at the foot AND at the cell above it, the same pair the
     * loading station asks and for the same reason (the body arrives in the upper cell first).
     *
     * <p>Ranked by how close it is to the pool, because everything the rung does next is sized off
     * that distance, and tie-broken by how far the BODY has to walk — which on a ring around a lake
     * is what keeps the answer on the side the body is already standing on.
     *
     * @param refuseTheLip false runs the same scan without the lip rule, so a bank that has no clear
     *        cell at all is no worse off than it is today — a preference, not a rule, the two-pass
     *        shape {@code standToFill} and {@code pinTheFillStation} both already use.
     */
    public static BlockPos bankStandNear(ServerLevel level, BlockPos lava, BlockPos from,
                                         Map<String, Integer> why, boolean refuseTheLip) {
        BlockPos best = null;
        long bestToPool = Long.MAX_VALUE;
        long bestToBody = Long.MAX_VALUE;
        for (int dx = -BANK_REACH; dx <= BANK_REACH; dx++) {
            for (int dz = -BANK_REACH; dz <= BANK_REACH; dz++) {
                int x = lava.getX() + dx;
                int z = lava.getZ() + dz;
                BlockPos foot = new BlockPos(x, daylightAt(level, new BlockPos(x, 0, z)), z);
                if (foot.getY() <= lava.getY() || foot.getY() > lava.getY() + BANK_RISE) {
                    why.merge("this column's surface is not within " + BANK_RISE + " blocks above the lava level",
                            1, Integer::sum);
                    continue;
                }
                if (!level.getBlockState(foot.below()).blocksMotion()) {
                    why.merge("not solid underfoot", 1, Integer::sum);
                    continue;
                }
                if (!level.getFluidState(foot).isEmpty()
                        || !level.getFluidState(foot.above()).isEmpty()) {
                    why.merge("standing in fluid", 1, Integer::sum);
                    continue;
                }
                if (!level.getBlockState(foot).getCollisionShape(level, foot).isEmpty()
                        || !level.getBlockState(foot.above())
                                .getCollisionShape(level, foot.above()).isEmpty()) {
                    why.merge("foot or head cell occupied", 1, Integer::sum);
                    continue;
                }
                if (refuseTheLip && (onThePoolsLip(level, foot) != null
                        || onThePoolsLip(level, foot.above()) != null)) {
                    why.merge("an opening down to the lava is right beside the feet", 1, Integer::sum);
                    continue;
                }
                // HORIZONTAL, both of them. A bank cell three rows over a lake is not further from
                // it than one two rows over, and the rung's next question is a COLUMN — see the
                // archive's "a radius is not a distance" for the search this repo has already had
                // truncated by mixing the vertical in.
                long toPool = (long) dx * dx + (long) dz * dz;
                long ddx = x - from.getX();
                long ddz = z - from.getZ();
                long toBody = ddx * ddx + ddz * ddz;
                if (toPool < bestToPool || (toPool == bestToPool && toBody < bestToBody)) {
                    best = foot;
                    bestToPool = toPool;
                    bestToBody = toBody;
                }
            }
        }
        return best;
    }

    /** The nearest lava SOURCE — flowing lava reads as the same block and does not fill a bucket. */
    public static BlockPos nearestLavaSource(ServerLevel level, BlockPos from, int radius) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    BlockPos c = from.offset(dx, dy, dz);
                    if (level.getBlockState(c).getBlock() != Blocks.LAVA) continue;
                    if (!level.getFluidState(c).isSource()) continue;
                    double d = from.distSqr(c);
                    if (d < bestD) { bestD = d; best = c; }
                }
            }
        }
        return best;
    }

    /**
     * Is {@code c} a source of the fluid asked for, in a block a BUCKET can actually work with?
     *
     * <p><b>Three tests, and the third one is the one that keeps getting dropped.</b> A cell can
     * answer {@code isSource()} and carry the right fluid tag and still refuse the bucket, because
     * {@code BucketItem.use} does not ask about the fluid at all — it asks whether the BLOCK is a
     * {@code BucketPickup}. Seagrass and kelp are not, so filling from them returns {@code FAIL};
     * they also have a {@code Block.OUTLINE} a ray stops on, which is what lets them survive every
     * line-of-sight test written to catch a blocked aim. This cost two runs at one coordinate, on
     * the pouring side, before {@link #shallowWaterNear} grew the check.
     *
     * <p>It existed there and nowhere else. {@code JourneyFill.standToFill} and
     * {@code JourneyFill.visibleSourceNear} both open with the same three lines and both wrote the
     * block test as {@code if (lava && …)} — correct for lava, absent for water, in two files that
     * had no idea they agreed. One author, so the next copy cannot drift: this is what those three
     * lines meant.
     *
     * <p>Waterlogged stairs and slabs WOULD fill — they are {@code SimpleWaterloggedBlock}, hence
     * {@code BucketPickup} — so "plain" is conservative rather than exact. That is the right
     * direction for a chooser: a refused candidate costs one cell out of a scan, and a wrong one
     * costs the use, which on this ladder is the rung.
     */
    public static boolean plainSource(ServerLevel level, BlockPos c, boolean lava) {
        var fluid = level.getFluidState(c);
        if (!fluid.isSource()) return false;
        if (fluid.is(net.minecraft.tags.FluidTags.LAVA) != lava) return false;
        return level.getBlockState(c).is(lava ? Blocks.LAVA : Blocks.WATER);
    }

    /**
     * The nearest water source standing on solid ground — a shore, not a lake bed.
     *
     * <p>The floor matters more than the water does. A pour aimed into deep water hits the bed
     * several blocks down, so the fluid lands nowhere near the cell the rung named, and the obsidian
     * it casts — if it casts any — is at the bottom of a lake.
     */
    public static BlockPos shallowWaterNear(JourneyRig rig, int radius) {
        ServerLevel level = rig.ctx().level();
        BlockPos from = rig.player().blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    BlockPos c = from.offset(dx, dy, dz);
                    // The seagrass lesson, and the two runs it cost, now live in `plainSource`.
                    if (!plainSource(level, c, false)) continue;
                    if (!level.getBlockState(c.below()).blocksMotion()) continue;
                    double d = from.distSqr(c);
                    if (d < bestD) { bestD = d; best = c; }
                }
            }
        }
        return best;
    }

    /**
     * {@link #daylightAt} for a caller that has a rig rather than a level — read that one for what
     * this number is and for the rung it cost to learn.
     *
     * <p>The level it reads is the SCENE's ({@code rig.ctx().level()}), which is the right one for
     * every current caller and is the thing to look at first if a rung above 19 ever asks — after
     * that one the body is in another dimension and the scene's level answers about overworld
     * terrain at nether coordinates. {@code JourneyShaft.ascendByTowering}'s washed-off branch is
     * the one place that already takes the body's level instead, and says why.
     */
    public static int daylightY(JourneyRig rig, BlockPos at) {
        return daylightAt(rig.ctx().level(), at);
    }
}
