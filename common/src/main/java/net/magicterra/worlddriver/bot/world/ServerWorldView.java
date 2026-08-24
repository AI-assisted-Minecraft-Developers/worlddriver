package net.magicterra.worlddriver.bot.world;

import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.magicterra.worlddriver.bot.util.BotUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Server-side {@link WorldView} over a {@link ServerLevel}. Reads block states
 * directly from the server's chunk storage. Used by {@code mc.observe.scene}
 * (and any other server-only analysis that needs a WorldView without a client).
 *
 * <p>Break-to-move, place, and parkour-place are disabled ({@code +∞} / false):
 * this view is for read-only hazard analysis, not pathfinding.
 */
public final class ServerWorldView implements WorldView {

    private final ServerLevel level;

    public ServerWorldView(ServerLevel level) {
        this.level = level;
    }

    @Override
    public boolean isSolid(BlockPos pos) {
        return level.getBlockState(pos).blocksMotion();
    }

    @Override
    public boolean isKnown(BlockPos pos) {
        return level.isLoaded(pos);
    }

    @Override
    public boolean isPassable(BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return !s.blocksMotion() || s.getFluidState().is(FluidTags.WATER);
    }

    /** The shared policy, not a server opinion about it — {@link BotUtil#isHazardState} carries the
     *  FluidTags-not-Fluids reasoning and the extras list this used to restate line for line. */
    @Override
    public boolean isHazard(BlockPos pos) {
        return BotUtil.isHazardState(level.getBlockState(pos));
    }

    @Override
    public boolean isWater(BlockPos pos) {
        return level.getBlockState(pos).getFluidState().is(FluidTags.WATER);
    }

    @Override
    public boolean isFallingBlock(BlockPos pos) {
        return level.getBlockState(pos).getBlock() instanceof FallingBlock;
    }

    @Override
    public boolean isClimbable(BlockPos pos) {
        return level.getBlockState(pos).is(BlockTags.CLIMBABLE);
    }

    @Override
    public boolean isLeaves(BlockPos pos) {
        return level.getBlockState(pos).is(BlockTags.LEAVES);
    }

    // ---- break / place stubs (not needed for hazard analysis) ----

    @Override
    public double breakCost(BlockPos pos) { return Double.POSITIVE_INFINITY; }

    @Override
    public double escapeBreakCost(BlockPos pos) { return Double.POSITIVE_INFINITY; }

    @Override
    public boolean canPlace() { return false; }

    @Override
    public boolean canParkourPlace() { return false; }

}
