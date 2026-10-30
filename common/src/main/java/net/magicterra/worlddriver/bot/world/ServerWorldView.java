package net.magicterra.worlddriver.bot.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Server-side {@link net.magicterra.worlddriver.bot.world.WorldView} over a {@link ServerLevel}. Reads block states
 * directly from the server's chunk storage. Used by {@code mc.observe.scene} (and any other server-only analysis that
 * needs a WorldView without a client).
 *
 * <p>Break-to-move, place, and parkour-place are disabled ({@code +∞} / false): this view is for read-only hazard
 * analysis, not pathfinding.
 */
public final class ServerWorldView implements StateWorldView {

    private final ServerLevel level;

    /** A read-only view over {@code level}. */
    public ServerWorldView(ServerLevel level) {
        this.level = level;
    }

    @Override
    public BlockState blockState(BlockPos pos) {
        return level.getBlockState(pos);
    }

    @Override
    public Level stateLevel() {
        return level;
    }

    @Override
    public long tickMarker() {
        return level.getGameTime();
    }

    @Override
    public boolean isKnown(BlockPos pos) {
        return level.isLoaded(pos);
    }

    // ---- break / place stubs (not needed for hazard analysis) ----

    @Override
    public double breakCost(BlockPos pos) {
        return Double.POSITIVE_INFINITY;
    }

    @Override
    public double escapeBreakCost(BlockPos pos) {
        return Double.POSITIVE_INFINITY;
    }

    @Override
    public boolean canPlace() {
        return false;
    }

    @Override
    public boolean canParkourPlace() {
        return false;
    }
}
