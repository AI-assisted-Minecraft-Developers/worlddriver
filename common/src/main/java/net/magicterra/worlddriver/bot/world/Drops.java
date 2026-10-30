package net.magicterra.worlddriver.bot.world;

import net.minecraft.core.BlockPos;

/** Drops beside a cell, read off a {@link WorldView}: what the navigator's edge guards and a route's ledge tax ask. */
public final class Drops {
    private Drops() {}

    /** Horizontal neighbour offsets (4 cardinals + 4 diagonals) of the foot cell. */
    public static final int[][] EDGE_NEIGHBOURS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    /**
     * Whether a neighbour of {@code foot} opens onto a fall deeper than {@code threshold}, or onto a hazard at any
     * depth.
     */
    public static boolean adjacentExceeds(WorldView world, BlockPos foot, int threshold) {
        for (int[] o : EDGE_NEIGHBOURS) {
            BlockPos n = foot.offset(o[0], 0, o[1]);
            // A drop needs the foot-cell AND the cell below it both open (no floor).
            // A present floor = flat walk or a safe 1-block step-down; water = a splash.
            if (world.isSolid(n) || world.isWater(n)) continue;
            BlockPos below = n.below();
            if (world.isHazard(below)) return true;
            if (world.isSolid(below) || world.isWater(below)) continue;
            int fall = 1;
            BlockPos pr = below.below();
            while (fall <= threshold + 2 && !world.isSolid(pr) && !world.isWater(pr)) {
                // Lava is neither solid nor water, so the height scan used to fall
                // THROUGH it to the lake floor — a 2-deep lava pocket measured as a
                // "survivable" 2-block drop, edgeBrake stayed off, sprint stayed on,
                // and downhill momentum slid the bot in (round52: enteredLava ×3).
                // Any hazard in the fall column is lethal regardless of height.
                if (world.isHazard(pr)) return true;
                fall++;
                pr = pr.below();
            }
            if (fall > threshold) return true;
        }
        return false;
    }
}
