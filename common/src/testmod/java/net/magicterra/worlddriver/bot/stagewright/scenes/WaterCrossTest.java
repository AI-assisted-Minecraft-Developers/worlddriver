package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Locale;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts;
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
 * The walking open-water arenas of the WaterCross family: goal-snap and the forbidDig pad-ram leak. Each is an
 * {@code mc.bot.goto} (or a scripted plan through {@code mc.debug.walkPlan}) ticked one whole bot tick at a time inside
 * one server task. Crossing open water itself is timed by the benchmark's swimming course.
 *
 * <p>The planner arenas (anti-basin-dive depth penalty, floating-water climb-out tax, the deep submerged-cross surface
 * bias, the vine- and pad-over-water taxes, the pad-cluster tax) are {@code WaterCrossPlanTest}, and the
 * deep-water-float bee-line anchor is {@code DeepWaterFloatBeelineTest}: unit tests over a grid.
 */
@Scene("wd.scenes")
class WaterCrossTest {

    private static final String NAVIGATOR_DEBUG = "navigatorDebug";

    private static final String WALK = "walk";

    /**
     * A {@code goto} whose exact target is UNSTANDABLE (buried in a solid pillar) must snap to the nearest standable
     * cell and ARRIVE, not oscillate forever.
     */
    @Test
    void goalSnapBuried(Game game, @Plot.Centered @Plot.Size(x = 32, y = 24, z = 16) Plot plot, TestReporter reporter) {
        BlockPos o = plot.origin();
        final int cx = o.getX();
        final int cz = o.getZ();
        final int floorY = o.getY();
        game.runOnServer(s -> {
            ServerLevel level = s.getLevel(plot.dimension());
            // Flat stone floor the bot walks on; air above.
            for (int dx = -2; dx <= 14; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
                }
            }
            // The goal column is a SOLID stone pillar → its foot cell is not standable, no 1.8-tall pocket.
            for (int y = floorY; y <= floorY + 5; y++) {
                level.setBlockAndUpdate(new BlockPos(cx + 10, y, cz), Blocks.STONE.defaultBlockState());
            }
        });
        BlockPos buried = new BlockPos(cx + 10, floorY + 1, cz); // solid → unstandable

        Settings.pinUntimed(game).set(NAVIGATOR_DEBUG, false);

        SceneBot bot = SceneBot.spawn(game, plot.dimension(), cx + 0.5, floorY + 1, cz + 0.5);
        String[] row = new String[1];
        String failure = game.onServer(s -> {
            final ServerPlayer fp = bot.player();
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
            double goalDist = Math.hypot(fp.getX() - (cx + 10 + 0.5), fp.getZ() - (cz + 0.5));
            row[0] = "step=" + st + " pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") arrivedTick="
                    + arrivedTick + " dToBuried=" + String.format(Locale.ROOT, "%.1f", goalDist);
            // Snap → ARRIVED at a standable cell adjacent to the buried pillar (within snap radius 6 + slack).
            if (walking) {
                return "goalSnapBuried: bot never arrived at a buried/unstandable goal (snap failed): " + row[0];
            }
            if (goalDist > 7.0) {
                return "goalSnapBuried: arrived too far from the buried goal (d=" + goalDist + ")";
            }
            return null;
        });
        reporter.publishEntry(WALK, row[0]);
        if (failure != null) {
            fail(failure);
        }
    }

    /** The 1-wide channel along -X the pad-ram arena runs in: dirt floor, 1-deep water, stone walls at dz=±1. */
    private static void channel(ServerLevel level, int cx, int cz, int floorY) {
        final int waterY = floorY + 1;
        for (int dx = -7; dx <= 2; dx++) {
            level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz), Blocks.DIRT.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(cx + dx, waterY, cz), Blocks.WATER.defaultBlockState());
            for (int dz = -1; dz <= 1; dz += 2) {
                for (int y = floorY; y <= waterY + 3; y++) {
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
                }
            }
        }
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
        final int cx = o.getX();
        final int cz = o.getZ();
        final int floorY = o.getY();
        final int waterY = floorY + 1;
        BlockPos node = new BlockPos(cx, waterY, cz);
        final BlockPos cont = new BlockPos(cx - 3, waterY, cz);
        final BlockPos goalN = new BlockPos(cx - 6, waterY, cz);
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
                .set(NAVIGATOR_DEBUG, false)
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
        if (!survivedA) {
            fail("forbidDigPadRam: forbidDig LEAK: the head-on lily-pad break punched the pad despite NoBreak "
                    + "(pad removed pre-fix). Under forbidDig the pad MUST survive.");
        }
        if (survivedB) {
            fail("forbidDigPadRam: precision guard: WITHOUT forbidDig the head-on pad break must still fire "
                    + "(pad removed) — the pad survived, so the gate OVER-KILLED legitimate pad clearance.");
        }
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

    /** The scripted crossing the pad-ram arena replays: approach, the stepDown onto the node, on west. */
    private static List<DebugContracts.PlanEdge> padPlan(
            BlockPos approach, BlockPos node, BlockPos cont, BlockPos goalN) {
        return List.of(
                ScenePlan.edge(approach, WALK),
                ScenePlan.edge(node, "stepDown"), // the water-surface foothold
                ScenePlan.edge(cont, WALK),
                ScenePlan.edge(goalN, WALK));
    }
}
