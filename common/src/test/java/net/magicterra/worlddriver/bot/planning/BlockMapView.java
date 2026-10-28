package net.magicterra.worlddriver.bot.planning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.magicterra.worlddriver.bot.world.CellRules;
import net.magicterra.worlddriver.bot.world.MoveSignals;
import net.magicterra.worlddriver.bot.world.StateWorldView;
import net.magicterra.worlddriver.config.WorldConfig;
import net.magicterra.worlddriver.game.observe.BuildBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Real block states in a map, planned over as {@code LevelWorldView.forBody} plans for a server-side
 * player: the same {@link CellRules} answers for passing, standing and obstruction, the same break
 * price from the best tool in the bag, the same placeable count over the whole bag, a player's body
 * and moves. Every cell never set reads as air, which is what the old sky arenas had around them.
 *
 * <p>What it leaves out is what a planner arena never read: entities (the search scope is empty, as
 * it is in an arena with no mobs), loaded chunks (every cell is known), and the dig modifiers of
 * potion effects (the bag is bare-handed apart from what it holds).
 */
final class BlockMapView implements StateWorldView, BlockGetter {

    private final MoveSignals signals = new MoveSignals();
    @Override public MoveSignals signals() { return signals; }

    private final Map<BlockPos, BlockState> cells = new HashMap<>();
    /** What the planning body carries: its tools price breaks, its blocks are what it may place. */
    final List<ItemStack> bag = new ArrayList<>();
    private boolean diveSearch;

    BlockMapView set(BlockPos p, BlockState s) {
        if (s.isAir()) cells.remove(p.immutable());
        else cells.put(p.immutable(), s);
        return this;
    }

    BlockMapView set(int x, int y, int z, Block b) { return set(new BlockPos(x, y, z), b.defaultBlockState()); }

    /** Every cell of the inclusive box. */
    BlockMapView fill(int ax, int ay, int az, int bx, int by, int bz, Block b) {
        for (int x = Math.min(ax, bx); x <= Math.max(ax, bx); x++)
            for (int y = Math.min(ay, by); y <= Math.max(ay, by); y++)
                for (int z = Math.min(az, bz); z <= Math.max(az, bz); z++)
                    set(x, y, z, b);
        return this;
    }

    BlockMapView give(ItemStack stack) { bag.add(stack); return this; }

    // ---- StateWorldView

    @Override public BlockState blockState(BlockPos p) { return getBlockState(p); }

    /** No level: the three rules that need a block getter are answered over this map below. */
    @Override public Level stateLevel() { return null; }

    @Override public boolean isPassable(BlockPos p) { return CellRules.isPassable(this, p, blockState(p)); }

    @Override public boolean canStandOn(BlockPos p) { return CellRules.canStandOn(this, p, blockState(p)); }

    @Override public boolean isBreakableObstruction(BlockPos p) {
        return CellRules.isBreakableObstruction(this, p, blockState(p));
    }

    @Override public double breakCost(BlockPos p) {
        if (!WorldConfig.allowBreak) return Double.POSITIVE_INFINITY;
        return CellRules.breakCost(this, p, blockState(p), bag, CellRules.DigSnapshot.BARE);
    }

    /** Inventory alone, as {@code LevelWorldView.canPlace} answers (its javadoc says why). */
    @Override public boolean canPlace() { return placeableBlockCount() > 0; }

    @Override public int placeableBlockCount() { return BuildBlocks.placeableCount(bag); }

    @Override public boolean canParkourPlace() { return WorldConfig.allowParkourPlace && placeableBlockCount() > 0; }

    @Override public void diveSearch(boolean on) { diveSearch = on; }

    @Override public boolean surfaceWaterNodes() { return WorldConfig.pathfinderSurfaceWaterNodes && !diveSearch; }

    // ---- BlockGetter

    @Override public BlockEntity getBlockEntity(BlockPos p) { return null; }

    @Override public BlockState getBlockState(BlockPos p) { return cells.getOrDefault(p, Blocks.AIR.defaultBlockState()); }

    @Override public FluidState getFluidState(BlockPos p) { return getBlockState(p).getFluidState(); }

    @Override public int getHeight() { return 384; }

    @Override public int getMinBuildHeight() { return -64; }
}
