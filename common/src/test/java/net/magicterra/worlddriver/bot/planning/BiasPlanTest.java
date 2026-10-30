package net.magicterra.worlddriver.bot.planning;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import net.magicterra.worlddriver.VanillaBoot;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts.Route;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.config.BotConfig;
import net.magicterra.worlddriver.config.PlanningConfig;
import net.magicterra.worlddriver.config.WorldConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The per-route cost bias and hard constraints over real blocks ({@code route.regions} avoid, a hard column leash, a
 * hard Y floor, a hard point leash, walking only, {@code break: never}, {@code hug: shore}, the parkour capability),
 * the dig-up/dig-down reuse probes, and the two escape-farthest churn guards.
 *
 * <p>Each stages an arena, gives the planning body the bag that decides what it may place and dig with, runs one or two
 * read-only searches as {@code mc.debug.plan} does, and judges the path they answer against the blocks it built. Every
 * check is a discrete planner OUTCOME (goalReached / node-inside-zone / straight-shaft / minPathY), integer-cell and
 * position-invariant. The settings are the test baseline with the planner's wall clock lifted, so a search ends on its
 * node budget alone.
 *
 * <p>Whether smoothing keeps a shore-hugging route in its band is pure logic over a path and a world view, and is
 * {@code ShorelineSmootherTest}'s.
 */
class BiasPlanTest {

    private BotConfig.ConfigPin pin;

    @BeforeAll
    static void boot() {
        VanillaBoot.boot();
    }

    @BeforeEach
    void pinBaseline() {
        pin = BotConfig.pinnedBaseline();
        PlanningConfig.pathfinderMaxMs = Long.MAX_VALUE / 4;
    }

    @AfterEach
    void restore() {
        pin.close();
    }

    /** An iron pickaxe in hand, and a stack of cobblestone when {@code blocks}. */
    private static void kit(BlockMapView w, boolean blocks) {
        w.give(new ItemStack(Items.IRON_PICKAXE));
        if (blocks) {
            w.give(new ItemStack(Items.COBBLESTONE, 64)); // pillar blocks → can place
        }
    }

    /**
     * A 2-high chamber inside a solid slab, cobblestone in the bag, a level above the slab as the target — the plan
     * must chain pillaring to reach the level. Break+place ON.
     */
    @Test
    void digUpY() {
        BlockMapView w = new BlockMapView();
        final int base = 30;
        final int top = 40;
        final int targetY = 41;
        w.fill(-2, base, -2, 2, top, 2, Blocks.STONE);
        w.set(0, base + 1, 0, Blocks.AIR);
        w.set(0, base + 2, 0, Blocks.AIR);
        BlockPos start = new BlockPos(0, base + 1, 0);
        kit(w, true);
        WorldConfig.allowBreak = true;
        WorldConfig.allowPlace = true;

        PathFinder.Result r = PlanProbe.plan(w, start, new Goal.YLevel(targetY));
        List<BlockPos> path = r.path();
        assertTrue(
                r.goalReached() && PlanProbe.maxY(path) >= targetY,
                "chained PillarUp did NOT plan a dig-up to Y=" + targetY + ": reached=" + r.goalReached() + " maxY="
                        + PlanProbe.maxY(path) + " expanded=" + r.expanded());
    }

    /**
     * Reuse-first — "dig down to Y=N" must plan with the existing {@code DownBreak} move. Iron pickaxe on top of a
     * solid slab, a level inside it as the target; the plan must reach the level down a STRAIGHT shaft (no sideways
     * dig). Break ON.
     */
    @Test
    void digDownY() {
        BlockMapView w = new BlockMapView();
        final int top = 40;
        final int targetY = 34;
        final int slabLo = 28;
        w.fill(-2, slabLo, -2, 2, top, 2, Blocks.STONE);
        BlockPos start = new BlockPos(0, top + 1, 0);
        kit(w, false);
        WorldConfig.allowBreak = true;

        PathFinder.Result r = PlanProbe.plan(w, start, new Goal.YLevel(targetY));
        List<BlockPos> path = r.path();
        assertTrue(
                r.goalReached() && PlanProbe.minY(path) == targetY,
                "DownBreak chain did NOT plan a dig-down to Y=" + targetY + ": reached=" + r.goalReached() + " minY="
                        + PlanProbe.minY(path) + " path=" + path);
        for (BlockPos p : path) {
            assertTrue(
                    p.getX() == 0 && p.getZ() == 0,
                    "dig-down plan wandered out of the start column (expected a straight DownBreak shaft): " + path);
        }
    }

    /**
     * The hard column leash (ascent-drift fix). PLAIN planning to a level takes a cheap staircase and DRIFTS +8 in X;
     * planning WITH a hard column leash must still reach the level up a straight start-column pillar and every node
     * must stay within the radius. Break+place ON.
     */
    @Test
    void columnRadius() {
        BlockMapView w = new BlockMapView();
        final int base = 30;
        final int rungs = 8;
        final int targetY = base + rungs + 1;
        w.set(0, base, 0, Blocks.STONE);
        for (int k = 1; k <= rungs; k++) {
            w.set(k, base + k, 0, Blocks.STONE);
        }
        BlockPos start = new BlockPos(0, base + 1, 0);
        kit(w, true);
        // The leash is measured from the column's centre (x+0.5, z+0.5), as below.
        final double radius = 0.9;
        WorldConfig.allowBreak = true;
        WorldConfig.allowPlace = true;

        PathFinder.Result plain = PlanProbe.plan(w, start, new Goal.YLevel(targetY));
        PathFinder.Result held = PlanProbe.plan(w, start, new Goal.YLevel(targetY), leashColumn(0, 0, radius));
        double plainMaxXZ = maxXZDist(plain.path(), 0.5, 0.5);
        double heldMaxXZ = maxXZDist(held.path(), 0.5, 0.5);

        assertTrue(
                plain.goalReached() && plainMaxXZ > radius,
                "baseline: PLAIN plan did not drift out of the start column (reached=" + plain.goalReached()
                        + " maxXZ=" + plainMaxXZ + " radius=" + radius
                        + ") — arena geometry wrong, the ascent-drift bug is not reproduced");
        assertTrue(
                held.goalReached() && PlanProbe.maxY(held.path()) >= targetY,
                "ColumnRadius over-pruned: constrained plan did NOT reach Y=" + targetY
                        + " up the start column (reached=" + held.goalReached() + " maxY=" + PlanProbe.maxY(held.path())
                        + ")");
        assertTrue(
                heldMaxXZ <= radius + 1e-6,
                "ColumnRadius did NOT contain the ascent: a planned node strayed " + heldMaxXZ
                        + " from the start column (radius=" + radius + ") — path=" + held.path());
    }

    /**
     * The per-route move-type gate. Two platforms across a full-width missing-floor column; the only crossing is a
     * parkour leap (bag empty so no place-bypass). PLAIN must cross, parkour-forbidden must NOT.
     */
    @Test
    void parkourGate() {
        BlockMapView w = new BlockMapView();
        final int y = 40;
        w.fill(-1, y, -1, 3, y, 1, Blocks.STONE);
        w.fill(5, y, -1, 9, y, 1, Blocks.STONE);
        BlockPos start = new BlockPos(0, y + 1, 0);
        Goal goal = new Goal.Block(new BlockPos(7, y + 1, 0)); // CRITICAL: no placeable block in the bag

        PathFinder.Result plain = PlanProbe.plan(w, start, goal);
        PathFinder.Result noParkour =
                PlanProbe.plan(w, start, goal, with(Route.NONE).parkour(false).build());
        assertTrue(
                plain.goalReached(),
                "baseline: PLAIN plan did NOT cross the gap — arena geometry wrong (no parkour move fired)");
        assertFalse(
                noParkour.goalReached(),
                "capability gate failed: walk-only (PARKOUR forbidden) plan still crossed the gap");
    }

    /**
     * The hard Y floor. A platform edge drops 3 blocks into an enclosed pit; PLAIN reaches the pit, a hard floor at
     * pitY+1 prunes the only descent edge so the goal becomes unreachable.
     */
    @Test
    void yFloorConstraint() {
        BlockMapView w = new BlockMapView();
        final int y = 40;
        w.fill(-1, y, -1, 0, y, 1, Blocks.STONE);
        int pitFloorY = y - 3;
        w.fill(1, pitFloorY, -1, 1, pitFloorY, 1, Blocks.STONE);
        BlockPos start = new BlockPos(-1, y + 1, 0);
        int dipY = pitFloorY + 1; // standing cell on the pit floor
        Goal goal = new Goal.Block(new BlockPos(1, dipY, 0));

        PathFinder.Result plain = PlanProbe.plan(w, start, goal);
        PathFinder.Result held = PlanProbe.plan(
                w,
                start,
                goal,
                with(Route.NONE)
                        .yRange(new RouteContracts.YRange(Optional.of(dipY + 1), Optional.empty(), true, 0))
                        .build());
        assertTrue(
                plain.goalReached() && PlanProbe.minY(plain.path()) <= dipY,
                "baseline: PLAIN plan did not reach/dip into the pit — arena geometry wrong");
        assertFalse(
                held.goalReached(),
                "YFloor(dipY+1) did NOT prune the pit descent: goal still reached with the hard floor set");
    }

    /**
     * The hard point leash. One anchor, one radius R shared between the leash and the goals: a goal inside R must be
     * reached, an identical leash to a goal beyond R must NOT.
     */
    @Test
    void leashHard() {
        BlockMapView w = new BlockMapView();
        final int y = 40;
        w.fill(-1, y, -1, 18, y, 1, Blocks.STONE);
        final double radius = 10.0;
        BlockPos start = new BlockPos(0, y + 1, 0);
        // A point anchor is the cell's corner (0, y+1, 0): half a block from the start's centre, far from
        // either goal's side of R.
        Route leashed = with(Route.NONE)
                .leash(new RouteContracts.Leash(
                        new RouteContracts.PointAnchor(new BlockPosition(0, y + 1, 0)), radius, true, 0))
                .build();
        PathFinder.Result inside =
                PlanProbe.plan(w, start, new Goal.Block(new BlockPos(8, y + 1, 0)), leashed); // dist 8  < R
        PathFinder.Result outside =
                PlanProbe.plan(w, start, new Goal.Block(new BlockPos(16, y + 1, 0)), leashed); // dist 16 > R
        assertTrue(
                inside.goalReached(),
                "baseline: goal INSIDE the hard leash radius was not reached — arena geometry wrong");
        assertFalse(outside.goalReached(), "LeashHardRadius did NOT hold: a goal OUTSIDE the radius was still reached");
    }

    /**
     * Walking only ({@code NoWater}). A dry stone lane walled in bedrock with a full-width foot-layer wading pool
     * mid-lane; PLAIN wades through (only route), walking only prunes every water-foot edge so the goal becomes
     * unreachable.
     */
    @Test
    void forbidWater() {
        BlockMapView w = new BlockMapView();
        final int y = 40;
        final int stripLo = 5;
        final int stripHi = 6;
        for (int dx = -1; dx <= 12; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                w.set(dx, y, dz, Blocks.STONE);
                if (dx >= stripLo && dx <= stripHi) {
                    w.set(dx, y + 1, dz, Blocks.WATER);
                }
            }
            for (int dz : new int[] {-2, 2}) {
                w.fill(dx, y, dz, dx, y + 3, dz, Blocks.BEDROCK);
            }
        }
        BlockPos start = new BlockPos(0, y + 1, 0);
        Goal goal = new Goal.Block(new BlockPos(10, y + 1, 0)); // no placeable block — no pillar/bridge bypass

        PathFinder.Result plain = PlanProbe.plan(w, start, goal);
        PathFinder.Result held =
                PlanProbe.plan(w, start, goal, with(Route.NONE).walkOnly().build());
        boolean plainEntersWater = plain.path().stream().anyMatch(w::isWater);
        assertTrue(
                plain.goalReached() && plainEntersWater,
                "baseline: PLAIN plan did not cross the water strip — arena geometry wrong (goalReached="
                        + plain.goalReached() + " enteredWater=" + plainEntersWater + ") path=" + plain.path()
                        + " moves="
                        + plain.edges().stream()
                                .map(e -> e == null ? "-" : e.move)
                                .toList());
        assertFalse(
                held.goalReached(),
                "NoWater did NOT hold: constrained plan still reached the goal (a route avoided the strip "
                        + "without going through it — geometry allows a bypass) path=" + held.path());
    }

    /**
     * {@code break: never} ({@code NoBreak}). The digDownY recipe: a solid slab, iron pickaxe, a level inside it. PLAIN
     * digs down (baseline), {@code NoBreak} prunes every break-edge so the goal is unreachable even with the
     * {@code allowBreak} setting ON.
     */
    @Test
    void forbidDig() {
        BlockMapView w = new BlockMapView();
        final int top = 40;
        final int targetY = 34;
        final int slabLo = 28;
        w.fill(-2, slabLo, -2, 2, top, 2, Blocks.STONE);
        BlockPos start = new BlockPos(0, top + 1, 0);
        kit(w, false);
        WorldConfig.allowBreak = true;

        PathFinder.Result plain = PlanProbe.plan(w, start, new Goal.YLevel(targetY));
        PathFinder.Result held = PlanProbe.plan(
                w,
                start,
                new Goal.YLevel(targetY),
                with(Route.NONE).breakPolicy(RouteContracts.BreakPolicy.NEVER).build());
        assertTrue(
                plain.goalReached() && PlanProbe.minY(plain.path()) == targetY,
                "baseline: PLAIN plan did NOT dig down to Y=" + targetY + ": reached=" + plain.goalReached() + " minY="
                        + PlanProbe.minY(plain.path()));
        assertFalse(
                held.goalReached(),
                "NoBreak did NOT hold: constrained plan still reached Y=" + targetY + " with allowBreak="
                        + WorldConfig.allowBreak + " — path=" + held.path());
    }

    /**
     * {@code hug: shore}. A slab with a receding shoreline and a straight dry lane; PLAIN stays on the lane (no node
     * past z0-2), a shore hug of 30 with walking only must dip south to hug the receding shore (some node z<=z0-5)
     * while every node stays within 2 of water.
     */
    @Test
    void shorelineHug() {
        BlockMapView w = new BlockMapView();
        final int y = 40;
        w.fill(-1, y, -9, 38, y, 4, Blocks.STONE);
        for (int dx = -1; dx <= 38; dx++) {
            int shoreDz = dx >= 12 && dx < 24 ? -7 : -1;
            for (int dz = -9; dz <= shoreDz; dz++) {
                w.set(dx, y + 1, dz, Blocks.WATER);
            }
        }
        w.fill(-1, y, 4, 38, y + 3, 4, Blocks.BEDROCK);
        w.fill(-1, y, -10, 38, y + 3, -10, Blocks.BEDROCK);
        BlockPos start = new BlockPos(0, y + 1, 0);
        Goal goal = new Goal.Block(new BlockPos(36, y + 1, 0)); // no placeable block — no bridge/pillar bypass

        PathFinder.Result plain = PlanProbe.plan(w, start, goal);
        PathFinder.Result hugged = PlanProbe.plan(
                w,
                start,
                goal,
                with(Route.NONE)
                        .hug(new RouteContracts.Hug(RouteContracts.HugWhat.SHORE, 30.0))
                        .walkOnly()
                        .build());
        int plainMinZ = minZ(plain.path());
        int huggedMinZ = minZ(hugged.path());

        assertTrue(
                plain.goalReached() && hugged.goalReached(),
                "baseline: PLAIN and/or HUGGED plan did not reach the goal — plain=" + plain.goalReached() + " hugged="
                        + hugged.goalReached());
        assertTrue(
                plainMinZ >= -2,
                "baseline: PLAIN plan cut away from the straight lane past z0-2 (minZ(rel)=" + plainMinZ
                        + ") — no bias should ever leave it; arena geometry wrong");
        assertTrue(
                huggedMinZ <= -5,
                "ShorelineHug did NOT dip to the receding shoreline: worst minZ(rel)=" + huggedMinZ
                        + " (expected <= -5 somewhere in the middle third) path=" + hugged.path());
        for (BlockPos p : hugged.path()) {
            assertTrue(
                    waterWithin(w, p, 2),
                    "ShorelineHug did NOT stay within 2 of water at every node — worst offender=" + p + " path="
                            + hugged.path());
        }
    }

    /**
     * A budget-capped search from a chamber sealed in rock toward a cell ABOVE must never commit an escape-farthest
     * best-effort segment that DIGS AWAY DOWNWARD. Break+place ON. Passes on the goal reached or no path at all; only a
     * committed partial is judged.
     */
    @Test
    void escapeFarthestNoRockDrill() {
        BlockMapView w = new BlockMapView();
        final int base = 18;
        final int top = 40;
        final int footY = top - 4;
        w.fill(-3, base, -3, 3, top, 3, Blocks.STONE);
        w.set(0, footY, 0, Blocks.AIR);
        w.set(0, footY + 1, 0, Blocks.AIR);
        BlockPos start = new BlockPos(0, footY, 0);
        kit(w, true);
        WorldConfig.allowBreak = true;
        WorldConfig.allowPlace = true;

        PathFinder.Result r = PlanProbe.plan(w, start, new Goal.Block(new BlockPos(0, top + 2, 0)), null, 200, 100L);
        List<BlockPos> path = r.path();
        boolean hasPath = !path.isEmpty();
        int minY = hasPath ? PlanProbe.minY(path) : start.getY();
        assertFalse(
                !r.goalReached() && hasPath && minY < start.getY() - 2,
                "escape-farthest committed a downward rock drill (goal is ABOVE): minPathY=" + minY + " startY="
                        + start.getY() + " pathLen=" + path.size());
    }

    /**
     * A budget-capped search from a chamber sealed in rock toward a goal ABOVE must not commit a best-effort segment
     * that runs a walkable tunnel AWAY from the goal. GREEN = goal reached, a partial that does not end farther from
     * the goal (small slack), or NO path. Break+place ON.
     */
    @Test
    void budgetAwayTunnelChurn() {
        BlockMapView w = new BlockMapView();
        final int base = 18;
        final int top = 40;
        final int footY = top - 4;
        w.fill(-3, base, -3, 20, top, 3, Blocks.STONE);
        w.fill(0, footY, 0, 16, footY + 1, 0, Blocks.AIR); // chamber cell + away tunnel
        BlockPos start = new BlockPos(0, footY, 0);
        BlockPos goalPos = new BlockPos(0, top + 2, 0);
        kit(w, true);
        WorldConfig.allowBreak = true;
        WorldConfig.allowPlace = true;

        PathFinder.Result r = PlanProbe.plan(w, start, new Goal.Block(goalPos), null, 200, 100L);
        List<BlockPos> path = r.path();
        boolean hasPath = !path.isEmpty();
        double startDist = Math.sqrt(start.distSqr(goalPos));
        double endDist = hasPath ? Math.sqrt(path.get(path.size() - 1).distSqr(goalPos)) : startDist;
        assertFalse(
                !r.goalReached() && hasPath && endDist > startDist + 2.0,
                "budget-capped partial path runs AWAY from the goal (the drift-churn seed): startDist="
                        + String.format("%.1f", startDist) + " endDist=" + String.format("%.1f", endDist)
                        + " pathLen=" + path.size());
    }

    // ---- geometry over the planned path and the staged world

    /**
     * Max Euclidean XZ distance of any node from the column centre, on the cell-centre (x+0.5, z+0.5) convention the
     * column leash uses.
     */
    private static double maxXZDist(List<BlockPos> path, double cx, double cz) {
        double max = 0;
        for (BlockPos p : path) {
            double dx = (p.getX() + 0.5) - cx;
            double dz = (p.getZ() + 0.5) - cz;
            max = Math.max(max, Math.sqrt(dx * dx + dz * dz));
        }
        return max;
    }

    /** Lowest Z on the path (negative = south, toward the water); {@code Integer.MAX_VALUE} for an empty path. */
    private static int minZ(List<BlockPos> path) {
        int min = Integer.MAX_VALUE;
        for (BlockPos p : path) {
            min = Math.min(min, p.getZ());
        }
        return min;
    }

    /**
     * Chebyshev 5x5 ring (rad=2) at the node's own Y and one below — the shoreline "close enough to the water" band,
     * looser than the shore hug's own tax-free adjacency.
     */
    private static boolean waterWithin(BlockMapView w, BlockPos p, int rad) {
        for (int dx = -rad; dx <= rad; dx++) {
            for (int dz = -rad; dz <= rad; dz++) {
                for (int dy = 0; dy >= -1; dy--) {
                    if (w.isWater(p.offset(dx, dy, dz))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ---- routes, a condition at a time

    private static RouteBuilder with(Route r) {
        return new RouteBuilder(r);
    }

    private static Route leashColumn(int x, int z, double radius) {
        return with(Route.NONE)
                .leash(new RouteContracts.Leash(new RouteContracts.ColumnAnchor(x, z), radius, true, 0))
                .build();
    }

    /** A {@link Route} with one field changed per call. */
    private static final class RouteBuilder {
        private Route r;

        RouteBuilder(Route r) {
            this.r = r;
        }

        Route build() {
            return r;
        }

        RouteBuilder parkour(boolean on) {
            r = new Route(
                    r.via(),
                    r.mode(),
                    r.breakPolicy(),
                    r.place(),
                    on,
                    r.risk(),
                    r.yRange(),
                    r.hug(),
                    r.leash(),
                    r.regions(),
                    r.mobs(),
                    r.sight(),
                    r.corridor(),
                    r.ledge(),
                    r.requireTool());
            return this;
        }

        RouteBuilder breakPolicy(RouteContracts.BreakPolicy b) {
            r = new Route(
                    r.via(),
                    r.mode(),
                    b,
                    r.place(),
                    r.parkour(),
                    r.risk(),
                    r.yRange(),
                    r.hug(),
                    r.leash(),
                    r.regions(),
                    r.mobs(),
                    r.sight(),
                    r.corridor(),
                    r.ledge(),
                    r.requireTool());
            return this;
        }

        /** Never enter water: walking alone. */
        RouteBuilder walkOnly() {
            r = new Route(
                    r.via(),
                    Optional.of(List.of(RouteContracts.TravelMode.WALK)),
                    r.breakPolicy(),
                    r.place(),
                    r.parkour(),
                    r.risk(),
                    r.yRange(),
                    r.hug(),
                    r.leash(),
                    r.regions(),
                    r.mobs(),
                    r.sight(),
                    r.corridor(),
                    r.ledge(),
                    r.requireTool());
            return this;
        }

        RouteBuilder yRange(RouteContracts.YRange y) {
            r = new Route(
                    r.via(),
                    r.mode(),
                    r.breakPolicy(),
                    r.place(),
                    r.parkour(),
                    r.risk(),
                    Optional.of(y),
                    r.hug(),
                    r.leash(),
                    r.regions(),
                    r.mobs(),
                    r.sight(),
                    r.corridor(),
                    r.ledge(),
                    r.requireTool());
            return this;
        }

        RouteBuilder hug(RouteContracts.Hug h) {
            r = new Route(
                    r.via(),
                    r.mode(),
                    r.breakPolicy(),
                    r.place(),
                    r.parkour(),
                    r.risk(),
                    r.yRange(),
                    Optional.of(h),
                    r.leash(),
                    r.regions(),
                    r.mobs(),
                    r.sight(),
                    r.corridor(),
                    r.ledge(),
                    r.requireTool());
            return this;
        }

        RouteBuilder leash(RouteContracts.Leash l) {
            r = new Route(
                    r.via(),
                    r.mode(),
                    r.breakPolicy(),
                    r.place(),
                    r.parkour(),
                    r.risk(),
                    r.yRange(),
                    r.hug(),
                    Optional.of(l),
                    r.regions(),
                    r.mobs(),
                    r.sight(),
                    r.corridor(),
                    r.ledge(),
                    r.requireTool());
            return this;
        }
    }
}
