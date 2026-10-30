package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.Locale;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.bot.stagewright.kit.NavWalk;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneArena;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.magicterra.worlddriver.bot.stagewright.kit.SimProbes;
import net.magicterra.worlddriver.bot.stagewright.kit.Targets;
import net.magicterra.worlddriver.testcontent.TestMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Terrain traversal arenas: a bot's {@code mc.bot.goto} over a sheer wall, a rising parkour gap, a foothold cliff, a
 * 1-wide stair over a pit, a stair ascent, a sprint off a ledge and a bare-hand dig. Each test stages its arena in plot
 * coordinates (the old scenes' offsets from their origin, kept), spawns a parked bot, starts the walk and ticks it one
 * whole bot tick at a time inside one server task, reading the player every tick.
 *
 * <p>Every gate here is an OUTCOME (reached / fell in the pit) or a WIDE-tolerance metric (ascent b/s floor 1.5 vs
 * baseline 3.0; ledge worstBack &lt; −0.6 vs −0.12; ridge maxNoProgress budget 80).
 *
 * <p>{@code descentDrift} has no test: its old arena was a proven false green (it passed only because a concurrent
 * arena shoved the shared player out of the wedge; solo it launched off the stair into open void), its RED path is an
 * open-void A* churn needing ~110 s of compute, and its gate is the live A/B. Descent behaviour stays covered by
 * {@link #descent}.
 */
@Scene("wd.scenes")
@Execution(ExecutionMode.SAME_THREAD)
class TerrainTest {

    /**
     * A bot's goto climbs a +5 SHEER (vertical, stepless) wall onto a plateau behind it. Break OFF (no tunnelling),
     * place ON (pillar/scaffold is the only way up). Asserts the avatar reaches the plateau.
     */
    @Test
    void sheerWall(Game game, @Plot.Centered @Plot.Size(x = 32, y = 96, z = 32) Plot plot) {
        final int floorY = 20;
        final int standY = floorY + 1;
        final int wallH = 5;
        final int wallZ = 2;
        final int topY = floorY + wallH; // wall-top block; plateau surface = topY (stand topY+1)
        SceneArena.buildFloor(plot, 0, floorY, 0);
        plot.fill(-5, floorY + 1, wallZ, 5, topY, wallZ, Blocks.STONE);
        plot.fill(-5, topY, 3, 5, topY, 5, Blocks.STONE);
        plot.fill(-5, topY + 1, 3, 5, topY + 4, 5, Blocks.AIR);
        BlockPos goal = plot.at(0, topY + 1, 3); // first plateau cell behind the wall
        final int wallZAbs = plot.at(0, 0, wallZ).getZ();
        final int topAbs = plot.at(0, topY, 0).getY();

        Settings.pin(game).set("allowBreak", false).set("allowPlace", true).set("navigatorDebug", true);
        SceneBot bot = SceneBot.spawn(game, plot.at(0, standY, 0));
        bot.give(Items.DIRT, 64);

        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            fp.getInventory().selected = 0;
            bot.goTo(Targets.block(goal));
            double maxY = fp.getY();
            boolean walking = true;
            for (int t = 0; t < 600 && walking; t++) {
                bot.tick();
                walking = bot.busy();
                maxY = Math.max(maxY, fp.getY());
            }
            String st = NavWalk.step(bot);
            boolean onPlateau = fp.getZ() > wallZAbs + 0.5 && fp.getY() >= topAbs + 1 - 0.4;
            TestMod.LOG.info(
                    "[wd.sheerWall] step={} pos=({},{},{}) maxY={} onPlateau={}",
                    st,
                    fp.getX(),
                    fp.getY(),
                    fp.getZ(),
                    maxY,
                    onPlateau);
            if (!onPlateau) {
                fail("sheerWall: the goto failed to climb the +" + wallH + " sheer wall: pos=(" + fp.getX() + ","
                        + fp.getY() + "," + fp.getZ() + ") maxY=" + maxY + " step=" + st);
            }
        });
    }

    /**
     * Regression guard for the parkour-ascend sprint fix — a 2-block cardinal gap landing +1 higher clears only with a
     * SPRINT-jump. Break/place OFF. Asserts the navigator sprint-jumps onto the +1 landing without falling into the
     * deep pit.
     */
    @Test
    void parkourAscend(Game game, @Plot.Centered @Plot.Size(x = 32, y = 96, z = 32) Plot plot) {
        final int launchY = 30;
        final int pitY = 0;
        plot.fill(-6, pitY, -3, 7, pitY, 3, Blocks.STONE);
        plot.fill(-5, launchY, -2, 0, launchY, 2, Blocks.STONE);
        plot.fill(2, launchY + 1, -2, 6, launchY + 1, 2, Blocks.STONE);
        BlockPos goal = plot.at(4, launchY + 2, 0);
        final int cx = plot.origin().getX();
        final int pitAbs = plot.at(0, pitY, 0).getY();
        final int launchAbs = plot.at(0, launchY, 0).getY();

        Settings.pinUntimed(game)
                .set("allowBreak", false)
                .set("allowPlace", false)
                .set("navigatorDebug", true);
        SceneBot bot = SceneBot.spawn(game, plot.at(-5, launchY + 1, 0));

        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            SimProbes.grantWaterEffects(fp);
            bot.goTo(Targets.block(goal));
            double minY = fp.getY();
            boolean walking = true;
            for (int t = 0; t < 300 && walking; t++) {
                bot.tick();
                walking = bot.busy();
                minY = Math.min(minY, fp.getY());
            }
            String st = NavWalk.step(bot);
            boolean fellInPit = minY <= pitAbs + 3;
            boolean onLanding = fp.getX() > cx + 1.5 && fp.getY() >= launchAbs + 2 - 0.4;
            TestMod.LOG.info(
                    "[wd.parkourAscend] step={} pos=({},{},{}) minY={} fellInPit={} onLanding={}",
                    st,
                    fp.getX(),
                    fp.getY(),
                    fp.getZ(),
                    minY,
                    fellInPit,
                    onLanding);
            if (fellInPit) {
                fail("parkourAscend: parkour ascend fell into the gap (sprint disabled?): minY=" + minY);
            }
            if (!onLanding) {
                fail("parkourAscend: parkour ascend did not reach the +1 landing: pos=(" + fp.getX() + "," + fp.getY()
                        + "," + fp.getZ() + ") step=" + st);
            }
        });
    }

    /**
     * The FALL-OVERSHOOT re-sync dead-zone SMOOTHNESS guard — a steep foothold cliff (top platform, floating foothold 3
     * below the rim, bottom plain 6 below carrying the goal). Break/place OFF. Asserts the bot descends to the goal
     * WITHOUT stalling (max consecutive no-horizontal-progress ticks under a tight budget).
     */
    @Test
    void ridgeOvershoot(Game game, @Plot.Centered @Plot.Size(x = 32, y = 96, z = 48) Plot plot) {
        final int plainY = -20;
        final int topY = -14;
        plot.fill(-2, plainY, -7, 2, topY, 0, Blocks.STONE);
        plot.fill(-2, topY - 3, 1, 2, topY - 3, 1, Blocks.STONE);
        plot.fill(-2, plainY, 2, 2, plainY, 16, Blocks.STONE);
        BlockPos goal = plot.at(0, plainY + 1, 12);

        Settings.pinUntimed(game)
                .set("allowBreak", false)
                .set("allowPlace", false)
                .set("navigatorDebug", true);
        BlockPos start = plot.at(0, topY + 1, -4);
        SceneBot bot = SceneBot.spawn(game, plot.dimension(), start.getX() + 0.5, start.getY(), start.getZ() + 0.5);

        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            SimProbes.grantWaterEffects(fp);
            bot.goTo(Targets.block(goal));
            double px = fp.getX();
            double pz = fp.getZ();
            int noProgress = 0;
            int maxNoProgress = 0;
            boolean walking = true;
            for (int t = 0; t < 700 && walking; t++) {
                bot.tick();
                walking = bot.busy();
                double dx = fp.getX() - px;
                double dz = fp.getZ() - pz;
                if (dx * dx + dz * dz < 0.0025) {
                    noProgress++; // <0.05 b/tick horizontally
                } else {
                    noProgress = 0;
                }
                maxNoProgress = Math.max(maxNoProgress, noProgress);
                px = fp.getX();
                pz = fp.getZ();
            }
            String st = NavWalk.step(bot);
            boolean atGoal = Math.abs(fp.getX() - (goal.getX() + 0.5)) < 1.5
                    && Math.abs(fp.getZ() - (goal.getZ() + 0.5)) < 2.0
                    && Math.abs(fp.getY() - goal.getY()) < 1.5;
            TestMod.LOG.info(
                    "[wd.ridgeOvershoot] step={} pos=({},{},{}) atGoal={} maxNoProgress={}",
                    st,
                    fp.getX(),
                    fp.getY(),
                    fp.getZ(),
                    atGoal,
                    maxNoProgress);
            if (!atGoal) {
                fail("ridgeOvershoot: did not reach the plain goal: pos=(" + fp.getX() + "," + fp.getY() + ","
                        + fp.getZ() + ") step=" + st + " maxNoProgress=" + maxNoProgress);
            }
            if (maxNoProgress > 80) {
                fail("ridgeOvershoot: fall-overshoot wedge — stalled " + maxNoProgress
                        + " ticks at a node the bot dropped past (budget 80)");
            }
        });
    }

    /**
     * Regression guard for the descent crouch-deadlock fix (plannedDescent releases the lethal-edge sneak brake). A
     * 1-wide staircase descends 12 steps over a DEEP pit with void on both x sides. Break/place OFF. Asserts the bot
     * reaches the bottom and never falls off the 1-wide stair into the pit.
     */
    @Test
    void descent(Game game, @Plot.Centered @Plot.Size(x = 16, y = 96, z = 48) Plot plot) {
        final int pitFloorY = -20;
        final int topY = 20;
        final int steps = 12;
        plot.fill(-2, pitFloorY, -2, 2, pitFloorY, steps + 2, Blocks.STONE);
        for (int i = 0; i <= steps; i++) {
            plot.place(0, topY - i, i, Blocks.STONE);
        }
        BlockPos goal = plot.at(0, topY - steps + 1, steps);
        final int pitAbs = plot.at(0, pitFloorY, 0).getY();

        Settings.pinUntimed(game)
                .set("allowBreak", false)
                .set("allowPlace", false)
                .set("navigatorDebug", true);
        SceneBot bot = SceneBot.spawn(game, plot.at(0, topY + 1, 0));

        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            SimProbes.grantWaterEffects(fp);
            bot.goTo(Targets.block(goal));
            double minY = fp.getY();
            boolean walking = true;
            for (int t = 0; t < 400 && walking; t++) {
                bot.tick();
                walking = bot.busy();
                minY = Math.min(minY, fp.getY());
            }
            String st = NavWalk.step(bot);
            boolean fellInPit = minY <= pitAbs + 2;
            boolean atBottom =
                    Math.abs(fp.getZ() - (goal.getZ() + 0.5)) < 1.5 && Math.abs(fp.getY() - goal.getY()) < 1.5;
            TestMod.LOG.info(
                    "[wd.descent] step={} pos=({},{},{}) minY={} fellInPit={} atBottom={}",
                    st,
                    fp.getX(),
                    fp.getY(),
                    fp.getZ(),
                    minY,
                    fellInPit,
                    atBottom);
            if (fellInPit) {
                fail("descent: fell off the 1-wide stair into the pit: minY=" + minY);
            }
            if (!atBottom) {
                fail("descent: crouch-deadlock: did not reach the bottom step: pos=(" + fp.getX() + "," + fp.getY()
                        + "," + fp.getZ() + ") step=" + st);
            }
        });
    }

    /**
     * SMOOTHNESS gate measuring a bot's goto's average horizontal speed UP a gentle staircase vs across flat ground (6
     * steps, 2 blocks deep each, +1 y per step, flat run-up + flat top). Break/place OFF. Logs ascent/flat b/s +
     * sprint%/hCol% and asserts the bot tops out and keeps a minimum ascent speed floor.
     */
    @Test
    void ascentSpeed(Game game, @Plot.Centered @Plot.Size(x = 64, y = 96, z = 16) Plot plot) {
        final int baseY = 10;
        final int stepCount = 6;
        plot.fill(-10, baseY, -2, -1, baseY, 2, Blocks.STONE);
        for (int i = 0; i < stepCount; i++) {
            plot.fill(2 * i, baseY, -2, 2 * i + 1, baseY + 1 + i, 2, Blocks.STONE);
        }
        final int topSurf = baseY + stepCount;
        plot.fill(2 * stepCount, topSurf, -2, 2 * stepCount + 12, topSurf, 2, Blocks.STONE);
        BlockPos goal = plot.at(2 * stepCount + 10, topSurf + 1, 0);
        final int cx = plot.origin().getX();
        final int ascEndX = cx + 2 * stepCount - 1;
        final int topAbs = plot.at(0, topSurf, 0).getY();

        Settings.pinUntimed(game)
                .set("allowBreak", false)
                .set("allowPlace", false)
                .set("navigatorDebug", false);
        SceneBot bot = SceneBot.spawn(game, plot.at(-9, baseY + 1, 0));

        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            SimProbes.grantWaterEffects(fp);
            bot.goTo(Targets.block(goal));
            int flatTicks = 0;
            int ascTicks = 0;
            double flatStartX = Double.NaN;
            double flatEndX = 0;
            double ascStartX = Double.NaN;
            double ascEndXObs = 0;
            int flatSprint = 0;
            int ascSprint = 0;
            int ascHcol = 0;
            boolean walking = true;
            for (int t = 0; t < 500 && walking; t++) {
                bot.tick();
                walking = bot.busy();
                double x = fp.getX();
                boolean spr = fp.isSprinting();
                boolean hc = fp.horizontalCollision;
                if (x < cx) {
                    if (Double.isNaN(flatStartX)) {
                        flatStartX = x;
                    }
                    flatEndX = x;
                    flatTicks++;
                    if (spr) {
                        flatSprint++;
                    }
                } else if (x <= ascEndX + 1) {
                    if (Double.isNaN(ascStartX)) {
                        ascStartX = x;
                    }
                    ascEndXObs = x;
                    ascTicks++;
                    if (spr) {
                        ascSprint++;
                    }
                    if (hc) {
                        ascHcol++;
                    }
                }
            }
            double flatBps = flatTicks > 0 ? (flatEndX - flatStartX) / (flatTicks * 0.05) : 0;
            double ascBps = ascTicks > 0 ? (ascEndXObs - ascStartX) / (ascTicks * 0.05) : 0;
            int flatSprintPct = flatTicks > 0 ? 100 * flatSprint / flatTicks : 0;
            int ascSprintPct = ascTicks > 0 ? 100 * ascSprint / ascTicks : 0;
            int ascHcolPct = ascTicks > 0 ? 100 * ascHcol / ascTicks : 0;
            String st = NavWalk.step(bot);
            boolean reachedTop = fp.getX() > ascEndX && fp.getY() >= topAbs + 1 - 0.4;
            TestMod.LOG.info(
                    "[wd.ascentSpeed] step={} pos=({},{},{}) reachedTop={} flatBps={} ascBps={} flatSprint%={}"
                            + " ascSprint%={} ascHcol%={} flatTicks={} ascTicks={}",
                    st,
                    String.format(Locale.ROOT, "%.1f", fp.getX()),
                    String.format(Locale.ROOT, "%.1f", fp.getY()),
                    String.format(Locale.ROOT, "%.1f", fp.getZ()),
                    reachedTop,
                    String.format(Locale.ROOT, "%.2f", flatBps),
                    String.format(Locale.ROOT, "%.2f", ascBps),
                    flatSprintPct,
                    ascSprintPct,
                    ascHcolPct,
                    flatTicks,
                    ascTicks);
            if (!reachedTop) {
                fail("ascentSpeed: did not reach the flat top: pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ()
                        + ") step=" + st);
            }
            if (ascBps < 1.5) {
                fail("ascentSpeed: ascent speed collapsed to " + ascBps + " b/s");
            }
        });
    }

    /**
     * BACKWARD-HOP reproduction — a SPRINT-OVERSHOOT off a ledge (long flat runway → sheer 3-block drop → terrace).
     * Metric = motion AWAY from the due-east goal after the lip + the worst single backward step. Break/place OFF.
     * Guards a GROSS regression (worstBack &lt; −0.6, baseline ~−0.12).
     */
    @Test
    void ledgeOvershoot(Game game, @Plot.Centered @Plot.Size(x = 96, y = 96, z = 16) Plot plot) {
        final int h = 40;
        final int run = 26;
        final int drop = 3;
        plot.fill(0, h - 4, -2, run, h - 1, 2, Blocks.STONE);
        plot.fill(run + 1, h - 4 - drop, -2, run + 18, h - 1 - drop, 2, Blocks.STONE);
        BlockPos goal = plot.at(run + 16, h - drop, 0);
        final int cx = plot.origin().getX();
        final int hAbs = plot.at(0, h, 0).getY();

        Settings.pinUntimed(game)
                .set("allowBreak", false)
                .set("allowPlace", false)
                .set("navigatorDebug", false);
        BlockPos start = plot.at(1, h, 0);
        SceneBot bot = SceneBot.spawn(game, plot.dimension(), start.getX() + 0.5, start.getY(), start.getZ() + 0.5);

        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            SimProbes.grantWaterEffects(fp);
            bot.goTo(Targets.block(goal));
            double prevX = fp.getX();
            double prevZ = fp.getZ();
            int backSteps = 0;
            int moved = 0;
            double worstBack = 0;
            double landX = 0;
            boolean pastLip = false;
            boolean walking = true;
            for (int t = 0; t < 400 && walking; t++) {
                bot.tick();
                walking = bot.busy();
                double ddx = fp.getX() - prevX;
                double ddz = fp.getZ() - prevZ;
                if (!pastLip && fp.getX() > cx + run && fp.getY() < hAbs - 1.5) {
                    pastLip = true;
                    landX = fp.getX();
                }
                if (pastLip && ddx * ddx + ddz * ddz > 1e-4) {
                    moved++;
                    if (ddx < -0.02) {
                        backSteps++;
                        worstBack = Math.min(worstBack, ddx);
                    }
                }
                prevX = fp.getX();
                prevZ = fp.getZ();
            }
            String st = NavWalk.step(bot);
            double dGoal = Math.hypot(fp.getX() - (goal.getX() + 0.5), fp.getZ() - (goal.getZ() + 0.5));
            boolean reached = dGoal < 2.5;
            double overshoot = landX - (cx + run);
            TestMod.LOG.info(
                    "[wd.ledgeOvershoot] step={} pos=({},{},{}) reached={} dGoal={} landX-overshoot={}"
                            + " backSteps={}/{} worstBack={}",
                    st,
                    String.format(Locale.ROOT, "%.1f", fp.getX()),
                    String.format(Locale.ROOT, "%.1f", fp.getY()),
                    String.format(Locale.ROOT, "%.1f", fp.getZ()),
                    reached,
                    String.format(Locale.ROOT, "%.1f", dGoal),
                    String.format(Locale.ROOT, "%.1f", overshoot),
                    backSteps,
                    moved,
                    String.format(Locale.ROOT, "%.2f", worstBack));
            if (!reached) {
                fail("ledgeOvershoot: did not reach the terrace goal: pos=(" + fp.getX() + "," + fp.getY() + ","
                        + fp.getZ() + ") dGoal=" + dGoal + " step=" + st);
            }
            if (worstBack < -0.6) {
                fail("ledgeOvershoot: backward-hop regressed to " + worstBack + " blocks/tick (baseline ~-0.12)");
            }
        });
    }

    /**
     * BARE-HAND DIG CADENCE gate (gap #66). A sealed corridor whose only route is a traverseBreak through a 2-high
     * stone wall (2 blocks × 150t bare-hand). Break ON, BARE HANDS. Asserts the breakHold does NOT drop while the wall
     * is still solid (each drop zeroes vanilla mining progress) and the bot reaches the goal chamber.
     */
    @Test
    void bareHandDigCadence(Game game, @Plot.Centered Plot plot) {
        final int base = 0;
        plot.fill(-1, base, -1, 3, base + 4, 1, Blocks.STONE);
        plot.fill(0, base + 1, 0, 0, base + 2, 0, Blocks.AIR); // start chamber
        plot.fill(2, base + 1, 0, 2, base + 2, 0, Blocks.AIR); // goal chamber
        BlockPos wallFeet = plot.at(1, base + 1, 0);
        BlockPos wallHead = plot.at(1, base + 2, 0);
        BlockPos goal = plot.at(2, base + 1, 0);
        final int cx = plot.origin().getX();

        Settings.pinUntimed(game).set("allowBreak", true).set("navigatorDebug", true);
        SceneBot bot = SceneBot.spawn(game, plot.at(0, base + 1, 0)); // spawned empty: BARE HANDS is the point

        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            ServerLevel level = bot.level();
            bot.goTo(Targets.block(goal));
            boolean prevHeld = false;
            int heldTicks = 0;
            int dropsWhileSolid = 0;
            boolean walking = true;
            for (int t = 0; t < 1500 && walking; t++) {
                bot.tick();
                walking = bot.busy();
                boolean held = bot.body().hands().breakHeld();
                boolean wallSolid = level.getBlockState(wallFeet).isSolid()
                        || level.getBlockState(wallHead).isSolid();
                if (prevHeld && !held && wallSolid) {
                    dropsWhileSolid++;
                    TestMod.LOG.info(
                            "[wd.bareHandDigCadence] DROP #{} after {}t held (wall still solid) t={}",
                            dropsWhileSolid,
                            heldTicks,
                            t);
                }
                heldTicks = held ? (prevHeld ? heldTicks + 1 : 1) : 0;
                prevHeld = held;
            }
            String st = NavWalk.step(bot);
            boolean wallOpen = !level.getBlockState(wallFeet).isSolid()
                    && !level.getBlockState(wallHead).isSolid();
            boolean arrived = fp.getX() > cx + 1.5;
            TestMod.LOG.info(
                    "[wd.bareHandDigCadence] step={} pos=({},{},{}) wallOpen={} arrived={} dropsWhileSolid={}",
                    st,
                    String.format(Locale.ROOT, "%.1f", fp.getX()),
                    String.format(Locale.ROOT, "%.1f", fp.getY()),
                    String.format(Locale.ROOT, "%.1f", fp.getZ()),
                    wallOpen,
                    arrived,
                    dropsWhileSolid);
            if (dropsWhileSolid > 2) {
                fail("bareHandDigCadence: dig cadence broken: breakHold dropped " + dropsWhileSolid
                        + "x while the wall was still solid (each drop zeroes vanilla mining progress — gap #66)");
            }
            if (!arrived) {
                fail("bareHandDigCadence: never reached the goal chamber: pos=(" + fp.getX() + "," + fp.getY() + ","
                        + fp.getZ() + ") wallOpen=" + wallOpen + " drops=" + dropsWhileSolid);
            }
        });
    }
}
