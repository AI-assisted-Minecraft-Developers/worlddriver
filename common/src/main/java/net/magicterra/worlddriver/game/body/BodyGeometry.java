package net.magicterra.worlddriver.game.body;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Where a driven entity's own bounding box meets the blocks around it: the cells its sole rests
 * on, and the cells that stop it rising.
 *
 * <p>"Is this bot standing on something" must have exactly ONE answer in this repo: an actuator
 * asking a different one from the walker's footing guard is how an executor and a guard come to
 * disagree about the same tick. So the enumeration lives here, below both, and the question of
 * which cell counts as solid is the caller's: the walker passes its {@code WorldView}'s, a body
 * passes the block's own {@code blocksMotion()}.
 */
public final class BodyGeometry {
    private BodyGeometry() {}

    /**
     * How much of the bot's own sole is resting on solid ground, in blocks² out of 0.36.
     *
     * <p><b>The reading every edge guard here was missing.</b> They all ask about a CELL — the foot
     * cell, its neighbours, the cell 0.6 blocks toward the waypoint — and a player is 0.6 wide, so
     * its support is whatever its bounding box happens to overlap. A bot can be grounded with a
     * twentieth of one sole on the corner of a block, and every cell-shaped question about it comes
     * back clean: {@code foot.below()} is solid because the FEET position is still inside that
     * block, the cell ahead is solid because it is real ground, and the neighbours are whatever they
     * are. Measured on the nether crossing, one tick before an eleven-block drop into lava:
     * a solid contact area of {@code 0.0000/0.36} while {@code onGround} said true.
     *
     * <p>The row is {@code floor(minY − 1e-7)} — the row the sole SITS ON, which is the block below
     * for a bot flush on a full cube and the block itself for one on a shorter shape. The cells are
     * enumerated OUTWARD (vanilla's own 1e-7), so a sliver of overlap counts as the sliver it is
     * rather than being rounded away: a bot walking off a ledge really is held by 0.0004 of a block
     * for one tick, and a reading that discarded that would report "no support" about a bot vanilla
     * still calls grounded.
     */
    public static double soleOnSolid(Predicate<BlockPos> solid, LivingEntity p) {
        double[] area = { 0 };
        eachSoleCell(p, (cell, cellArea) -> { if (solid.test(cell)) area[0] += cellArea; });
        return area[0];
    }

    /** How far a pillar-up has to lift the bot before the cell it jumped from can be filled: one
     *  whole block, because that cell is where the block goes and vanilla refuses a placement that
     *  intersects the placer's own box. {@code TowerProcess} gates PLACING on exactly this rise. */
    public static final double PILLAR_RISE = 1.0;

    /**
     * Every cell that would stop this bot from rising {@link #PILLAR_RISE} — empty when nothing does.
     *
     * <p><b>A bot is 0.6 wide, so "the cell above the head" is not one cell.</b> Asking
     * {@code blockPosition().above(2)} answers about the column {@code floor(x), floor(z)} names, and
     * a bot standing anywhere within 0.3 of a cell boundary also has to lift a corner of itself
     * through the NEIGHBOUR's cell. Measured 2026-08-19 by {@code wd.serverTowersUnderTheNeighboursCeiling},
     * whose two runs differ by 0.45 of a block in x and by nothing else: at {@code x=cx+0.05} the box
     * is {@code [cx-0.25, cx+0.35]}, one stone cell over {@code cx-1} clips the jump at {@code +0.20}
     * and the tower gains 0 of 4 courses; at {@code x=cx+0.5} the same stone is beside the box and it
     * gains 4 of 4. {@code blockPosition()} reads {@code cx} in both.
     *
     * <p>That is why this returns CELLS rather than a boolean: the process that finds the obstruction
     * cannot mine it ({@code TowerProcess} places, it never breaks), and the caller that can —
     * {@code JourneyShaft.ascendByTowering} — can only mine a cell somebody names.
     *
     * <p>The decision is vanilla's own ({@link Level#noCollision(net.minecraft.world.entity.Entity, AABB)}
     * over the box moved up by the rise); the scan below only puts names to it. They can disagree in
     * one direction — a hard-collision ENTITY blocks and has no cell — and the caller is
     * expected to say so rather than print an empty list as "nothing in the way".
     *
     * @return the blocking cells, lowest first, or an empty list when the rise is clear
     */
    public static List<BlockPos> pillarRiseBlockers(LivingEntity p) {
        return riseBlockers(p, PILLAR_RISE);
    }

    /**
     * {@link #pillarRiseBlockers} for a rise that is not a pillar's.
     *
     * <p>The scan is the question, the rise is the caller's. A tower asks about {@link #PILLAR_RISE}
     * because that is when the cell it jumped from frees up; drown-escape asks about half a block
     * because that is roughly the face a bot rising at terminal buoyancy is about to meet, and a
     * lid a whole block higher is not in its way YET. Same geometry either way — the reason it is
     * shared is that both were written from the same measurement and one of them was written twice.
     *
     * @param rise how far up to sweep the bot's own box, in blocks
     */
    public static List<BlockPos> riseBlockers(LivingEntity p, double rise) {
        Level lvl = p.level();
        AABB box = p.getBoundingBox().move(0.0, rise, 0.0);
        if (lvl.noCollision(p, box)) return List.of();
        VoxelShape want = Shapes.create(box);
        List<BlockPos> out = new ArrayList<>();
        for (int x = Mth.floor(box.minX + 1.0E-7); x <= Mth.floor(box.maxX - 1.0E-7); x++) {
            for (int z = Mth.floor(box.minZ + 1.0E-7); z <= Mth.floor(box.maxZ - 1.0E-7); z++) {
                for (int y = Mth.floor(box.minY + 1.0E-7); y <= Mth.floor(box.maxY - 1.0E-7); y++) {
                    BlockPos at = new BlockPos(x, y, z);
                    VoxelShape s = lvl.getBlockState(at).getCollisionShape(lvl, at);
                    if (s.isEmpty()) continue;
                    if (Shapes.joinIsNotEmpty(want, s.move(x, y, z), BooleanOp.AND)) out.add(at);
                }
            }
        }
        out.sort(Comparator.comparingInt(BlockPos::getY));
        return out;
    }

    /** The row {@link #soleOnSolid} sums over: {@code floor(minY − 1e-7)}, the row the sole SITS ON
     *  — the block below for a bot flush on a full cube, the block itself for one on a shorter
     *  shape. Named so a caller can print or reason about the row without re-deriving the epsilon. */
    public static int soleRowY(LivingEntity p) {
        return Mth.floor(p.getBoundingBox().minY - 1.0E-7);
    }

    /** What {@link #eachSoleCell} hands back: one cell of the sole row and how much of the bot's
     *  0.6x0.6 footprint lies over it. */
    @FunctionalInterface
    public interface SoleCellVisitor {
        void cell(BlockPos at, double area);
    }

    /**
     * Every cell of {@link #soleRowY}'s row that the bot's own bounding box overlaps, with that
     * cell's share of the footprint.
     *
     * <p><b>The single enumeration.</b> {@link #soleOnSolid}, {@link #soleRow} and
     * {@link BlastFooting#refuseSwing} all run through here, so "which cell is the bot standing on" has exactly one
     * answer in this repo — the rule this class's header states, applied to the one piece of it
     * that used to be copied by eye. Cells are walked OUTWARD with vanilla's own {@code 1e-7}, so a
     * sliver of overlap is reported as the sliver it is (a bot walking off a ledge really is held
     * by 0.0004 of a block for one tick) rather than being rounded away, and the x-outer/z-inner
     * order is what {@link #soleRow}'s printed evidence has always used.
     */
    public static void eachSoleCell(LivingEntity p, SoleCellVisitor v) {
        AABB box = p.getBoundingBox();
        int y = Mth.floor(box.minY - 1.0E-7);
        for (int x = Mth.floor(box.minX - 1.0E-7); x <= Mth.floor(box.maxX + 1.0E-7); x++) {
            for (int z = Mth.floor(box.minZ - 1.0E-7); z <= Mth.floor(box.maxZ + 1.0E-7); z++) {
                v.cell(new BlockPos(x, y, z),
                        Math.max(0, Math.min(box.maxX, x + 1.0) - Math.max(box.minX, x))
                      * Math.max(0, Math.min(box.maxZ, z + 1.0) - Math.max(box.minZ, z)));
            }
        }
    }

    /**
     * The cells {@link #soleOnSolid} sums over, written out — the row index, each column, and what
     * that column contributes.
     *
     * <p>Exists because a bare area is not falsifiable. {@code soleOnSolid=0.0000} is consistent with
     * three different worlds — the bot is not in the cell the block coordinate suggests, the terrain
     * is not what the arena's comments say, or the row being read is not the row a reader assumed —
     * and they want different fixes. The area alone cannot separate them; the row and the per-column
     * contributions can, and they are the very numbers the sum is built from, so no second opinion
     * about "standing" is introduced by asking. Same enumeration, same 1e-7 outward epsilon: change
     * one and this print changes with it.
     */
    public static String soleRow(Predicate<BlockPos> solid, LivingEntity p) {
        StringBuilder sb = new StringBuilder("rowY=").append(soleRowY(p));
        // JourneyCrossingScenes tests the row for "solid" to tell a bot perched on a corner from
        // one in mid-air.
        eachSoleCell(p, (cell, area) -> sb.append(String.format(java.util.Locale.ROOT,
                " [%d,%d]%s %.4f", cell.getX(), cell.getZ(), solid.test(cell) ? "solid" : "open", area)));
        return sb.toString();
    }
}
