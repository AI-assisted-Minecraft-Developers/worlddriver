package net.magicterra.agent.bot.util;

import net.minecraft.core.BlockPos;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared nearest-first cell-offset enumeration for bounded block scans
 * (gap#67-⑤). {@code MineProcess.scanForTarget} and
 * {@code GoalResolver.findNearestStandForBlock} both walked a dy-outer,
 * dx/dz-inner triple loop with a flat "cells visited" budget: at a large
 * horizontal radius, a SINGLE dy layer alone blows the whole budget (r=32 ->
 * 65x65=4225 cells/layer, a 50_000 cap -> dy in [+4,+8] never scanned at
 * all), so the top of the vertical search band went silently unscanned — a
 * jungle-canopy log a few blocks above the bot was invisible even though it
 * sits well inside both the horizontal and vertical limits.
 *
 * <p>This enumerates every (dx,dy,dz) offset in the box exactly once, sorted
 * ascending by squared distance from the origin, so a budget cutoff at the
 * call site drops the FARTHEST cells — harmless, they're the least likely to
 * matter — instead of an entire height band. The sorted arrays are cached
 * per (radius, vertRadius) pair since they're pure geometry, independent of
 * any world state.
 */
public final class NearestFirstScan {
    private NearestFirstScan() {}

    private static final Map<Long, BlockPos[]> CACHE = new ConcurrentHashMap<>();

    /** Every (dx,dy,dz) offset with |dx|,|dz| &lt;= radius and |dy| &lt;=
     *  vertRadius, sorted nearest-first by squared distance from the origin.
     *  Callers apply their own budget by only consuming a prefix of the
     *  returned array — the farthest cells simply sort last. */
    public static BlockPos[] offsetsNearestFirst(int radius, int vertRadius) {
        long key = (((long) radius) << 32) ^ (vertRadius & 0xffffffffL);
        return CACHE.computeIfAbsent(key, k -> buildSorted(radius, vertRadius));
    }

    private static BlockPos[] buildSorted(int radius, int vertRadius) {
        int nx = 2 * radius + 1, ny = 2 * vertRadius + 1;
        BlockPos[] offsets = new BlockPos[nx * nx * ny];
        int i = 0;
        for (int dx = -radius; dx <= radius; dx++)
            for (int dy = -vertRadius; dy <= vertRadius; dy++)
                for (int dz = -radius; dz <= radius; dz++)
                    offsets[i++] = new BlockPos(dx, dy, dz);
        Arrays.sort(offsets, Comparator.comparingLong(NearestFirstScan::distSq));
        return offsets;
    }

    private static long distSq(BlockPos p) {
        return (long) p.getX() * p.getX() + (long) p.getY() * p.getY() + (long) p.getZ() * p.getZ();
    }
}
