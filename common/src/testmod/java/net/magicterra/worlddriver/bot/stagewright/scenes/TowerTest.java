package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.Locale;

import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.magicterra.worlddriver.bot.stagewright.kit.Targets;
import net.magicterra.worlddriver.testcontent.TestMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * <b>One order, many courses</b> — {@code TowerProcess} asked, in one {@code mc.bot.construct}, to climb from a low
 * place to a high one, and then checked that the bot got there.
 *
 * <h2>Why this family exists</h2>
 *
 * The only other test that puts a {@code TowerProcess} on a bot drives it <b>one course at a time</b>, landing the bot
 * before every call, in a shaft with stone on all four sides. The production caller is not shaped like that at all:
 * {@code JourneyEndRungs.smashCrystal} — the ladder's rung 20, and the <b>only</b> site that asks for more than one
 * course from a single call — issues {@code new TowerProcess(top, pillar)} where {@code top} is dozens of blocks up,
 * in the continuation of a walk, with <b>no hold and no landing wait</b>. So: that N courses produce N blocks of climb,
 * that the blocks land in the column the bot started in, that a bot still moving when the order arrives climbs at all.
 *
 * <h2>Rig rules this family keeps</h2>
 *
 * <ul>
 *   <li><b>Three readings between staging and driving, on every arm.</b> Where the feet actually are, that the target
 *       column is <b>air right now</b>, and how many blocks the bot carries. Without them every criterion is at risk of
 *       being {@code 0 == 0}. A staging mismatch is a hard failure that names the rig, never a soft check.</li>
 *   <li><b>Criteria are equalities, not inequalities.</b> {@code climbed == 12} and {@code spent == 12}, never
 *       {@code >= 1}. A tower that manages one course out of twelve is a defect.</li>
 *   <li><b>Three independent readings of the same climb, kept separate.</b> The bot's end cell, the inventory delta,
 *       and the blocks actually standing in the start column. They disagree in informative ways — a lean spends blocks
 *       and gains height while leaving the start column empty; a rejected place gains nothing and spends nothing; a
 *       bot that fell back down leaves a full column and no height.</li>
 *   <li><b>Every evidence row is published, so a PASS carries it too.</b> {@code placeTally()} is on every arm because
 *       it is the only reading that splits "never called / no face / no block" — and this process never calls
 *       {@code exAlarms.notePlace}, so a place it silently loses is invisible everywhere else.</li>
 *   <li><b>Only the walk arm plans.</b> The others drive the bot one whole tick at a time inside one server task:
 *       TowerProcess never touches the pathfinder, so a few hundred of its ticks in one task cost nothing. The walk
 *       arm's walk runs over real server ticks and only its tower half is synchronous.</li>
 * </ul>
 */
@Scene("wd.scenes")
@Execution(ExecutionMode.SAME_THREAD)
class TowerTest {

    /** The one block every arm builds with, in all three forms the code needs it in: the process is told an item ID,
     *  the inventory holds an Item and the world holds a Block, and a family that asserted about a different one of
     *  the three than it stocked would pass while measuring nothing. */
    private static final String BLOCK_ID = "minecraft:cobblestone";
    private static final Item ITEM = Items.COBBLESTONE;
    private static final Block BLOCK = Blocks.COBBLESTONE;

    /** Clear cells kept above every floor. Well past the tallest arm (12 courses) so no arm can be stopped by its own
     *  ceiling — a tower that hits rock reports the stuck guard, which would read like a verdict on the process. */
    private static final int HEADROOM = 40;

    /** Synchronous ticks any single tower drive may spend: roughly three times the longest honest run (twelve courses
     *  at about ten ticks each) and still well under the stuck guard's patience times the number of courses. */
    private static final int DRIVE_BUDGET = 400;

    /** The floor every arm stands on, plot-relative; {@link #HEADROOM} clear above it. */
    private static final int FLOOR = 0;

    /** {@code allowBreak} is OFF deliberately: a bot that can dig has a second way up, and a test about placing blocks
     *  that accepts a staircase is measuring something else. */
    private static Settings towerConfig(Game game) {
        return Settings.pin(game).set("allowPlace", true).set("allowBreak", false).set("navigatorDebug", false);
    }

    /** Air out the working box, then lay a {@code (2*half+1)} square of stone at {@code floorY} with {@link #HEADROOM}
     *  clear above it and <b>no walls</b>. */
    private static void stageFlatArena(Plot plot, int half, int floorY) {
        int pad = half + 2;
        plot.fill(-pad, floorY - 3, -pad, pad, floorY + HEADROOM + 4, pad, Blocks.AIR);
        plot.fill(-half, floorY, -half, half, floorY, half, Blocks.STONE);
    }

    /**
     * Three physics steps with no input, so the player is standing flush before anything is measured: a player that
     * has never moved reports {@code onGround() == false}, and vanilla's jump refuses a press on that.
     *
     * <p>{@link #serverTowersAfterAWalk} deliberately does NOT settle. Not being landed is the one variable that arm
     * exists to hold.
     */
    private static void settle(SceneBot bot) {
        bot.step(3);
    }

    /** Total count of an item across the WHOLE inventory, not the hotbar. */
    private static int carrying(ServerPlayer fp, Item item) {
        int n = 0;
        for (ItemStack st : fp.getInventory().items) if (st.is(item)) n += st.getCount();
        return n;
    }

    /** Cell-by-cell readback of a column, as {@code y221=cobblestone y222=air …}. */
    private static String column(ServerLevel level, int x, int y0, int n, int z) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            sb.append('y').append(y0 + i).append('=').append(BuiltInRegistries.BLOCK
                    .getKey(level.getBlockState(new BlockPos(x, y0 + i, z)).getBlock()).getPath());
        }
        return sb.toString();
    }

    private static int columnOf(ServerLevel level, int x, int y0, int n, int z, Block want) {
        int c = 0;
        for (int i = 0; i < n; i++) if (level.getBlockState(new BlockPos(x, y0 + i, z)).is(want)) c++;
        return c;
    }

    private static int columnSolid(ServerLevel level, int x, int y0, int n, int z) {
        int c = 0;
        for (int i = 0; i < n; i++) if (!level.getBlockState(new BlockPos(x, y0 + i, z)).isAir()) c++;
        return c;
    }

    /** Stock {@code stock} of the block in hotbar slot 0 and select it. Server thread. */
    private static void stockSlot0(SceneBot bot, int stock) {
        ServerPlayer fp = bot.player();
        fp.getInventory().items.set(0, new ItemStack(ITEM, stock));
        fp.getInventory().selected = 0;
    }

    /**
     * The three readings every arm takes BETWEEN staging and driving, published and then enforced — with a hard
     * failure rather than a soft check on purpose: these are statements about the rig, and a rig that is wrong makes
     * every criterion downstream meaningless rather than merely unmet. Server thread.
     *
     * @param wantFeet the foot cell the bot must be standing in, or {@code null} to skip that half (the walk arm
     *                 latches its tower's start from where the walk actually left the bot)
     */
    private static void requireStaged(TestReporter reporter, String arm, ServerPlayer fp, BlockPos wantFeet,
                                      int colX, int colY0, int colN, int colZ, int wantCarry) {
        ServerLevel level = fp.serverLevel();
        BlockPos feet = fp.blockPosition();
        int solid = columnSolid(level, colX, colY0, colN, colZ);
        int carry = carrying(fp, ITEM);
        String row = String.format(Locale.ROOT,
                "feet=%s%s | target column x=%d z=%d y=%d..%d -> %s | carrying %d %s (expected %d)",
                feet.toShortString(), wantFeet == null ? "" : " (expected " + wantFeet.toShortString() + ")",
                colX, colZ, colY0, colY0 + colN - 1, column(level, colX, colY0, colN, colZ),
                carry, BuiltInRegistries.ITEM.getKey(ITEM), wantCarry);
        reporter.publishEntry(arm + ".staged", row);
        if (wantFeet != null && !feet.equals(wantFeet))
            fail(arm + ": THE RIG, not the subject — the bot is not standing where the test staged it, so every climb"
                    + " measured against that datum is meaningless: " + row);
        if (solid != 0)
            fail(arm + ": THE RIG, not the subject — " + solid + " of the " + colN + " target cells are already solid,"
                    + " so 'the tower filled this column' would be true before the tower ran: " + row);
        if (carry != wantCarry)
            fail(arm + ": THE RIG, not the subject — the inventory does not hold what the test stocked, so the spend"
                    + " delta measures the staging: " + row);
    }

    // -------------------------------------------------------------- drive ----

    /** Everything one tower order produced, read three independent ways. */
    private static final class Run {
        int startX, startZ, startFeetY, endX, endZ, endFeetY;
        int ticks, spent, carriedBefore, carriedAfter, columnPlaced, columnSolid;
        boolean finished;
        String lastError, placeTally, columnAfter;

        int climbed() { return endFeetY - startFeetY; }
    }

    /** The highest y the player reached during one order: "jumped but rose only 0.2" and "never jumped at all" print
     *  the same floored cell, and this separates them. A jump that fired peaks at +1.25 in the open and at +0.2 under
     *  a lid; one that never fired never leaves +0.00. */
    private static final class Apex implements Runnable {
        final ServerPlayer fp;
        final double base;
        double apex;

        Apex(ServerPlayer fp) {
            this.fp = fp;
            this.base = fp.getY();
            this.apex = base;
        }

        @Override
        public void run() {
            apex = Math.max(apex, fp.getY());
        }

        String row() {
            return String.format(Locale.ROOT, "start y=%.2f highest y=%.2f (rose %.2f blocks; full jump +1.25, hit the"
                    + " lid +0.20, no jump +0.00)", base, apex, apex - base);
        }
    }

    /**
     * Latch the start, run ONE tower order to completion (or to {@code budget}), and publish every reading it produced.
     * Server thread. The order goes through {@code mc.bot.construct}, which builds exactly the process
     * {@code JourneyEndRungs.smashCrystal} constructs ({@code TowerProcess(targetY, block)}, hotbar only).
     *
     * @param columnCells how many cells of the START column to read back afterwards — the reading that catches a tower
     *                    which gained height by leaning into another column
     * @param beforeEachTick run immediately before every {@code bot.tick()}
     */
    private static Run runTower(TestReporter reporter, String arm, SceneBot bot, int targetY, int columnCells,
                                int budget, Runnable beforeEachTick) {
        ServerPlayer fp = bot.player();
        ServerLevel level = fp.serverLevel();
        Run r = new Run();
        BlockPos start = fp.blockPosition();
        r.startX = start.getX(); r.startZ = start.getZ(); r.startFeetY = start.getY();
        r.carriedBefore = carrying(fp, ITEM);

        bot.tower(targetY, BLOCK_ID, false);
        int t = 0;
        for (; t < budget && bot.busy(); t++) {
            beforeEachTick.run();
            bot.tick();
        }
        r.ticks = t;
        r.finished = !bot.busy();

        BlockPos end = fp.blockPosition();
        r.endX = end.getX(); r.endZ = end.getZ(); r.endFeetY = end.getY();
        r.carriedAfter = carrying(fp, ITEM);
        r.spent = r.carriedBefore - r.carriedAfter;
        r.lastError = bot.status().slots().builder().lastError().orElse(null);
        r.placeTally = bot.body().hands().placeTally();
        bot.cancel();
        r.columnAfter = column(level, r.startX, r.startFeetY, columnCells, r.startZ);
        r.columnPlaced = columnOf(level, r.startX, r.startFeetY, columnCells, r.startZ, BLOCK);
        r.columnSolid = columnSolid(level, r.startX, r.startFeetY, columnCells, r.startZ);

        reporter.publishEntry(arm + ".order", "TowerProcess(targetY=" + targetY + ", " + BLOCK_ID + ") from feet y="
                + r.startFeetY + " (" + (targetY - r.startFeetY) + " courses asked)");
        reporter.publishEntry(arm + ".drive", "ran " + r.ticks + " ticks of " + budget + " | finished=" + r.finished
                + (r.finished ? "" : " — the budget ran out, the process never reported a verdict"));
        reporter.publishEntry(arm + ".lastError", r.lastError == null ? "null (the process never set one)" : r.lastError);
        reporter.publishEntry(arm + ".feet", "start " + r.startX + "," + r.startFeetY + "," + r.startZ
                + " -> end " + r.endX + "," + r.endFeetY + "," + r.endZ + " | climbed " + r.climbed()
                + " | drift " + (r.endX - r.startX) + "," + (r.endZ - r.startZ));
        reporter.publishEntry(arm + ".stock", r.carriedBefore + " -> " + r.carriedAfter + " "
                + BuiltInRegistries.ITEM.getKey(ITEM) + " (spent " + r.spent + ")");
        reporter.publishEntry(arm + ".column", "start column x=" + r.startX + " z=" + r.startZ + " y=" + r.startFeetY
                + ".." + (r.startFeetY + columnCells - 1) + " -> " + r.columnAfter + " (" + r.columnPlaced
                + " placed, " + r.columnSolid + " solid)");
        reporter.publishEntry(arm + ".placeTally", r.placeTally);
        TestMod.LOG.info("[tower:{}] ticks={} climbed={} spent={} columnPlaced={} drift={},{} lastError={}", arm,
                r.ticks, r.climbed(), r.spent, r.columnPlaced, r.endX - r.startX, r.endZ - r.startZ, r.lastError);
        return r;
    }

    // ---------------------------------------------------------------- arms ----

    /**
     * <b>The missing base case: twelve courses from one order, on open ground.</b> Flat 7x7 stone, the bot in the
     * middle, no walls, 40 clear cells overhead, 64 cobblestone in hotbar slot 0.
     *
     * <h2>Criteria — four, all equalities, and none of them redundant</h2>
     *
     * <ol>
     *   <li><b>{@code endFeetY == startFeetY + 12}</b>, not {@code >=}.</li>
     *   <li><b>exactly 12 blocks spent.</b> Fewer means courses that never cost anything; more means placements that
     *       did not become the tower.</li>
     *   <li><b>all 12 cells of the START column are cobblestone.</b> Height and spend together still admit a tower that
     *       leaned.</li>
     *   <li><b>the verdict reads {@code done (placed=12}.</b> The process's own count is verified against the world, so
     *       a disagreement between it and 2/3 localises the defect to the place actuator.</li>
     * </ol>
     */
    @Test
    void serverTowersTwelveCourses(Game game, @Plot.Centered @Plot.Size(x = 16, y = 96, z = 16) Plot plot,
                                   TestReporter reporter) {
        final int standY = FLOOR + 1, courses = 12, stock = 64;
        towerConfig(game);
        stageFlatArena(plot, 3, FLOOR);
        BlockPos stand = plot.at(0, standY, 0);
        SceneBot driver = SceneBot.spawn(game, stand);

        Run r = game.onServer(s -> {
            stockSlot0(driver, stock);
            settle(driver);
            ServerPlayer fp = driver.player();
            requireStaged(reporter, "tower", fp, stand, stand.getX(), stand.getY(), courses, stand.getZ(), stock);
            return runTower(reporter, "tower", driver, fp.blockPosition().getY() + courses, courses, DRIVE_BUDGET,
                    () -> {});
        });
        final int startFeetY = r.startFeetY;
        game.check(r.endFeetY == startFeetY + courses, "A: the tower reaches the target height: end feet y "
                + r.endFeetY + " (start " + startFeetY + " + " + courses + " courses = " + (startFeetY + courses) + ")");
        game.check(r.spent == courses, "B: exactly " + courses + " blocks spent: spent " + r.spent + " (started with "
                + stock + ", ended with " + r.carriedAfter + ")");
        game.check(r.columnPlaced == courses, "C: all " + courses + " cells of the column in the world are "
                + BLOCK_ID + ": " + r.columnAfter + " (" + r.columnPlaced + " placed)");
        game.check(r.lastError != null && r.lastError.startsWith("done (placed=" + courses),
                "D: the process's own result message is 'done (placed=" + courses + "': got »" + r.lastError + "«");
    }

    /**
     * <b>{@code JourneyEndRungs.smashCrystal}'s own ordering.</b> A twelve-cell runway, an {@code mc.bot.goto} to its
     * far end, and then — from the walk's continuation, with <b>no hold and no {@code onGround} wait</b> — an
     * eight-course tower.
     *
     * <ul>
     *   <li><b>Drift.</b> {@code TowerProcess} computes the support cell from the player's CURRENT x/z, while
     *       {@code jumpFromY} was latched at the jump. A player still carrying the walk's momentum fills a column it has
     *       already left — criteria C and D.</li>
     *   <li><b>The settle tax.</b> READY returns early while {@code !onGround()}, and {@code stuckTicks} counts from the
     *       first tick regardless — criteria A and B.</li>
     * </ul>
     *
     * <p><b>Do not add a hold to make this green.</b> If the answer is "callers must land first", that belongs in
     * {@code TowerProcess} as a landing wait that does not spend the stuck budget.
     */
    @Test
    void serverTowersAfterAWalk(Game game, @Plot.Centered @Plot.Size(x = 32, y = 96, z = 16) Plot plot,
                                TestReporter reporter) {
        final int standY = FLOOR + 1, runway = 12, courses = 8, stock = 64;
        // The only arm that plans: real slices over real server ticks.
        towerConfig(game).set("pathfinder.sliceMs", 20).set("pathfinder.maxMs", 2000);
        plot.fill(-3, FLOOR - 3, -3, runway + 3, FLOOR + HEADROOM + 4, 3, Blocks.AIR);
        plot.fill(0, FLOOR, -1, runway, FLOOR, 1, Blocks.STONE);
        BlockPos stand = plot.at(0, standY, 0);
        BlockPos far = plot.at(runway, standY, 0);
        SceneBot driver = SceneBot.spawn(game, stand);

        game.runOnServer(s -> {
            stockSlot0(driver, stock);
            // NO settle(): starting un-landed is this arm's variable, and the walk provides it. Staged against the
            // runway END, where the tower is expected to start; re-checked where the walk actually left the bot.
            requireStaged(reporter, "walk", driver.player(), stand, far.getX(), far.getY(), courses, far.getZ(), stock);
        });

        // A started goto puts the bot on the tick list, so the walk runs on real server ticks. The bot is parked on the
        // very tick the walk ends, so no idle tick falls between the walk and the order.
        driver.goTo(Targets.block(far));
        game.await().within(400).as("the walk to the runway end ends").until(() -> {
            if (driver.busy()) return false;
            driver.park();
            return true;
        });

        Run r = game.onServer(s -> {
            ServerPlayer fp = driver.player();
            BlockPos at = fp.blockPosition();
            reporter.publishEntry("walk.end", "arrived at " + at.toShortString() + " (runway end " + far.toShortString()
                    + ") | endReason=" + driver.gotoSlot().endReason().orElse(null) + " | onGround=" + fp.onGround()
                    + " | h=" + String.format(Locale.ROOT, "%.4f",
                            Math.hypot(fp.getDeltaMovement().x, fp.getDeltaMovement().z)));
            if (at.getY() != standAbs(plot, standY) || at.getX() < far.getX() - 1)
                fail("serverTowersAfterAWalk: THE RIG, not the subject — the walk did not reach the runway end, so"
                        + " nothing this arm measures would be about a tower started from a walk: ended at "
                        + at.toShortString());

            requireStaged(reporter, "tower", fp, null, at.getX(), at.getY(), courses, at.getZ(), stock);
            return runTower(reporter, "tower", driver, at.getY() + courses, courses, DRIVE_BUDGET, () -> {});
        });
        final int startX = r.startX, startZ = r.startZ, startFeetY = r.startFeetY;
        game.check(r.endFeetY == startFeetY + courses, "A: the bot rises " + courses + " blocks: end feet y "
                + r.endFeetY + " (start " + startFeetY + "; the walk's momentum is the only difference from"
                + " serverTowersTwelveCourses)");
        game.check(r.spent == courses, "B: exactly " + courses + " blocks spent: spent " + r.spent);
        game.check(r.columnPlaced == courses, "C: all " + courses + " cells of the start column hold placed blocks: "
                + r.columnAfter);
        game.check(r.endX == startX && r.endZ == startZ, "D: the end x/z equals the x/z where the tower started:"
                + " started at x=" + startX + " z=" + startZ + ", ended at x=" + r.endX + " z=" + r.endZ);
    }

    private static int standAbs(Plot plot, int standY) {
        return plot.at(0, standY, 0).getY();
    }

    /**
     * <b>Two orders that ask for no climb at all</b>: tower to the cell the bot already stands in, and to one three
     * blocks BELOW it. Both used to exit on the first tick with {@code "done (placed=0, feetY=…)"} — the SAME verdict
     * shape a finished tower reports, so a caller could not tell "never needed to build" from "finished building".
     *
     * <ol>
     *   <li>nothing is placed, nothing is spent, the foot cell stays air, and the process reports on its FIRST tick;</li>
     *   <li>the verdict must not read {@code done (placed=…}. Phrased as "not that prefix" rather than naming a
     *       replacement string this test would then be dictating.</li>
     * </ol>
     *
     * <p>Each order gets its own bot two cells away, so the second order's staged readings cannot depend on the first
     * order's outcome.
     */
    @Test
    void serverTowersToWhereItStands(Game game, @Plot.Centered @Plot.Size(x = 16, y = 96, z = 16) Plot plot,
                                     TestReporter reporter) {
        final int standY = FLOOR + 1, stock = 64;
        towerConfig(game);
        stageFlatArena(plot, 3, FLOOR);
        noOpLeg(game, plot, reporter, "atLevel", -2, standY, stock, 0, "targetY == current feet cell");
        noOpLeg(game, plot, reporter, "below", 2, standY, stock, -3, "targetY 3 blocks below the current feet cell");
    }

    /** One order that asks for no climb at all: its own bot, its own column, its own evidence keys. */
    private static void noOpLeg(Game game, Plot plot, TestReporter reporter, String arm, int x, int standY, int stock,
                                int dTargetY, String order) {
        BlockPos stand = plot.at(x, standY, 0);
        SceneBot driver = SceneBot.spawn(game, stand);
        Run r = game.onServer(s -> {
            stockSlot0(driver, stock);
            settle(driver);
            ServerPlayer fp = driver.player();
            requireStaged(reporter, arm, fp, stand, stand.getX(), stand.getY(), 1, stand.getZ(), stock);
            return runTower(reporter, arm, driver, fp.blockPosition().getY() + dTargetY, 1, DRIVE_BUDGET, () -> {});
        });
        String what = order + " (" + (r.startFeetY + dTargetY) + ")";
        game.check(r.spent == 0 && r.columnSolid == 0, arm + " A: nothing is built [" + what + "]: spent " + r.spent
                + ", foot cell now »" + r.columnAfter + "«");
        game.check(r.ticks == 1, arm + " B: the order ends on the first tick [" + what + "]: ran " + r.ticks
                + " ticks, finished=" + r.finished);
        game.check(r.lastError != null && !r.lastError.startsWith("done (placed="), arm + " C: \"never needed to"
                + " build\" and \"finished building\" must not produce the same result message [" + what + "]: got »"
                + r.lastError + "«");
    }

    /**
     * <b>The anti-overfit arm: nine non-blocks in the hotbar, the cobblestone in the bag.</b> {@code TowerProcess} scans
     * slots 0..8 for the preferred block outside creative — spending blocks the caller did not put in hand is a side
     * effect no verb should have — and the cheapest way to turn any arm above green is to widen it.
     *
     * <ol>
     *   <li><b>Nothing is placed and nothing is spent.</b></li>
     *   <li><b>The verdict is {@code "no placeable block in hotbar"} and NOT {@code "…out of blocks?"}.</b> A bot holding
     *       64 cobblestone told it ran out of blocks has been pointed at the inventory when the fault is in the hotbar
     *       scan. Checked as two clauses: a deliberate reword updates the first and is still caught by the second if
     *       it starts naming the wrong cause.</li>
     * </ol>
     */
    @Test
    void serverTowersWithAFullBackpack(Game game, @Plot.Centered @Plot.Size(x = 16, y = 96, z = 16) Plot plot,
                                       TestReporter reporter) {
        final int standY = FLOOR + 1, courses = 6, stock = 64, bagSlot = 20;
        towerConfig(game);
        stageFlatArena(plot, 3, FLOOR);
        BlockPos stand = plot.at(0, standY, 0);
        SceneBot driver = SceneBot.spawn(game, stand);

        Run r = game.onServer(s -> {
            ServerPlayer fp = driver.player();
            // Nine hotbar slots of things that are emphatically not blocks, so the hotbar is FULL rather than merely
            // lacking cobblestone: an empty slot would let a "shuffle the bag into the gap" fix pass unnoticed.
            Item[] hotbar = {
                    Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.WOODEN_PICKAXE,
                    Items.BUCKET, Items.WATER_BUCKET, Items.LAVA_BUCKET,
                    Items.BREAD, Items.COOKED_BEEF, Items.APPLE };
            for (int i = 0; i < hotbar.length; i++) fp.getInventory().items.set(i, new ItemStack(hotbar[i]));
            fp.getInventory().items.set(bagSlot, new ItemStack(ITEM, stock));
            fp.getInventory().selected = 0;
            settle(driver);

            requireStaged(reporter, "tower", fp, stand, stand.getX(), stand.getY(), courses, stand.getZ(), stock);
            return runTower(reporter, "tower", driver, fp.blockPosition().getY() + courses, courses, DRIVE_BUDGET,
                    () -> {});
        });
        game.check(r.spent == 0 && r.columnSolid == 0, "A: not a single block is placed: spent " + r.spent + " of the "
                + stock + " parked in the bag, target column now »" + r.columnAfter + "«");
        game.check("no placeable block in hotbar".equals(r.lastError), "B-a: the result message names the HOTBAR:"
                + " got »" + r.lastError + "« (place tally: " + r.placeTally + ")");
        game.check(r.lastError != null && !r.lastError.contains("out of blocks"), "B-b: the result message does not"
                + " claim the bot ran out of blocks: got »" + r.lastError + "« while the bot carries "
                + r.carriedAfter + " " + BLOCK_ID);
    }

    // ------------------------------------------------- the ceiling arms ----

    /** Ticks a tower may spend learning that it cannot rise. "Is the head cell solid?" is a block read; ten leaves room
     *  for the settle and one attempt without leaving room for a sixty-tick patience. */
    private static final int CEILING_VERDICT_TICKS = 10;

    /** The player's own box as cells, so a straddle is a reading rather than an inference. */
    private static String footprint(ServerPlayer fp) {
        AABB b = fp.getBoundingBox();
        return String.format(Locale.ROOT, "x=%.2f z=%.2f box x[%.2f,%.2f] z[%.2f,%.2f] -> columns x %d..%d"
                        + " z %d..%d (blockPosition reports only %d,%d)", fp.getX(), fp.getZ(),
                b.minX, b.maxX, b.minZ, b.maxZ,
                Mth.floor(b.minX + 1.0E-7), Mth.floor(b.maxX - 1.0E-7),
                Mth.floor(b.minZ + 1.0E-7), Mth.floor(b.maxZ - 1.0E-7),
                fp.blockPosition().getX(), fp.blockPosition().getZ());
    }

    /**
     * <b>A ceiling one cell over the head: the wedge that cost the ladder rungs 10 through 12.</b>
     *
     * <pre>
     * vein2.exit#3.climb.1.stalled = stuck (no Y gain in 60t: placed=0, holding=64, phase=JUMPING, apexFeetY=44)
     * vein2.exit#3.climb.1.state   = onGround=true inWater=false y=44.00
     * </pre>
     *
     * {@code JUMPING} is left on {@code p.getY() >= jumpFromY + 1.0} and on nothing else, so <b>a jump that fails to
     * lift the player one whole block ends the order</b>. A block at {@code feet+2} is the cheapest way to produce that
     * short rise — a two-cell mine corridor.
     *
     * <h2>The control arm runs first, and it is the arm with the fault</h2>
     *
     * Under the ceiling the tower cannot deliver the height it was ordered; a control that climbs anyway means the
     * ceiling was never in the way, a hard failure naming the rig. Its criteria are about the VERDICT: the order ends,
     * it ends quickly, and the verdict names the obstruction rather than {@code phase=JUMPING}. The subject is the same
     * order with that one ceiling row taken away, and it must climb all four courses.
     */
    @Test
    void serverTowersUnderALowCeiling(Game game, @Plot.Centered @Plot.Size(x = 16, y = 96, z = 16) Plot plot,
                                      TestReporter reporter) {
        final int standY = FLOOR + 1, courses = 4, stock = 64, ceilY = standY + 2;
        towerConfig(game);
        BlockPos stand = plot.at(0, standY, 0);
        final int ceilAbs = plot.at(0, ceilY, 0).getY();

        // ---- control: the ceiling is in, and the tower must NOT get out from under it ----
        stageFlatArena(plot, 3, FLOOR);
        plot.fill(-3, ceilY, -3, 3, ceilY, 3, Blocks.STONE);
        SceneBot control = SceneBot.spawn(game, stand);
        Run c = game.onServer(s -> {
            stockSlot0(control, stock);
            settle(control);
            ServerPlayer cfp = control.player();
            // Only the cells UNDER the lid: the lid deliberately sits inside the four cells the order asks for.
            requireStaged(reporter, "control", cfp, stand, stand.getX(), stand.getY(), ceilY - standY, stand.getZ(), stock);
            reporter.publishEntry("control.ceiling", "stone at y=" + ceilAbs + " over the whole 7x7 | feet y="
                    + stand.getY() + ", so the cell a one-block rise needs is y=" + ceilAbs + " = "
                    + cfp.serverLevel().getBlockState(plot.at(0, ceilY, 0)).getBlock());
            Apex apex = new Apex(cfp);
            Run run = runTower(reporter, "control", control, stand.getY() + courses, ceilY - standY, DRIVE_BUDGET, apex);
            reporter.publishEntry("control.apex", apex.row());
            reporter.publishEntry("control.jumpTick", "last emitted jump impulse t="
                    + control.navigator().counter("lastJumpTick") + ". With control.apex: not -1 and apex ≈ +0.20 means"
                    + " the bot jumped, hit the lid and fell back; -1 and apex = +0.00 means READY named the lid first");
            control.remove();
            return run;
        });
        TestMod.LOG.info("[tower:ceiling] control climbed={} ticks={} finished={} err={}",
                c.climbed(), c.ticks, c.finished, c.lastError);
        if (c.climbed() == courses)
            fail("THE RIG, not the subject: the bot climbed " + c.climbed() + " courses with stone at y=" + ceilAbs
                    + " over every column of the arena, so the ceiling was never in the way — " + c.columnAfter);

        game.check(c.finished, "A: a tower that cannot rise still reports a result message instead of exhausting the"
                + " budget: ran " + c.ticks + " of " + DRIVE_BUDGET + " ticks, verdict »" + c.lastError + "«");
        game.check(c.ticks <= CEILING_VERDICT_TICKS, "B: the result message arrives quickly: " + c.ticks
                + " ticks (limit " + CEILING_VERDICT_TICKS + ")");
        game.check(c.lastError != null && c.lastError.contains(String.valueOf(ceilAbs))
                && !c.lastError.contains("phase=JUMPING"), "C: the result message names the blocking cell (y="
                + ceilAbs + ") rather than only the phase: got »" + c.lastError + "«");
        game.check(c.spent == 0, "D: no block is spent: spent " + c.spent + ", tower start column »" + c.columnAfter + "«");

        // ---- subject: the same order, the same rig, that one row of stone gone ----
        stageFlatArena(plot, 3, FLOOR);
        SceneBot subject = SceneBot.spawn(game, stand);
        Run sr = game.onServer(s -> {
            stockSlot0(subject, stock);
            settle(subject);
            ServerPlayer sfp = subject.player();
            requireStaged(reporter, "subject", sfp, stand, stand.getX(), stand.getY(), courses, stand.getZ(), stock);
            Apex apex = new Apex(sfp);
            Run run = runTower(reporter, "subject", subject, stand.getY() + courses, courses, DRIVE_BUDGET, apex);
            reporter.publishEntry("subject.apex", apex.row());
            return run;
        });
        game.check(sr.climbed() == courses, "E: the two arms differ only by the stone layer at y=" + ceilAbs
                + ": rose " + sr.climbed() + " blocks (control arm " + c.climbed() + ")");
        game.check(sr.spent == courses, "F: exactly " + courses + " blocks spent: spent " + sr.spent);
    }

    /**
     * <b>The ceiling is over the NEXT column, and the caller's own check cannot see it.</b>
     * {@code JourneyShaft.ascendByTowering} clears {@code at.above(2)} before every course, where {@code at} is the
     * player's {@code blockPosition()} — one column. A player is 0.6 wide, so its box straddles two columns whenever it
     * stands within 0.3 of a cell boundary, and a rise is stopped by whichever of them is lowest.
     *
     * <h2>One variable, and it is a fifth of a block</h2>
     *
     * Both runs use the same single stone cell at {@code (-1, standY+2, 0)}. The control's player sits at
     * {@code x = +0.05} — box straddling into that neighbour — and the subject's at {@code x = +0.5}, clear of it. Both
     * report the same {@code blockPosition()}, so <b>the cell the caller checks is air in both</b>.
     */
    @Test
    void serverTowersUnderTheNeighboursCeiling(Game game, @Plot.Centered @Plot.Size(x = 16, y = 96, z = 16) Plot plot,
                                               TestReporter reporter) {
        final int standY = FLOOR + 1, courses = 4, stock = 64, ceilY = standY + 2;
        towerConfig(game);
        BlockPos stand = plot.at(0, standY, 0);
        final BlockPos lid = plot.at(-1, ceilY, 0), own = plot.at(0, ceilY, 0);

        // ---- control: the player straddles into the lidded column ----
        stageFlatArena(plot, 3, FLOOR);
        plot.place(-1, ceilY, 0, Blocks.STONE);
        SceneBot control = SceneBot.spawn(game, plot.dimension(), stand.getX() + 0.05, stand.getY(), stand.getZ() + 0.5);
        Run c = game.onServer(s -> {
            stockSlot0(control, stock);
            settle(control);
            ServerPlayer cfp = control.player();
            ServerLevel level = cfp.serverLevel();
            requireStaged(reporter, "control", cfp, stand, stand.getX(), stand.getY(), courses, stand.getZ(), stock);
            reporter.publishEntry("control.footprint", footprint(cfp) + " | lid " + lid.toShortString() + "="
                    + level.getBlockState(lid).getBlock() + " | cell the caller checks " + own.toShortString() + "="
                    + level.getBlockState(own).getBlock());
            Apex apex = new Apex(cfp);
            Run run = runTower(reporter, "control", control, stand.getY() + courses, courses, DRIVE_BUDGET, apex);
            reporter.publishEntry("control.apex", apex.row());
            if (run.climbed() == courses)
                fail("THE RIG, not the subject: a player whose box reaches into " + lid.toShortString() + " climbed all "
                        + courses + " courses, so either the stone is not there or the player is not straddling — "
                        + footprint(cfp) + " | " + run.columnAfter);
            control.remove();
            return run;
        });
        TestMod.LOG.info("[tower:neighbour] control climbed={} ticks={} finished={} err={}",
                c.climbed(), c.ticks, c.finished, c.lastError);

        game.check(c.finished, "A: a result message is reported even when the neighbouring column is sealed: ran "
                + c.ticks + " of " + DRIVE_BUDGET + " ticks, verdict »" + c.lastError + "«");
        game.check(c.lastError != null && c.lastError.contains(lid.toShortString()), "B: the result message names the"
                + " neighbouring cell " + lid.toShortString() + " (not the bot's own column " + own.toShortString()
                + ", which is empty): got »" + c.lastError + "«");

        // ---- subject: same arena, same lid, the player half a block further in ----
        stageFlatArena(plot, 3, FLOOR);
        plot.place(-1, ceilY, 0, Blocks.STONE);
        SceneBot subject = SceneBot.spawn(game, stand);
        Run sr = game.onServer(s -> {
            stockSlot0(subject, stock);
            settle(subject);
            ServerPlayer sfp = subject.player();
            requireStaged(reporter, "subject", sfp, stand, stand.getX(), stand.getY(), courses, stand.getZ(), stock);
            reporter.publishEntry("subject.footprint", footprint(sfp) + " | lid still at " + lid.toShortString() + "="
                    + sfp.serverLevel().getBlockState(lid).getBlock());
            return runTower(reporter, "subject", subject, stand.getY() + courses, courses, DRIVE_BUDGET, () -> {});
        });
        game.check(sr.climbed() == courses, "C: the two arms differ only by the bot's position within the cell"
                + " (control x+0.05, subject x+0.5), with an identical lid: rose " + sr.climbed() + " blocks (control"
                + " arm " + c.climbed() + ")");
        game.check(sr.spent == courses, "D: exactly " + courses + " blocks spent: spent " + sr.spent);
    }
}
