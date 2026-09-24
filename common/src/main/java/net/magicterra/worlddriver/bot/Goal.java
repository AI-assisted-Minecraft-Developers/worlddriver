package net.magicterra.worlddriver.bot;

import net.minecraft.core.BlockPos;

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

    /**
     * True when this goal's heuristic IGNORES Y — an XZ column goal. For such a
     * goal descending reads as free progress (the estimate doesn't change with Y),
     * so A* can be lured into diving/tunnelling DOWN through water and rock to reach
     * the target column at a lower Y (the root cause of the bot getting stuck climbing ashore from a deep-water bowl).
     * {@link net.magicterra.worlddriver.bot.pathfinder.PathFinder}'s descend-tax applies
     * ONLY to these. A goal that knows its target Y (Block/Near/TwoBlocks/GetToBlock
     * — a seabed monument, shipwreck, or any {@code pos:}/{@code block:} target)
     * guides a genuine dive correctly via its 3D heuristic and must NOT be taxed, so
     * deep-water exploration and ocean-monument runs are unaffected. Default false.
     *
     * <p><b>The same fact bites CALLERS, and that half has its own scar.</b> A goal whose heuristic
     * ignores Y also ARRIVES without an opinion about Y: "I reached that column" is not "I am on the
     * row you meant". Anything that computed something for a specific row — a ray, a reach, a
     * placement — and then walked there with an XZ goal is holding a result for a row the body may
     * not be on, and nothing in the arrival will say so. Measured on the journey ladder's portal
     * rung, 2026-08-16: a bucket column was verified with the eye at one row, the walk to it was a
     * {@code Goal.XZ}, the body arrived one row high, and the run blamed the block that was then in
     * the way. Walk with a Y-aware goal, or re-check on arrival.
     */
    default boolean ignoresY() { return false; }

    /** True for the open goals {@link Inverted} and {@link StrictDirection}: {@link #reached} never
     *  fires for them, so a walk toward one is done when it stops, not when it arrives. */
    default boolean open() { return false; }

    /**
     * The concrete block this goal converges on, or {@code null} for "open" /
     * column / direction goals that have no single target cell (XZ, YLevel,
     * RunAway, Axis, Inverted, StrictDirection, Composite). The pathfinder uses it
     * to tell a SURFACE/LAND navigation goal (target on dry land — water is a
     * transient obstacle to cross, so the buoyancy water-taxes apply) from a
     * DELIBERATE DIVE (target itself underwater — a seabed monument / shipwreck,
     * where taxing the descent would fight the intended route). Default null.
     */
    default BlockPos targetPos() { return null; }

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
        @Override public BlockPos targetPos() { return target; }
        public boolean reached(BlockPos p) { return p.equals(target); }
        public double estimate(BlockPos p) {
            return blockHeuristic(p.getX() - target.getX(), p.getY() - target.getY(), p.getZ() - target.getZ());
        }
    }

    /** Get within {@code radius} blocks (Euclidean) of {@code target}. */
    record Near(BlockPos target, int radius) implements Goal {
        @Override public BlockPos targetPos() { return target; }
        public boolean reached(BlockPos p) { return p.distSqr(target) <= radius * radius; }
        public double estimate(BlockPos p) {
            double d = Math.sqrt(p.distSqr(target)) - radius;
            return d <= 0 ? 0 : 10 * d;
        }
    }

    /** Reach an XZ column at any Y, within {@code radius} blocks (Euclidean) of it.
     *  radius 0 = the exact column. Honors the goto {@code near:N} arg so a target
     *  landing on water (where the bot floats and can't step onto the exact cell)
     *  is still satisfied by the surrounding column instead of spinning forever. */
    record XZ(int x, int z, int radius) implements Goal {
        /** Exact-column XZ goal (radius 0) — preserves the original 2-arg callers. */
        public XZ(int x, int z) { this(x, z, 0); }
        @Override public boolean ignoresY() { return true; }
        public boolean reached(BlockPos p) {
            long dx = p.getX() - x, dz = p.getZ() - z;
            return dx * dx + dz * dz <= (long) radius * radius;
        }
        public double estimate(BlockPos p) {
            // Admissible: the goal is a disk of the given radius, not a point.
            double h = xzHeuristic(p.getX() - x, p.getZ() - z) - 10.0 * radius;
            return h <= 0 ? 0 : h;
        }
    }

    /** Reach a given Y plane (used for surface/cave navigation). */
    record YLevel(int y) implements Goal {
        public boolean reached(BlockPos p) { return p.getY() == y; }
        public double estimate(BlockPos p) { return 10 * Math.abs(p.getY() - y); }
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
        @Override public BlockPos targetPos() { return target; }
        public boolean reached(BlockPos p) {
            int dy = p.getY() - target.getY();
            return Math.abs(p.getX() - target.getX()) + Math.abs(dy < 0 ? dy + 1 : dy)
                    + Math.abs(p.getZ() - target.getZ()) <= 1;
        }
        public double estimate(BlockPos p) {
            int dy = p.getY() - target.getY();
            return blockHeuristic(p.getX() - target.getX(), dy < 0 ? dy + 1 : dy, p.getZ() - target.getZ());
        }
    }

    /**
     * Stand inside {@code target} at either foot or eye level — Baritone's
     * {@code GoalTwoBlocks}. Reached when the foot is at the target Y or one
     * below it (so the body occupies the target cell).
     */
    record TwoBlocks(BlockPos target) implements Goal {
        @Override public BlockPos targetPos() { return target; }
        public boolean reached(BlockPos p) {
            return p.getX() == target.getX() && (p.getY() == target.getY() || p.getY() == target.getY() - 1) && p.getZ() == target.getZ();
        }
        public double estimate(BlockPos p) {
            int dy = p.getY() - target.getY();
            return blockHeuristic(p.getX() - target.getX(), dy < 0 ? dy + 1 : dy, p.getZ() - target.getZ());
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
            return p.getY() == axisHeight && (p.getX() == 0 || p.getZ() == 0 || Math.abs(p.getX()) == Math.abs(p.getZ()));
        }
        public double estimate(BlockPos p) {
            int x = Math.abs(p.getX()), z = Math.abs(p.getZ());
            int shrt = Math.min(x, z), lng = Math.max(x, z);
            int diff = lng - shrt;
            double flat = Math.min(x, Math.min(z, diff * SQRT_2_OVER_2));
            return 10.0 * flat + 10.0 * Math.abs(p.getY() - axisHeight);
        }
    }

    /**
     * Negate another goal: flee from where {@code origin} would converge —
     * Baritone's {@code GoalInverted}. Never {@link #reached}; the (negative)
     * heuristic drives A*'s best-effort fallback to the node that maximizes the
     * origin's heuristic, i.e. the farthest-from-target reachable cell.
     */
    record Inverted(Goal origin) implements Goal {
        @Override public boolean open() { return true; }
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
        @Override public boolean open() { return true; }
        public boolean reached(BlockPos p) { return false; }
        public double estimate(BlockPos p) {
            int forward = (p.getX() - origin.getX()) * dx + (p.getZ() - origin.getZ()) * dz;
            int sideways = Math.abs((p.getX() - origin.getX()) * dz) + Math.abs((p.getZ() - origin.getZ()) * dx);
            int vertical = Math.abs(p.getY() - origin.getY());
            return -forward * 100.0 + sideways * 1000.0 + vertical * 1000.0;
        }
    }
}
