package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;
import java.util.Locale;

import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.pathfinder.CapabilityProfile;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.PathFinder;
import net.magicterra.worlddriver.bot.pathfinder.SearchProfile;
import net.magicterra.worlddriver.bot.pathfinder.constraints.NoBreak;
import net.magicterra.worlddriver.bot.pathfinder.moves.Fall;
import net.magicterra.worlddriver.bot.pathfinder.moves.FallIntoWater;
import net.magicterra.worlddriver.bot.sim.ServerPlayerBody;
import net.magicterra.worlddriver.bot.stagewright.SceneBody;
import net.magicterra.worlddriver.bot.stagewright.ScenePlan;
import net.magicterra.worlddriver.bot.world.LevelWorldView;
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
 * Dogfooded worlddriver scenes — <b>P4b wave 4, the WaterCross family</b>: the 10
 * {@code AgentGameTestWaterCross} open-water crossing / planner arenas (goal-snap /
 * anti-basin-dive depth penalty / floating-water climb-out gate / water-surface step-down
 * float / forbidDig pad-ram leak / deep submerged-cross surface bias / vine- & pad-over-water
 * taxes / pad-cluster tax / deep-water-float bee-line anchor-gate), migrated verbatim to
 * testkit {@code wd.*} scenes and their legacy twins retired in the same commit.
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
 *   <li>{@code try/finally} per-key config save/restore → {@link BotConfig#pinnedBaseline()}
 *       + {@code ctx.cleanup(pin::close)} registered FIRST (LIFO → closes LAST, after the
 *       avatar discard) then the SAME keys the legacy body flipped;</li>
 *   <li>{@code ServerPlayerBody.create(...)} → {@link ServerPlayerBody#createUnique}
 *       (per-scene server-side player) + {@code ctx.cleanup(() -> fp.discard())}. The
 *       legacy per-arena isolation batches drop out — a player from createUnique cannot bleed
 *       into another scene;</li>
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
 * <p><b>Origin slots + chunk radius.</b> All 10 take AUTO slots. Two overflow the radius-1
 * window (usable dx/dz {@code [−16,+31]}) and are widened:
 * <ul>
 *   <li>{@code wd.basin} needs {@code .withChunkRadius(2)}: the plateau spans dz {@code 0..44}
 *       (goal at dz +40, plateau built to dz +44), past the +31 edge — radius-2 window is
 *       {@code [−32,+47]}, which covers +44.</li>
 *   <li>{@code wd.deepWaterFloatBeeline} needs {@code .withChunkRadius(4)}: with {@code spanX = 56}
 *       the clear-box / basin floor reach dx {@code cx + spanX + 16 = +72} (far node cx+48, dry
 *       probe platform cx+68±1); +72 overflows radius-2 {@code [−32,+47]} and radius-3
 *       {@code [−48,+63]} — radius-4 window {@code [−64,+79]} covers it (usable dx/dz =
 *       {@code [−16r, 16r+15]}). No scene is pinned to a fixed slot: every gate is a discrete
 *       planner outcome (node-in-lane / reached / accept-reject), integer-cell and
 *       position-invariant.</li>
 * </ul>
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
                Scene.of("wd.padClusterCross", 200, WorldDriverWaterCrossScenes::padClusterCross),
                Scene.of("wd.deepWaterFloatBeeline", 200, WorldDriverWaterCrossScenes::deepWaterFloatBeeline)
                        .withChunkRadius(4));
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

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        LevelWorldView w = new LevelWorldView(level, fp);
        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(buried));
        Walker.Step s = Walker.Step.WALKING;
        int arrivedTick = -1;
        for (int t = 0; t < 300 && s == Walker.Step.WALKING; t++) {
            s = walker.tick(av, w);
            av.step();
            if (s == Walker.Step.ARRIVED) { arrivedTick = t; break; }
        }
        double dGoal = Math.hypot(fp.getX() - (cx + 10 + 0.5), fp.getZ() - (cz + 0.5));
        WorldDriverCommon.LOG.info("[wd.goalSnapBuried] step={} pos=({},{},{}) arrivedTick={} dToBuried={}",
                s, fp.getX(), fp.getY(), fp.getZ(), arrivedTick, String.format("%.1f", dGoal));
        // Snap → ARRIVED at a standable cell adjacent to the buried pillar (within snap radius 6 + slack).
        if (s != Walker.Step.ARRIVED)
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
        Goal goal = new Goal.XZ(cx, cz + 40);            // ignoresY → the dive-prone case

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDebug = false;
        // pfDepthPenalty() returns max(penalty, 25) when boxed-escalation is ON, which would clamp BOTH
        // penalty=0 and penalty=6 to an identical 25 → false failure. Force it OFF so pfDepthPenalty()
        // returns the raw penalty we set.
        BotConfig.pathfinderBoxedEscalate = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;   // each search runs to completion (deterministic)
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxNodes = 1_000_000;

        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx + 0.5, plY + 1, cz + 2);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        LevelWorldView w = new LevelWorldView(level, fp);

        BotConfig.pathfinderDepthPenalty = 0;
        var r0 = runSearch(w, start, goal);
        BotConfig.pathfinderDepthPenalty = 6;
        var r6 = runSearch(w, start, goal);
        WorldDriverCommon.LOG.info("[wd.basin] penalty0: reached={} expanded={} | penalty6: reached={} expanded={}",
                r0.goalReached(), r0.expanded(), r6.goalReached(), r6.expanded());
        if (!r6.goalReached())
            ctx.fail("basin: depthPenalty=6 failed to reach via the rim go-around");
        if (r6.expanded() >= r0.expanded())
            ctx.fail("basin: depthPenalty did not cut basin-dive exploration: "
                    + "penalty0 expanded=" + r0.expanded() + " penalty6 expanded=" + r6.expanded());

        // Failure-case: a node budget BETWEEN the two counts — penalty=0 burns it in the trap and FAILS;
        // penalty=6 reaches via the rim within it.
        int budget = (r0.expanded() + r6.expanded()) / 2;
        BotConfig.pathfinderMaxNodes = budget;
        BotConfig.pathfinderDepthPenalty = 0;
        var b0 = runSearch(w, start, goal);
        BotConfig.pathfinderDepthPenalty = 6;
        var b6 = runSearch(w, start, goal);
        WorldDriverCommon.LOG.info("[wd.basin] budget={}: penalty0 reached={} | penalty6 reached={}",
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
        Goal goal = new Goal.XZ(cx, cz + 15);               // ignoresY → buoyant climb-out case

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        double oco = BotConfig.pathfinderWaterClimbOutCost;   // the configured default (baseline value)
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxNodes = 1_000_000;

        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx + 0.5, wsurf, cz);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        LevelWorldView w = new LevelWorldView(level, fp);

        // Tax OFF: the +1 exit is allowed (its maxY is recorded, not judged). Tax default: flush wins.
        BotConfig.pathfinderWaterClimbOutCost = 0;
        var rOff = runSearch(w, start, goal);
        int maxYOff = maxPathY(rOff);
        BotConfig.pathfinderWaterClimbOutCost = oco;        // the configured default
        var rOn = runSearch(w, start, goal);
        int maxYOn = maxPathY(rOn);
        WorldDriverCommon.LOG.info("[wd.waterClimbOutRoute] taxOff: reached={} maxY={} | taxDefault({}): reached={} maxY={}",
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
     *  {@code walkerWaterStepDownFloat}. */
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

        Goal goal = new Goal.Block(goalN);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = false;          // no carving — the stall must be the buoyant reach, not a dig
        BotConfig.allowPlace = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        int[] advanceTick = { -1, -1 };    // first tick the step-pointer left the water node, per run
        int[] nodeDwell = new int[2];      // ticks the step-pointer sat ON the water node, per run
        boolean[] advanced = new boolean[2];
        double[] minCur2 = { Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY };
        // Run 0 = flag OFF (must WEDGE), run 1 = flag ON (must ADVANCE).
        for (int leg = 0; leg < 2; leg++) {
            BotConfig.walkerWaterStepDownFloat = (leg == 1);
            BotConfig.walkerDebug = true;
            // SEED the bot in the EAST-adjacent 1-deep water cell (cx+1), pressed WEST toward the head-walled
            // node, with a small west velocity. It grounds (foot=waterY) but its head is blocked → pins short.
            ServerPlayerBody av = SceneBody.avatar(ctx, level, cx + 1.5, waterY, cz + 0.5);
            ServerPlayer fp = av.fakePlayer();
            final ServerPlayer fpc = fp;
            ctx.cleanup(() -> fpc.discard());
            fp.setDeltaMovement(-0.10, 0, 0);
            SimProbes.grantWaterEffects(fp);
            LevelWorldView w = new LevelWorldView(level, fp);
            Walker walker = new Walker();
            // Plan: step0 = east approach cell (cx+1) → step1 = NODE → cont → goal, all WEST along the shelf.
            BlockPos approach = new BlockPos(cx + 1, waterY, cz);
            List<BlockPos> plan = List.of(approach, node, cont, goalN);
            List<Move.Edge> planEdges = List.of(
                    new Move.Edge(approach, 10, List.of(), List.of(), "walk"),
                    new Move.Edge(node,  10, List.of(), List.of(), "stepDown"),   // the water-surface foothold
                    new Move.Edge(cont,  10, List.of(), List.of(), "walk"),
                    new Move.Edge(goalN, 10, List.of(), List.of(), "walk"));
            // beginReplay: pin the scripted plan with replayMode → the safety repath is DISABLED, so the OFF
            // run's wedge is a TRUE permanent stall (no A* escape muddies the A/B).
            walker.beginReplay(w, plan, planEdges, goal, approach);

            Walker.Step s = Walker.Step.WALKING;
            for (int t = 0; t < 200 && s == Walker.Step.WALKING; t++) {
                s = walker.tick(av, w);
                av.step();
                BlockPos pn = walker.pathNode();
                if (pn != null && pn.equals(node)) {
                    double dx = (node.getX() + 0.5) - fp.getX();
                    double dz = (node.getZ() + 0.5) - fp.getZ();
                    minCur2[leg] = Math.min(minCur2[leg], dx * dx + dz * dz);
                    nodeDwell[leg]++;            // ticks the step-pointer sat ON the water node
                }
                // ADVANCE = the step-pointer moved PAST the water node (step>=2).
                if (advanceTick[leg] < 0 && walker.pathStep() >= 2) {
                    advanceTick[leg] = t;
                    advanced[leg] = true;
                }
            }
            WorldDriverCommon.LOG.info("[wd.waterStepDownFloat] leg={} flagOn={} advanced={} advanceTick={} nodeDwell={} endStep={} minCur2={} endPos=({},{},{}) step={}",
                    leg, leg == 1, advanced[leg], advanceTick[leg], nodeDwell[leg], walker.pathStep(),
                    String.format(Locale.ROOT, "%.3f", minCur2[leg]),
                    String.format(Locale.ROOT, "%.2f", fp.getX()), String.format(Locale.ROOT, "%.2f", fp.getY()),
                    String.format(Locale.ROOT, "%.2f", fp.getZ()), s);
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
            ctx.fail("waterStepDownFloat: with walkerWaterStepDownFloat ON the step-pointer "
                    + "FAILED to advance past the water-surface stepDown node (pinned " + nodeDwell[1] + " ticks) — the fix did "
                    + "not fire. minCur2_ON=" + String.format(Locale.ROOT, "%.3f", minCur2[1]));
        if (advanceTick[1] > 40)
            ctx.fail("waterStepDownFloat: with the fix ON the advance was SLOW (advanceTick="
                    + advanceTick[1] + " > 40) — the relaxation should fire shortly after the stall gate (~"
                    + "WATER_STEPDOWN_STALL_TICKS), not drift.");
    }

    /** Ported from {@code AgentGameTestWaterCross#forbidDigPadRamArena}: the engine-gap per-goto
     *  {@code forbidDig} execution-layer leak — the lily-pad head-on break, the one executor dig-fallback
     *  that reproduces under a clean {@link NoBreak} plan. Phase A ({@code NoBreak}): the pad MUST survive
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
            level.setBlockAndUpdate(node.above(), Blocks.STONE.defaultBlockState());       // head-wall → deterministic pin
            level.setBlockAndUpdate(padCell, Blocks.LILY_PAD.defaultBlockState());
        };
        Goal goal = new Goal.Block(goalN);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = true;             // leak precondition: only forbidDig should stop the pad punch
        BotConfig.allowPlace = false;
        BotConfig.walkerDebug = false;
        BotConfig.walkerWallDigFallback = false; // irrelevant in water; isolate the pad break
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        // Phase A: forbidDig (NoBreak) — the pad MUST survive.
        setup.run();
        boolean survivedA = runPadLeg(ctx, level, cx, cz, waterY, node, cont, goalN, goal,
                new SearchProfile(List.of(), CapabilityProfile.ALL, List.of(new NoBreak())), padCell);
        // Phase B: no constraint (precision) — the head-on break MUST fire, removing the pad.
        setup.run();
        boolean survivedB = runPadLeg(ctx, level, cx, cz, waterY, node, cont, goalN, goal,
                SearchProfile.NONE, padCell);
        WorldDriverCommon.LOG.info("[wd.forbidDigPadRam] survivedA(forbidDig)={} survivedB(plain)={}", survivedA, survivedB);
        if (!survivedA)
            ctx.fail("forbidDigPadRam: forbidDig LEAK: the head-on lily-pad break punched the pad despite NoBreak "
                    + "(pad removed pre-fix). Under forbidDig the pad MUST survive.");
        if (survivedB)
            ctx.fail("forbidDigPadRam: precision guard: WITHOUT forbidDig the head-on pad break must still fire "
                    + "(pad removed) — the pad survived, so the gate OVER-KILLED legitimate pad clearance.");
    }

    /** Seed the buoyant pin, drive the scripted plan under {@code profile}, return whether the lily pad at
     *  {@code padCell} SURVIVED (still a lily pad) after 120 ticks. See {@link #forbidDigPadRam}. */
    private static boolean runPadLeg(SceneContext ctx, ServerLevel level, int cx, int cz, int waterY,
                                     BlockPos node, BlockPos cont, BlockPos goalN, Goal goal,
                                     SearchProfile profile, BlockPos padCell) {
        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx + 1.5, waterY, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        fp.setDeltaMovement(-0.10, 0, 0);        // residual west approach momentum → press into the head-wall
        SimProbes.grantWaterEffects(fp);
        LevelWorldView w = new LevelWorldView(level, fp);
        Walker walker = new Walker();
        BlockPos approach = new BlockPos(cx + 1, waterY, cz);
        List<BlockPos> plan = List.of(approach, node, cont, goalN);
        List<Move.Edge> planEdges = List.of(
                new Move.Edge(approach, 10, List.of(), List.of(), "walk"),
                new Move.Edge(node,  10, List.of(), List.of(), "stepDown"),
                new Move.Edge(cont,  10, List.of(), List.of(), "walk"),
                new Move.Edge(goalN, 10, List.of(), List.of(), "walk"));
        walker.beginReplay(w, plan, planEdges, goal, approach);
        walker.setSearchProfile(profile);        // AFTER beginReplay so it isn't reset
        Walker.Step s = Walker.Step.WALKING;
        for (int t = 0; t < 120 && s == Walker.Step.WALKING; t++) {
            s = walker.tick(av, w);
            av.step();
        }
        return level.getBlockState(padCell).is(Blocks.LILY_PAD);
    }

    /** Ported from {@code AgentGameTestWaterCross#deepWaterSubmergedCrossArena}: deep-water submerged-crossing
     *  surface-bias ({@code pathfinderFloatingSurfaceCross}). A deep (9-block) open-water channel; the search
     *  is seeded submerged (surface-1) for a Y-aware Near goal on the far bank. Part A (planner A/B): the legacy
     *  water model with the bias OFF threads MANY submerged crossing nodes (the bug); the surface-node model
     *  with the bias ON surfaces immediately. Part B (integration): the floating Walker crosses and reaches the
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
        Goal goal = new Goal.Near(new BlockPos(cx + spanX + 4, surface - 1, cz), 1);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = false;          // pure swim/walk — the route choice must be the gate, not a dig
        BotConfig.allowPlace = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;   // deterministic: each search completes in one go
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        // ---- Part A: predicate A/B on the committed plan ----
        int[] subNodes = new int[2];
        boolean[] reached = new boolean[2];
        for (int leg = 0; leg < 2; leg++) {
            BotConfig.pathfinderFloatingSurfaceCross = (leg == 1);
            // The bug lives in the legacy model, where every water cell is a node; run 0 reproduces it
            // there. Under the surface-node model (run 1) a submerged crossing cannot be planned at all.
            BotConfig.pathfinderSurfaceWaterNodes = (leg == 1);
            ServerPlayerBody av = SceneBody.avatar(ctx, level, cx + 1.5, surface - 1, cz + 0.5);
            ServerPlayer fp = av.fakePlayer();
            final ServerPlayer fpc = fp;
            ctx.cleanup(() -> fpc.discard());
            SimProbes.grantWaterEffects(fp);
            fp.getInventory().clearContent();
            LevelWorldView w = new LevelWorldView(level, fp);
            PathFinder.Search s = new PathFinder(w).newSearch(seed, goal);
            s.advance(Long.MAX_VALUE / 2);
            PathFinder.Result r = s.result();
            reached[leg] = r.goalReached();
            for (BlockPos p : r.path())
                if (p.getY() < surface && w.isWater(p) && w.isWater(p.above())) subNodes[leg]++;
            WorldDriverCommon.LOG.info("[wd.deepWaterSubmergedCross] legA flagOn={} reached={} pathLen={} submergedNodes={}",
                    leg == 1, r.goalReached(), r.path().size(), subNodes[leg]);
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

        // ---- Part B: integration — the floating Walker crosses cleanly with the flag ON ----
        BotConfig.pathfinderFloatingSurfaceCross = true;
        BotConfig.pathfinderSurfaceWaterNodes = true;
        BotConfig.walkerDebug = true;
        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx + 1.5, surface - 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        SimProbes.grantWaterEffects(fp);
        fp.getInventory().clearContent();
        LevelWorldView w = new LevelWorldView(level, fp);
        Walker walker = new Walker();
        walker.setGoal(goal);
        Walker.Step st = Walker.Step.WALKING;
        int submergedTicks = 0;
        double maxX = fp.getX();
        for (int t = 0; t < 1200 && st == Walker.Step.WALKING; t++) {
            st = walker.tick(av, w);
            av.step();
            maxX = Math.max(maxX, fp.getX());
            BlockPos foot = BlockPos.containing(fp.getX(), fp.getY() + 0.1, fp.getZ());
            if (fp.isInWater() && foot.getY() < surface - 1 && w.isWater(foot.above())) submergedTicks++;
        }
        boolean ashore = !fp.isInWater() && fp.getX() >= cx + spanX + 1 - 0.5;
        WorldDriverCommon.LOG.info("[wd.deepWaterSubmergedCross] legB ashore={} step={} pos=({},{},{}) maxX={} submergedTicks={}",
                ashore, st, String.format(Locale.ROOT, "%.2f", fp.getX()), String.format(Locale.ROOT, "%.2f", fp.getY()),
                String.format(Locale.ROOT, "%.2f", fp.getZ()), String.format(Locale.ROOT, "%.2f", maxX), submergedTicks);
        if (!ashore)
            ctx.fail("deepWaterSubmergedCross: with the flag ON the floating Walker failed to cross "
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
        Goal goal = new Goal.Near(new BlockPos(cx + spanX + 3, surface, cz), 1);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;   // deterministic: each search completes in one go
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxNodes = 1_000_000;

        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx - 2.5, surface, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        SimProbes.grantWaterEffects(fp);
        fp.getInventory().clearContent();
        LevelWorldView w = new LevelWorldView(level, fp);

        // #1 silent-no-op guards.
        BlockPos bodyMid = new BlockPos(cx + (treeX0 + treeX1) / 2, surface + 1, cz);   // a vine cell
        BlockPos capMid  = new BlockPos(cx + (treeX0 + treeX1) / 2, surface + 2, cz);    // a leaf cell
        if (!w.isClimbable(bodyMid))
            ctx.fail("vineOverWaterCross: center head-level cell is NOT climbable (vine) at "
                    + bodyMid + " — the vine did not survive setBlockAndUpdate; the repro is vacuous.");
        if (w.isLeaves(bodyMid))
            ctx.fail("vineOverWaterCross: center head-level cell reads as LEAVES at " + bodyMid
                    + " — leafCellTax would already catch it and the new tax would be redundant; expected a VINE.");
        if (w.isBreakableObstruction(bodyMid))
            ctx.fail("vineOverWaterCross: center head-level vine reads as a breakable obstruction at "
                    + bodyMid + " — padCellTax would already catch it; a vine must have no collision shape.");
        if (!w.isLeaves(capMid))
            ctx.fail("vineOverWaterCross: leaf canopy missing at " + capMid + ".");

        // A/B: OFF threads the center lane (bug), ON detours around it (fix).
        int[] laneNodes = new int[2];
        boolean[] reached = new boolean[2];
        for (int leg = 0; leg < 2; leg++) {
            BotConfig.pathfinderVineOverWaterTax = (leg == 1);
            PathFinder.Search s = new PathFinder(w).newSearch(start, goal);
            s.advance(Long.MAX_VALUE / 2);
            PathFinder.Result r = s.result();
            reached[leg] = r.goalReached();
            for (BlockPos p : r.path()) {
                int rx = p.getX() - cx, rz = p.getZ() - cz;
                if (rz == 0 && rx >= treeX0 && rx <= treeX1 && w.isWater(p)) laneNodes[leg]++;
            }
            WorldDriverCommon.LOG.info("[wd.vineOverWaterCross] flagOn={} reached={} pathLen={} centerLaneNodes={}",
                    leg == 1, r.goalReached(), r.path().size(), laneNodes[leg]);
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
        Goal goal = new Goal.Near(new BlockPos(cx + spanX + 3, surface, cz), 1);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;   // deterministic: each search completes in one go
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxNodes = 1_000_000;

        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx - 2.5, surface, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        SimProbes.grantWaterEffects(fp);
        fp.getInventory().clearContent();
        LevelWorldView w = new LevelWorldView(level, fp);

        // #1 silent-no-op guards.
        BlockPos padBody = new BlockPos(cx + padDx[1], surface + 1, cz);   // a lily-pad cell
        BlockPos padFoot = new BlockPos(cx + padDx[1], surface, cz);       // the water cell under it
        if (!w.isWater(padFoot))
            ctx.fail("padOverWaterCross: foot under the pad is NOT water at " + padFoot
                    + " — the pad did not land on a water surface cell; the repro is vacuous.");
        if (!w.isBreakableObstruction(padBody))
            ctx.fail("padOverWaterCross: pad head-level cell is NOT a breakable obstruction at "
                    + padBody + " — the lily pad did not survive setBlockAndUpdate; the repro is vacuous.");
        if (goal.ignoresY())
            ctx.fail("padOverWaterCross: the goal reads as ignoresY (XZ) — padCellTax "
                    + "would already fire and the new goal-neutral tax would be redundant; expected a Y-aware goal.");

        // A/B: OFF threads the center lane through the pads (bug), ON detours around them (fix).
        int[] padNodes = new int[2];
        boolean[] reached = new boolean[2];
        for (int leg = 0; leg < 2; leg++) {
            BotConfig.pathfinderPadOverWaterTax = (leg == 1);
            PathFinder.Search s = new PathFinder(w).newSearch(start, goal);
            s.advance(Long.MAX_VALUE / 2);
            PathFinder.Result r = s.result();
            reached[leg] = r.goalReached();
            for (BlockPos p : r.path()) {
                if (p.getZ() != cz || !w.isWater(p)) continue;
                int rx = p.getX() - cx;
                for (int pd : padDx) if (rx == pd) { padNodes[leg]++; break; }
            }
            WorldDriverCommon.LOG.info("[wd.padOverWaterCross] flagOn={} reached={} pathLen={} padLaneNodes={}",
                    leg == 1, r.goalReached(), r.path().size(), padNodes[leg]);
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
        Goal goal = new Goal.Near(new BlockPos(cx + spanX + 3, surface, cz), 1);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;   // deterministic: each search completes in one go
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxNodes = 1_000_000;

        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx - 2.5, surface, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        SimProbes.grantWaterEffects(fp);
        fp.getInventory().clearContent();
        LevelWorldView w = new LevelWorldView(level, fp);

        // #1 silent-no-op guards.
        BlockPos wallBody = new BlockPos(cx + wallDx, surface + 1, cz);   // the wall's centre lily-pad cell
        BlockPos wallFoot = new BlockPos(cx + wallDx, surface, cz);       // the water cell under it
        BlockPos wallSibN = new BlockPos(cx + wallDx, surface + 1, cz + 1);
        if (!w.isWater(wallFoot))
            ctx.fail("padClusterCross: foot under the wall is NOT water at " + wallFoot
                    + " — the repro is vacuous.");
        if (!w.isBreakableObstruction(wallBody) || !w.isBreakableObstruction(wallSibN))
            ctx.fail("padClusterCross: wall pad cells are NOT breakable obstructions ("
                    + wallBody + " / " + wallSibN + ") — the lily-pad wall did not survive setBlockAndUpdate; "
                    + "the repro is vacuous.");
        if (goal.ignoresY())
            ctx.fail("padClusterCross: the goal reads as ignoresY (XZ) — padCellTax would "
                    + "already fire; expected a Y-aware goal.");

        // ---- REGION 1: the CLUSTER WALL A/B (single-pad tax ON for BOTH runs; CLUSTER flag is the variable).
        BotConfig.pathfinderPadOverWaterTax = true;        // the shipped single-pad fix stays ON for the A/B
        int[] wallNodes = new int[2];
        boolean[] reached = new boolean[2];
        for (int leg = 0; leg < 2; leg++) {
            BotConfig.pathfinderPadClusterTax = (leg == 1);
            PathFinder.Search s = new PathFinder(w).newSearch(start, goal);
            s.advance(Long.MAX_VALUE / 2);
            PathFinder.Result r = s.result();
            reached[leg] = r.goalReached();
            for (BlockPos p : r.path()) {
                if (p.getX() - cx != wallDx || !w.isWater(p)) continue;
                int rz = p.getZ() - cz;
                if (rz >= -wallHalfZ && rz <= wallHalfZ) wallNodes[leg]++;
            }
            WorldDriverCommon.LOG.info("[wd.padClusterCross] region=wall clusterOn={} reached={} pathLen={} wallPadNodes={}",
                    leg == 1, r.goalReached(), r.path().size(), wallNodes[leg]);
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
        BotConfig.pathfinderPadClusterTax = true;
        PathFinder.Result rLone = new PathFinder(w).findPath(start, goal);
        int loneNodes = 0;
        for (BlockPos p : rLone.path())
            if (p.getX() - cx == loneDx && p.getZ() == cz && w.isWater(p)) loneNodes++;
        WorldDriverCommon.LOG.info("[wd.padClusterCross] region=lone clusterOn=true reached={} lonePadNodes={}",
                rLone.goalReached(), loneNodes);
        if (loneNodes != 0)
            ctx.fail("padClusterCross: with the cluster tax ON the plan threads the LONE "
                    + "sparse pad (lonePadNodes=" + loneNodes + " > 0) — the cluster surcharge must NOT change "
                    + "single-pad routing; a lone pad should still be detoured at the flat tax.");

        // ---- REGION 3: NO clear lane (no stranding).
        final int fullDx = 4;
        for (int dz = -halfZ; dz <= halfZ; dz++)
            level.setBlockAndUpdate(new BlockPos(cx + fullDx, surface + 1, cz + dz), Blocks.LILY_PAD.defaultBlockState());
        PathFinder.Result rFull = new PathFinder(w).findPath(start, goal);
        int fullCross = 0;
        for (BlockPos p : rFull.path())
            if (p.getX() - cx == fullDx && w.isWater(p)) fullCross++;
        WorldDriverCommon.LOG.info("[wd.padClusterCross] region=fullwall clusterOn=true reached={} crossNodes={}",
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

    /** Ported from {@code AgentGameTestWaterCross#deepWaterFloatBeelineArena}: deep-water-float bee-line
     *  anchor-gate exemption ({@code walkerDeepWaterFloatBeeline}). Exercises the fix's DECISION POINT via
     *  {@link Walker#adoptForTest}: (1) OFF — the far open-water continuation is REJECTED (the live dead-stop
     *  root); (2) ON — the SAME continuation is ACCEPTED and drives EAST; (2b) ON + SUNK foot accepts past the
     *  |Δy| jump gate; (3a) ON is INERT for a WALLED line, (3b) ON is INERT from a DRY foot. Unit-level. */
    private static void deepWaterFloatBeeline(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY(), depth = 8;   // legacy floorY 200 = origin.y
        final int surface = floorY + depth;        // deep-water surface
        final int spanX = 56;                      // E-W open-water crossing length (the far node is ~48 b out)

        // Clear a generous air box.
        for (int dx = -6; dx <= spanX + 16; dx++)
            for (int dz = -5; dz <= 5; dz++)
                for (int y = floorY - 1; y <= surface + 8; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        // DEEP basin floor (floorY) + N/S walls + a west cap to hold the water.
        for (int dx = -5; dx <= spanX + 16; dx++)
            for (int dz = -4; dz <= 4; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        for (int dx = -4; dx <= spanX + 1; dx++)
            for (int y = floorY + 1; y <= surface + 2; y++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz - 3), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + 3), Blocks.STONE.defaultBlockState());
            }
        for (int dz = -3; dz <= 3; dz++)
            for (int y = floorY + 1; y <= surface + 2; y++)
                level.setBlockAndUpdate(new BlockPos(cx - 4, y, cz + dz), Blocks.STONE.defaultBlockState());
        // Deep open water filling the basin up to `surface` — a wide-open clear-LOS channel.
        for (int dx = -3; dx <= spanX; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int y = floorY + 1; y <= surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());

        // The deep-water-float dead-stop geometry: foot at the start, a continuation [farNode, beyond, goal]
        // whose first node is ~48 b east over clear open water.
        final int waterFootY = surface;                       // a surface water cell (floating foot rides here)
        BlockPos foot    = new BlockPos(cx, waterFootY, cz);
        BlockPos farNode = new BlockPos(cx + 48, waterFootY, cz);          // continuation path[0] — ~48 b east
        BlockPos beyond  = new BlockPos(cx + 52, waterFootY, cz);
        BlockPos goalN   = new BlockPos(cx + spanX, waterFootY, cz);

        // Deliberately NOT anchored at the foot — path[0] is ~48 b east. That is the input the
        // segment anchor-gate exists to judge, not a malformed plan: three of the five arms below
        // expect a REJECT, and the reason they expect one is precisely this gap. Built through the
        // named factory so the next reader (or scanner) sees the intent in the source instead of
        // inferring it from a shape that a genuinely broken plan shares.
        ScenePlan seg = ScenePlan.misanchoredSegment(
                List.of(farNode, beyond, goalN),
                List.of(new Move.Edge(farNode, 50, List.of(), List.of(), "walk"),
                        new Move.Edge(beyond,  10, List.of(), List.of(), "walk"),
                        new Move.Edge(goalN,   30, List.of(), List.of(), "walk")));
        List<BlockPos> cont = seg.nodes();
        List<Move.Edge> contEdges = seg.edges();

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDebug = false;

        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx + 0.5, waterFootY, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        SimProbes.grantWaterEffects(fp);
        LevelWorldView w = new LevelWorldView(level, fp);

        // Vacuity guards: the geometry must carry the dead-stop shape.
        if (!w.isWater(foot) || !w.isWater(foot.below()))
            ctx.fail("deepWaterFloatBeeline: foot " + foot + " is not buoyant deep water "
                    + "(water at it AND below) — the float pin is vacuous.");
        if (!w.isWater(farNode))
            ctx.fail("deepWaterFloatBeeline: far node " + farNode + " is not a water cell "
                    + "— the open-water bee-line target is vacuous.");
        double d2 = farNode.distSqr(foot);
        if (d2 < 64)
            ctx.fail("deepWaterFloatBeeline: far node only d2=" + d2 + " from the foot — not a "
                    + "long open-water crossing (need ≥ the 64 reject gate). Lengthen spanX.");

        // (1) OFF: the far open-water continuation must be REJECTED (the live dead-stop root).
        BotConfig.walkerDeepWaterFloatBeeline = false;
        Walker wOff = new Walker();
        boolean acceptOff = wOff.adoptForTest(w, cont, contEdges, foot);
        WorldDriverCommon.LOG.info("[wd.deepWaterFloatBeeline] OFF accept={} (expect false: anchor-gate rejects mis-anchored)", acceptOff);
        if (acceptOff)
            ctx.fail("deepWaterFloatBeeline: with the fix OFF the anchor-gate ACCEPTED the far "
                    + "open-water continuation (d2=" + (int) d2 + ") — the mis-anchored reject (the live dead-stop root) did "
                    + "not reproduce. Baseline broken.");

        // (2) ON: the SAME continuation must be ACCEPTED and the step driven toward the far node.
        BotConfig.walkerDeepWaterFloatBeeline = true;
        Walker wOn = new Walker();
        boolean acceptOn = wOn.adoptForTest(w, cont, contEdges, foot);
        WorldDriverCommon.LOG.info("[wd.deepWaterFloatBeeline] ON accept={} step={} node={} (expect true: clear-LOS open-water bee-line)",
                acceptOn, wOn.pathStep(), wOn.pathNode());
        if (!acceptOn)
            ctx.fail("deepWaterFloatBeeline: with walkerDeepWaterFloatBeeline ON the anchor-gate "
                    + "STILL rejected the clear-LOS open-water continuation — the bee-line exemption did not fire.");
        BlockPos drive = wOn.pathNode();
        if (drive == null || drive.getX() <= foot.getX())
            ctx.fail("deepWaterFloatBeeline: after accepting, the step does not drive EAST toward "
                    + "the far node (step node=" + drive + ", foot=" + foot + ") — the accepted segment must carry the bot "
                    + "toward the far open-water node.");

        // (2b) ON + SUNK foot: the live foot bobs UNDER the surface node (|Δy| up to 4), exceeding the jump bar.
        BlockPos sunkFoot = new BlockPos(cx, waterFootY - 4, cz);
        if (!w.isWater(sunkFoot) || Math.abs(farNode.getY() - sunkFoot.getY()) <= w.maxJumpUpBlocks() + 2)
            ctx.fail("deepWaterFloatBeeline: the sunk-foot probe " + sunkFoot + " is not 4-below "
                    + "submerged water past the jump gate — vacuous (|Δy|=" + Math.abs(farNode.getY() - sunkFoot.getY()) + ").");
        Walker wSunk = new Walker();
        boolean acceptSunk = wSunk.adoptForTest(w, cont, contEdges, sunkFoot);
        WorldDriverCommon.LOG.info("[wd.deepWaterFloatBeeline] ON+SUNK accept={} (expect true: swim-up bee-line past the |Δy| jump gate)", acceptSunk);
        if (!acceptSunk)
            ctx.fail("deepWaterFloatBeeline: with the fix ON a SUNK foot (4 below the surface node, "
                    + "the live deep-bob) was REJECTED — the exemption must bypass the |Δy| jump gate for an open-water swim-up "
                    + "(else the reject storm survives the y58 bob ticks).");

        // (3a) ON-INERT: a WALLED continuation (stone across the LOS line) must STILL be rejected.
        BlockPos wallAt = new BlockPos(cx + 20, waterFootY, cz);          // a stone block mid-line
        BlockPos wallHi = new BlockPos(cx + 20, waterFootY + 1, cz);
        level.setBlockAndUpdate(wallAt, Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(wallHi, Blocks.STONE.defaultBlockState());
        Walker wWall = new Walker();
        boolean acceptWall = wWall.adoptForTest(w, cont, contEdges, foot);
        WorldDriverCommon.LOG.info("[wd.deepWaterFloatBeeline] ON+WALL accept={} (expect false: LOS blocked by stone)", acceptWall);
        level.setBlockAndUpdate(wallAt, Blocks.WATER.defaultBlockState());   // restore the channel
        level.setBlockAndUpdate(wallHi, Blocks.AIR.defaultBlockState());
        if (acceptWall)
            ctx.fail("deepWaterFloatBeeline: with the fix ON a WALLED continuation (stone across "
                    + "the LOS) was ACCEPTED — the exemption must reject a non-open-water line (else it bee-lines through a wall).");

        // (3b) ON-INERT: a DRY foot (not floating), far from every continuation node, must STILL reject.
        BlockPos dryFoot = new BlockPos(cx + spanX + 12, surface + 1, cz);
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) {
                level.setBlockAndUpdate(new BlockPos(dryFoot.getX() + dx, surface, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(dryFoot.getX() + dx, surface + 1, cz + dz), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(dryFoot.getX() + dx, surface + 2, cz + dz), Blocks.AIR.defaultBlockState());
            }
        Walker wDry = new Walker();
        boolean acceptDry = wDry.adoptForTest(w, cont, contEdges, dryFoot);   // far continuation, dry foot
        WorldDriverCommon.LOG.info("[wd.deepWaterFloatBeeline] ON+DRYFOOT accept={} (expect false: exemption needs a floating foot)", acceptDry);
        if (acceptDry)
            ctx.fail("deepWaterFloatBeeline: with the fix ON a continuation from a DRY foot was "
                    + "ACCEPTED — the open-water bee-line exemption must be gated on a FLOATING foot.");
    }

    // ---- inlined helpers (faithful copies of the legacy AgentGameTestSupport helpers) ----

    /** Inlined from {@code AgentGameTestSupport#runSearch}: run one A* to completion (caller sets the
     *  budget knobs) and return its result. */
    private static PathFinder.Result runSearch(LevelWorldView w, BlockPos start, Goal goal) {
        PathFinder.Search s = new PathFinder(w).newSearch(start, goal);
        s.advance(Long.MAX_VALUE / 2);
        return s.result();
    }

    /** Inlined from {@code AgentGameTestSupport#maxPathY}: highest Y of any cell on the planned path
     *  (−1 for an empty path). */
    private static int maxPathY(PathFinder.Result r) {
        int max = Integer.MIN_VALUE;
        for (BlockPos p : r.path()) max = Math.max(max, p.getY());
        return r.path().isEmpty() ? -1 : max;
    }
}
