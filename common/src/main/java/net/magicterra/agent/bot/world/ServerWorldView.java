package net.magicterra.agent.bot.world;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.bot.util.BotUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
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

    @Override
    public boolean isHazard(BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        // FluidTags, not Fluids: the type compare misses FLOWING lava/water
        // (lake edges, falls) — see ClientWorldView.isHazard.
        if (s.getFluidState().is(FluidTags.LAVA)) return true;
        if (s.is(BlockTags.FIRE)) return true;
        if (BotUtil.HAZARD_BLOCKS.contains(s.getBlock())) return true;
        // User-configurable extras (Baritone-style blocksToAvoid). Map is
        // checked last so the built-ins stay short-circuit cheap.
        var extras = BotConfig.extraHazardBlocks;
        if (!extras.isEmpty()) {
            String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
            if (extras.contains(id)) return true;
        }
        return false;
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
