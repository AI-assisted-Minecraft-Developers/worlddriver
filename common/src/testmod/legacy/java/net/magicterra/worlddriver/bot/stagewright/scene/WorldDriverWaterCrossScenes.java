package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;
import java.util.Locale;

import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts.PlanParams;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts.SinglePlan;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.bot.stagewright.Harness;
import net.magicterra.worlddriver.bot.stagewright.Routes;
import net.magicterra.worlddriver.bot.stagewright.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.ScenePlan;
import net.magicterra.worlddriver.bot.stagewright.Targets;
import net.magicterra.worlddriver.testcontent.TestMod;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Dogfooded worlddriver scenes — <b>P4b wave 4, the WaterCross family</b>: nine of the 10
 * {@code AgentGameTestWaterCross} open-water crossing / planner arenas (goal-snap /
 * anti-basin-dive depth penalty / floating-water climb-out gate / water-surface step-down
 * float / forbidDig pad-ram leak / deep submerged-cross surface bias / vine- & pad-over-water
 * taxes / pad-cluster tax), migrated verbatim to testkit {@code wd.*} scenes and their legacy
 * twins retired in the same commit. The tenth, the deep-water-float bee-line anchor gate, judged a
 * hand-made continuation over the world view and drove nothing; it is {@code DeepWaterFloatBeelineTest}
 * now, a unit test over a grid.
 *
 * <p>The planner arenas ask {@code mc.debug.plan} (the same bare search, run to completion) and read
 * cells through {@code mc.debug.cells}; the walking ones are an {@code mc.bot.goto} ticked one whole
 * bot tick at a time.
 *
 * <p><b>Porting is by the canonical pattern established in {@link WorldDriverScenes} /
 * {@link WorldDriverTerrainScenes} / {@link WorldDriverBiasScenes}</b> (study their class
 * javadocs for the full rationale — this class applies the same mechanical substitutions and
 * does not re-explain them):
 * <ul>
 *   <li>{@code helper.getLevel()} → {@link SceneContext#level()};</li>
 *   <li>absolute {@code cx/cz} → {@link SceneContext#origin()} X/Z (arena math
 *       byte-identical, just relocated to the harness grid cell);</li>
 *   <li>absolute Y constants → {@code origin.y + (legacy_Y − 200)}. Every grid origin sits at
 *       {@code GRID_Y = 200}, so the mapped ABSOLUTE Y equals the legacy absolute Y — the
 *       vertical water geometry is literally unchanged (A* is integer-cell, so a planner scene
 *       is position-invariant regardless). {@code wd.goalSnapBuried} keeps its legacy
 *       {@code floorY = 64} (mapped {@code origin.y − 136}); the rest sit at/above origin;</li>
 *   <li>{@code try/finally} per-key config save/restore → {@code Harness.pin(ctx)} (the pinned
 *       baseline, restored when the scene ends) then the SAME keys the legacy body flipped;</li>
 *   <li>{@code ServerPlayerBody.create(...)} → a per-scene roster bot ({@code SceneBot.spawn},
 *       removed when the scene ends; the two replay arms hand theirs a scripted plan through
 *       {@code mc.debug.walkPlan}). The
 *       legacy per-arena isolation batches drop out — a per-scene player cannot bleed into another
 *       scene;</li>
 *   <li>{@code AgentGameTestSupport.grantWaterEffects} → {@link SimProbes#grantWaterEffects};</li>
 *   <li>{@code AgentGameTestSupport.runSearch}/{@code maxPathY} → the inlined
 *       {@link #runSearch}/{@link #maxPathY} helpers below (faithful copies, promoted-into-class
 *       rather than imported across the neoforge testmod source-set boundary — the sibling wave-3
 *       {@code WorldDriverBiasScenes.maxPathY} is PRIVATE, so it cannot be shared here; this is
 *       the second in-package inline of the same one-liner, per the "each provider self-contains
 *       its needed helpers" precedent, not a third stray copy of a promoted symbol);</li>
 *   <li>{@code throw new GameTestAssertException(msg)} → {@link SceneContext#fail(String)}
 *       (prefixed with the scene's short name for multi-scene log attribution);</li>
 *   <li>{@code helper.succeed()} → normal return;</li>
 *   <li>the {@code gtOnlySkips(...)} probe first line → deleted (the testkit gate
 *       reconciles itself).</li>
 * </ul>
 *
 * <p><b>Lottery / optional governance.</b> The {@code deepwatercross*} family names flagged by
 * the wave brief as candidate lottery members are, in this class, either a WaterBank scene
 * ({@code wd.deepWaterCross} / {@code ad.deepWaterClimbout*} live there) or the pure-planner
 * {@code wd.deepWaterSubmergedCross} here. As a deterministic planner A/B (a committed A* plan
 * over a fixed world, no executor stepping) it is NOT subject to the shared-player flake, and it
 * ran identically across the ×2×2 dogfood — so it stays {@code required=true}. No WaterCross
 * scene was flaky across the ×2 runs; none is marked optional.
 *
 * <p><b>Origin slots + chunk radius.</b> All nine take AUTO slots. One overflows the radius-1
 * window (usable dx/dz {@code [−16,+31]}) and is widened: {@code wd.basin} needs
 * {@code .withChunkRadius(2)}: the plateau spans dz {@code 0..44} (goal at dz +40, plateau built
 * to dz +44), past the +31 edge — radius-2 window is {@code [−32,+47]}, which covers +44. No scene
 * is pinned to a fixed slot: every gate is a discrete planner outcome (node-in-lane / reached),
 * integer-cell and position-invariant.
 */
public final class WorldDriverWaterCrossScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.goalSnapBuried", 200, WorldDriverWaterCrossScenes::goalSnapBuried),
                Scene.of("wd.basin", 200, WorldDriverWaterCrossScenes::basin).withChunkRadius(2),
                Scene.of("wd.waterClimbOutRoute", 200, WorldDriverWaterCrossScenes::waterClimbOutRoute),
                Scene.of("wd.waterStepDownFloat", 200, WorldDriverWaterCrossScenes::waterStepDownFloat),
                Scene.of("wd.forbidDigPadRam", 200, WorldDriverWaterCrossScenes::forbidDigPadRam),
                Scene.of("wd.deepWaterSubmergedCross", 200, WorldDriverWaterCrossScenes::deepWaterSubmergedCross),
                Scene.of("wd.vineOverWaterCross", 200, WorldDriverWaterCrossScenes::vineOverWaterCross),
                Scene.of("wd.padOverWaterCross", 200, WorldDriverWaterCrossScenes::padOverWaterCross),
                Scene.of("wd.padClusterCross", 200, WorldDriverWaterCrossScenes::padClusterCross));
    }

    /** Ported from {@code AgentGameTestWaterCross#goalSnapBuriedArena}: a {@code goto} whose exact target
     *  is UNSTANDABLE (buried in a solid pillar) must snap to the nearest standable cell and ARRIVE, not
     *  oscillate forever. */
    private static void goalSnapBuried(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() - 136;   // legacy floorY 64 = origin.y-136
        // Flat stone floor the bot walks on; air above.
        for (int dx = -2; dx <= 14; dx++)
            for (int dz = -4; dz <= 4; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
                for (int y = floorY + 1; y <= floorY + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
            }
        // The goal column is a SOLID stone pillar → its foot cell is not standable, no 1.8-tall pocket.
        for (int y = floorY; y <= floorY + 5; y++)
            level.setBlockAndUpdate(new BlockPos(cx + 10, y, cz), Blocks.STONE.defaultBlockState());

        BlockPos buried = new BlockPos(cx + 10, floorY + 1, cz);   // solid → unstandable

        Harness.pin(ctx);
        Harness.set("navigatorDebug", false);
        Harness.untimed(ctx);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = bot.player();
        bot.goTo(Targets.block(buried));
        boolean walking = true;
        int arrivedTick = -1;
        for (int t = 0; t < 300 && walking; t++) {
            bot.tick();
            walking = bot.busy();
            if (!walking) { arrivedTick = t; break; }
        }
        String s = NavWalk.step(bot);
        bot.cancel();
        double dGoal = Math.hypot(fp.getX() - (cx + 10 + 0.5), fp.getZ() - (cz + 0.5));
        TestMod.LOG.info("[wd.goalSnapBuried] step={} pos=({},{},{}) arrivedTick={} dToBuried={}",
                s, fp.getX(), fp.getY(), fp.getZ(), arrivedTick, String.format("%.1f", dGoal));
        // Snap → ARRIVED at a standable cell adjacent to the buried pillar (within snap radius 6 + slack).
        if (walking)
            ctx.fail("goalSnapBuried: bot never arrived at a buried/unstandable goal"
                    + " (snap failed): step=" + s + " pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ")");
        if (dGoal > 7.0)
            ctx.fail("goalSnapBuried: arrived too far from the buried goal (d=" + dGoal + ")");
    }

    /** Ported from {@code AgentGameTestWaterCross#basinArena}: failure-case A/B for the anti-basin-dive
     *  {@code pathfinderDepthPenalty}. An XZ goal across a plateau; the direct corridor is a deep trap
     *  pocket. penalty=6 reaches via the rim + cuts node count vs penalty=0; under a budget BETWEEN the two
     *  counts penalty=0 fails while penalty=6 reaches. Pure planner. {@code .withChunkRadius(2)} (dz +44). */
    private static void basin(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int plY = ctx.origin().getY() + 40;   // legacy plY 240 = origin.y+40
        // Flat plateau (the go-around) across the whole arena.
        for (int dx = -12; dx <= 12; dx++)
            for (int dz = 0; dz <= 44; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, plY, cz + dz), Blocks.STONE.defaultBlockState());
                for (int y = plY + 1; y <= plY + 3; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
            }
        // Carve the valley corridor: clear it, lay a −1/step down-ramp and a deep floor. A dead-end pocket.
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = 8; dz <= 30; dz++)
                for (int y = plY - 13; y <= plY + 3; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = 8; dz <= 19; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, plY - (dz - 7), cz + dz), Blocks.STONE.defaultBlockState());
            for (int dz = 20; dz <= 30; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, plY - 12, cz + dz), Blocks.STONE.defaultBlockState());
        }
        BlockPos start = new BlockPos(cx, plY + 1, cz + 2);
        GotoContracts.Target goal = Targets.column(cx, cz + 40, 0);   // ignoresY → the dive-prone case

        Harness.pin(ctx);
        Harness.set("navigatorDebug", false);
        Harness.untimed(ctx);   // each search runs to completion (deterministic)
        Harness.set("pathfinder.maxNodes", 1_000_000);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, plY + 1, cz + 2);

        Harness.set("pathfinderDepthPenalty", 0);
        var r0 = runSearch(bot, start, goal);
        Harness.set("pathfinderDepthPenalty", 6);
        var r6 = runSearch(bot, start, goal);
        TestMod.LOG.info("[wd.basin] penalty0: reached={} expanded={} | penalty6: reached={} expanded={}",
                r0.goalReached(), r0.search().expanded(), r6.goalReached(), r6.search().expanded());
        if (!r6.goalReached())
            ctx.fail("basin: depthPenalty=6 failed to reach via the rim go-around");
        if (r6.search().expanded() >= r0.search().expanded())
            ctx.fail("basin: depthPenalty did not cut basin-dive exploration: "
                    + "penalty0 expanded=" + r0.search().expanded() + " penalty6 expanded=" + r6.search().expanded());

        // Failure-case: a node budget BETWEEN the two counts — penalty=0 burns it in the trap and FAILS;
        // penalty=6 reaches via the rim within it.
        int budget = (r0.search().expanded() + r6.search().expanded()) / 2;
        Harness.set("pathfinder.maxNodes", budget);
        Harness.set("pathfinderDepthPenalty", 0);
        var b0 = runSearch(bot, start, goal);
        Harness.set("pathfinderDepthPenalty", 6);
        var b6 = runSearch(bot, start, goal);
        TestMod.LOG.info("[wd.basin] budget={}: penalty0 reached={} | penalty6 reached={}",
                budget, b0.goalReached(), b6.goalReached());
        if (b0.goalReached() || !b6.goalReached())
            ctx.fail("basin: budget A/B not decisive: budget=" + budget
                    + " penalty0.reached=" + b0.goalReached() + " penalty6.reached=" + b6.goalReached());
    }

    /** Ported from {@code AgentGameTestWaterCross#waterClimbOutRouteArena}. Two exits at equal crossing
     *  distance: a +1 bank (jump-needed) and a surface-level (+0) flush bank.
     *
     *  <p>This used to pin the +1 exit as STRUCTURALLY unavailable to a floating player. It no longer is: a
     *  surface floater mounts a +1 dry bank on vanilla's collision boost, measured on the real client by
     *  {@code wd.clientFlushBankClimbOut}, and {@code StepUp} now offers that edge from the surface cell.
     *  What remains pinned is the PREFERENCE: with the climb-out tax at its default the flush exit must
     *  win (maxY ≤ wsurf); with the tax off the +1 exit is merely allowed, and both routes must reach.
     *  Pure planner. */
    private static void waterClimbOutRoute(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int wsurf = ctx.origin().getY() + 20;      // legacy wsurf 220 = origin.y+20; air at wsurf+1
        // Two-column pool, dx 0 and dx 1, dz 0..6: solid floor wsurf-2, water at wsurf-1 & wsurf.
        for (int dx = 0; dx <= 1; dx++)
            for (int dz = 0; dz <= 6; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, wsurf - 2, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, wsurf - 1, cz + dz), Blocks.WATER.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, wsurf, cz + dz), Blocks.WATER.defaultBlockState());
                for (int y = wsurf + 1; y <= wsurf + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
            }
        // dx 0 STRAIGHT exit: a +1 bank at dz=7 (solid top wsurf → stand foot wsurf+1).
        for (int y = wsurf - 2; y <= wsurf; y++)
            level.setBlockAndUpdate(new BlockPos(cx, y, cz + 7), Blocks.STONE.defaultBlockState());
        for (int y = wsurf + 1; y <= wsurf + 4; y++)
            level.setBlockAndUpdate(new BlockPos(cx, y, cz + 7), Blocks.AIR.defaultBlockState());
        // dx 1 GENTLE exit: pool fingers ONE cell farther north (dz=7 stays water), then a SURFACE-level
        // bank at dz=8 (solid top wsurf-1 → stand foot wsurf, rise 0).
        level.setBlockAndUpdate(new BlockPos(cx + 1, wsurf - 2, cz + 7), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(cx + 1, wsurf - 1, cz + 7), Blocks.WATER.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(cx + 1, wsurf, cz + 7), Blocks.WATER.defaultBlockState());
        for (int y = wsurf + 1; y <= wsurf + 4; y++)
            level.setBlockAndUpdate(new BlockPos(cx + 1, y, cz + 7), Blocks.AIR.defaultBlockState());
        // Flat land dz 8..18 at stand-foot wsurf (solid top wsurf-1), dx -1..2 — both exits converge here.
        for (int dx = -1; dx <= 2; dx++)
            for (int dz = 8; dz <= 18; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, wsurf - 1, cz + dz), Blocks.STONE.defaultBlockState());
                for (int y = wsurf; y <= wsurf + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
            }
        BlockPos start = new BlockPos(cx, wsurf, cz);       // floating at the pool's south end
        GotoContracts.Target goal = Targets.column(cx, cz + 15, 0);   // ignoresY → buoyant climb-out case

        Harness.pin(ctx);
        double oco = Harness.number("pathfinderWaterClimbOutCost");   // the configured default (baseline value)
        Harness.set("navigatorDebug", false);
        Harness.untimed(ctx);
        Harness.set("pathfinder.maxNodes", 1_000_000);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, wsurf, cz);

        // Tax OFF: the +1 exit is allowed (its maxY is recorded, not judged). Tax default: flush wins.
        Harness.set("pathfinderWaterClimbOutCost", 0);
        var rOff = runSearch(bot, start, goal);
        int maxYOff = maxPathY(rOff);
        Harness.set("pathfinderWaterClimbOutCost", oco);        // the configured default
        var rOn = runSearch(bot, start, goal);
        int maxYOn = maxPathY(rOn);
        TestMod.LOG.info("[wd.waterClimbOutRoute] taxOff: reached={} maxY={} | taxDefault({}): reached={} maxY={}",
                rOff.goalReached(), maxYOff, oco, rOn.goalReached(), maxYOn);
        if (!rOff.goalReached() || !rOn.goalReached())
            ctx.fail("waterClimbOutRoute: a climb-out route failed to reach: off=" + rOff.goalReached()
                    + " on=" + rOn.goalReached());
        ctx.record("taxOff.maxY", maxYOff + " (wsurf=" + wsurf + "; the +1 exit is allowed here, only the tax steers)");
        if (maxYOn > wsurf)
            ctx.fail("waterClimbOutRoute: floating-water +1 climb-out was NOT forbidden (tax default): maxY="
                    + maxYOn + " (expected ≤" + wsurf + ")");
    }

    /** Ported from {@code AgentGameTestWaterCross#waterStepDownFloatArena}: deterministic repro of the #47
     *  SHALLOW WATER-SURFACE STEP-DOWN bob-stall. The node is HEAD-WALLED so the seeded buoyant bot pins in
     *  the east-adjacent 1-deep water cell (cur2≈0.6-1.0, hCol). Run 0 (flag OFF) must WEDGE (step frozen —
     *  the live bug), run 1 (flag ON) must ADVANCE. Clean A/B on the step-advance gate; the only variable is
     *  {@code navigatorWaterStepDownFloat}. */
    private static void waterStepDownFloat(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY();   // legacy floorY 200 = origin.y
        final int waterY = floorY + 1;     // the 1-deep water surface (dirt floor at floorY)
        // Clear a generous air box for a clean slate.
        for (int dx = -10; dx <= 8; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int y = floorY - 2; y <= floorY + 8; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        // 1-WIDE channel along -X (the path runs WEST). Solid floor (dirt) at floorY, 1-deep water at waterY,
        // from the east approach (dx=+2) to west of the goal (dx=-7). Channel walls at dz=±1.
        for (int dx = -7; dx <= 2; dx++) {
            level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz), Blocks.DIRT.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(cx + dx, waterY, cz), Blocks.WATER.defaultBlockState());
            for (int dz = -1; dz <= 1; dz += 2)
                for (int y = floorY; y <= waterY + 3; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        }
        // NODE = the water-surface stepDown foothold at (cx, waterY, cz). HEAD-WALL the node so the buoyant
        // bot pins in the east cell ~0.6-1.0 b short.
        //
        // No lily pad in the east cell's head any more. Its 1.5/16 collision box leaves room for neither a
        // standing nor a crouching player, so vanilla's updatePlayerPose drops the player into the 0.6-tall
        // swimming pose, and that pose slides under the head-wall: the pumped server-side player reached the
        // node centre in 5 ticks and the OFF run measured nothing. The hand-integrated player never ran
        // updatePlayerPose, stayed standing, and pinned; the pin was the missing pose, not the pad.
        BlockPos node  = new BlockPos(cx,     waterY, cz);
        BlockPos cont  = new BlockPos(cx - 3, waterY, cz);   // continuation further WEST along the shelf
        BlockPos goalN = new BlockPos(cx - 6, waterY, cz);
        level.setBlockAndUpdate(node.above(), Blocks.STONE.defaultBlockState());   // head-wall at the node cell

        Harness.pin(ctx);
        Harness.set("allowBreak", false);          // no carving — the stall must be the buoyant reach, not a dig
        Harness.set("allowPlace", false);
        Harness.untimed(ctx);

        int[] advanceTick = { -1, -1 };    // first tick the step-pointer left the water node, per run
        int[] nodeDwell = new int[2];      // ticks the step-pointer sat ON the water node, per run
        boolean[] advanced = new boolean[2];
        double[] minCur2 = { Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY };
        // Run 0 = flag OFF (must WEDGE), run 1 = flag ON (must ADVANCE).
        for (int leg = 0; leg < 2; leg++) {
            Harness.set("navigatorWaterStepDownFloat", (leg == 1));
            Harness.set("navigatorDebug", true);
            // SEED the bot in the EAST-adjacent 1-deep water cell (cx+1), pressed WEST toward the head-walled
            // node, with a small west velocity. It grounds (foot=waterY) but its head is blocked → pins short.
            SceneBot bot = SceneBot.spawn(ctx, level, cx + 1.5, waterY, cz + 0.5);
            ServerPlayer fp = bot.player();
            fp.setDeltaMovement(-0.10, 0, 0);
            SimProbes.grantWaterEffects(fp);
            // Plan: step0 = east approach cell (cx+1) → step1 = NODE → cont → goal, all WEST along the shelf.
            // Replay mode: the safety repath is DISABLED, so the OFF run's wedge is a TRUE permanent stall
            // (no A* escape muddies the A/B).
            BlockPos approach = new BlockPos(cx + 1, waterY, cz);
            bot.walkPlan(DebugContracts.WalkPlanParams.replay(bot.ref(), padPlan(approach, node, cont, goalN))
                    .goal(Targets.block(goalN)).from(Targets.pos(approach)));

            boolean walking = true;
            int endStep = 0;
            for (int t = 0; t < 200 && walking; t++) {
                bot.tick();
                walking = bot.busy();
                BotDebugContracts.PlanCursor at = bot.navigator().plan().orElse(null);
                if (at == null) continue;
                endStep = at.step();
                if (at.node().map(Targets.pos(node)::equals).orElse(false)) {
                    double dx = (node.getX() + 0.5) - fp.getX();
                    double dz = (node.getZ() + 0.5) - fp.getZ();
                    minCur2[leg] = Math.min(minCur2[leg], dx * dx + dz * dz);
                    nodeDwell[leg]++;            // ticks the step-pointer sat ON the water node
                }
                // ADVANCE = the step-pointer moved PAST the water node (step>=2).
                if (advanceTick[leg] < 0 && at.step() >= 2) {
                    advanceTick[leg] = t;
                    advanced[leg] = true;
                }
            }
            TestMod.LOG.info("[wd.waterStepDownFloat] leg={} flagOn={} advanced={} advanceTick={} nodeDwell={} endStep={} minCur2={} endPos=({},{},{}) walking={}",
                    leg, leg == 1, advanced[leg], advanceTick[leg], nodeDwell[leg], endStep,
                    String.format(Locale.ROOT, "%.3f", minCur2[leg]),
                    String.format(Locale.ROOT, "%.2f", fp.getX()), String.format(Locale.ROOT, "%.2f", fp.getY()),
                    String.format(Locale.ROOT, "%.2f", fp.getZ()), walking);
            bot.remove();
        }
        // CONFIRM the pin reproduced: the OFF run must have stayed above the tight reach gate.
        if (minCur2[0] < 0.45)
            ctx.fail("waterStepDownFloat: the head-wall did NOT reproduce the buoyant pin "
                    + "(OFF minCur2=" + String.format(Locale.ROOT, "%.3f", minCur2[0]) + " < REACH_DIST_SQ=0.45 → the bot reached "
                    + "the node centre on its own, so there is no water-surface stall to exercise). Re-tune the seed/geometry.");
        // OFF must WEDGE — the step-pointer NEVER advances past the water node.
        if (advanced[0])
            ctx.fail("waterStepDownFloat: with the fix OFF the step-pointer ADVANCED past "
                    + "the water-surface stepDown node (advanceTick=" + advanceTick[0] + ") — the bug did not reproduce; the "
                    + "OFF run must stay pinned (within needs cur2<0.45, unreachable at the buoyant pin).");
        // ON must ADVANCE promptly.
        if (!advanced[1])
            ctx.fail("waterStepDownFloat: with navigatorWaterStepDownFloat ON the step-pointer "
                    + "FAILED to advance past the water-surface stepDown node (pinned " + nodeDwell[1] + " ticks) — the fix did "
                    + "not fire. minCur2_ON=" + String.format(Locale.ROOT, "%.3f", minCur2[1]));
        if (advanceTick[1] > 40)
            ctx.fail("waterStepDownFloat: with the fix ON the advance was SLOW (advanceTick="
                    + advanceTick[1] + " > 40) — the relaxation should fire shortly after the stall gate (~"
                    + "WATER_STEPDOWN_STALL_TICKS), not drift.");
    }

    /** Ported from {@code AgentGameTestWaterCross#forbidDigPadRamArena}: the engine-gap per-goto
     *  {@code forbidDig} execution-layer leak — the lily-pad head-on break, the one executor dig-fallback
     *  that reproduces under a clean {@code break: never} plan. Phase A ({@code break: never}): the pad MUST survive
     *  (pre-fix RED = the head-on break punches it despite forbidDig). Phase B (no constraint): the pad MUST
     *  be removed (proof the gate is precise). {@code allowBreak=true} throughout — the leak precondition. */
    private static void forbidDigPadRam(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY();   // legacy floorY 200 = origin.y
        final int waterY = floorY + 1;
        for (int dx = -10; dx <= 8; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int y = floorY - 2; y <= floorY + 8; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        for (int dx = -7; dx <= 2; dx++) {
            level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz), Blocks.DIRT.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(cx + dx, waterY, cz), Blocks.WATER.defaultBlockState());
            for (int dz = -1; dz <= 1; dz += 2)
                for (int y = floorY; y <= waterY + 3; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        }
        BlockPos node  = new BlockPos(cx,     waterY, cz);
        BlockPos cont  = new BlockPos(cx - 3, waterY, cz);
        BlockPos goalN = new BlockPos(cx - 6, waterY, cz);
        BlockPos padCell = new BlockPos(cx + 1, waterY + 1, cz);   // the pinned bot's OWN head cell
        Runnable setup = () -> {
            // Wall across the channel at the node, foot and head: the head block alone pinned only an upright
            // treader, and a sprint-swimmer (0.6 tall) slid under it; the pass came from scraping the side wall.
            level.setBlockAndUpdate(node, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(node.above(), Blocks.STONE.defaultBlockState());       // head-wall → deterministic pin
            level.setBlockAndUpdate(padCell, Blocks.LILY_PAD.defaultBlockState());
        };
        Harness.pin(ctx);
        Harness.set("allowBreak", true);             // leak precondition: only forbidDig should stop the pad punch
        Harness.set("allowPlace", false);
        Harness.set("navigatorDebug", false);
        Harness.set("navigatorWallDigFallback", false); // irrelevant in water; isolate the pad break
        Harness.untimed(ctx);

        // Phase A: forbidDig (NoBreak) — the pad MUST survive.
        setup.run();
        boolean survivedA = runPadLeg(ctx, level, cx, cz, waterY, node, cont, goalN,
                Routes.plain().noBreak().build(), padCell);
        // Phase B: no constraint (precision) — the head-on break MUST fire, removing the pad.
        setup.run();
        boolean survivedB = runPadLeg(ctx, level, cx, cz, waterY, node, cont, goalN,
                Routes.plain().build(), padCell);
        TestMod.LOG.info("[wd.forbidDigPadRam] survivedA(forbidDig)={} survivedB(plain)={}", survivedA, survivedB);
        if (!survivedA)
            ctx.fail("forbidDigPadRam: forbidDig LEAK: the head-on lily-pad break punched the pad despite NoBreak "
                    + "(pad removed pre-fix). Under forbidDig the pad MUST survive.");
        if (survivedB)
            ctx.fail("forbidDigPadRam: precision guard: WITHOUT forbidDig the head-on pad break must still fire "
                    + "(pad removed) — the pad survived, so the gate OVER-KILLED legitimate pad clearance.");
    }

    /** Seed the buoyant pin, drive the scripted plan under {@code route}, return whether the lily pad at
     *  {@code padCell} SURVIVED (still a lily pad) after 120 ticks. See {@link #forbidDigPadRam}. */
    private static boolean runPadLeg(SceneContext ctx, ServerLevel level, int cx, int cz, int waterY,
                                     BlockPos node, BlockPos cont, BlockPos goalN,
                                     RouteContracts.Route route, BlockPos padCell) {
        SceneBot bot = SceneBot.spawn(ctx, level, cx + 1.5, waterY, cz + 0.5);
        ServerPlayer fp = bot.player();
        fp.setDeltaMovement(-0.10, 0, 0);        // residual west approach momentum → press into the head-wall
        SimProbes.grantWaterEffects(fp);
        BlockPos approach = new BlockPos(cx + 1, waterY, cz);
        bot.walkPlan(DebugContracts.WalkPlanParams.replay(bot.ref(), padPlan(approach, node, cont, goalN))
                .goal(Targets.block(goalN)).from(Targets.pos(approach)).route(route));
        bot.tickUntilIdle(120);
        bot.remove();
        return level.getBlockState(padCell).is(Blocks.LILY_PAD);
    }

    /** The scripted crossing both water-surface arenas replay: approach, the stepDown onto the node, on west. */
    private static List<DebugContracts.PlanEdge> padPlan(BlockPos approach, BlockPos node, BlockPos cont, BlockPos goalN) {
        return List.of(
                ScenePlan.edge(approach, "walk"),
                ScenePlan.edge(node, "stepDown"),      // the water-surface foothold
                ScenePlan.edge(cont, "walk"),
                ScenePlan.edge(goalN, "walk"));
    }

    /** Ported from {@code AgentGameTestWaterCross#deepWaterSubmergedCrossArena}: deep-water submerged-crossing
     *  surface-bias ({@code pathfinderFloatingSurfaceCross}). A deep (9-block) open-water channel; the search
     *  is seeded submerged (surface-1) for a Y-aware Near goal on the far bank. Part A (planner A/B): the legacy
     *  water model with the bias OFF threads MANY submerged crossing nodes (the bug); the surface-node model
     *  with the bias ON surfaces immediately. Part B (integration): the floating Navigator crosses and reaches the
     *  far bank, spending almost no ticks below the surface. */
    private static void deepWaterSubmergedCross(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY();   // legacy floorY 200 = origin.y
        final int surface = floorY + 9;            // deep-water surface (floor at floorY → 9 deep)
        final int spanX = 24;                      // E-W open-water crossing length
        final int halfZ = 5;                       // open water half-width in Z
        // Clear a generous air box.
        for (int dx = -5; dx <= spanX + 7; dx++)
            for (int dz = -halfZ - 2; dz <= halfZ + 2; dz++)
                for (int y = floorY - 1; y <= surface + 6; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        // DEEP basin floor (floorY) under the whole arena.
        for (int dx = -3; dx <= spanX + 7; dx++)
            for (int dz = -halfZ - 1; dz <= halfZ + 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // Deep water filling the basin up to `surface` (dx=-1..spanX+1).
        for (int dx = -1; dx <= spanX + 1; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
        // SHALLOW WADE shelf at the EAST end (floor raised to surface-2 → 1-deep water = grounded).
        for (int dx = spanX - 1; dx <= spanX; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface - 1; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        // Dry banks at each end (top at surface-1, flush with the wade shelf) — start ground / goal.
        for (int dx = -5; dx <= -2; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface - 1; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        for (int dx = spanX + 1; dx <= spanX + 6; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface - 1; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        // Lily pad at surface+1 mid-crossing — incidental scenery only.
        level.setBlockAndUpdate(new BlockPos(cx + spanX / 2, surface + 1, cz), Blocks.LILY_PAD.defaultBlockState());

        // SUBMERGED search seed: one cell INTO the deep water at surface-1 (floating, submerged).
        BlockPos seed = new BlockPos(cx + 1, surface - 1, cz);
        // Y-AWARE Near goal on the dry east bank.
        GotoContracts.Target goal = Targets.near(new BlockPos(cx + spanX + 4, surface - 1, cz), 1);

        Harness.pin(ctx);
        Harness.set("allowBreak", false);          // pure swim/walk — the route choice must be the gate, not a dig
        Harness.set("allowPlace", false);
        Harness.untimed(ctx);   // deterministic: each search completes in one go

        // ---- Part A: predicate A/B on the committed plan ----
        int[] subNodes = new int[2];
        boolean[] reached = new boolean[2];
        for (int leg = 0; leg < 2; leg++) {
            Harness.set("pathfinderFloatingSurfaceCross", (leg == 1));
            // The bug lives in the legacy model, where every water cell is a node; run 0 reproduces it
            // there. Under the surface-node model (run 1) a submerged crossing cannot be planned at all.
            Harness.set("pathfinderSurfaceWaterNodes", (leg == 1));
            SceneBot probe = SceneBot.spawn(ctx, level, cx + 1.5, surface - 1, cz + 0.5);
            ServerPlayer fp = probe.player();
            SimProbes.grantWaterEffects(fp);
            fp.getInventory().clearContent();
            SinglePlan r = runSearch(probe, seed, goal);
            reached[leg] = r.goalReached();
            List<BlockPosition> path = r.cells();
            List<BlockPosition> above = path.stream().map(p -> new BlockPosition(p.x(), p.y() + 1, p.z())).toList();
            List<BotDebugContracts.Cell> at = NavWalk.cells(probe, path), over = NavWalk.cells(probe, above);
            for (int i = 0; i < path.size(); i++)
                if (path.get(i).y() < surface && at.get(i).water() && over.get(i).water()) subNodes[leg]++;
            TestMod.LOG.info("[wd.deepWaterSubmergedCross] legA flagOn={} reached={} pathLen={} submergedNodes={}",
                    leg == 1, r.goalReached(), r.pathLen(), subNodes[leg]);
        }
        // OFF must reproduce the bug: the committed plan threads the crossing SUBMERGED (many nodes).
        if (subNodes[0] < 10)
            ctx.fail("deepWaterSubmergedCross: with the flag OFF the plan did NOT thread the "
                    + "submerged crossing (submergedNodes=" + subNodes[0] + " < 10) — the bug did not reproduce; the buoyant "
                    + "deep-water entry should route the whole crossing one below the surface. Re-tune the geometry/seed.");
        // ON must surface: at most the start cell stays submerged.
        if (subNodes[1] > 1)
            ctx.fail("deepWaterSubmergedCross: with pathfinderFloatingSurfaceCross ON the plan STILL "
                    + "threads submerged crossing nodes (submergedNodes=" + subNodes[1] + " > 1) — the surface bias did not fire; "
                    + "A* must surface and cross on top.");
        if (!reached[1])
            ctx.fail("deepWaterSubmergedCross: with the flag ON A* failed to reach the goal "
                    + "(the surface route must still solve the crossing).");

        // ---- Part B: integration — the floating Navigator crosses cleanly with the flag ON ----
        Harness.set("pathfinderFloatingSurfaceCross", true);
        Harness.set("pathfinderSurfaceWaterNodes", true);
        Harness.set("navigatorDebug", true);
        SceneBot bot = SceneBot.spawn(ctx, level, cx + 1.5, surface - 1, cz + 0.5);
        ServerPlayer fp = bot.player();
        SimProbes.grantWaterEffects(fp);
        fp.getInventory().clearContent();
        bot.goTo(goal);
        int submergedTicks = 0;
        double maxX = fp.getX();
        boolean walking = true;
        for (int t = 0; t < 1200 && walking; t++) {
            bot.tick();
            walking = bot.busy();
            maxX = Math.max(maxX, fp.getX());
            BlockPos foot = BlockPos.containing(fp.getX(), fp.getY() + 0.1, fp.getZ());
            if (fp.isInWater() && foot.getY() < surface - 1 && NavWalk.cell(bot, foot.above()).water()) submergedTicks++;
        }
        String st = NavWalk.step(bot);
        bot.cancel();
        boolean ashore = !fp.isInWater() && fp.getX() >= cx + spanX + 1 - 0.5;
        TestMod.LOG.info("[wd.deepWaterSubmergedCross] legB ashore={} step={} pos=({},{},{}) maxX={} submergedTicks={}",
                ashore, st, String.format(Locale.ROOT, "%.2f", fp.getX()), String.format(Locale.ROOT, "%.2f", fp.getY()),
                String.format(Locale.ROOT, "%.2f", fp.getZ()), String.format(Locale.ROOT, "%.2f", maxX), submergedTicks);
        if (!ashore)
            ctx.fail("deepWaterSubmergedCross: with the flag ON the floating Navigator failed to cross "
                    + "and reach the far bank: pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") maxX=" + maxX + " step=" + st);
        if (submergedTicks > 40)
            ctx.fail("deepWaterSubmergedCross: with the flag ON the bot spent " + submergedTicks
                    + " ticks pinned below the surface (>40) — the surface crossing should keep it on top.");
    }

    /** Ported from {@code AgentGameTestWaterCross#vineOverWaterCrossArena}: planner A/B for the vine/leaf-
     *  canopy-OVER-DEEP-WATER tax ({@code pathfinderVineOverWaterTax}). A tree canopy grows in a 1-cell CENTER
     *  lane over a deep crossing; OFF threads the center lane (bug), ON detours around it (fix) and still
     *  reaches. #1 silent-no-op guards confirm the center head-level cell is a vine (climbable, not leaves, not a
     *  breakable obstruction). Pure planner. */
    private static void vineOverWaterCross(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY();   // legacy floorY 200 = origin.y
        final int surface = floorY + 8;            // deep-water surface (floor at floorY → 8 deep)
        final int spanX = 16;                      // E-W open-water crossing length
        final int halfZ = 3;                       // open water half-width in Z (room for a side detour)
        final int treeX0 = 5, treeX1 = 11;         // tree-canopy band over the CENTER lane

        // Clear a generous air box.
        for (int dx = -5; dx <= spanX + 7; dx++)
            for (int dz = -halfZ - 2; dz <= halfZ + 2; dz++)
                for (int y = floorY - 1; y <= surface + 8; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        // DEEP basin floor (floorY) under the whole arena.
        for (int dx = -3; dx <= spanX + 7; dx++)
            for (int dz = -halfZ - 1; dz <= halfZ + 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // Deep water filling the basin up to `surface` (dx=-1..spanX+1), the full Z width.
        for (int dx = -1; dx <= spanX + 1; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
        // Dry banks at each end, top at surface-1 → the walkable bank FOOT is `surface`, FLUSH with the water.
        for (int dx = -5; dx <= -1; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface - 1; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        for (int dx = spanX + 1; dx <= spanX + 6; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface - 1; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        // GROUNDED wade shelf one cell into the water at each bank (floor raised to surface-1).
        for (int dx = -1; dx <= spanX + 1; dx += spanX + 2)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, surface - 1, cz + dz), Blocks.STONE.defaultBlockState());

        // TREE-CANOPY band over the center lane (z=cz), treeX0..treeX1: surface+1 = hanging vine (head cell),
        // surface+2/+3 = oak-leaf canopy. Vine hangs DOWN from the leaf above. Lily pads at z=cz±2 (scenery).
        BlockState leaf = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, Boolean.TRUE);
        BlockState vineHang = Blocks.VINE.defaultBlockState().setValue(VineBlock.UP, Boolean.TRUE);
        for (int dx = treeX0; dx <= treeX1; dx++) {
            level.setBlockAndUpdate(new BlockPos(cx + dx, surface + 3, cz), leaf);
            level.setBlockAndUpdate(new BlockPos(cx + dx, surface + 2, cz), leaf);
            level.setBlockAndUpdate(new BlockPos(cx + dx, surface + 1, cz), vineHang);   // head-level vine
            level.setBlockAndUpdate(new BlockPos(cx + dx, surface + 1, cz - 2), Blocks.LILY_PAD.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(cx + dx, surface + 1, cz + 2), Blocks.LILY_PAD.defaultBlockState());
        }

        BlockPos start = new BlockPos(cx - 3, surface, cz);
        GotoContracts.Target goal = Targets.near(new BlockPos(cx + spanX + 3, surface, cz), 1);

        Harness.pin(ctx);
        Harness.set("navigatorDebug", false);
        Harness.untimed(ctx);   // deterministic: each search completes in one go
        Harness.set("pathfinder.maxNodes", 1_000_000);

        SceneBot bot = SceneBot.spawn(ctx, level, cx - 2.5, surface, cz + 0.5);
        ServerPlayer fp = bot.player();
        SimProbes.grantWaterEffects(fp);
        fp.getInventory().clearContent();

        // #1 silent-no-op guards.
        BlockPos bodyMid = new BlockPos(cx + (treeX0 + treeX1) / 2, surface + 1, cz);   // a vine cell
        BlockPos capMid  = new BlockPos(cx + (treeX0 + treeX1) / 2, surface + 2, cz);    // a leaf cell
        BotDebugContracts.Cell body = NavWalk.cell(bot, bodyMid);
        if (!body.climbable())
            ctx.fail("vineOverWaterCross: center head-level cell is NOT climbable (vine) at "
                    + bodyMid + " — the vine did not survive setBlockAndUpdate; the repro is vacuous.");
        if (body.leaves())
            ctx.fail("vineOverWaterCross: center head-level cell reads as LEAVES at " + bodyMid
                    + " — leafCellTax would already catch it and the new tax would be redundant; expected a VINE.");
        if (body.breakableObstruction())
            ctx.fail("vineOverWaterCross: center head-level vine reads as a breakable obstruction at "
                    + bodyMid + " — padCellTax would already catch it; a vine must have no collision shape.");
        if (!NavWalk.cell(bot, capMid).leaves())
            ctx.fail("vineOverWaterCross: leaf canopy missing at " + capMid + ".");

        // A/B: OFF threads the center lane (bug), ON detours around it (fix).
        int[] laneNodes = new int[2];
        boolean[] reached = new boolean[2];
        for (int leg = 0; leg < 2; leg++) {
            Harness.set("pathfinderVineOverWaterTax", (leg == 1));
            SinglePlan r = runSearch(bot, start, goal);
            reached[leg] = r.goalReached();
            List<BlockPosition> path = r.cells();
            List<BotDebugContracts.Cell> cells = pathCells(bot, r);
            for (int i = 0; i < path.size(); i++) {
                int rx = path.get(i).x() - cx, rz = path.get(i).z() - cz;
                if (rz == 0 && rx >= treeX0 && rx <= treeX1 && cells.get(i).water()) laneNodes[leg]++;
            }
            TestMod.LOG.info("[wd.vineOverWaterCross] flagOn={} reached={} pathLen={} centerLaneNodes={}",
                    leg == 1, r.goalReached(), r.pathLen(), laneNodes[leg]);
        }
        if (laneNodes[0] < 3)
            ctx.fail("vineOverWaterCross: with the tax OFF the plan did NOT thread the "
                    + "vine/leaf center lane (centerLaneNodes=" + laneNodes[0] + " < 3) — the bug did not reproduce; "
                    + "the straight crossing should pass through the canopy band. Re-tune the geometry.");
        if (laneNodes[1] != 0)
            ctx.fail("vineOverWaterCross: with pathfinderVineOverWaterTax ON the plan STILL "
                    + "threads the vine/leaf center lane (centerLaneNodes=" + laneNodes[1] + " > 0) — the tax did not "
                    + "deflect A* around the tree-over-water cluster.");
        if (!reached[1])
            ctx.fail("vineOverWaterCross: with the tax ON A* failed to reach the goal — the "
                    + "tax must DETOUR around the tree, never forbid the crossing (a clear side lane exists).");
    }

    /** Ported from {@code AgentGameTestWaterCross#padOverWaterCrossArena}: planner A/B for the SPARSE-single-
     *  lily-pad-OVER-DEEP-WATER tax ({@code pathfinderPadOverWaterTax}), the Y-aware-goal sibling of the
     *  XZ-only padCellTax. Sparse single pads on the center line; OFF threads them (bug), ON detours around
     *  (fix) and still reaches. #1 guards confirm the pad's head-level cell is a breakable obstruction AND padCellTax does
     *  not fire on the Y-aware goal. Pure planner. */
    private static void padOverWaterCross(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY();   // legacy floorY 200 = origin.y
        final int surface = floorY + 8;            // deep-water surface (floor at floorY → 8 deep)
        final int spanX = 16;                      // E-W open-water crossing length
        final int halfZ = 3;                       // open water half-width in Z (room for a side detour)
        final int[] padDx = { 6, 9, 12 };          // sparse single pads on the CENTER line

        // Clear a generous air box.
        for (int dx = -5; dx <= spanX + 7; dx++)
            for (int dz = -halfZ - 2; dz <= halfZ + 2; dz++)
                for (int y = floorY - 1; y <= surface + 8; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        // DEEP basin floor (floorY) under the whole arena.
        for (int dx = -3; dx <= spanX + 7; dx++)
            for (int dz = -halfZ - 1; dz <= halfZ + 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // Deep water filling the basin up to `surface` (dx=-1..spanX+1), the full Z width.
        for (int dx = -1; dx <= spanX + 1; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
        // Dry banks at each end, top at surface-1 → the walkable bank FOOT is `surface`, FLUSH with the water.
        for (int dx = -5; dx <= -1; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface - 1; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        for (int dx = spanX + 1; dx <= spanX + 6; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface - 1; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        // GROUNDED wade shelf one cell into the water at each bank (floor raised to surface-1).
        for (int dx = -1; dx <= spanX + 1; dx += spanX + 2)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, surface - 1, cz + dz), Blocks.STONE.defaultBlockState());

        // Sparse SINGLE lily pads on the center line, at the WATER surface (surface+1), z=cz only.
        for (int dx : padDx)
            level.setBlockAndUpdate(new BlockPos(cx + dx, surface + 1, cz), Blocks.LILY_PAD.defaultBlockState());

        BlockPos start = new BlockPos(cx - 3, surface, cz);
        GotoContracts.Target goal = Targets.near(new BlockPos(cx + spanX + 3, surface, cz), 1);

        Harness.pin(ctx);
        Harness.set("navigatorDebug", false);
        Harness.untimed(ctx);   // deterministic: each search completes in one go
        Harness.set("pathfinder.maxNodes", 1_000_000);

        SceneBot bot = SceneBot.spawn(ctx, level, cx - 2.5, surface, cz + 0.5);
        ServerPlayer fp = bot.player();
        SimProbes.grantWaterEffects(fp);
        fp.getInventory().clearContent();

        // #1 silent-no-op guards.
        BlockPos padBody = new BlockPos(cx + padDx[1], surface + 1, cz);   // a lily-pad cell
        BlockPos padFoot = new BlockPos(cx + padDx[1], surface, cz);       // the water cell under it
        if (!NavWalk.cell(bot, padFoot).water())
            ctx.fail("padOverWaterCross: foot under the pad is NOT water at " + padFoot
                    + " — the pad did not land on a water surface cell; the repro is vacuous.");
        if (!NavWalk.cell(bot, padBody).breakableObstruction())
            ctx.fail("padOverWaterCross: pad head-level cell is NOT a breakable obstruction at "
                    + padBody + " — the lily pad did not survive setBlockAndUpdate; the repro is vacuous.");
        if (ignoresY(goal))
            ctx.fail("padOverWaterCross: the goal reads as ignoresY (XZ) — padCellTax "
                    + "would already fire and the new goal-neutral tax would be redundant; expected a Y-aware goal.");

        // A/B: OFF threads the center lane through the pads (bug), ON detours around them (fix).
        int[] padNodes = new int[2];
        boolean[] reached = new boolean[2];
        for (int leg = 0; leg < 2; leg++) {
            Harness.set("pathfinderPadOverWaterTax", (leg == 1));
            SinglePlan r = runSearch(bot, start, goal);
            reached[leg] = r.goalReached();
            List<BlockPosition> path = r.cells();
            List<BotDebugContracts.Cell> cells = pathCells(bot, r);
            for (int i = 0; i < path.size(); i++) {
                if (path.get(i).z() != cz || !cells.get(i).water()) continue;
                int rx = path.get(i).x() - cx;
                for (int pd : padDx) if (rx == pd) { padNodes[leg]++; break; }
            }
            TestMod.LOG.info("[wd.padOverWaterCross] flagOn={} reached={} pathLen={} padLaneNodes={}",
                    leg == 1, r.goalReached(), r.pathLen(), padNodes[leg]);
        }
        if (padNodes[0] < 1)
            ctx.fail("padOverWaterCross: with the tax OFF the plan did NOT thread any "
                    + "pad center-lane cell (padLaneNodes=" + padNodes[0] + " < 1) — the bug did not reproduce; "
                    + "the straight crossing should pass through the sparse pads. Re-tune the geometry.");
        if (padNodes[1] != 0)
            ctx.fail("padOverWaterCross: with pathfinderPadOverWaterTax ON the plan STILL "
                    + "threads a pad center-lane cell (padLaneNodes=" + padNodes[1] + " > 0) — the tax did not "
                    + "deflect A* around the sparse single pads.");
        if (!reached[1])
            ctx.fail("padOverWaterCross: with the tax ON A* failed to reach the goal — the "
                    + "tax must DETOUR around the pads, never forbid the crossing (a clear side lane exists).");
    }

    /** Ported from {@code AgentGameTestWaterCross#padClusterCrossArena}: planner A/B for the ADJACENT-pad-
     *  CLUSTER tax ({@code pathfinderPadClusterTax}). A pad WALL (7 pads, z=cz±3) with clear lanes only at
     *  z=cz±4: with only the flat single-pad tax a single dig (+20) beats the detour (+32) — the cluster
     *  surcharge flips it. Three regions in one arena: (1) cluster wall A/B; (2) a lone pad (no single-pad
     *  regression); (3) a full-width wall (a tax, never a forbid → no stranding). Single-pad tax ON for both
     *  runs. Pure planner. */
    private static void padClusterCross(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY();   // legacy floorY 200 = origin.y
        final int surface = floorY + 8;            // deep-water surface (floor at floorY → 8 deep)
        final int spanX = 16;                      // E-W open-water crossing length
        final int halfZ = 6;                       // open water half-width in Z: room for a 4-deep detour
        final int wallDx = 8;                      // the pad WALL sits at this mid-crossing X column
        final int wallHalfZ = 3;                   // wall spans z=cz-3..cz+3 (7 pads) → clear lanes only at cz±4

        // Clear a generous air box.
        for (int dx = -5; dx <= spanX + 7; dx++)
            for (int dz = -halfZ - 2; dz <= halfZ + 2; dz++)
                for (int y = floorY - 1; y <= surface + 8; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        // DEEP basin floor (floorY) under the whole arena.
        for (int dx = -3; dx <= spanX + 7; dx++)
            for (int dz = -halfZ - 1; dz <= halfZ + 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // Deep water filling the basin up to `surface` (dx=-1..spanX+1), the full Z width.
        for (int dx = -1; dx <= spanX + 1; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
        // Dry banks at each end, top at surface-1 → the walkable bank FOOT is `surface`, FLUSH with the water.
        for (int dx = -5; dx <= -1; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface - 1; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        for (int dx = spanX + 1; dx <= spanX + 6; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface - 1; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        // GROUNDED wade shelf one cell into the water at each bank (floor raised to surface-1).
        for (int dx = -1; dx <= spanX + 1; dx += spanX + 2)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, surface - 1, cz + dz), Blocks.STONE.defaultBlockState());

        // The ADJACENT pad CLUSTER: a WALL of lily pads one X-column thick at wallDx, spanning z=cz-3..cz+3.
        for (int dz = -wallHalfZ; dz <= wallHalfZ; dz++)
            level.setBlockAndUpdate(new BlockPos(cx + wallDx, surface + 1, cz + dz), Blocks.LILY_PAD.defaultBlockState());

        // A LONE sparse pad farther east on the center line (z=cz), the no-regression control.
        final int loneDx = 13;
        level.setBlockAndUpdate(new BlockPos(cx + loneDx, surface + 1, cz), Blocks.LILY_PAD.defaultBlockState());

        BlockPos start = new BlockPos(cx - 3, surface, cz);
        GotoContracts.Target goal = Targets.near(new BlockPos(cx + spanX + 3, surface, cz), 1);

        Harness.pin(ctx);
        Harness.set("navigatorDebug", false);
        Harness.untimed(ctx);   // deterministic: each search completes in one go
        Harness.set("pathfinder.maxNodes", 1_000_000);

        SceneBot bot = SceneBot.spawn(ctx, level, cx - 2.5, surface, cz + 0.5);
        ServerPlayer fp = bot.player();
        SimProbes.grantWaterEffects(fp);
        fp.getInventory().clearContent();

        // #1 silent-no-op guards.
        BlockPos wallBody = new BlockPos(cx + wallDx, surface + 1, cz);   // the wall's centre lily-pad cell
        BlockPos wallFoot = new BlockPos(cx + wallDx, surface, cz);       // the water cell under it
        BlockPos wallSibN = new BlockPos(cx + wallDx, surface + 1, cz + 1);
        if (!NavWalk.cell(bot, wallFoot).water())
            ctx.fail("padClusterCross: foot under the wall is NOT water at " + wallFoot
                    + " — the repro is vacuous.");
        if (!NavWalk.cell(bot, wallBody).breakableObstruction() || !NavWalk.cell(bot, wallSibN).breakableObstruction())
            ctx.fail("padClusterCross: wall pad cells are NOT breakable obstructions ("
                    + wallBody + " / " + wallSibN + ") — the lily-pad wall did not survive setBlockAndUpdate; "
                    + "the repro is vacuous.");
        if (ignoresY(goal))
            ctx.fail("padClusterCross: the goal reads as ignoresY (XZ) — padCellTax would "
                    + "already fire; expected a Y-aware goal.");

        // ---- REGION 1: the CLUSTER WALL A/B (single-pad tax ON for BOTH runs; CLUSTER flag is the variable).
        Harness.set("pathfinderPadOverWaterTax", true);        // the shipped single-pad fix stays ON for the A/B
        int[] wallNodes = new int[2];
        boolean[] reached = new boolean[2];
        for (int leg = 0; leg < 2; leg++) {
            Harness.set("pathfinderPadClusterTax", (leg == 1));
            SinglePlan r = runSearch(bot, start, goal);
            reached[leg] = r.goalReached();
            List<BlockPosition> path = r.cells();
            List<BotDebugContracts.Cell> cells = pathCells(bot, r);
            for (int i = 0; i < path.size(); i++) {
                if (path.get(i).x() - cx != wallDx || !cells.get(i).water()) continue;
                int rz = path.get(i).z() - cz;
                if (rz >= -wallHalfZ && rz <= wallHalfZ) wallNodes[leg]++;
            }
            TestMod.LOG.info("[wd.padClusterCross] region=wall clusterOn={} reached={} pathLen={} wallPadNodes={}",
                    leg == 1, r.goalReached(), r.pathLen(), wallNodes[leg]);
        }
        if (wallNodes[0] < 1)
            ctx.fail("padClusterCross: with the cluster tax OFF (flat single-pad tax only) "
                    + "the plan did NOT thread any wall pad cell (wallPadNodes=" + wallNodes[0] + " < 1) — the "
                    + "adjacent-cluster bug did not reproduce; the single dig should be cheaper than the 3-deep "
                    + "detour. Re-tune the wall span / lane offset.");
        if (wallNodes[1] != 0)
            ctx.fail("padClusterCross: with pathfinderPadClusterTax ON the plan STILL "
                    + "threads a wall pad cell (wallPadNodes=" + wallNodes[1] + " > 0) — the cluster surcharge did "
                    + "not deflect A* around the adjacent-pad wall.");
        if (!reached[1])
            ctx.fail("padClusterCross: with the cluster tax ON A* failed to reach the goal "
                    + "— the surcharge must DETOUR around the wall, never forbid the crossing (clear lanes at cz±3).");

        // ---- REGION 2: the LONE sparse pad (no single-pad regression).
        Harness.set("pathfinderPadClusterTax", true);
        SinglePlan rLone = runSearch(bot, start, goal);
        int loneNodes = 0;
        List<BlockPosition> lonePath = rLone.cells();
        List<BotDebugContracts.Cell> loneCells = pathCells(bot, rLone);
        for (int i = 0; i < lonePath.size(); i++)
            if (lonePath.get(i).x() - cx == loneDx && lonePath.get(i).z() == cz && loneCells.get(i).water()) loneNodes++;
        TestMod.LOG.info("[wd.padClusterCross] region=lone clusterOn=true reached={} lonePadNodes={}",
                rLone.goalReached(), loneNodes);
        if (loneNodes != 0)
            ctx.fail("padClusterCross: with the cluster tax ON the plan threads the LONE "
                    + "sparse pad (lonePadNodes=" + loneNodes + " > 0) — the cluster surcharge must NOT change "
                    + "single-pad routing; a lone pad should still be detoured at the flat tax.");

        // ---- REGION 3: NO clear lane (no stranding).
        final int fullDx = 4;
        for (int dz = -halfZ; dz <= halfZ; dz++)
            level.setBlockAndUpdate(new BlockPos(cx + fullDx, surface + 1, cz + dz), Blocks.LILY_PAD.defaultBlockState());
        SinglePlan rFull = runSearch(bot, start, goal);
        int fullCross = 0;
        List<BlockPosition> fullPath = rFull.cells();
        List<BotDebugContracts.Cell> fullCells = pathCells(bot, rFull);
        for (int i = 0; i < fullPath.size(); i++)
            if (fullPath.get(i).x() - cx == fullDx && fullCells.get(i).water()) fullCross++;
        TestMod.LOG.info("[wd.padClusterCross] region=fullwall clusterOn=true reached={} crossNodes={}",
                rFull.goalReached(), fullCross);
        if (!rFull.goalReached())
            ctx.fail("padClusterCross: with a FULL-WIDTH pad wall (no clear lane) the "
                    + "cluster tax made the goal UNREACHABLE — it must be a TAX, never a forbid; A* must still "
                    + "thread the shortest line through (no stranding).");
        if (fullCross < 1)
            ctx.fail("padClusterCross: the full-width-wall search reached the goal WITHOUT "
                    + "crossing the wall column (crossNodes=" + fullCross + ") — the no-lane region is not testing "
                    + "the through-the-wall thread; re-check the geometry.");
    }

    // ---- inlined helpers (faithful copies of the legacy AgentGameTestSupport helpers) ----

    /** One A* from {@code start} to {@code goal} over {@code bot}'s view, run to completion with the
     *  settings' budget (the caller sets the budget knobs): {@code mc.debug.plan}, which runs the same
     *  bare search the legacy {@code AgentGameTestSupport#runSearch} did. */
    private static SinglePlan runSearch(SceneBot bot, BlockPos start, GotoContracts.Target goal) {
        return bot.plan(PlanParams.of(bot.ref(), goal).from(Targets.pos(start)));
    }

    /** Inlined from {@code AgentGameTestSupport#maxPathY}: highest Y of any cell on the planned path
     *  (−1 for an empty path). */
    private static int maxPathY(SinglePlan r) {
        int max = Integer.MIN_VALUE;
        for (int y : r.yProfile()) max = Math.max(max, y);
        return r.yProfile().isEmpty() ? -1 : max;
    }

    /** Whether {@code goal} leaves Y free, as the legacy {@code Goal.XZ} did: the column target. */
    private static boolean ignoresY(GotoContracts.Target goal) {
        return goal instanceof GotoContracts.Column;
    }

    /** The planned path's cells as {@code bot}'s view classifies them, in path order. */
    private static List<BotDebugContracts.Cell> pathCells(SceneBot bot, SinglePlan r) {
        return NavWalk.cells(bot, r.cells());
    }
}
