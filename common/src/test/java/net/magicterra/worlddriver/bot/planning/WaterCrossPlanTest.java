package net.magicterra.worlddriver.bot.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.magicterra.worlddriver.VanillaBoot;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.config.BotConfig;
import net.magicterra.worlddriver.config.PlanningConfig;
import net.magicterra.worlddriver.config.WorldConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The open-water crossing planner arenas, each an A/B on one planner setting over real blocks: the
 * anti-basin-dive depth penalty, the floating-water climb-out tax, the deep-water surface bias, the
 * vine- and pad-over-water taxes and the pad-cluster tax. Each runs the search {@code mc.debug.plan}
 * runs, to completion (the planner's wall clock lifted), and judges the committed plan; nothing walks.
 * The settings are the test baseline plus the keys each arena flips.
 *
 * <p>What the walking half of {@code wd.deepWaterSubmergedCross} asked — that the floating navigator
 * then crosses on top — needs a body and a level, and stays an in-game scene.
 */
class WaterCrossPlanTest {

    private BotConfig.ConfigPin pin;

    @BeforeAll
    static void boot() { VanillaBoot.boot(); }

    @BeforeEach
    void pinBaseline() {
        pin = BotConfig.pinnedBaseline();
        PlanningConfig.pathfinderMaxMs = Long.MAX_VALUE / 4;   // each search runs to completion (deterministic)
    }

    @AfterEach
    void restore() { pin.close(); }

    /** Failure-case A/B for the anti-basin-dive {@code pathfinderDepthPenalty}. An XZ goal across a plateau;
     *  the direct corridor is a deep trap pocket. penalty=6 reaches via the rim and cuts the node count vs
     *  penalty=0; under a budget BETWEEN the two counts penalty=0 fails while penalty=6 reaches. */
    @Test
    void basin() {
        BlockMapView w = new BlockMapView();
        final int plY = 40;
        w.fill(-12, plY, 0, 12, plY, 44, Blocks.STONE);   // flat plateau (the go-around) across the whole arena
        // Carve the valley corridor: clear it, lay a −1/step down-ramp and a deep floor. A dead-end pocket.
        w.fill(-3, plY - 13, 8, 3, plY + 3, 30, Blocks.AIR);
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = 8; dz <= 19; dz++) w.set(dx, plY - (dz - 7), dz, Blocks.STONE);
            for (int dz = 20; dz <= 30; dz++) w.set(dx, plY - 12, dz, Blocks.STONE);
        }
        BlockPos start = new BlockPos(0, plY + 1, 2);
        Goal goal = new Goal.XZ(0, 40, 0);   // ignoresY → the dive-prone case
        PlanningConfig.pathfinderMaxNodes = 1_000_000;

        PlanningConfig.pathfinderDepthPenalty = 0;
        PathFinder.Result r0 = PlanProbe.plan(w, start, goal);
        PlanningConfig.pathfinderDepthPenalty = 6;
        PathFinder.Result r6 = PlanProbe.plan(w, start, goal);
        assertTrue(r6.goalReached(), "depthPenalty=6 failed to reach via the rim go-around");
        assertTrue(r6.expanded() < r0.expanded(), "depthPenalty did not cut basin-dive exploration: penalty0 expanded="
                + r0.expanded() + " penalty6 expanded=" + r6.expanded());

        // A node budget BETWEEN the two counts: penalty=0 burns it in the trap and FAILS; penalty=6 reaches
        // via the rim within it.
        int budget = (r0.expanded() + r6.expanded()) / 2;
        PlanningConfig.pathfinderMaxNodes = budget;
        PlanningConfig.pathfinderDepthPenalty = 0;
        PathFinder.Result b0 = PlanProbe.plan(w, start, goal);
        PlanningConfig.pathfinderDepthPenalty = 6;
        PathFinder.Result b6 = PlanProbe.plan(w, start, goal);
        assertTrue(!b0.goalReached() && b6.goalReached(), "budget A/B not decisive: budget=" + budget
                + " penalty0.reached=" + b0.goalReached() + " penalty6.reached=" + b6.goalReached());
    }

    /** Two exits at equal crossing distance: a +1 bank (jump-needed) and a surface-level (+0) flush bank.
     *
     *  <p>This used to pin the +1 exit as STRUCTURALLY unavailable to a floating player. It no longer is: a
     *  surface floater mounts a +1 dry bank on vanilla's collision boost, measured on the real client by
     *  {@code wd.clientFlushBankClimbOut}, and {@code StepUp} now offers that edge from the surface cell.
     *  What remains pinned is the PREFERENCE: with the climb-out tax at its default the flush exit must win
     *  (maxY ≤ wsurf); with the tax off the +1 exit is merely allowed, and both routes must reach. */
    @Test
    void waterClimbOutRoute() {
        BlockMapView w = new BlockMapView();
        final int wsurf = 20;   // air at wsurf+1
        // Two-column pool, dx 0 and dx 1, dz 0..6: solid floor wsurf-2, water at wsurf-1 & wsurf.
        w.fill(0, wsurf - 2, 0, 1, wsurf - 2, 6, Blocks.STONE);
        w.fill(0, wsurf - 1, 0, 1, wsurf, 6, Blocks.WATER);
        // dx 0 STRAIGHT exit: a +1 bank at dz=7 (solid top wsurf → stand foot wsurf+1).
        w.fill(0, wsurf - 2, 7, 0, wsurf, 7, Blocks.STONE);
        // dx 1 GENTLE exit: pool fingers ONE cell farther north (dz=7 stays water), then a SURFACE-level
        // bank at dz=8 (solid top wsurf-1 → stand foot wsurf, rise 0).
        w.set(1, wsurf - 2, 7, Blocks.STONE);
        w.fill(1, wsurf - 1, 7, 1, wsurf, 7, Blocks.WATER);
        // Flat land dz 8..18 at stand-foot wsurf (solid top wsurf-1), dx -1..2 — both exits converge here.
        w.fill(-1, wsurf - 1, 8, 2, wsurf - 1, 18, Blocks.STONE);
        BlockPos start = new BlockPos(0, wsurf, 0);   // floating at the pool's south end
        Goal goal = new Goal.XZ(0, 15, 0);            // ignoresY → buoyant climb-out case
        double defaultTax = PlanningConfig.pathfinderWaterClimbOutCost;   // the baseline's value
        PlanningConfig.pathfinderMaxNodes = 1_000_000;

        // Tax OFF: the +1 exit is allowed (its maxY is not judged). Tax default: flush wins.
        PlanningConfig.pathfinderWaterClimbOutCost = 0;
        PathFinder.Result off = PlanProbe.plan(w, start, goal);
        PlanningConfig.pathfinderWaterClimbOutCost = defaultTax;
        PathFinder.Result on = PlanProbe.plan(w, start, goal);
        assertTrue(off.goalReached() && on.goalReached(),
                "a climb-out route failed to reach: off=" + off.goalReached() + " on=" + on.goalReached());
        int maxYOn = PlanProbe.maxY(on.path());
        assertTrue(maxYOn <= wsurf, "floating-water +1 climb-out was NOT forbidden (tax default): maxY=" + maxYOn
                + " (expected ≤" + wsurf + ") path=" + on.path());
    }

    /** Deep-water submerged-crossing surface bias ({@code pathfinderFloatingSurfaceCross}). A deep (9-block)
     *  open-water channel; the search is seeded submerged (surface-1) for a Y-aware Near goal on the far bank.
     *  The legacy water model with the bias OFF threads MANY submerged crossing nodes (the bug); the
     *  surface-node model with the bias ON surfaces immediately and still reaches. */
    @Test
    void deepWaterSubmergedCross() {
        BlockMapView w = new BlockMapView();
        final int floorY = 0, surface = floorY + 9, spanX = 24, halfZ = 5;
        w.fill(-3, floorY, -halfZ - 1, spanX + 7, floorY, halfZ + 1, Blocks.STONE);         // deep basin floor
        w.fill(-1, floorY + 1, -halfZ, spanX + 1, surface, halfZ, Blocks.WATER);             // deep water
        w.fill(spanX - 1, floorY + 1, -halfZ, spanX, surface - 1, halfZ, Blocks.STONE);      // shallow wade shelf, east
        w.fill(-5, floorY + 1, -halfZ, -2, surface - 1, halfZ, Blocks.STONE);                // dry banks at each end
        w.fill(spanX + 1, floorY + 1, -halfZ, spanX + 6, surface - 1, halfZ, Blocks.STONE);
        w.set(spanX / 2, surface + 1, 0, Blocks.LILY_PAD);                                   // incidental scenery
        BlockPos seed = new BlockPos(1, surface - 1, 0);                  // one cell INTO the deep water, submerged
        Goal goal = new Goal.Near(new BlockPos(spanX + 4, surface - 1, 0), 1);   // Y-aware, dry east bank
        WorldConfig.allowBreak = false;   // pure swim/walk — the route choice must be the gate, not a dig
        WorldConfig.allowPlace = false;

        int[] subNodes = new int[2];
        boolean[] reached = new boolean[2];
        for (int leg = 0; leg < 2; leg++) {
            PlanningConfig.pathfinderFloatingSurfaceCross = leg == 1;
            // The bug lives in the legacy model, where every water cell is a node; leg 0 reproduces it there.
            // Under the surface-node model (leg 1) a submerged crossing cannot be planned at all.
            WorldConfig.pathfinderSurfaceWaterNodes = leg == 1;
            PathFinder.Result r = PlanProbe.plan(w, seed, goal);
            reached[leg] = r.goalReached();
            for (BlockPos p : r.path())
                if (p.getY() < surface && w.isWater(p) && w.isWater(p.above())) subNodes[leg]++;
        }
        assertTrue(subNodes[0] >= 10, "with the flag OFF the plan did NOT thread the submerged crossing (submergedNodes="
                + subNodes[0] + " < 10) — the bug did not reproduce; the buoyant deep-water entry should route the whole"
                + " crossing one below the surface");
        assertTrue(subNodes[1] <= 1, "with pathfinderFloatingSurfaceCross ON the plan STILL threads submerged crossing"
                + " nodes (submergedNodes=" + subNodes[1] + " > 1) — the surface bias did not fire; A* must surface and cross on top");
        assertTrue(reached[1], "with the flag ON A* failed to reach the goal (the surface route must still solve the crossing)");
    }

    /** The deep crossing the three tax arenas share: stone floor, deep water to {@code surface} across
     *  {@code -halfZ..halfZ}, dry banks flush with the water, a grounded wade shelf one cell in at each bank. */
    private static BlockMapView deepCrossing(int floorY, int surface, int spanX, int halfZ) {
        BlockMapView w = new BlockMapView();
        w.fill(-3, floorY, -halfZ - 1, spanX + 7, floorY, halfZ + 1, Blocks.STONE);
        w.fill(-1, floorY + 1, -halfZ, spanX + 1, surface, halfZ, Blocks.WATER);
        w.fill(-5, floorY + 1, -halfZ, -1, surface - 1, halfZ, Blocks.STONE);   // bank FOOT is `surface`
        w.fill(spanX + 1, floorY + 1, -halfZ, spanX + 6, surface - 1, halfZ, Blocks.STONE);
        for (int dx = -1; dx <= spanX + 1; dx += spanX + 2)
            w.fill(dx, surface - 1, -halfZ, dx, surface - 1, halfZ, Blocks.STONE);
        return w;
    }

    /** Planner A/B for the vine/leaf-canopy-OVER-DEEP-WATER tax ({@code pathfinderVineOverWaterTax}). A tree
     *  canopy grows in a 1-cell CENTER lane over a deep crossing; OFF threads the center lane (the bug), ON
     *  detours around it (the fix) and still reaches. The silent-no-op guards confirm the center head-level
     *  cell is a vine: climbable, not leaves (leafCellTax would already catch it), not a breakable
     *  obstruction (padCellTax would). */
    @Test
    void vineOverWaterCross() {
        final int surface = 8, spanX = 16, halfZ = 3, treeX0 = 5, treeX1 = 11;
        BlockMapView w = deepCrossing(0, surface, spanX, halfZ);
        // surface+1 = hanging vine (head cell), surface+2/+3 = oak-leaf canopy; lily pads at z=±2 (scenery).
        BlockState leaf = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, Boolean.TRUE);
        BlockState vineHang = Blocks.VINE.defaultBlockState().setValue(VineBlock.UP, Boolean.TRUE);
        for (int dx = treeX0; dx <= treeX1; dx++) {
            w.set(new BlockPos(dx, surface + 3, 0), leaf);
            w.set(new BlockPos(dx, surface + 2, 0), leaf);
            w.set(new BlockPos(dx, surface + 1, 0), vineHang);
            w.set(dx, surface + 1, -2, Blocks.LILY_PAD);
            w.set(dx, surface + 1, 2, Blocks.LILY_PAD);
        }
        BlockPos start = new BlockPos(-3, surface, 0);
        Goal goal = new Goal.Near(new BlockPos(spanX + 3, surface, 0), 1);
        PlanningConfig.pathfinderMaxNodes = 1_000_000;

        BlockPos bodyMid = new BlockPos((treeX0 + treeX1) / 2, surface + 1, 0);
        BlockPos capMid = new BlockPos((treeX0 + treeX1) / 2, surface + 2, 0);
        assertTrue(w.isClimbable(bodyMid), "center head-level cell is NOT climbable (vine) at " + bodyMid + "; the repro is vacuous");
        assertFalse(w.isLeaves(bodyMid), "center head-level cell reads as LEAVES at " + bodyMid
                + " — leafCellTax would already catch it and the new tax would be redundant; expected a VINE");
        assertFalse(w.isBreakableObstruction(bodyMid), "center head-level vine reads as a breakable obstruction at " + bodyMid
                + " — padCellTax would already catch it; a vine must have no collision shape");
        assertTrue(w.isLeaves(capMid), "leaf canopy missing at " + capMid);

        int[] laneNodes = new int[2];
        boolean[] reached = new boolean[2];
        for (int leg = 0; leg < 2; leg++) {
            PlanningConfig.pathfinderVineOverWaterTax = leg == 1;
            PathFinder.Result r = PlanProbe.plan(w, start, goal);
            reached[leg] = r.goalReached();
            for (BlockPos p : r.path())
                if (p.getZ() == 0 && p.getX() >= treeX0 && p.getX() <= treeX1 && w.isWater(p)) laneNodes[leg]++;
        }
        assertTrue(laneNodes[0] >= 3, "with the tax OFF the plan did NOT thread the vine/leaf center lane (centerLaneNodes="
                + laneNodes[0] + " < 3) — the bug did not reproduce; the straight crossing should pass through the canopy band");
        assertEquals(0, laneNodes[1], "with pathfinderVineOverWaterTax ON the plan STILL threads the vine/leaf center lane"
                + " — the tax did not deflect A* around the tree-over-water cluster");
        assertTrue(reached[1], "with the tax ON A* failed to reach the goal — the tax must DETOUR around the tree, never"
                + " forbid the crossing (a clear side lane exists)");
    }

    /** Planner A/B for the SPARSE-single-lily-pad-OVER-DEEP-WATER tax ({@code pathfinderPadOverWaterTax}), the
     *  Y-aware-goal sibling of the XZ-only padCellTax. Sparse single pads on the center line; OFF threads them
     *  (the bug), ON detours around them (the fix) and still reaches. The guards confirm the pad's head-level
     *  cell is a breakable obstruction over water, and the goal is Y-aware so padCellTax does not fire. */
    @Test
    void padOverWaterCross() {
        final int surface = 8, spanX = 16, halfZ = 3;
        final int[] padDx = { 6, 9, 12 };   // sparse single pads on the CENTER line
        BlockMapView w = deepCrossing(0, surface, spanX, halfZ);
        for (int dx : padDx) w.set(dx, surface + 1, 0, Blocks.LILY_PAD);
        BlockPos start = new BlockPos(-3, surface, 0);
        Goal goal = new Goal.Near(new BlockPos(spanX + 3, surface, 0), 1);   // Y-aware: not an XZ goal
        PlanningConfig.pathfinderMaxNodes = 1_000_000;

        BlockPos padBody = new BlockPos(padDx[1], surface + 1, 0), padFoot = new BlockPos(padDx[1], surface, 0);
        assertTrue(w.isWater(padFoot), "foot under the pad is NOT water at " + padFoot + "; the repro is vacuous");
        assertTrue(w.isBreakableObstruction(padBody), "pad head-level cell is NOT a breakable obstruction at " + padBody
                + "; the repro is vacuous");

        int[] padNodes = new int[2];
        boolean[] reached = new boolean[2];
        for (int leg = 0; leg < 2; leg++) {
            PlanningConfig.pathfinderPadOverWaterTax = leg == 1;
            PathFinder.Result r = PlanProbe.plan(w, start, goal);
            reached[leg] = r.goalReached();
            for (BlockPos p : r.path()) {
                if (p.getZ() != 0 || !w.isWater(p)) continue;
                for (int pd : padDx) if (p.getX() == pd) { padNodes[leg]++; break; }
            }
        }
        assertTrue(padNodes[0] >= 1, "with the tax OFF the plan did NOT thread any pad center-lane cell — the bug did not"
                + " reproduce; the straight crossing should pass through the sparse pads");
        assertEquals(0, padNodes[1], "with pathfinderPadOverWaterTax ON the plan STILL threads a pad center-lane cell —"
                + " the tax did not deflect A* around the sparse single pads");
        assertTrue(reached[1], "with the tax ON A* failed to reach the goal — the tax must DETOUR around the pads, never"
                + " forbid the crossing (a clear side lane exists)");
    }

    /** Planner A/B for the ADJACENT-pad-CLUSTER tax ({@code pathfinderPadClusterTax}). A pad WALL (7 pads,
     *  z=±3) with clear lanes only at z=±4: with only the flat single-pad tax a single dig (+20) beats the
     *  detour (+32) — the cluster surcharge flips it. Three regions in one arena: (1) the cluster wall A/B;
     *  (2) a lone pad (no single-pad regression); (3) a full-width wall (a tax, never a forbid → no
     *  stranding). Single-pad tax ON for every search. */
    @Test
    void padClusterCross() {
        final int surface = 8, spanX = 16, halfZ = 6, wallDx = 8, wallHalfZ = 3, loneDx = 13;
        BlockMapView w = deepCrossing(0, surface, spanX, halfZ);
        for (int dz = -wallHalfZ; dz <= wallHalfZ; dz++) w.set(wallDx, surface + 1, dz, Blocks.LILY_PAD);
        w.set(loneDx, surface + 1, 0, Blocks.LILY_PAD);   // the no-regression control
        BlockPos start = new BlockPos(-3, surface, 0);
        Goal goal = new Goal.Near(new BlockPos(spanX + 3, surface, 0), 1);   // Y-aware: not an XZ goal
        PlanningConfig.pathfinderMaxNodes = 1_000_000;

        BlockPos wallBody = new BlockPos(wallDx, surface + 1, 0), wallFoot = new BlockPos(wallDx, surface, 0);
        BlockPos wallSibN = new BlockPos(wallDx, surface + 1, 1);
        assertTrue(w.isWater(wallFoot), "foot under the wall is NOT water at " + wallFoot + "; the repro is vacuous");
        assertTrue(w.isBreakableObstruction(wallBody) && w.isBreakableObstruction(wallSibN), "wall pad cells are NOT"
                + " breakable obstructions (" + wallBody + " / " + wallSibN + "); the repro is vacuous");

        // REGION 1: the cluster wall A/B; the cluster flag is the variable.
        PlanningConfig.pathfinderPadOverWaterTax = true;
        int[] wallNodes = new int[2];
        boolean[] reached = new boolean[2];
        for (int leg = 0; leg < 2; leg++) {
            PlanningConfig.pathfinderPadClusterTax = leg == 1;
            PathFinder.Result r = PlanProbe.plan(w, start, goal);
            reached[leg] = r.goalReached();
            for (BlockPos p : r.path())
                if (p.getX() == wallDx && w.isWater(p) && Math.abs(p.getZ()) <= wallHalfZ) wallNodes[leg]++;
        }
        assertTrue(wallNodes[0] >= 1, "with the cluster tax OFF (flat single-pad tax only) the plan did NOT thread any"
                + " wall pad cell — the adjacent-cluster bug did not reproduce; the single dig should be cheaper than the"
                + " 3-deep detour");
        assertEquals(0, wallNodes[1], "with pathfinderPadClusterTax ON the plan STILL threads a wall pad cell — the"
                + " cluster surcharge did not deflect A* around the adjacent-pad wall");
        assertTrue(reached[1], "with the cluster tax ON A* failed to reach the goal — the surcharge must DETOUR around the"
                + " wall, never forbid the crossing (clear lanes at z=±4)");

        // REGION 2: the lone sparse pad — the cluster surcharge must not change single-pad routing.
        PlanningConfig.pathfinderPadClusterTax = true;
        PathFinder.Result lone = PlanProbe.plan(w, start, goal);
        long loneNodes = lone.path().stream().filter(p -> p.getX() == loneDx && p.getZ() == 0 && w.isWater(p)).count();
        assertEquals(0, loneNodes, "with the cluster tax ON the plan threads the LONE sparse pad — a lone pad should still"
                + " be detoured at the flat tax");

        // REGION 3: no clear lane — a tax, never a forbid.
        final int fullDx = 4;
        for (int dz = -halfZ; dz <= halfZ; dz++) w.set(fullDx, surface + 1, dz, Blocks.LILY_PAD);
        PathFinder.Result full = PlanProbe.plan(w, start, goal);
        long fullCross = full.path().stream().filter(p -> p.getX() == fullDx && w.isWater(p)).count();
        assertTrue(full.goalReached(), "with a FULL-WIDTH pad wall (no clear lane) the cluster tax made the goal"
                + " UNREACHABLE — it must be a TAX, never a forbid; A* must still thread the shortest line through");
        assertTrue(fullCross >= 1, "the full-width-wall search reached the goal WITHOUT crossing the wall column — the"
                + " no-lane region is not testing the through-the-wall thread");
    }
}
