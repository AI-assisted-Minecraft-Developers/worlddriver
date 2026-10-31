package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneArena;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.ScenePlan;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.magicterra.worlddriver.bot.stagewright.kit.SimProbes;
import net.magicterra.worlddriver.bot.stagewright.kit.Targets;
import net.magicterra.worlddriver.testcontent.TestMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

/**
 * Arenas whose purpose is to exercise navigator execution paths the outcome-focused families never enter, so a refactor
 * has a regression net over them. Each still asserts a real behavioural outcome — coverage without an assertion is not
 * a sensor. Every flag below is one the test baseline turns off and production ships on, so each test turns its own
 * back on.
 *
 * <p>Each course is a server bot sent through {@code mc.bot.goto} and ticked by the test, the navigator read through
 * {@code mc.debug.navigator}. Several mutate the world mid-walk (a ceiling slammed and lifted, a trench filled, a wall
 * opened, a slice budget restored at tick 40), so the goto and the whole tick loop run inside one
 * {@code game.runOnServer}: no world tick between the bot's ticks, the edit lands exactly between two of them. Arenas
 * are built at absolute coordinates taken from the plot ({@code cx}, {@code cz}, {@code floorY}).
 */
@Scene("wd.scenes")
class CoverageTest {

    /**
     * JUMP-noRise expectation alarm: a clean +1 step-up course is PLANNED first; once the bot closes on the riser a
     * bedrock CEILING is slammed over the launch cells, so every step-up jump is height-capped and the alarm fires
     * ({@code expectAlarmsFired}); the ceiling is then lifted and the navigator must still finish on the plateau.
     * Earlier shapes were defeated by the stack itself: a wall slam re-routed around it; bare-hand obsidian is rejected
     * by the planner's tool gate; and server-body digs are instant, so the DIG observers cannot fire here. A blocked
     * JUMP is the observer a server body can pin.
     */
    @Test
    void expectAlarmBlockedJump(Game game, @Plot.Centered Plot plot) {
        BlockPos o = plot.at(0, 0, 0);
        final int cx = o.getX();
        final int cz = o.getZ();
        final int floorY = o.getY();
        final int standY = floorY + 1;
        SceneArena.buildFloor(plot, 0, 0, 0);
        plot.fill(1, 1, -5, 5, 1, 5, Blocks.STONE); // +1 plateau over the east half
        BlockPos goal = new BlockPos(cx + 4, standY + 1, cz);
        Settings.pin(game)
                .set("navigatorExpectAlarm", true)
                .set("allowBreak", false)
                .set("allowPlace", false);
        SceneBot bot = SceneBot.spawn(game, Level.OVERWORLD, cx - 4 + 0.5, standY, cz + 0.5);

        game.runOnServer(s -> {
            ServerLevel level = s.overworld();
            ServerPlayer fp = walkTo(bot, goal);
            long firedBefore = alarmsFired(bot);
            int t = 0;
            boolean capped = false;
            boolean lifted = false;
            for (; t < 600 && bot.busy(); t++) {
                if (!capped && fp.getX() > cx - 1.5) {
                    // Route committed, bot closing on the riser: cap the launch cells.
                    ceiling(level, cx, floorY, cz, Blocks.BEDROCK);
                    capped = true;
                }
                if (capped && !lifted && alarmsFired(bot) > firedBefore) {
                    ceiling(level, cx, floorY, cz, Blocks.AIR);
                    lifted = true;
                }
                bot.tick();
            }
            String end = ending(bot);
            long fired = alarmsFired(bot) - firedBefore;
            boolean reached = Math.abs(fp.getX() - (goal.getX() + 0.5)) < 1.5
                    && Math.abs(fp.getZ() - (goal.getZ() + 0.5)) < 1.5
                    && fp.getY() >= standY + 1 - 0.4;
            TestMod.LOG.warn(
                    "[wd.expectAlarmBlockedJump] step={} ticks={} fired={} capped={} lifted={} reached={}",
                    end,
                    t,
                    fired,
                    capped,
                    lifted,
                    reached);
            if (fired == 0) {
                fail("expectAlarmBlockedJump: no alarm fired under a jump-capping ceiling: t=" + t + " step=" + end
                        + " lastStats=" + lastSearch(bot) + " capped=" + capped + " pos=" + fp.position());
            }
            if (!reached) {
                fail("expectAlarmBlockedJump: navigator never finished after the ceiling lifted: step=" + end
                        + " capped=" + capped + " lifted=" + lifted + " pos=" + fp.position());
            }
        });
    }

    /**
     * Drowning escape ({@code navigatorDrowningEscape}): the body starts at the BOTTOM of a 4-deep pool with its air
     * nearly gone (40/300) while the goal sits on the far dry rim. The escape latch (air ≤ 60 while submerged) must
     * override route-following with the surface-for-air climb until air recovers, and the run must still end on the
     * rim. The pool is RAMPED (deep end west, walk-out east): a flat-bottom pool was a sealed water graph A* exhausted
     * with no exit edge, so the override, which needs a live path, never engaged. The drowning reflexes are off, so a
     * pass says something about the navigator's own override.
     */
    @Test
    void drownEscapeSurface(Game game, @Plot.Centered Plot plot) {
        BlockPos o = plot.at(0, 0, 0);
        final int cx = o.getX();
        final int cz = o.getZ();
        final int floorY = o.getY();
        final int standY = floorY + 1;
        SceneArena.buildFloor(plot, 0, 0, 0);
        // Shell first — an unwalled pool drains sideways.
        plot.fill(-4, -5, -3, 2, 0, 3, Blocks.STONE);
        // Basin: depth 4 at dx=-3 shallowing by 1 per column to depth 1 at dx=0, then rim.
        for (int dx = -3; dx <= 1; dx++) {
            int depth = Math.max(1, 4 - (dx + 3));
            plot.fill(dx, 1 - depth, -2, dx, 0, 2, Blocks.WATER);
        }
        BlockPos goal = new BlockPos(cx + 4, standY, cz);
        Settings.pin(game)
                .set("navigatorDrowningEscape", true)
                .set("allowBreak", false)
                .set("allowPlace", false)
                .set("autoDrownEscape", false)
                .set("autoFloatWhenDrowning", false);
        SceneBot bot = SceneBot.spawn(game, Level.OVERWORLD, cx - 3 + 0.5, floorY - 3, cz + 0.5);

        game.runOnServer(s -> {
            ServerLevel level = s.overworld();
            ServerPlayer fp = bot.player();
            fp.setAirSupply(40);
            walkTo(bot, goal);
            // A bot's air does not tick on its own here: drive the air simulation per tick, the same idiom as
            // DrownEscapeChainTest: −1/tick with eyes under, +4/tick (cap 300) at the surface.
            int airSim = 40;
            int minAir = airSim;
            int maxAir = airSim;
            int t = 0;
            for (; t < 400 && bot.busy(); t++) {
                bot.tick();
                airSim = fp.isEyeInFluid(FluidTags.WATER) ? Math.max(airSim - 1, -20) : Math.min(airSim + 4, 300);
                fp.setAirSupply(airSim);
                minAir = Math.min(minAir, airSim);
                maxAir = Math.max(maxAir, airSim);
            }
            String end = ending(bot);
            boolean reached =
                    Math.abs(fp.getX() - (goal.getX() + 0.5)) < 1.5 && Math.abs(fp.getZ() - (goal.getZ() + 0.5)) < 1.5;
            TestMod.LOG.warn(
                    "[wd.drownEscapeSurface] step={} ticks={} minAir={} maxAir={} pos={}",
                    end,
                    t,
                    minAir,
                    maxAir,
                    fp.position());
            if (minAir <= 0 || !fp.isAlive()) {
                fail("drownEscapeSurface: body drowned (minAir=" + minAir + ", alive=" + fp.isAlive() + ")");
            }
            if (maxAir < 200) {
                fail("drownEscapeSurface: air never recovered (maxAir=" + maxAir + "): t=" + t + " step=" + end
                        + " pos=" + fp.position() + " eyeInWater=" + fp.isEyeInFluid(FluidTags.WATER)
                        + " blockAtFeet=" + level.getBlockState(fp.blockPosition()));
            }
            if (!reached) {
                fail("drownEscapeSurface: navigator did not finish the crossing after surfacing: step=" + end + " pos="
                        + fp.position());
            }
        });
    }

    /**
     * Boxed-pocket churn and escalation: the goal sits behind a bedrock pocket sealed except its west mouth — no
     * around-route — so the horizon-committed search drives the bot in and it churns at the dead end until the
     * anti-churn escape fires (blacklist charge + sticky escalation, read from the progress probe). Once TWO churn
     * windows have fired, the east wall is OPENED and the run must still arrive through it. The release is deliberate:
     * self-escape geometries were each defeated by the stack (an around-corridor near the pocket snaps the adopted
     * escape's carrot THROUGH the thin wall; a sharp convex corner on the way out pins the drive in a micro-orbit). A
     * 3-tall perimeter rim keeps a best-effort route from walking the bot off the platform.
     */
    @Test
    void boxedChurnEscalate(Game game, @Plot.Centered @Plot.Size(x = 32, y = 16, z = 32) Plot plot) {
        BlockPos o = plot.at(0, 0, 0);
        final int cx = o.getX();
        final int cz = o.getZ();
        final int floorY = o.getY();
        final int standY = floorY + 1;
        plot.fill(-8, 0, -8, 13, 0, 8, Blocks.STONE);
        // Bedrock mass dx 0..5 across the full floor width, pocket interior dx 0..3 / dz -2..2 carved out.
        plot.fill(0, 1, -8, 5, 4, 8, Blocks.BEDROCK);
        plot.fill(0, 1, -2, 3, 4, 2, Blocks.AIR);
        plot.fill(-8, 1, -8, 13, 3, -8, Blocks.BEDROCK);
        plot.fill(-8, 1, 8, 13, 3, 8, Blocks.BEDROCK);
        plot.fill(-8, 1, -8, -8, 3, 8, Blocks.BEDROCK);
        plot.fill(13, 1, -8, 13, 3, 8, Blocks.BEDROCK);
        BlockPos goal = new BlockPos(cx + 9, standY, cz);
        // Horizon soft-commit: a short receding horizon best-effort-commits INTO the pocket. In a truly sealed pocket
        // the futile-search cap races the churn windows, so it gets headroom: this test is about the churn machinery.
        Settings.pin(game)
                .set("navigatorDebug", true)
                .set("pathfinderHorizonBlocks", 8)
                .set("navigatorFasterChurnRepath", true)
                .set("navigatorWallCornerFastChurn", true)
                .set("navigatorFutileSearchCap", 60)
                .set("allowBreak", false)
                .set("allowPlace", false);
        SceneBot bot = SceneBot.spawn(game, Level.OVERWORLD, cx - 7 + 0.5, standY, cz + 0.5);

        game.runOnServer(s -> {
            ServerLevel level = s.overworld();
            ServerPlayer fp = walkTo(bot, goal);
            Pattern churn = Pattern.compile("churnEsc=(\\d+)");
            int t = 0;
            int churnSeen = 0;
            boolean enteredPocket = false;
            boolean released = false;
            StringBuilder trace = new StringBuilder();
            for (; t < 1400 && bot.busy(); t++) {
                bot.tick();
                if (fp.getX() > cx + 1 && Math.abs(fp.getZ() - (cz + 0.5)) < 2.5) {
                    enteredPocket = true;
                }
                Matcher m = churn.matcher(progressProbe(bot));
                if (m.find()) {
                    churnSeen = Math.max(churnSeen, Integer.parseInt(m.group(1)));
                }
                if (!released && churnSeen >= 2) {
                    for (int dx = 4; dx <= 5; dx++) {
                        for (int dz = -2; dz <= 2; dz++) {
                            for (int dy = 1; dy <= 4; dy++) {
                                level.setBlockAndUpdate(
                                        new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
                            }
                        }
                    }
                    released = true;
                }
                if (t % 100 == 0) {
                    trace.append(String.format(
                            Locale.ROOT,
                            " t%d:(%.1f,%.1f,%.1f)[%s]",
                            t,
                            fp.getX() - cx,
                            fp.getY() - standY,
                            fp.getZ() - cz,
                            progressProbe(bot)));
                }
            }
            String end = ending(bot);
            boolean reached =
                    Math.abs(fp.getX() - (goal.getX() + 0.5)) < 1.5 && Math.abs(fp.getZ() - (goal.getZ() + 0.5)) < 1.5;
            TestMod.LOG.warn(
                    "[wd.boxedChurnEscalate] step={} ticks={} enteredPocket={} churnSeen={} pos={}",
                    end,
                    t,
                    enteredPocket,
                    churnSeen,
                    fp.position());
            String why = " step=" + end + " t=" + t + " pos=" + fp.position() + " lastStats=" + lastSearch(bot)
                    + " trace=" + trace;
            if (!enteredPocket) {
                fail("boxedChurnEscalate: rig broken — the horizon-committed search never entered the pocket:" + why);
            }
            if (churnSeen < 2) {
                fail("boxedChurnEscalate: churn escape fired " + churnSeen + "×(<2) in a sealed pocket:" + why);
            }
            if (!reached) {
                fail("boxedChurnEscalate: navigator never finished after the wall release: churnSeen=" + churnSeen
                        + " released=" + released + why);
            }
        });
    }

    /**
     * The water-SURFACE pillar: the step pointer must not pass a support that was never placed. A buoyant
     * {@code pillarUp} is classified by {@code shaftFlooded}, and two phases computed it differently: the climb phase
     * has a carve-out ({@code navigatorPillarSurfacePlace}: destination water with air above ⇒ PLACE a support), the
     * progress phase had none and treated an unfilled place cell as not-really-pending. Both now read one shared
     * answer, so the progress phase's arrival gate demands {@code onGround()} on a water-SURFACE cell and the pointer
     * waits for the support. The defect reads {@code pointerPastUnplaced}; the healthy run places the support.
     *
     * <p>TWO pillar steps, not one: a pillarUp's destination is always support+1, and vanilla will not place into a
     * cell the player still overlaps, so "high enough to place" and "standing on the destination" are the same
     * inequality — a plan ENDING at the destination returns ARRIVED before the climb phase can place anything. The goal
     * is the crest, one above. Pre-registered outcomes: support placed ⇒ PASS; not placed and pointer past ⇒ FAIL
     * (product defect); neither ⇒ the bot never rose to the placement threshold, which tested nothing and is logged,
     * not failed. Production runs with the flags ON; the test baseline turns them off, so this turns them back on.
     */
    @Test
    void surfacePillarPointerNeedsItsSupport(Game game, @Plot.Centered Plot plot) {
        BlockPos o = plot.at(0, 0, 0);
        final int cx = o.getX();
        final int cz = o.getZ();
        final int standY = o.getY() + 1;
        SceneArena.buildFloor(plot, 0, 0, 0);
        // Two-deep column whose TOP cell is the surface: water at standY and standY+1, air above.
        plot.fill(0, 1, 0, 0, 2, 0, Blocks.WATER);
        final BlockPos foot = new BlockPos(cx, standY, cz); // where the bot starts
        final BlockPos dest = new BlockPos(cx, standY + 1, cz); // the first pillarUp's waypoint
        final BlockPos support = foot; // its toPlace
        final BlockPos crest = dest.above(); // node 2 — the goal
        // navigatorPillarReachGoalNoSnap: production ON, and the subject is pillaring.
        Settings.pin(game)
                .set("navigatorPillarSurfacePlace", true)
                .set("allowPlace", true)
                .set("allowBreak", false)
                .set("navigatorPillarReachGoalNoSnap", true);
        SceneBot bot = SceneBot.spawn(game, Level.OVERWORLD, cx + 0.5, standY, cz + 0.5);

        game.runOnServer(s -> {
            ServerLevel level = s.overworld();
            ServerPlayer fp = bot.player();
            SimProbes.grantWaterEffects(fp);
            fp.getInventory().add(new ItemStack(Items.COBBLESTONE, 64)); // something to place
            // Vacuity guards: each names a way this arena could go green while staging a DIFFERENT case.
            if (!level.getFluidState(dest).is(FluidTags.WATER)) {
                fail("surfacePillar: the destination cell " + dest.toShortString() + " is not water; there is no "
                        + "divergence to observe, and a pass proves nothing.");
            }
            if (level.getFluidState(dest.above()).is(FluidTags.WATER)) {
                fail("surfacePillar: " + dest.above().toShortString() + " is water, so this is a FLOODED shaft, not a "
                        + "water-surface one; the carve-out condition does not hold.");
            }
            // Follow mode, adopted as a search result with recovery on, goalReached=true: a best-effort segment is one
            // the navigator may replace, and on the first run it did, inside tick one.
            List<DebugContracts.PlanEdge> plan = ScenePlan.syntheticPlan(
                    foot,
                    List.of(
                            ScenePlan.edge(dest, "pillarUp", List.of(support)),
                            ScenePlan.edge(crest, "pillarUp", List.of(dest))));
            bot.walkPlan(DebugContracts.WalkPlanParams.follow(bot.ref(), plan, true)
                    .goal(Targets.block(crest))
                    .from(Targets.pos(foot)));

            final double startY = fp.getY();
            double maxY = startY;
            boolean placed = false;
            boolean ranPillarUp = false;
            boolean pointerPastUnplaced = false;
            boolean walking = true;
            int maxStep = 0;
            int t = 0;
            for (; t < 200 && walking; t++) {
                // Before the tick as well as after: a tick that runs an edge and consumes its node leaves the pointer
                // past it. The plan is adopted on the first tick, so a pre-tick sample means something from the second.
                if (t > 0 && "pillarUp".equals(move(bot))) {
                    ranPillarUp = true;
                }
                bot.tick();
                walking = bot.busy();
                maxY = Math.max(maxY, fp.getY());
                if ("pillarUp".equals(move(bot))) {
                    ranPillarUp = true;
                }
                // blocksMotion, not !isAir: the support cell STARTS as water.
                if (level.getBlockState(support).blocksMotion()) {
                    placed = true;
                }
                int step = step(bot);
                maxStep = Math.max(maxStep, step);
                // Latched, and deliberately so: a placement after the pointer moved on does not undo the advance.
                if (step >= 2 && !placed) {
                    pointerPastUnplaced = true;
                }
            }
            BotDebugContracts.NavigatorReading nav = bot.navigator();
            String walk = nav.traces().getOrDefault("walkResult", "");
            String end = ending(bot);
            String readings = "support " + support.toShortString() + " placed=" + placed + " (now "
                    + level.getBlockState(support).getBlock() + "), peakY=" + maxY + " (placement threshold "
                    + (support.getY() + 1.0) + ", goal row " + crest.getY() + "), outcome " + end + " (" + t
                    + "/200 ticks), ranPillarUp=" + ranPillarUp + ", pointer high water " + maxStep + "/" + plan.size()
                    + ", walkResult=" + walk + ", finalPlan=" + nav.traces().getOrDefault("planTally", "")
                    + ", progressProbe=" + nav.traces().getOrDefault("progressProbe", "");
            if (end.contains("the plan was refused")) {
                fail("surfacePillar: the navigator rejected this two-step pillarUp plan (anchor gate); nothing below "
                        + "describes the subject under test: " + readings);
            }
            if (walk.contains("goalSnapped=true")) {
                fail("surfacePillar: snapGoalToStandable moved the goal off the cell under test, so every reading "
                        + "describes a different move; navigatorPillarReachGoalNoSnap must be on: " + readings);
            }
            if (!ranPillarUp) {
                fail("surfacePillar: no tick of this run executed the pillarUp edge, so the criteria do not describe "
                        + "the subject under test; the setup needs fixing, not the product. If finalPlan is not the "
                        + "pillarUp plan, a repath replaced it: " + readings);
            }
            if (pointerPastUnplaced) {
                fail("the pointer passed a support that was never placed: the climb phase classifies this as a "
                        + "water-surface shaft that needs a support placed, but the progress phase still treats it as a "
                        + "buoyant pillar whose unfilled place cell is not really pending: " + readings);
            }
            if (!placed) {
                TestMod.LOG.warn(
                        "[wd.surfacePillarPointerNeedsItsSupport] third tier: the pointer did not pass and the "
                                + "support was not placed. This run did not test the divergence; it only shows that the bot did "
                                + "not rise. Not a failure, but not a pass either: {}",
                        readings);
            }
        });
    }

    // ---------------------------------------------------------------- the walking bot

    /** The bot's empty-handed walk to {@code goal} started; its player. Server thread. */
    private static ServerPlayer walkTo(SceneBot bot, BlockPos goal) {
        bot.player().getInventory().clearContent();
        bot.goTo(Targets.block(goal));
        return bot.player();
    }

    /** The bedrock (or air) cap over the launch cells of the +1 riser at dx 1. */
    private static void ceiling(
            ServerLevel level, int cx, int floorY, int cz, net.minecraft.world.level.block.Block block) {
        for (int dz = -5; dz <= 5; dz++) {
            for (int dx = -1; dx <= 0; dx++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + 3, cz + dz), block.defaultBlockState());
            }
        }
    }

    /** {@code walking} while the goto runs; after, how it ended and its last error. */
    private static String ending(SceneBot bot) {
        if (bot.busy()) {
            return "walking";
        }
        ControlContracts.Slot g = bot.gotoSlot();
        return g.endReason().orElse("no end reason")
                + g.lastError().map(e -> " (" + e + ")").orElse("");
    }

    /** The navigator's last search, as {@code mc.debug.navigator} reads it. */
    private static String lastSearch(SceneBot bot) {
        return String.valueOf(bot.navigator().lastSearch().orElse(null));
    }

    /** The navigator's progress probe line, empty without a navigator. */
    private static String progressProbe(SceneBot bot) {
        return bot.navigator().traces().getOrDefault("progressProbe", "");
    }

    /** The move entering the node the plan steers at now, or null without a plan. */
    private static String move(SceneBot bot) {
        return bot.navigator()
                .plan()
                .flatMap(BotDebugContracts.PlanCursor::move)
                .orElse(null);
    }

    /** The plan's step pointer, 0 without a plan. */
    private static int step(SceneBot bot) {
        return bot.navigator().plan().map(BotDebugContracts.PlanCursor::step).orElse(0);
    }

    /** The process-wide count of navigator expectation alarms fired. */
    private static long alarmsFired(SceneBot bot) {
        return bot.navigator().counter("expectAlarmsFired");
    }
}
