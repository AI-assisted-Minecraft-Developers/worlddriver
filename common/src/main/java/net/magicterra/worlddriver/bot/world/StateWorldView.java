package net.magicterra.worlddriver.bot.world;

import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.game.BotUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A {@link WorldView} over real block states. What a cell is — solid, water, a hazard, a ladder — is
 * one reading of its state for every such view, client or server; a view supplies only where its
 * states come from. Passability, footing and obstruction are {@link CellRules}' answers.
 */
public interface StateWorldView extends WorldView {

    /** The state at {@code p}; air when the level is gone. */
    BlockState blockState(BlockPos p);

    /** The level the states are read from; null when it is gone, and every rule that needs it answers no. */
    Level stateLevel();

    @Override default boolean isSolid(BlockPos p) { return blockState(p).blocksMotion(); }

    /** The shared policy — {@link BotUtil#isHazardState} carries the FluidTags-not-Fluids reasoning. */
    @Override default boolean isHazard(BlockPos p) { return BotUtil.isHazardState(blockState(p)); }

    @Override default boolean isWater(BlockPos p) { return blockState(p).getFluidState().is(FluidTags.WATER); }

    @Override default boolean isFallingBlock(BlockPos p) { return blockState(p).getBlock() instanceof FallingBlock; }

    @Override default boolean isClimbable(BlockPos p) { return blockState(p).is(BlockTags.CLIMBABLE); }

    @Override default boolean isLeaves(BlockPos p) { return blockState(p).is(BlockTags.LEAVES); }

    @Override default boolean isPassable(BlockPos p) {
        Level level = stateLevel();
        return level != null && CellRules.isPassable(level, p, blockState(p));
    }

    @Override default boolean canStandOn(BlockPos p) {
        Level level = stateLevel();
        return level != null && CellRules.canStandOn(level, p, blockState(p));
    }

    /** The lily-pad taxes ({@code padCellTax} / {@code padOverWaterTax}) key on this, so every live view
     *  answers it rather than take {@link WorldView}'s {@code false}. */
    @Override default boolean isBreakableObstruction(BlockPos p) {
        Level level = stateLevel();
        return level != null && CellRules.isBreakableObstruction(level, p, blockState(p));
    }
}
