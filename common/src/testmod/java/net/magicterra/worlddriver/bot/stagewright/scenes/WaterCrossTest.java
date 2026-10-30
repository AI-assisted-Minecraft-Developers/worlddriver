package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Locale;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts;
import net.magicterra.worlddriver.bot.stagewright.kit.NavWalk;
import net.magicterra.worlddriver.bot.stagewright.kit.Routes;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.ScenePlan;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.magicterra.worlddriver.bot.stagewright.kit.SimProbes;
import net.magicterra.worlddriver.bot.stagewright.kit.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

/**
 * The walking open-water arenas of the WaterCross family: goal-snap, the water-surface step-down float, the forbidDig
 * pad-ram leak and the deep submerged-cross walk. Each is an {@code mc.bot.goto} (or a scripted plan through
 * {@code mc.debug.walkPlan}) ticked one whole bot tick at a time inside one server task, reading cells through
 * {@code mc.debug.cells}.
 *
 * <p>The planner arenas (anti-basin-dive depth penalty, floating-water climb-out tax, the deep submerged-cross surface
 * bias, the vine- and pad-over-water taxes, the pad-cluster tax) are {@code WaterCrossPlanTest}, and the
 * deep-water-float bee-line anchor is {@code DeepWaterFloatBeelineTest}: unit tests over a grid.
 */
@Scene("wd.scenes")
class WaterCrossTest {

    /**
     * A {@code goto} whose exact target is UNSTANDABLE (buried in a solid pillar) must snap to the nearest standable
     * cell and ARRIVE, not oscillate forever.
     */
    @Test
    void goalSnapBuried(Game game, @Plot.Centered @Plot.Size(x = 32, y = 24, z = 16) Plot plot, TestReporter reporter) {
        BlockPos o = plot.origin();
        final int cx = o.getX(), cz = o.getZ();
        final int floorY = o.getY();
        game.runOnServer(s -> {
            ServerLevel level = s.getLevel(plot.dimension());
            // Flat stone floor the bot walks on; air above.
            for (int dx = -2; dx <= 14; dx++)
                for (int dz = -4; dz <= 4; dz++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
            // The goal column is a SOLID stone pillar → its foot cell is not standable, no 1.8-tall pocket.
            for (int y = floorY; y <= floorY + 5; y++)
                level.setBlockAndUpdate(new BlockPos(cx + 10, y, cz), Blocks.STONE.defaultBlockState());
        });
        BlockPos buried = new BlockPos(cx + 10, floorY + 1, cz); // solid → unstandable

        Settings.pinUntimed(game).set("navigatorDebug", false);

        SceneBot bot = SceneBot.spawn(game, plot.dimension(), cx + 0.5, floorY + 1, cz + 0.5);
        String[] row = new String[1];
        String failure = game.onServer(s -> {
            ServerPlayer fp = bot.player();
            bot.goTo(Targets.block(buried));
            boolean walking = true;
            int arrivedTick = -1;
            for (int t = 0; t < 300 && walking; t++) {
                bot.tick();
                walking = bot.busy();
                if (!walking) {
                    arrivedTick = t;
                    break;
                }
            }
            String st = NavWalk.step(bot);
            bot.cancel();
            double dGoal = Math.hypot(fp.getX() - (cx + 10 + 0.5), fp.getZ() - (cz + 0.5));
            row[0] = "step=" + st + " pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") arrivedTick="
                    + arrivedTick + " dToBuried=" + String.format(Locale.ROOT, "%.1f", dGoal);
            // Snap → ARRIVED at a standable cell adjacent to the buried pillar (within snap radius 6 + slack).
            if (walking)
                return "goalSnapBuried: bot never arrived at a buried/unstandable goal (snap failed): " + row[0];
            if (dGoal > 7.0) return "goalSnapBuried: arrived too far from the buried goal (d=" + dGoal + ")";
            return null;
        });
        reporter.publishEntry("walk", row[0]);
        if (failure != null) fail(failure);
    }

    /** The 1-wide channel along -X both water-surface arenas share: dirt floor, 1-deep water, stone walls at dz=±1. */
    private static void channel(ServerLevel level, int cx, int cz, int floorY) {
        final int waterY = floorY + 1;
        for (int dx = -7; dx <= 2; dx++) {
            level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz), Blocks.DIRT.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(cx + dx, waterY, cz), Blocks.WATER.defaultBlockState());
            for (int dz = -1; dz <= 1; dz += 2)
                for (int y = floorY; y <= waterY + 3; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        }
    }

    /**
     * Deterministic repro of the SHALLOW WATER-SURFACE STEP-DOWN bob-stall. The node is HEAD-WALLED so the seeded
     * buoyant bot pins in the east-adjacent 1-deep water cell (cur2≈0.6-1.0, hCol). Run 0 (flag OFF) must WEDGE (step
     * frozen — the live bug), run 1 (flag ON) must ADVANCE. Clean A/B on the step-advance gate; the only variable is
     * {@code navigatorWaterStepDownFloat}.
     */
    @Test
    void waterStepDownFloat(
            Game game, @Plot.Centered @Plot.Size(x = 32, y = 24, z = 16) Plot plot, TestReporter reporter) {
        BlockPos o = plot.origin();
        final int cx = o.getX(), cz = o.getZ();
        final int floorY = o.getY();
        final int waterY = floorY + 1; // the 1-deep water surface (dirt floor at floorY)
        // NODE = the water-surface stepDown foothold at (cx, waterY, cz). HEAD-WALL the node so the buoyant
        // bot pins in the east cell ~0.6-1.0 b short.
        //
        // No lily pad in the east cell's head any more. Its 1.5/16 collision box leaves room for neither a
        // standing nor a crouching player, so vanilla's updatePlayerPose drops the player into the 0.6-tall
        // swimming pose, and that pose slides under the head-wall: the pumped server-side player reached the
        // node centre in 5 ticks and the OFF run measured nothing.
        BlockPos node = new BlockPos(cx, waterY, cz);
        BlockPos cont = new BlockPos(cx - 3, waterY, cz); // continuation further WEST along the shelf
        BlockPos goalN = new BlockPos(cx - 6, waterY, cz);
        game.runOnServer(s -> {
            ServerLevel level = s.getLevel(plot.dimension());
            channel(level, cx, cz, floorY);
            level.setBlockAndUpdate(node.above(), Blocks.STONE.defaultBlockState()); // head-wall at the node cell
        });

        Settings settings = Settings.pinUntimed(game)
                .set("allowBreak", false) // no carving — the stall must be the buoyant reach, not a dig
                .set("allowPlace", false);

        int[] advanceTick = {-1, -1}; // first tick the step-pointer left the water node, per run
        int[] nodeDwell = new int[2]; // ticks the step-pointer sat ON the water node, per run
        boolean[] advanced = new boolean[2];
        double[] minCur2 = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        // Run 0 = flag OFF (must WEDGE), run 1 = flag ON (must ADVANCE).
        for (int leg = 0; leg < 2; leg++) {
            final int l = leg;
            settings.set("navigatorWaterStepDownFloat", leg == 1).set("navigatorDebug", true);
            // SEED the bot in the EAST-adjacent 1-deep water cell (cx+1), pressed WEST toward the head-walled
            // node, with a small west velocity. It grounds (foot=waterY) but its head is blocked → pins short.
            SceneBot bot = SceneBot.spawn(game, plot.dimension(), cx + 1.5, waterY, cz + 0.5);
            String row = game.onServer(s -> {
                ServerPlayer fp = bot.player();
                fp.setDeltaMovement(-0.10, 0, 0);
                SimProbes.grantWaterEffects(fp);
                // Plan: step0 = east approach cell (cx+1) → step1 = NODE → cont → goal, all WEST along the shelf.
                // Replay mode: the safety repath is DISABLED, so the OFF run's wedge is a TRUE permanent stall
                // (no A* escape muddies the A/B).
                BlockPos approach = new BlockPos(cx + 1, waterY, cz);
                bot.walkPlan(DebugContracts.WalkPlanParams.replay(bot.ref(), padPlan(approach, node, cont, goalN))
                        .goal(Targets.block(goalN))
                        .from(Targets.pos(approach)));

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
                        minCur2[l] = Math.min(minCur2[l], dx * dx + dz * dz);
                        nodeDwell[l]++; // ticks the step-pointer sat ON the water node
                    }
                    // ADVANCE = the step-pointer moved PAST the water node (step>=2).
                    if (advanceTick[l] < 0 && at.step() >= 2) {
                        advanceTick[l] = t;
                        advanced[l] = true;
                    }
                }
                return String.format(
                        Locale.ROOT,
                        "flagOn=%s advanced=%s advanceTick=%d nodeDwell=%d endStep=%d minCur2=%.3f"
                                + " endPos=(%.2f,%.2f,%.2f) walking=%s",
                        l == 1,
                        advanced[l],
                        advanceTick[l],
                        nodeDwell[l],
                        endStep,
                        minCur2[l],
                        fp.getX(),
                        fp.getY(),
                        fp.getZ(),
                        walking);
            });
            reporter.publishEntry("leg" + leg, row);
            bot.remove();
        }
        // CONFIRM the pin reproduced: the OFF run must have stayed above the tight reach gate.
        if (minCur2[0] < 0.45)
            fail(
                    "waterStepDownFloat: the head-wall did NOT reproduce the buoyant pin "
                            + "(OFF minCur2=" + String.format(Locale.ROOT, "%.3f", minCur2[0])
                            + " < REACH_DIST_SQ=0.45 → the bot reached "
                            + "the node centre on its own, so there is no water-surface stall to exercise). Re-tune the seed/geometry.");
        // OFF must WEDGE — the step-pointer NEVER advances past the water node.
        if (advanced[0])
            fail("waterStepDownFloat: with the fix OFF the step-pointer ADVANCED past "
                    + "the water-surface stepDown node (advanceTick=" + advanceTick[0]
                    + ") — the bug did not reproduce; the "
                    + "OFF run must stay pinned (within needs cur2<0.45, unreachable at the buoyant pin).");
        // ON must ADVANCE promptly.
        if (!advanced[1])
            fail("waterStepDownFloat: with navigatorWaterStepDownFloat ON the step-pointer "
                    + "FAILED to advance past the water-surface stepDown node (pinned " + nodeDwell[1]
                    + " ticks) — the fix did "
                    + "not fire. minCur2_ON=" + String.format(Locale.ROOT, "%.3f", minCur2[1]));
        if (advanceTick[1] > 40)
            fail("waterStepDownFloat: with the fix ON the advance was SLOW (advanceTick="
                    + advanceTick[1] + " > 40) — the relaxation should fire shortly after the stall gate (~"
                    + "WATER_STEPDOWN_STALL_TICKS), not drift.");
    }

    /**
     * The per-goto {@code forbidDig} execution-layer leak — the lily-pad head-on break, the one executor dig-fallback
     * that reproduces under a clean {@code break: never} plan. Phase A ({@code break: never}): the pad MUST survive
     * (pre-fix RED = the head-on break punches it despite forbidDig). Phase B (no constraint): the pad MUST be removed
     * (proof the gate is precise). {@code allowBreak=true} throughout — the leak precondition.
     */
    @Test
    void forbidDigPadRam(
            Game game, @Plot.Centered @Plot.Size(x = 32, y = 24, z = 16) Plot plot, TestReporter reporter) {
        BlockPos o = plot.origin();
        final int cx = o.getX(), cz = o.getZ();
        final int floorY = o.getY();
        final int waterY = floorY + 1;
        BlockPos node = new BlockPos(cx, waterY, cz);
        BlockPos cont = new BlockPos(cx - 3, waterY, cz);
        BlockPos goalN = new BlockPos(cx - 6, waterY, cz);
        BlockPos padCell = new BlockPos(cx + 1, waterY + 1, cz); // the pinned bot's OWN head cell
        game.runOnServer(s -> channel(s.getLevel(plot.dimension()), cx, cz, floorY));
        Runnable setup = () -> game.runOnServer(s -> {
            ServerLevel level = s.getLevel(plot.dimension());
            // Wall across the channel at the node, foot and head: the head block alone pinned only an upright
            // treader, and a sprint-swimmer (0.6 tall) slid under it; the pass came from scraping the side wall.
            level.setBlockAndUpdate(node, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(node.above(), Blocks.STONE.defaultBlockState()); // head-wall → deterministic pin
            level.setBlockAndUpdate(padCell, Blocks.LILY_PAD.defaultBlockState());
        });
        Settings.pinUntimed(game)
                .set("allowBreak", true) // leak precondition: only forbidDig should stop the pad punch
                .set("allowPlace", false)
                .set("navigatorDebug", false)
                .set("navigatorWallDigFallback", false); // irrelevant in water; isolate the pad break

        // Phase A: forbidDig (NoBreak) — the pad MUST survive.
        setup.run();
        boolean survivedA = runPadLeg(
                game,
                plot,
                cx,
                cz,
                waterY,
                node,
                cont,
                goalN,
                Routes.plain().noBreak().build(),
                padCell);
        // Phase B: no constraint (precision) — the head-on break MUST fire, removing the pad.
        setup.run();
        boolean survivedB = runPadLeg(
                game, plot, cx, cz, waterY, node, cont, goalN, Routes.plain().build(), padCell);
        reporter.publishEntry("pad", "survivedA(forbidDig)=" + survivedA + " survivedB(plain)=" + survivedB);
        if (!survivedA)
            fail("forbidDigPadRam: forbidDig LEAK: the head-on lily-pad break punched the pad despite NoBreak "
                    + "(pad removed pre-fix). Under forbidDig the pad MUST survive.");
        if (survivedB)
            fail("forbidDigPadRam: precision guard: WITHOUT forbidDig the head-on pad break must still fire "
                    + "(pad removed) — the pad survived, so the gate OVER-KILLED legitimate pad clearance.");
    }

    /**
     * Seed the buoyant pin, drive the scripted plan under {@code route}, return whether the lily pad at {@code padCell}
     * SURVIVED (still a lily pad) after 120 ticks. See {@link #forbidDigPadRam}.
     */
    private static boolean runPadLeg(
            Game game,
            Plot plot,
            int cx,
            int cz,
            int waterY,
            BlockPos node,
            BlockPos cont,
            BlockPos goalN,
            RouteContracts.Route route,
            BlockPos padCell) {
        SceneBot bot = SceneBot.spawn(game, plot.dimension(), cx + 1.5, waterY, cz + 0.5);
        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            fp.setDeltaMovement(-0.10, 0, 0); // residual west approach momentum → press into the head-wall
            SimProbes.grantWaterEffects(fp);
            BlockPos approach = new BlockPos(cx + 1, waterY, cz);
            bot.walkPlan(DebugContracts.WalkPlanParams.replay(bot.ref(), padPlan(approach, node, cont, goalN))
                    .goal(Targets.block(goalN))
                    .from(Targets.pos(approach))
                    .route(route));
            bot.tickUntilIdle(120);
        });
        bot.remove();
        return game.onServer(
                s -> s.getLevel(plot.dimension()).getBlockState(padCell).is(Blocks.LILY_PAD));
    }

    /** The scripted crossing both water-surface arenas replay: approach, the stepDown onto the node, on west. */
    private static List<DebugContracts.PlanEdge> padPlan(
            BlockPos approach, BlockPos node, BlockPos cont, BlockPos goalN) {
        return List.of(
                ScenePlan.edge(approach, "walk"),
                ScenePlan.edge(node, "stepDown"), // the water-surface foothold
                ScenePlan.edge(cont, "walk"),
                ScenePlan.edge(goalN, "walk"));
    }

    /**
     * The walking half: with the deep-water surface bias ({@code pathfinderFloatingSurfaceCross}) and the surface-node
     * model ON, the floating Navigator crosses a deep (9-block) open-water channel from a submerged start and reaches
     * the far bank, spending almost no ticks below the surface.
     *
     * <p>The planner half — that the committed plan threads the crossing submerged with the bias OFF and surfaces at
     * once with it ON — is {@code WaterCrossPlanTest.deepWaterSubmergedCross}, a unit test over the same channel. What
     * stays here needs a body and a level.
     */
    @Test
    void deepWaterSubmergedCross(
            Game game, @Plot.Centered @Plot.Size(x = 64, y = 40, z = 16) Plot plot, TestReporter reporter) {
        BlockPos o = plot.origin();
        final int cx = o.getX(), cz = o.getZ();
        final int floorY = o.getY();
        final int surface = floorY + 9; // deep-water surface (floor at floorY → 9 deep)
        final int spanX = 24; // E-W open-water crossing length
        final int halfZ = 5; // open water half-width in Z
        // Built in the same server task as the walk, as it always was: the basin's top layer leaves a water
        // source on the east bank's edge, and given world ticks its flow covers the bank the bot must stand on
        // dry. Within one task the water never spreads.
        java.util.function.Consumer<ServerLevel> build = level -> {
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
        };
        // Y-AWARE Near goal on the dry east bank.
        GotoContracts.Target goal = Targets.near(new BlockPos(cx + spanX + 4, surface - 1, cz), 1);

        Settings.pinUntimed(game)
                .set("allowBreak", false) // pure swim/walk — the route choice must be the gate, not a dig
                .set("allowPlace", false)
                .set("pathfinderFloatingSurfaceCross", true)
                .set("pathfinderSurfaceWaterNodes", true)
                .set("navigatorDebug", true);
        SceneBot bot = SceneBot.spawn(game, plot.dimension(), cx + 1.5, surface - 1, cz + 0.5);
        String[] row = new String[1];
        String failure = game.onServer(s -> {
            build.accept(s.getLevel(plot.dimension()));
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
                if (fp.isInWater()
                        && foot.getY() < surface - 1
                        && NavWalk.cell(bot, foot.above()).water()) submergedTicks++;
            }
            String st = NavWalk.step(bot);
            bot.cancel();
            boolean ashore = !fp.isInWater() && fp.getX() >= cx + spanX + 1 - 0.5;
            row[0] = String.format(
                    Locale.ROOT,
                    "ashore=%s step=%s pos=(%.2f,%.2f,%.2f) maxX=%.2f submergedTicks=%d",
                    ashore,
                    st,
                    fp.getX(),
                    fp.getY(),
                    fp.getZ(),
                    maxX,
                    submergedTicks);
            if (!ashore)
                return "deepWaterSubmergedCross: with the flag ON the floating Navigator failed to cross "
                        + "and reach the far bank: " + row[0];
            if (submergedTicks > 40)
                return "deepWaterSubmergedCross: with the flag ON the bot spent " + submergedTicks
                        + " ticks pinned below the surface (>40) — the surface crossing should keep it on top.";
            return null;
        });
        reporter.publishEntry("walk", row[0]);
        if (failure != null) fail(failure);
    }
}
