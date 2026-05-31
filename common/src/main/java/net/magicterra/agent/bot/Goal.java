package net.magicterra.agent.bot;

import net.magicterra.agent.model.BlockPos;

/**
 * A* termination + admissible heuristic. Lightweight value type — created per
 * {@code mc.bot.goto} call, immutable, must be safe to share across threads.
 *
 * Heuristic contract: {@link #estimate(BlockPos)} returns a lower bound on the
 * remaining cost in the same units as {@code Move.cost()} (≈ ticks * 10). An
 * inflated heuristic breaks A* optimality but is sometimes desirable; keep it
 * admissible by default.
 *
 * The goal catalog mirrors Baritone's {@code baritone.api.pathing.goals}: every
 * goal there has a counterpart here, re-expressed in this project's cost units
 * (walk = 10, diagonal = 14, vertical = 10/block). {@link Inverted} and
 * {@link StrictDirection} are "open" goals that {@link #reached} never satisfies
 * — they steer the A* best-effort fallback (lowest-h node) rather than
 * terminating the search, exactly as Baritone uses them.
 */
public sealed interface Goal permits Goal.Block, Goal.Near, Goal.XZ, Goal.YLevel,
        Goal.RunAway, Goal.Composite, Goal.GetToBlock, Goal.TwoBlocks, Goal.Axis,
        Goal.Inverted, Goal.StrictDirection {
    boolean reached(BlockPos pos);
    double estimate(BlockPos pos);

    // === Shared heuristic helpers (mod cost units) ===========================

    /** 3D lower bound: Chebyshev-dominant axis at walk cost + the diagonal
     *  premium (14 vs 10 = 4 per paired horizontal step). Mirrors Baritone's
     *  {@code GoalBlock.calculate} but in this project's units. */
    static double blockHeuristic(int dx, int dy, int dz) {
        dx = Math.abs(dx); dy = Math.abs(dy); dz = Math.abs(dz);
        int diag = Math.min(dx, dz);
        int axes = Math.max(Math.max(dx, dz), dy);
        return 10.0 * axes + 4.0 * diag;
    }

    /** Horizontal-only lower bound: diagonal steps at 14, the straight remainder
     *  at 10. Mirrors Baritone's {@code GoalXZ.calculate}. */
    static double xzHeuristic(int dx, int dz) {
        dx = Math.abs(dx); dz = Math.abs(dz);
        int diag = Math.min(dx, dz);
        return 14.0 * diag + 10.0 * Math.abs(dx - dz);
    }

    /** Reach the exact block. Admissible 3D lower bound. */
    record Block(BlockPos target) implements Goal {
        public boolean reached(BlockPos p) { return p.equals(target); }
        public double estimate(BlockPos p) {
            return blockHeuristic(p.x - target.x, p.y - target.y, p.z - target.z);
        }
    }

    /** Get within {@code radius} blocks (Euclidean) of {@code target}. */
    record Near(BlockPos target, int radius) implements Goal {
        public boolean reached(BlockPos p) { return p.distSqr(target) <= radius * radius; }
        public double estimate(BlockPos p) {
            double d = Math.sqrt(p.distSqr(target)) - radius;
            return d <= 0 ? 0 : 10 * d;
        }
    }

    /** Reach an XZ column at any Y. */
    record XZ(int x, int z) implements Goal {
        public boolean reached(BlockPos p) { return p.x == x && p.z == z; }
        public double estimate(BlockPos p) { return xzHeuristic(p.x - x, p.z - z); }
    }

    /** Reach a given Y plane (used for surface/cave navigation). */
    record YLevel(int y) implements Goal {
        public boolean reached(BlockPos p) { return p.y == y; }
        public double estimate(BlockPos p) { return 10 * Math.abs(p.y - y); }
    }

    /**
     * Get at least {@code minDist} blocks away from {@code source}. Heuristic
     * decreases to 0 as the player gets further; admissible because the goal
     * is "be far," not "reach a specific point" — A* searches for any node
     * where {@link #reached} fires. (Bounded sibling of {@link Inverted}.)
     */
    record RunAway(BlockPos source, int minDist) implements Goal {
        public boolean reached(BlockPos p) { return p.distSqr(source) >= (long) minDist * minDist; }
        public double estimate(BlockPos p) {
            double d = Math.sqrt(p.distSqr(source));
            return d >= minDist ? 0 : 10 * (minDist - d);
        }
    }

    /** Any-of: reached when any child reached; heuristic = min child heuristic. */
    record Composite(Goal[] children) implements Goal {
        public boolean reached(BlockPos p) {
            for (Goal g : children) if (g.reached(p)) return true;
            return false;
        }
        public double estimate(BlockPos p) {
            double best = Double.POSITIVE_INFINITY;
            for (Goal g : children) best = Math.min(best, g.estimate(p));
            return best;
        }
    }

    /**
     * Stand directly adjacent to {@code target} (beside, on top, or in the cell
     * below it) — Baritone's {@code GoalGetToBlock}, the right goal for "walk up
     * to this chest/furnace" where stepping into the block itself is wrong.
     * The {@code yDiff < 0 ? yDiff + 1} shift lets the foot sit one block below
     * the target (head level adjacent) without inflating the distance.
     */
    record GetToBlock(BlockPos target) implements Goal {
        public boolean reached(BlockPos p) {
            int dy = p.y - target.y;
            return Math.abs(p.x - target.x) + Math.abs(dy < 0 ? dy + 1 : dy)
                    + Math.abs(p.z - target.z) <= 1;
        }
        public double estimate(BlockPos p) {
            int dy = p.y - target.y;
            return blockHeuristic(p.x - target.x, dy < 0 ? dy + 1 : dy, p.z - target.z);
        }
    }

    /**
     * Stand inside {@code target} at either foot or eye level — Baritone's
     * {@code GoalTwoBlocks}. Reached when the foot is at the target Y or one
     * below it (so the body occupies the target cell).
     */
    record TwoBlocks(BlockPos target) implements Goal {
        public boolean reached(BlockPos p) {
            return p.x == target.x && (p.y == target.y || p.y == target.y - 1) && p.z == target.z;
        }
        public double estimate(BlockPos p) {
            int dy = p.y - target.y;
            return blockHeuristic(p.x - target.x, dy < 0 ? dy + 1 : dy, p.z - target.z);
        }
    }

    /**
     * Reach a world axis (x==0, z==0, or the |x|==|z| diagonal) at a fixed Y —
     * Baritone's {@code GoalAxis}, used to navigate to a main highway/diagonal.
     * {@code axisHeight} mirrors Baritone's {@code axisHeight} setting (exposed
     * here via {@code mc.bot.setting{pathfinder.axisHeight:...}}).
     */
    record Axis(int axisHeight) implements Goal {
        private static final double SQRT_2_OVER_2 = Math.sqrt(2) / 2;
        public boolean reached(BlockPos p) {
            return p.y == axisHeight && (p.x == 0 || p.z == 0 || Math.abs(p.x) == Math.abs(p.z));
        }
        public double estimate(BlockPos p) {
            int x = Math.abs(p.x), z = Math.abs(p.z);
            int shrt = Math.min(x, z), lng = Math.max(x, z);
            int diff = lng - shrt;
            double flat = Math.min(x, Math.min(z, diff * SQRT_2_OVER_2));
            return 10.0 * flat + 10.0 * Math.abs(p.y - axisHeight);
        }
    }

    /**
     * Negate another goal: flee from where {@code origin} would converge —
     * Baritone's {@code GoalInverted}. Never {@link #reached}; the (negative)
     * heuristic drives A*'s best-effort fallback to the node that maximizes the
     * origin's heuristic, i.e. the farthest-from-target reachable cell.
     */
    record Inverted(Goal origin) implements Goal {
        public boolean reached(BlockPos p) { return false; }
        public double estimate(BlockPos p) { return -origin.estimate(p); }
    }

    /**
     * Head as far as possible in one cardinal direction from {@code origin} —
     * Baritone's {@code GoalStrictDirection}. Never {@link #reached}; the
     * heuristic rewards progress along (dx,dz) and heavily penalizes sideways
     * and vertical drift, so the best-effort fallback yields a straight bore in
     * that direction (Baritone "thisway"/tunnel without a fixed endpoint).
     */
    record StrictDirection(BlockPos origin, int dx, int dz) implements Goal {
        public boolean reached(BlockPos p) { return false; }
        public double estimate(BlockPos p) {
            int forward = (p.x - origin.x) * dx + (p.z - origin.z) * dz;
            int sideways = Math.abs((p.x - origin.x) * dz) + Math.abs((p.z - origin.z) * dx);
            int vertical = Math.abs(p.y - origin.y);
            return -forward * 100.0 + sideways * 1000.0 + vertical * 1000.0;
        }
    }
}
