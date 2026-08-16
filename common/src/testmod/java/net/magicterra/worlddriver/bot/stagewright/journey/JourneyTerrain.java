package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

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
            return "半径 " + r + " 的整列（y=" + floor + "..63）一格源块都没有 —— 这里确实没有岩浆湖";
        StringBuilder sb = new StringBuilder("半径 " + r + " 整列按 8 格分层（层=源块数）：");
        bands.descendingMap().forEach((y0, n) -> sb.append(" y").append(y0).append("~")
                .append(y0 + 7).append("=").append(n));
        return sb.toString();
    }

    /** The interior (or, for the top pair, the notch above) that the water goes into for this cell. */

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
     * returns the same column, and「换一根」becomes a loop rather than a remedy.
     */
    public static BlockPos pickDigColumn(ServerLevel level, BlockPos lava, int surfaceY,
                                          Map<String, Integer> rejected, List<BlockPos> banned) {
        for (int r = 2; r <= 8; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;   // the ring, not the disc
                    BlockPos c = new BlockPos(lava.getX() + dx, lava.getY(), lava.getZ() + dz);
                    if (sameColumn(banned, c)) {
                        rejected.merge("下挖时发现中段有水，这一柱已换掉", 1, Integer::sum);
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
            return "井口下方有流体";
        if (!dryBand(level, floor.getX(), floor.getZ(), floor.getY() + DRY_LANDING, DRY_LANDING))
            return "落脚处上方有流体";
        if (!level.getBlockState(floor).blocksMotion()) return "岩浆层没有落脚面";
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos c = floor.offset(dx, 0, dz);
                if (!dryUnderfoot(level, c.getX(), c.getZ())) return "邻柱地表是水";
                for (int y = c.getY() + 1; y <= surfaceY + 1; y++) {
                    if (level.getBlockState(new BlockPos(c.getX(), y, c.getZ())).getBlock() == Blocks.LAVA) {
                        return "邻柱里还有岩浆";
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

    public static int daylightAt(ServerLevel level, BlockPos at) {
        return level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types
                .MOTION_BLOCKING_NO_LEAVES, at).getY();
    }
    /** Standing room, not swimming room: no fluid in the few cells a body occupies at this column's
     *  own surface. This is the cell the descent's first course is taken from, and a body floating
     *  in a swamp pond never falls into the hole it just dug. */
    public static boolean dryUnderfoot(ServerLevel level, int x, int z) {
        int surface = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types
                .MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z)).getY();
        for (int y = surface + 2; y >= surface - 2; y--) {
            if (!level.getBlockState(new BlockPos(x, y, z)).getFluidState().isEmpty()) return false;
        }
        return true;
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
                    if (!level.getFluidState(c).isSource()) continue;
                    if (!level.getFluidState(c).is(net.minecraft.tags.FluidTags.WATER)) continue;
                    // PLAIN water, not merely a water source. A waterlogged block — seagrass, kelp —
                    // answers `isSource()` and the WATER tag exactly like open water does, and the
                    // fluid test was the only test here. It picked a seagrass cell twice, at the
                    // same coordinate both times, and the pour landed one cell short: seagrass has
                    // no collision but it does have a Block.OUTLINE shape, and OUTLINE is what the
                    // bucket's own clip stops at. So a cast target must be a cell a ray can enter,
                    // and "a water source is in it" does not say that.
                    if (level.getBlockState(c).getBlock() != Blocks.WATER) continue;
                    if (!level.getBlockState(c.below()).blocksMotion()) continue;
                    double d = from.distSqr(c);
                    if (d < bestD) { bestD = d; best = c; }
                }
            }
        }
        return best;
    }

    /**
     * The height of open sky over a column — where "climb back out" actually means.
     *
     * <p>Every mining rung records a {@code surfaceY} on arrival and climbs back to it afterwards,
     * and until now that number was {@code player().blockPosition().getY()}: <b>wherever the body
     * happened to be standing</b>. That is the surface only if the previous rung left it on the
     * surface, and mining rungs do not.
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
    public static int daylightY(JourneyRig rig, BlockPos at) {
        return rig.ctx().level().getHeightmapPos(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                at).getY();
    }
}
