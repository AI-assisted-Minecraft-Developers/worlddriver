package net.magicterra.worlddriver.bot.process;

import net.magicterra.worlddriver.bot.movement.Avatar;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import static net.magicterra.worlddriver.WorldDriverCommon.LOG;

/**
 * The ONE candidate scan for "hold this item and place it on a reachable cell".
 * placeTable and placeFurnace each carried their own copy of this loop and the
 * copies diverged: gap#61 fixed only the table's (hole-rim dy=+1), gap#62 then
 * found the furnace still on the pre-evolution version — 4 cardinals only, no dy
 * layers, and the isFaceSturdy gate that wrongly rejects leaf/dirt-path ground.
 * Divergent copies is how the #42 family happens; both callers now delegate here.
 */
final class PlaceNearby {
    private PlaceNearby() {}

    /**
     * Hold {@code item} and try to place it on a neighbouring cell. Candidate
     * columns: 4 cardinals + 4 diagonals around the feet, tried at foot level,
     * one below, and one ABOVE. dy=+1 is the hole-rim case (gap#61): standing in
     * a 1-deep depression — e.g. the very hole duskSecure digs each dusk — every
     * foot-level neighbour is solid wall and the only natural spot is on top of
     * the rim. Last so flat ground keeps preferring same-level spots. A full
     * block can sit on the top face of ANY non-air, non-replaceable support —
     * including leaves, which fail isFaceSturdy yet still accept a block on top.
     *
     * @return the cell now holding {@code expected}, or null (reason logged).
     */
    static BlockPos place(Avatar a, Player p, Level lvl, Item item, Block expected, String logTag) {
        if (!a.holdItem(item)) {
            LOG.info("[{}] placeNearby: holdItem({}) FAILED at foot={}", logTag, item, p.blockPosition());
            return null;
        }
        BlockPos foot = p.blockPosition();
        int[][] off = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}, {1, -1}, {1, 1}, {-1, -1}, {-1, 1}};
        int[] dyOrder = {0, -1, 1};
        for (int dy : dyOrder) {
            for (int[] o : off) {
                BlockPos cell = foot.offset(o[0], dy, o[1]);
                BlockPos below = cell.below();
                BlockState cs = lvl.getBlockState(cell);
                BlockState bs = lvl.getBlockState(below);
                if (!cs.canBeReplaced()) continue;
                if (bs.isAir() || bs.canBeReplaced()) continue;
                a.aimAtBlock(cell);
                // Click the support's top face → block lands in `cell`.
                a.useBlock(below, Direction.UP);
                if (lvl.getBlockState(cell).is(expected)) return cell;
                LOG.info("[{}] placeNearby: click failed cell={} ({}) below={} ({})",
                        logTag, cell, cs, below, bs);
            }
        }
        return null;
    }
}
