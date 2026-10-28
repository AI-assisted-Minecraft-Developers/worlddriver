package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Locale;

import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts;
import net.magicterra.worlddriver.bot.stagewright.kit.NavWalk;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.magicterra.worlddriver.bot.stagewright.kit.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * <b>A player barely on a ledge over the void, holding blocks, with no legal move</b> — and the other edge cases of
 * footing the journey ladder died on: a bridge the planner could not count the blocks for, the head of a bridge the bot
 * built itself, a bow that would not fire, a lava shore and a lava rim.
 *
 * <h2>The cell the first test is a copy of</h2>
 *
 * Journey rung 20 has died at one coordinate across two runs with two different symptoms:
 *
 * <pre>{@code
 * run l: footing guard: sole 0.1594 < 0.18 at -32,85,27 beside a lethal drop -> sneak-pin
 *        ascend dead-zone UNREACHABLE move=diagUp node=(-33,86,26) foot=(-32,85,27)
 *        -> stood there 2400 ticks, the whole walk budget
 * run t: bot left the world; last stood on -32,85,27 (108 ticks earlier)
 * }</pre>
 *
 * The bot stands on 16% of its sole at the end of a bridge it built itself, void on every side, the plan's next node
 * one cell diagonally up, and 500+ cobblestone in the bag. The footing guard sneak-pins it (right), the stride
 * floor-guard refuses the next step (right), the recovery hop refuses to jump with a lethal drop one cell away
 * (right), and the ascent executor calls the node UNREACHABLE (right). <b>Four correct refusals and no legal move.</b>
 *
 * <h2>Why arenas rather than another ladder run</h2>
 *
 * Three remedies were landed straight onto the ladder and the ladder could judge none of them: one helped but not
 * enough, one was never observed to fire, and one fired zero times in a whole run. A rung that dies somewhere new
 * every run cannot tell a fix from a coincidence. These arenas reproduce the cells on their own.
 *
 * <h2>Bottomless columns</h2>
 *
 * The widening, bridge and bridge-head tests stand over a column with no floor down to the world's bottom: the guards
 * they judge only arm over a column that is bottomless to {@code Safety.BOTTOMLESS_SCAN_FLOOR}, so a catch floor would
 * measure the arena. Their plots run the dimension's whole height, so everything under the deck is air.
 */
@Scene("wd.scenes")
@Execution(ExecutionMode.SAME_THREAD)
class ThinFootingTest {

    /** Clear cells under the ledge. Past survivable fall at full health, so a player that leaves the ledge is
     *  unambiguously lost rather than merely hurt. */
    private static final int VOID_DEPTH = 30;

    /** Idle ticks the stand must survive untouched before the subject is allowed to act. */
    private static final int SETTLE_TICKS = 20;

    /** Deck height, plot-relative, of the tests over a bottomless column. */
    private static final int DECK = 40;

    /**
     * <h2>The two clauses, and why the second is the whole point</h2>
     *
     * <ol>
     *   <li><b>It must not fall.</b> The lowest y must stay at the ledge.</li>
     *   <li><b>It must actually reach the node.</b> Without this, standing perfectly still is a full-marks answer —
     *       and standing still is exactly what the bot did. The guards already satisfy clause 1; the entire defect
     *       lives in clause 2.</li>
     * </ol>
     *
     * <p>The remedy this waits for is one block placed into the empty column under the overhanging half of the sole
     * ({@code Safety.widenFooting}): the stride guard plugs the cell AHEAD, and nothing had ever plugged the cell the bot
     * is already half off.
     */
    @Test
    void serverWidensAThinFooting(Game game, @Plot.Centered @Plot.Size(x = 24, y = 384, z = 24) Plot plot,
                                  TestReporter reporter) {
        final int slot = 0;
        final int deckY = DECK, standY = deckY + 1;
        // One 1-wide strip of ledge and a single landing cell one up and one across — the diagUp the ladder's plan
        // keeps producing. Everything else is air, so any block found under the bot afterwards was put there by it.
        plot.fill(0, deckY, -3, 0, deckY, 0, Blocks.OBSIDIAN);
        plot.place(-1, deckY + 1, 1, Blocks.OBSIDIAN);
        BlockPos landing = plot.at(-1, deckY + 1, 1);
        // A catch floor VOID_DEPTH down everywhere except a GENUINE shaft under the cells the bot's own footprint
        // spans (dx -2..2, dz -2..3), open to the world's floor. The remedy only spends a block over a column that is
        // bottomless — and it is right to: over an ordinary drop a thin sole is a graze, and paying a block per ridge
        // walk eats a bridging contract. The End void the ladder dies over has no floor, so neither does this.
        int floorY = deckY - VOID_DEPTH;
        plot.fill(-8, floorY, -8, 8, floorY, 8, Blocks.STONE);
        plot.fill(-2, floorY, -2, 2, floorY, 3, Blocks.AIR);

        Settings settings = Settings.pin(game)
                .set("allowPlace", true)                 // the remedy IS a placement
                .set("allowBreak", false)                // digging out is not the subject
                .set("navigatorDebug", true);

        BlockPos stand = plot.at(0, standY, 0);
        SceneBot bot = SceneBot.spawn(game, stand);
        final int cx = plot.origin().getX(), cz = plot.origin().getZ();
        final int deckAbs = plot.at(0, deckY, 0).getY(), standAbs = stand.getY();

        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            ServerLevel level = bot.level();
            // Nudge outward until the sole reads under the guard's own threshold rather than computing an offset: the
            // guard's predicate is the authority on what "barely on" means, and a rig that assumes a number can stage
            // a player the guard never looks at.
            final double footingMin = bot.body().footing().footingMin();
            double sole = bot.body().footing().soleOnSolid();
            double nudge = 0.5;
            for (int i = 0; i < 40 && sole >= footingMin; i++) {
                nudge += 0.02;
                fp.setPos(cx + nudge, standAbs, cz + 0.5);
                sole = bot.body().footing().soleOnSolid();
            }
            reporter.publishEntry("rig", "1-wide obsidian ledge x=" + cx + " z=" + (cz - 3) + ".." + cz
                    + " y=" + deckAbs + " | landing (one up, one across)=" + landing.toShortString() + " | "
                    + VOID_DEPTH + " clear cells under it, catch floor y=" + (deckAbs - VOID_DEPTH) + " outside the shaft");
            reporter.publishEntry("staged", String.format(Locale.ROOT, "bot x=%.2f sole=%.4f (threshold %s)",
                    nudge, sole, footingMin));
            if (sole >= footingMin)
                fail("THE RIG, not the subject: could not stage a stand under the footing threshold (sole=" + sole + ")");

            // Let vanilla physics have the stand BEFORE any driver touches it. Without this "the rig handed over a
            // stand vanilla was never going to hold" and "the subject walked off a stand it was given" produce the
            // same reading.
            bot.step(SETTLE_TICKS);
            reporter.publishEntry("settle", String.format(Locale.ROOT, "%d ticks with no driver: y=%.3f sole=%.4f",
                    SETTLE_TICKS, fp.getY(), bot.body().footing().soleOnSolid()));
            if (fp.getY() < standAbs - 0.5)
                fail("THE RIG, not the subject: vanilla itself dropped the staged stand in " + SETTLE_TICKS
                        + " idle ticks (y=" + fp.getY() + "), so this arena is not the ladder's cell — there the bot"
                        + " stood on it for 2400 ticks");

            // Every clause of the guard's own fire predicate, asked here in the same order it asks them. The guard's
            // log line is unconditional, so zero lines proves it never got past one of these — and "never entered" is
            // a different defect from "entered and declined".
            BotDebugContracts.Footing footing = bot.body().footing();
            BlockPos footCell = Targets.cell(footing.foot());
            reporter.publishEntry("guardClauses", "lethalEdgeBrake=" + settings.flag("lethalEdgeBrake")
                    + " inWater=" + fp.isInWater()
                    + " sole=" + String.format(Locale.ROOT, "%.4f", sole)
                    + " foot=" + footCell.toShortString() + " (" + level.getBlockState(footCell) + ")"
                    + " below=" + level.getBlockState(footCell.below())
                    + " lethalDropAdjacent=" + footing.lethalDropAdjacent());

            fp.getInventory().clearContent();
            fp.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            fp.getInventory().selected = 0;
            reporter.publishEntry("stock", "64 cobblestone in slot " + slot + " (hotbar), holdPlaceable="
                    + carryPlaceableUp(fp));

            bot.goTo(Targets.block(landing.above()));
            double minY = fp.getY();
            int t = 0;
            boolean walking = true;
            int fireable = 0;                 // ticks on which the guard's whole predicate held
            boolean brake = settings.flag("lethalEdgeBrake");
            StringBuilder soles = new StringBuilder();
            for (; t < 400 && walking; t++) {
                BotDebugContracts.Footing fn = bot.body().footing();
                double sn = fn.soleOnSolid();
                if (brake && !fp.isInWater() && sn > 0.0 && sn < footingMin && fn.lethalDropAdjacent()) fireable++;
                if (t < 8) soles.append(String.format(Locale.ROOT, " t%d=%.3f", t, sn));
                bot.tick();
                walking = bot.busy();
                minY = Math.min(minY, fp.getY());
                if (fp.getY() < deckAbs - 3) break;        // shed: nothing after this is about footing
            }
            String st = NavWalk.step(bot);
            bot.cancel();
            int spent = 64 - fp.getInventory().countItem(Items.COBBLESTONE);
            boolean underfootSolid = !level.getBlockState(new BlockPos(
                    (int) Math.floor(fp.getX()), deckAbs, (int) Math.floor(fp.getZ()))).isAir();
            boolean arrived = fp.blockPosition().getY() >= landing.getY() + 1
                    && Math.abs(fp.getX() - (landing.getX() + 0.5)) < 1.2
                    && Math.abs(fp.getZ() - (landing.getZ() + 0.5)) < 1.2;

            reporter.publishEntry("fireable", fireable + " of " + t + " drive ticks had the guard's WHOLE predicate"
                    + " true; its log line is unconditional, so any gap between this count and the [navigator] footing"
                    + " guard lines is the guard not being reached at all. sole per tick:" + soles);
            reporter.publishEntry("drive", String.format(Locale.ROOT, "%d ticks, bot=(%.2f,%.2f,%.2f) step=%s",
                    t, fp.getX(), fp.getY(), fp.getZ(), st));
            reporter.publishEntry("minY", String.format(Locale.ROOT, "%.3f (deck %d, criterion > %d)",
                    minY, standAbs, standAbs - 1));
            reporter.publishEntry("widen", spent + " cobblestone left the bag; the deck row under the bot is "
                    + (underfootSolid ? "SOLID" : "AIR"));

            assertTrue(minY > standAbs - 1, "the bot must not drop below the ledge: minY=" + minY);
            assertTrue(arrived, "it must actually reach the cell one up and one across — with only the first clause,"
                    + " standing perfectly still scores full marks: bot=" + fp.position() + " step=" + st);
        });
    }

    /**
     * What the stock row's {@code holdPlaceable=} has always done to the inventory, done here in vanilla terms: the row
     * used to call the body's {@code holdPlaceable()}, which is not a reading — it swaps the first full-block stack up
     * from slots 9..35 into the first empty hotbar slot (else the held one) and selects it. Kept as it was, because the
     * arm's recorded results were measured that way.
     */
    private static boolean carryPlaceableUp(ServerPlayer fp) {
        var inv = fp.getInventory();
        if (isSupport(inv.getSelected())) return true;
        for (int slot = 0; slot < 9; slot++)
            if (isSupport(inv.items.get(slot))) { inv.selected = slot; return true; }
        for (int slot = 9; slot < inv.items.size(); slot++) {
            if (!isSupport(inv.items.get(slot))) continue;
            int to = inv.selected;
            for (int h = 0; h < 9; h++) if (inv.items.get(h).isEmpty()) { to = h; break; }
            ItemStack bag = inv.items.get(slot);
            inv.items.set(slot, inv.items.get(to));
            inv.items.set(to, bag);
            inv.selected = to;
            return true;
        }
        return false;
    }

    private static boolean isSupport(ItemStack stk) {
        return !stk.isEmpty() && stk.getItem() instanceof BlockItem bi
                && !(bi.getBlock() instanceof FallingBlock)
                && bi.getBlock().defaultBlockState().blocksMotion();
    }

    /**
     * <b>Does A* know about the blocks the executor can reach?</b>
     *
     * <h2>The cell this is a copy of</h2>
     *
     * Journey rung 14's fortress corridor, waypoint 8. Three archived ladder runs seat the bot at the identical cell
     * and give the identical next hop, eleven blocks. Two crossed it. The third:
     *
     * <pre>{@code
     * fortress.wp8.at  stopped at 88, 41, 107, 11 blocks short   end=failed:no path (expanded=100000)
     * 205 placeable blocks still in the inventory (netherrack×2, cobblestone×104, dirt×79, diorite×7, granite×13)
     * }</pre>
     *
     * <p>Same seed, same terrain, same seat, same goal, blocks in hand — and 100000 nodes spent on a hop one bridge
     * edge crosses. The variable is which SLOTS those 205 blocks were sitting in.
     *
     * <h2>The asymmetry</h2>
     *
     * {@code ServerPlayerBody#holdPlaceable()} swaps a stack up from slots 9..35 when the hotbar has none — the
     * executor is not limited to the hotbar. The planner was: {@code LevelWorldView.placeableBlockCount()} counted 0..8
     * only. Two consumers turn that gap into a failed walk — {@code BridgePlace.eval} emits no bridge edge, and
     * {@code SearchPhase}'s block budget throws away a path A* has ALREADY FOUND and re-searches with placing OFF
     * whenever the edges outnumber the count.
     *
     * <h2>The three clauses, and why the first is not decoration</h2>
     *
     * <ol>
     *   <li><b>The staging must have created the condition.</b> The hotbar really is empty of placeables. Without it,
     *       a staging that quietly left a stack in slot 0 would make the other two clauses true for the wrong
     *       reason.</li>
     *   <li><b>The planner must count them.</b> The number A* reads, asserted directly, because it is the quantity the
     *       defect was in.</li>
     *   <li><b>And it must actually cross.</b> Counting right while still failing to bridge would mean the fix went to
     *       the wrong consumer.</li>
     * </ol>
     */
    @Test
    void serverPlansABridgeFromTheBackpack(Game game, @Plot.Centered @Plot.Size(x = 16, y = 384, z = 48) Plot plot,
                                           TestReporter reporter) {
        final int slot = 20;
        final int deckY = DECK, standY = deckY + 1;
        final int nearCells = 4, gapCells = 4, farCells = 4;
        // Void everywhere and genuinely bottomless: a catch floor below the gap would let the navigator fall and
        // recover, which measures the arena instead of the planner.
        plot.fill(0, deckY, 0, 0, deckY, nearCells - 1, Blocks.OBSIDIAN);
        final int farStart = nearCells + gapCells;
        plot.fill(0, deckY, farStart, 0, deckY, farStart + farCells - 1, Blocks.OBSIDIAN);

        Settings.pin(game)
                .set("allowPlace", true)
                .set("allowBreak", false)   // there is nothing to dig through; bridging is the only answer
                .set("navigatorDebug", true);

        BlockPos stand = plot.at(0, standY, 0);
        BlockPos goal = plot.at(0, standY, farStart + farCells - 1);
        final int farStartAbs = plot.at(0, 0, farStart).getZ(), deckAbs = plot.at(0, deckY, 0).getY();
        SceneBot bot = SceneBot.spawn(game, stand);

        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            fp.getInventory().clearContent();
            fp.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            fp.getInventory().selected = 0;

            bot.step(SETTLE_TICKS);
            if (fp.getY() < stand.getY() - 0.5)
                fail("THE RIG, not the subject: vanilla dropped the staged stand in " + SETTLE_TICKS
                        + " idle ticks (y=" + fp.getY() + ")");

            int hotbar = 0;
            for (int i = 0; i < 9; i++) hotbar += fp.getInventory().items.get(i).getCount();
            final int planner = bot.body().hands().placeableBlocks();

            bot.goTo(Targets.block(goal));
            double minY = fp.getY();
            double farZ = fp.getZ();
            int t = 0;
            boolean walking = true;
            for (; t < 500 && walking; t++) {
                bot.tick();
                walking = bot.busy();
                minY = Math.min(minY, fp.getY());
                farZ = Math.max(farZ, fp.getZ());
                if (fp.getY() < deckAbs - 3) break;
            }
            String st = NavWalk.step(bot);
            bot.cancel();
            int spent = 64 - fp.getInventory().countItem(Items.COBBLESTONE);
            boolean crossed = farZ >= farStartAbs;

            reporter.publishEntry("rig", "near bank " + nearCells + " blocks + void " + gapCells + " blocks + far bank "
                    + farCells + " blocks, 1-block-wide obsidian at y=" + deckAbs + ", nothing below to the world's floor");
            reporter.publishEntry("hotbar", hotbar + " items in the hotbar, expected 0");
            reporter.publishEntry("planner", "placeable count read by A* = " + planner + ", expected 64");
            reporter.publishEntry("drive", String.format(Locale.ROOT, "%d ticks, bot=(%.2f,%.2f,%.2f) step=%s",
                    t, fp.getX(), fp.getY(), fp.getZ(), st));
            reporter.publishEntry("crossed", String.format(Locale.ROOT,
                    "furthest z=%.2f (far bank starts at z=%d); %d cobblestone left the inventory", farZ, farStartAbs, spent));

            assertEquals(0, hotbar, "the test setup must actually place the stone outside the hotbar; otherwise this"
                    + " test tests nothing");
            assertEquals(64, planner, "the blocks the planner counts must be the ones the executor can reach; this is"
                    + " the quantity the defect was in");
            assertTrue(minY > stand.getY() - 1, "the bot must not fall into the void: minY=" + minY);
            assertTrue(crossed, "and it must actually bridge across; counting correctly without crossing means the fix"
                    + " was applied to the wrong consumer: furthest z=" + farZ + ", step=" + st);
        });
    }

    /**
     * <b>A bot with a FULL sole walks off the end of its own bridge</b> — with the goal off the bridge's axis, so the
     * path must TURN at the head instead of running straight out of it.
     *
     * <h2>The shape this is a copy of</h2>
     *
     * <pre>{@code
     * step=WALKING jumpFlag=unset bot=-10.38,111.00,-2.44 speedH=0.118 sole=0.360 node=-13,111,-3
     * }</pre>
     *
     * No jump. Sole 0.360 — the FULL footprint, not a graze. Walking speed, level target. The bot simply walked off the
     * end of a bridge it had built, at y=111 over the End void, and the node was three cells out and one across. The
     * straight arm of this arena passed first run (8 deck cells walked, then 7 cobblestone spent bridging past the end),
     * so the turn is the variable left. It is the only test that exercises {@code strideFloorGuard} at a bridge head.
     *
     * <h2>The two clauses</h2>
     *
     * <ol>
     *   <li><b>It must not fall.</b> The lowest y stays at the deck.</li>
     *   <li><b>It must actually have walked.</b> Without this, refusing to move at all is a full-marks answer — and a
     *       guard that pins the bot at the first cell would "pass" while making the ladder unable to cross anything.
     *       The bar is four of the eight deck cells.</li>
     * </ol>
     */
    @Test
    void serverTurnsAtTheBridgeHead(Game game, @Plot.Centered @Plot.Size(x = 16, y = 384, z = 48) Plot plot,
                                    TestReporter reporter) {
        final int lateral = -3;
        final int deckY = DECK, standY = deckY + 1, deckCells = 8;
        // A genuine shaft under and past the bridge: the guard only arms instantly over a column that is bottomless
        // to the navigator's own scan floor, so a catch floor would measure the arena.
        plot.fill(0, deckY, 0, 0, deckY, deckCells - 1, Blocks.OBSIDIAN);

        Settings.pin(game)
                .set("allowPlace", true)      // bridging onward is a legitimate answer, and the best one
                .set("allowBreak", false)
                .set("navigatorDebug", true);

        BlockPos startCell = plot.at(0, standY, 1);
        // A goal well past the deck's end, so the walk has every reason to keep going and the only thing that can stop
        // it at the head is the guard.
        BlockPos goal = plot.at(lateral, standY, deckCells + 6);
        final int deckAbs = plot.at(0, deckY, 0).getY();
        SceneBot bot = SceneBot.spawn(game, startCell);

        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            fp.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 64));
            fp.getInventory().selected = 0;
            bot.step(SETTLE_TICKS);
            if (fp.getY() < startCell.getY() - 0.5)
                fail("THE RIG, not the subject: vanilla dropped the staged stand in " + SETTLE_TICKS
                        + " idle ticks (y=" + fp.getY() + ")");

            bot.goTo(Targets.block(goal));
            double minY = fp.getY();
            double farZ = fp.getZ();
            int t = 0;
            boolean walking = true;
            for (; t < 400 && walking; t++) {
                bot.tick();
                walking = bot.busy();
                minY = Math.min(minY, fp.getY());
                farZ = Math.max(farZ, fp.getZ());
                if (fp.getY() < deckAbs - 3) break;
            }
            String st = NavWalk.step(bot);
            bot.cancel();
            int spent = 64 - fp.getInventory().countItem(Items.COBBLESTONE);
            double walked = farZ - (startCell.getZ() + 0.5);

            reporter.publishEntry("drive", String.format(Locale.ROOT, "%d ticks, bot=(%.2f,%.2f,%.2f) step=%s",
                    t, fp.getX(), fp.getY(), fp.getZ(), st));
            reporter.publishEntry("walked", String.format(Locale.ROOT,
                    "walked %.2f blocks along the bridge (of %d), furthest z=%.2f, expected >= 4", walked, deckCells, farZ));
            reporter.publishEntry("bridged", spent + " cobblestone left the inventory (the guard may choose to keep"
                    + " bridging onward, which is the best answer)");

            assertTrue(minY > startCell.getY() - 1, "the bot must not drop below the deck surface: minY=" + minY);
            assertTrue(walked >= 4.0, "and it must actually walk 4 blocks along the bridge; with only the previous"
                    + " check, not taking a single step would score full marks: walked " + walked + ", step=" + st);
        });
    }

    /**
     * <b>Can this player draw a bow and loose an arrow at all?</b>
     *
     * <p>Rung 20's ranged half has been fixed four times, and three consecutive rehearsals still reported {@code bow
     * draws 7, arrows in stock 256} — the same 7 before and after a change that should have altered it. A value that
     * does not move when its cause is removed is measuring something else.
     *
     * <p>So: flat stone, a player, a bow, arrows, and nothing else. Hold the use for well past a full draw and ask the
     * only question that matters: <b>did the quiver go down, and is there an arrow in the world?</b>
     */
    @Test
    void serverDrawsABow(Game game, @Plot.Centered Plot plot, TestReporter reporter) {
        final int deckY = 4;
        plot.fill(-3, deckY, -3, 3, deckY, 3, Blocks.STONE);
        Settings.pin(game);
        SceneBot bot = SceneBot.spawn(game, plot.at(0, deckY + 1, 0));

        int[] before = new int[1];
        int[] maxDraw = new int[1];
        StringBuilder draw = new StringBuilder();
        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            fp.getInventory().setItem(0, new ItemStack(Items.BOW, 1));
            fp.getInventory().setItem(9, new ItemStack(Items.ARROW, 64));
            fp.getInventory().selected = 0;
            fp.setXRot(-20.0F);                       // aim up a little so the arrow clears the deck
            before[0] = fp.getInventory().countItem(Items.ARROW);
            // Hold the use for three full draws' worth of ticks. If the counter resets on a cycle, the per-tick line
            // shows the cycle; if it climbs and holds, the release is the suspect instead.
            bot.press(bot.keys().use(true));
            for (int t = 0; t < 60; t++) {
                bot.step();
                int d = fp.isUsingItem() ? fp.getTicksUsingItem() : -1;
                maxDraw[0] = Math.max(maxDraw[0], d);
                if (t < 24) draw.append(' ').append(d);
            }
            bot.press(bot.keys().use(false));         // up-edge = release
        });
        // Real SERVER ticks, not bot steps: a freshly spawned arrow sits in the level's pending-entity queue until the
        // LEVEL ticks, so counting inside the synchronous loop asks the entity index about something it has not been
        // told about yet — and reports a working bow as a silent one.
        game.ticks(10);

        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            ServerLevel level = bot.level();
            int after = fp.getInventory().countItem(Items.ARROW);
            // Count the ARROWS IN THE WORLD, not the ones missing from the bag: under instabuild the quiver is never
            // touched while the bow works perfectly.
            int flew = level.getEntitiesOfClass(AbstractArrow.class, fp.getBoundingBox().inflate(ARROW_SEARCH)).size();
            reporter.publishEntry("draw", "held for 60 ticks; getTicksUsingItem per tick (-1 = not in use):" + draw
                    + " ... max " + maxDraw[0] + " (a full draw needs 20)");
            reporter.publishEntry("ammo", before[0] + " arrows before release -> " + after + " after release"
                    + " (instabuild=" + fp.getAbilities().instabuild + ")");
            reporter.publishEntry("flew", flew + " arrows present in the world");
            reporter.publishEntry("hand", "main hand=" + fp.getMainHandItem().getItem() + ", isUsingItem="
                    + fp.isUsingItem());
            // DISCRIMINATOR for an empty sky: stopUsingItem() never reached BowItem.releaseUsing, or releaseUsing ran
            // and this player cannot spawn a projectile at all. They call for opposite fixes, so ask directly.
            ItemStack bow = fp.getMainHandItem();
            String probe;
            try {
                bow.getItem().releaseUsing(bow, level, fp, bow.getItem().getUseDuration(bow, fp) - 30);
                probe = "calling releaseUsing directly did not throw";
            } catch (RuntimeException e) {
                probe = "calling releaseUsing directly threw " + e;
            }
            reporter.publishEntry("probe", probe + "; getProjectile=" + fp.getProjectile(bow).getItem());

            assertTrue(maxDraw[0] >= 20, "after holding for 60 ticks, the draw counter must reach a full draw of 20 at"
                    + " least once: max " + maxDraw[0] + ", per tick:" + draw);
            if (!fp.getAbilities().instabuild) {
                // Not a duplicate of flew: an entity in the sky only proves something spawned. The quiver going down
                // by exactly one proves it came out of BowItem.releaseUsing.
                assertEquals(1, before[0] - after, "after release the quiver must hold exactly one arrow fewer;"
                        + " measured " + before[0] + " -> " + after);
            }
            assertTrue(flew >= 1, "after release an arrow must appear in the world; asserting only the draw counter"
                    + " would give full marks to a full draw that fires nothing: " + flew + " arrows, " + probe);
        });
    }

    /**
     * How far from the player the arrow count looks. A full draw leaves the string at 3 blocks per tick, and the count
     * runs ten real server ticks later; three times the arrow's own reach, so no draw this test can produce outruns
     * the question.
     */
    private static final double ARROW_SEARCH = 96.0;

    // =====================================================================================
    // A lava shore — the drop none of the three brakes could see.
    // =====================================================================================

    /**
     * <b>A bot walks straight off a shelf into a lava bay, and the guard whose job that was declines because it counted
     * the lake as a floor.</b>
     *
     * <h2>The tick this is a copy of</h2>
     *
     * Journey rung 14 crosses the Nether to a fortress and ended the same way four times. Three brakes could have held
     * that bot and all three were off: {@code DrivePhase}'s {@code edgeBrake} and {@code Safety#footingGuard} were both
     * released by the plan's planned-descent exemption (deliberate and load-bearing: vanilla sneak refuses to walk off
     * ANY edge), and {@code Safety#strideFloorGuard}, the one guard with NO release here, asked its question and got a
     * wrong answer.
     *
     * <h2>The wrong answer, and why it is arithmetic rather than judgement</h2>
     *
     * The guard's fall scan walks down from the stride cell and stops at the first cell that is not
     * {@code isPassable}. <b>Lava is passable</b>, so the scan descended straight through the lake and stopped on its
     * bed — on the last index it looks at — and called the stride safe. {@code NavigatorGeometry.dropAdjacentExceeds}
     * learned this same lesson and carries the {@code isHazard} line; this guard never got it.
     *
     * <h2>Two arms, one variable</h2>
     *
     * The same headland, the same eight cells of walk, the same eleven-block bay — a drop that is comfortably
     * SURVIVABLE dry. {@code serverStopsAtALavaShore} fills it with lava and requires the guard to stop the bot;
     * {@code serverWalksOffASurvivableLedge} fills it with stone and requires the guard to stay out of the way.
     *
     * <h2>Each arm carries its own control</h2>
     *
     * Every arm drives the shelf TWICE over an identical staging, with {@code navigatorStrideFloorGuard} as the only
     * difference. On lava they must DISAGREE — the control walks in, and an arm whose control did not walk in has not
     * earned the right to report that the subject stayed out. On stone they must AGREE.
     *
     * <h2>{@code lethalEdgeBrake} is OFF in both arms, and that is the isolation</h2>
     *
     * With it on, {@code Safety#footingGuard} pins this bot as its sole thins and neither arm ever reaches the bay.
     *
     * <h2>The drive is the rig's, not the pathfinder's</h2>
     *
     * The bot is walked forward at walking speed on a heading the rig re-imposes every tick, and its brain runs on a
     * goto only so that the navigator's guards run. Between the brain's half of the tick and the body's step the rig
     * presses its own keys over the brain's. Sneak travels on a different channel from the impulse, so a guard's pin
     * survives the rig's drive and is what the bot is actually stopped by.
     */
    @Test
    void serverStopsAtALavaShore(Game game, @Plot.Centered @Plot.Size(x = 16, y = 80, z = 48) Plot plot,
                                 TestReporter reporter) {
        shoreArm(game, plot, reporter, true);
    }

    /** The lava arm's negative control — see {@link #serverStopsAtALavaShore}. The same bay filled with stone, where the
     *  guard must stay silent: an eleven-block drop onto rock is a graze this bot walks off, and pinning at every such
     *  lip would make ridge walking crawl. */
    @Test
    void serverWalksOffASurvivableLedge(Game game, @Plot.Centered @Plot.Size(x = 16, y = 80, z = 48) Plot plot,
                                        TestReporter reporter) {
        shoreArm(game, plot, reporter, false);
    }

    /** dy of the shelf's top block — the cell the bot's sole rests on. Its foot cell is one above. */
    private static final int SHELF = 30;
    /** Cells of shelf along +z. */
    private static final int SHELF_CELLS = 8;
    /** dy of the bay's surface: ten open rows under the shelf's top block, so the fall from the foot cell is eleven —
     *  under {@code SurvivalMath.survivableFall(20) = 22}, deliberately. */
    private static final int BAY_TOP = SHELF - 10;
    /** Rows of fill under that surface. Four, so a player that goes in is IN it. */
    private static final int BAY_ROWS = 4;
    /** dy of the bed's top block — the first solid cell the guard's downward scan can find. */
    private static final int BAY_BED = BAY_TOP - BAY_ROWS;
    /** Synchronous physics ticks one drive of the shelf gets: the walk out is ~50 and the fall ~25. */
    private static final int SHORE_TICKS = 240;

    /** What one drive of the shelf produced. */
    private record Shore(int ticks, double walked, double minY, boolean inLava, int pinnedTicks, String endedAt) {}

    private static void shoreArm(Game game, Plot plot, TestReporter reporter, boolean lava) {
        Settings settings = Settings.pin(game)
                .set("allowPlace", false)      // the subject is the PIN; paving the bay is another answer
                .set("allowBreak", false)
                .set("navigatorDebug", true)
                .set("lethalEdgeBrake", false); // see the class note: this is the isolation, not a shortcut

        stageShore(plot, lava);
        reporter.publishEntry("scan", game.onServer(s -> scanRow(settings, s.getLevel(plot.dimension()),
                plot.at(0, SHELF + 1, SHELF_CELLS), lava)));

        Shore control = shoreDrive(game, plot, settings, reporter, "control", false);
        if (lava && !control.inLava())
            fail("THE RIG, not the subject: with navigatorStrideFloorGuard off the bot still did not walk into the lava,"
                    + " so \"the subject stayed out of the lava\" cannot distinguish \"the guard stopped it\" from \"this"
                    + " arena cannot be walked into at all\": " + control.endedAt());
        if (!lava && control.inLava())
            fail("THE RIG, not the subject: lava appeared in the stone bay: " + control.endedAt());

        stageShore(plot, lava);
        Shore subject = shoreDrive(game, plot, settings, reporter, "subject", true);

        int shelfAbs = plot.at(0, SHELF, 0).getY();
        if (lava) {
            game.check(!subject.inLava(), "A: with the guard on, the bot must never touch the lava (control arm: "
                    + control.endedAt() + ")");
            game.check(subject.walked() >= 4.0, "B: and it must actually walk 4 blocks along the shelf: walked "
                    + String.format(Locale.ROOT, "%.2f", subject.walked()) + " blocks");
            game.check(subject.pinnedTicks() >= 1, "C: and the stop must come from the guard's pin, not from something"
                    + " else that happened to halt the bot: " + subject.pinnedTicks() + " ticks in the sneak-pinned state");
        } else {
            game.check(subject.minY() < shelfAbs, "A: over a dry bay the guard must stay out of the way: with the guard"
                    + " off the bot fell to " + String.format(Locale.ROOT, "%.2f", control.minY()) + ", and with it on"
                    + " the bot must fall as well; measured " + String.format(Locale.ROOT, "%.2f", subject.minY())
                    + " (shelf top y=" + (shelfAbs + 1) + ")");
            game.check(subject.pinnedTicks() == 0, "B: the guard must never pin: pinning the bot above a survivable"
                    + " drop turns every ledge into a wall: " + subject.endedAt());
        }
    }

    /**
     * Re-derive the guard's own downward scan off the LEVEL, cell by cell, from {@code stride}. The guard's decision
     * and the row that judges it must not be able to disagree about what the column holds, and "the guard declined"
     * has two causes that want opposite fixes: it never reached the scan, or it ran the scan and the scan said safe.
     */
    private static String scanRow(Settings settings, ServerLevel level, BlockPos stride, boolean lava) {
        int depth = Math.max(settings.integer("pathfinderMaxDryFall") + 1, 23);   // ceil(20 HP) + 3
        int hazardAt = -1, floorAt = -1;
        for (int i = 1; i <= depth && floorAt < 0; i++) {
            BlockPos c = stride.below(i);
            if (hazardAt < 0 && level.getBlockState(c).getFluidState().is(FluidTags.LAVA)) hazardAt = i;
            if (level.getBlockState(c).blocksMotion()) floorAt = i;
        }
        return "scanning down from stride cell " + stride.toShortString() + ", at most " + depth + " blocks: "
                + (hazardAt < 0 ? "no hazard in the column" : "block " + hazardAt + " is lava") + ", "
                + (floorAt < 0 ? "no solid block down to the end of the scan" : "block " + floorAt + " is solid")
                + (lava ? "; a scan that looks only at the second number finds a \"floor\" beneath the lava"
                        : "; the floor on the stone is a real floor, and the guard must stay silent");
    }

    /**
     * Walk the shelf once with a fresh bot and report what stopped it, if anything. {@code strideGuard} is the arm's
     * only variable. The navigator is ticked for its guards alone and the heading, the impulse and the jump are
     * re-imposed after that so the bot walks one straight line whatever the navigator would rather do. Sneak is NOT
     * re-imposed: it is the channel a guard pins on, and it is the thing being measured. The bot leaves when the drive
     * is over, so the next drive's restaging does not build around it.
     */
    private static Shore shoreDrive(Game game, Plot plot, Settings settings, TestReporter reporter, String arm,
                                    boolean strideGuard) {
        settings.set("navigatorStrideFloorGuard", strideGuard);
        BlockPos o = plot.origin();
        double startZ = o.getZ() + 1.5;
        int standY = plot.at(0, SHELF + 1, 0).getY(), shelfAbs = standY - 1;
        SceneBot bot = SceneBot.spawn(game, plot.dimension(), o.getX() + 0.5, standY, startZ);
        // One column aside from the line the rig walks: the bot never stands in the goal, so the walk is still
        // underway at the lip, where its guards run.
        BlockPos goal = plot.at(1, SHELF + 1, SHELF_CELLS - 1);
        Shore shore = game.onServer(s -> {
            ServerPlayer fp = bot.player();
            ServerLevel level = bot.level();
            aim(fp, 0f);
            bot.step(SETTLE_TICKS);
            if (fp.getY() < standY - 0.5)
                fail("THE RIG, not the subject: vanilla itself did not hold the staged stand (y=" + fp.getY()
                        + " after " + SETTLE_TICKS + " idle ticks)");
            bot.goTo(Targets.block(goal));
            double minY = fp.getY(), farZ = fp.getZ();
            boolean inLava = false;
            int pinned = 0, t = 0;
            for (; t < SHORE_TICKS; t++) {
                if (straightOn(bot, () -> aim(fp, 0f))) pinned++;
                bot.step();
                minY = Math.min(minY, fp.getY());
                farZ = Math.max(farZ, fp.getZ());
                if (fp.isInLava()) { inLava = true; break; }
                if (fp.onGround() && fp.getY() < shelfAbs) break;   // landed in the bay
            }
            String ended = String.format(Locale.ROOT, "%d ticks, bot=(%.2f,%.2f,%.2f), lowest y=%.2f, walked %.2f"
                            + " blocks along the shelf, pinned %d ticks, block below=%s%s",
                    t, fp.getX(), fp.getY(), fp.getZ(), minY, farZ - startZ, pinned, blockUnder(level, fp),
                    inLava ? ", submerged in lava" : "");
            bot.remove();
            return new Shore(t, farZ - startZ, minY, inLava, pinned, ended);
        });
        reporter.publishEntry(arm + ".drive", "navigatorStrideFloorGuard=" + strideGuard + " → " + shore.endedAt());
        return shore;
    }

    /**
     * The brain half of the rig's tick: the brain runs (its guards included), then the heading, the forward impulse
     * and the released jump are re-imposed over whatever it left. Sneak is left alone. Whether the brain held sneak;
     * the caller steps the body.
     */
    private static boolean straightOn(SceneBot bot, Runnable aim) {
        bot.think();
        aim.run();
        return bot.press(k -> k.move(0, 1).jump(false)).shift();
    }

    private static String blockUnder(ServerLevel level, ServerPlayer fp) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(fp.blockPosition().below()).getBlock()).getPath();
    }

    /** Face {@code yaw}, head and body with it. Vanilla rotates the movement impulse by the yaw, so a heading the
     *  navigator is free to slew would turn this rig's straight walk into whatever the navigator wanted. */
    private static void aim(ServerPlayer fp, float yaw) {
        fp.setYRot(yaw);
        fp.yHeadRot = yaw;
        fp.yBodyRot = yaw;
    }

    /**
     * The headland, the basin, and what the basin is filled with — the last of those being the two arms' only
     * difference. The basin's rim is laid solid all the way round BEFORE the fill goes in, so lava cannot flow out of
     * it and the "floor" the guard's scan finds is real rock.
     */
    private static void stageShore(Plot plot, boolean lava) {
        plot.fill(-6, 4, -3, 6, 36, 17, Blocks.AIR);
        plot.fill(-3, 4, -2, 3, SHELF, 7, Blocks.STONE);            // the headland the bot walks out on
        plot.fill(-5, 4, 8, 5, BAY_TOP, 16, Blocks.STONE);          // the basin: rim and bed, one piece
        plot.fill(-3, BAY_BED + 1, 8, 3, BAY_TOP, 14, lava ? Blocks.LAVA : Blocks.STONE);   // …and the fill
    }

    // ── the lava RIM ─────────────────────────────────────────────────────────────────────────
    //
    // The shore arms ask whether the guard refuses a stride INTO a lake; this asks what its refusal costs a bot that
    // was only ever walking PAST one.

    /** dy of the rim shelf's top block. The bot's foot cell is one above it. */
    private static final int RIM_DECK = 20;
    /** dy of the trench fill's surface. Four rows under the deck, so a dry player that goes over the edge falls four
     *  and loses one heart. The only lethal thing in this arena is what the trench is filled with. */
    private static final int RIM_FILL_TOP = RIM_DECK - 4;
    /** Rows of fill: four, so the bed sits at index 9 of the guard's downward scan, comfortably inside its reach. */
    private static final int RIM_FILL_ROWS = 4;
    /** dy of the trench bed's top block — the "floor" the scan used to stop on. */
    private static final int RIM_BED = RIM_FILL_TOP - RIM_FILL_ROWS;
    /** Westmost shelf cell; dx below this is open trench along the whole arena. */
    private static final int RIM_EDGE = -1;
    /** Heading in degrees; 0 is +z. 25° leans the walk toward -x, slightly into the trench: the reading the ladder
     *  actually logged — every one of rung 12's 83 pins was a bot travelling along the shore with a lateral drift
     *  toward the lake. A pure +z walk keeps the stride cell on the deck forever and would measure nothing. */
    private static final float RIM_YAW = 25f;
    /** Synchronous physics ticks one drive of the rim gets: room for the post-pin travel clause either way. */
    private static final int RIM_TICKS = 200;

    /** What one drive of the rim produced. {@code zAfterPin} is the +z distance covered after the FIRST pinned tick —
     *  the number the test exists to produce, and the one a total stop drives to zero while the plain "walked" figure
     *  stays healthy on the pre-pin run-up alone. */
    private record Rim(int ticks, double walked, double zAfterPin, double minY, boolean inLava, int pinnedTicks,
                       int longestPin, String endedAt) {}

    /**
     * <b>A bot walking ALONG a lava rim, not into it.</b>
     *
     * <h2>The reading this is a copy of</h2>
     *
     * The stride guard learned to break its fall scan on {@code isHazard}, and the very next ladder run logged it firing
     * 83 times inside one rung — every fire on two cells of one lake's rim, with a decaying velocity that says these
     * were lava columns one row under the stride cell, not bottomless ones.
     *
     * <h2>The question</h2>
     *
     * Here the bot is walking PAST a lake, and "stopped" is the failure: a guard that pins wherever an open column ends
     * in lava turns every rim into a wall, and a Nether crossing is nothing but rim. So:
     *
     * <ol>
     *   <li><b>It must not go in.</b></li>
     *   <li><b>The pin must be what held it.</b> Without this, an arena whose edge the bot never reaches scores full
     *       marks for the guard.</li>
     *   <li><b>It must keep going along the rim afterwards</b>, measured from the first pinned tick, not from the
     *       start.</li>
     * </ol>
     *
     * <p>Making the bottomless branch's {@code setDeltaMovement(0, dy, 0)} unconditional — the strongest stop the guard
     * can express — did not turn clause 3 red: post-pin travel fell from 11.08 cells to 7.53. A pin at a rim is a
     * refusal of the sideways step, not of the journey. Clause 3 is the floor under that finding.
     *
     * <p>Driven twice over identical staging with {@code navigatorStrideFloorGuard} the only difference; the control
     * must walk in. {@code lethalEdgeBrake} is OFF, for the shore pair's reason.
     */
    @Test
    void serverKeepsWalkingAtALavaRim(Game game, @Plot.Centered @Plot.Size(x = 16, y = 64, z = 48) Plot plot,
                                      TestReporter reporter) {
        Settings settings = Settings.pin(game)
                .set("allowPlace", false)      // the subject is the PIN; paving the trench is another answer
                .set("allowBreak", false)
                .set("navigatorDebug", true)
                .set("lethalEdgeBrake", false);

        stageRim(plot);
        reporter.publishEntry("scan", game.onServer(s -> scanRow(settings, s.getLevel(plot.dimension()),
                plot.at(RIM_EDGE - 1, RIM_DECK + 1, 6), true)));

        Rim control = rimDrive(game, plot, settings, reporter, "control", false);
        if (!control.inLava())
            fail("THE RIG, not the subject: with navigatorStrideFloorGuard off the bot still did not walk into the lava,"
                    + " so \"the subject stayed out of the lava\" cannot distinguish \"the guard stopped it\" from \"this"
                    + " arena cannot be walked into at all\": " + control.endedAt());

        stageRim(plot);
        Rim subject = rimDrive(game, plot, settings, reporter, "subject", true);
        // Whether the forced re-path threshold (guardPinStreak >= 30) is ever reached is a property of the approach:
        // reported so a reader can see which shape the measurement came from.
        reporter.publishEntry("streak", "longest continuous pin " + subject.longestPin()
                + " ticks; the forced re-path threshold is guardPinStreak >= 30");

        game.check(!subject.inLava(), "A: with the guard on, the bot must never touch the lava (control arm: "
                + control.endedAt() + ")");
        game.check(subject.pinnedTicks() >= 1, "B: and the stop must come from the guard's pin, not from the bot never"
                + " reaching the trench edge: " + subject.pinnedTicks() + " ticks in the sneak-pinned state");
        game.check(subject.zAfterPin() >= 2.0, "C: and after being pinned the bot must still keep walking along the rim;"
                + " measured from the first pin, because the walk up to the trench edge would satisfy a plain \"walked N"
                + " blocks\" check on its own: walked a further "
                + String.format(Locale.ROOT, "%.2f", subject.zAfterPin()) + " blocks after the pin");
    }

    /** Walk the rim once with a fresh bot and report what the guard cost; the same rig as {@link #shoreDrive}. The
     *  goal is level and well up the deck — a goal BELOW the foot would hand the guard its planned-descent exemption. */
    private static Rim rimDrive(Game game, Plot plot, Settings settings, TestReporter reporter, String arm,
                                boolean strideGuard) {
        settings.set("navigatorStrideFloorGuard", strideGuard);
        BlockPos o = plot.origin();
        double startX = o.getX() + 1.5, startZ = o.getZ() + 0.5;
        int standY = plot.at(0, RIM_DECK + 1, 0).getY();
        SceneBot bot = SceneBot.spawn(game, plot.dimension(), startX, standY, startZ);
        BlockPos goal = plot.at(0, RIM_DECK + 1, 18);
        Rim rim = game.onServer(s -> {
            ServerPlayer fp = bot.player();
            ServerLevel level = bot.level();
            aim(fp, RIM_YAW);
            bot.step(SETTLE_TICKS);
            if (fp.getY() < standY - 0.5)
                fail("THE RIG, not the subject: vanilla itself did not hold the staged stand (y=" + fp.getY()
                        + " after " + SETTLE_TICKS + " idle ticks)");
            bot.goTo(Targets.block(goal));
            double minY = fp.getY(), farZ = fp.getZ(), pinZ = Double.NaN, farAfterPin = 0.0;
            boolean inLava = false;
            int pinned = 0, run = 0, longest = 0, t = 0;
            for (; t < RIM_TICKS; t++) {
                if (straightOn(bot, () -> aim(fp, RIM_YAW))) {
                    pinned++;
                    longest = Math.max(longest, ++run);
                    if (Double.isNaN(pinZ)) pinZ = fp.getZ();
                } else run = 0;
                bot.step();
                minY = Math.min(minY, fp.getY());
                farZ = Math.max(farZ, fp.getZ());
                if (!Double.isNaN(pinZ)) farAfterPin = Math.max(farAfterPin, fp.getZ() - pinZ);
                if (fp.isInLava()) { inLava = true; break; }
                if (fp.getZ() > o.getZ() + 17) break;      // ran out of staged deck
            }
            String ended = String.format(Locale.ROOT, "%d ticks, bot=(%.2f,%.2f,%.2f), lowest y=%.2f, walked %.2f blocks"
                            + " along the shore, a further %.2f blocks after the first pin, pinned %d ticks (longest"
                            + " continuous %d), block below=%s%s",
                    t, fp.getX(), fp.getY(), fp.getZ(), minY, farZ - startZ, farAfterPin, pinned, longest,
                    blockUnder(level, fp), inLava ? ", submerged in lava" : "");
            bot.remove();
            return new Rim(t, farZ - startZ, farAfterPin, minY, inLava, pinned, longest, ended);
        });
        reporter.publishEntry(arm + ".drive", "navigatorStrideFloorGuard=" + strideGuard + " → " + rim.endedAt());
        return rim;
    }

    /**
     * One solid block, a trench cut out of it, and the lava put back — in that order, so the trench's walls and bed
     * exist BEFORE any lava does and the "floor" the guard's scan finds under it is real rock.
     */
    private static void stageRim(Plot plot) {
        plot.fill(-7, 4, -3, 6, 28, 21, Blocks.AIR);
        plot.fill(-7, 4, -3, 6, RIM_DECK, 21, Blocks.STONE);                     // one solid mass
        plot.fill(-6, RIM_BED + 1, -2, -2, RIM_DECK, 20, Blocks.AIR);            // the trench, cut back out of it
        plot.fill(-6, RIM_BED + 1, -2, -2, RIM_FILL_TOP, 20, Blocks.LAVA);       // …and the fill
    }
}
