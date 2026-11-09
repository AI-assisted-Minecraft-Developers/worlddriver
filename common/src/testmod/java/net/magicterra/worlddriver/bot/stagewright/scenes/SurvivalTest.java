package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Optional;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.observe.ObserveBlockingApi;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * The Survival family: escape / bunker shelter / low-HP flee / anti-suffocate / air-supply observation.
 *
 * <p>Several drive real tasks on a parked server bot ({@code serverEscape} / {@code serverLowHpEdgePin} /
 * {@code serverBunkerSlope}) through {@code mc.bot.escape} / {@code bunker} / {@code runAway}, ticked in full by the
 * test ({@link SceneBot#tickUntilIdle}, one server task). {@code serverObserveAirSupply} reads
 * {@code mc.observe.player}'s air for the bot. The pure state machines and gate tables (bunker anchor, drowning float,
 * drowning escape and its preemption of a user task, contact escape) are unit tests in {@code common/src/test}.
 */
@Scene("wd.scenes")
@Isolated // see ProcessTest: these tests hold the server thread for long spans
@Execution(ExecutionMode.SAME_THREAD)
class SurvivalTest {

    private static final String NAVIGATOR_DEBUG = "navigatorDebug";

    /**
     * Ticks the reflex gets to clear the head cell: a server-side break is instant, so one tick would do; the rest is
     * room for the trigger, which reads the damage the wall deals.
     */
    private static final int SUFFOCATE_BUDGET = 60;

    /** Real EscapeProcess climbs out of a 1-wide stone pit. */
    @Test
    void serverEscape(Game game, @Plot.Centered Plot plot) {
        Settings.pinUntimed(game)
                .set("allowBreak", true)
                .set("allowPlace", true)
                .set(NAVIGATOR_DEBUG, false);
        final int floorY = 0;
        // Solid stone block floorY..floorY+3 (top surface = floorY+3, stand = floorY+4); the 1-wide pit at the
        // centre: air floorY+1..floorY+3, the bot stands on floorY.
        plot.fill(-5, floorY, -5, 5, floorY + 3, 5, Blocks.STONE);
        plot.fill(0, floorY + 1, 0, 0, floorY + 3, 0, Blocks.AIR);

        SceneBot bot = SceneBot.spawn(game, plot, 0, floorY + 1, 0);
        bot.give(Items.DIRT, 64); // VERT_RISE fallback
        int floor = plot.at(0, floorY, 0).getY();
        game.runOnServer(s -> {
            bot.escape(floor + 4);
            bot.tickUntilIdle(800);
            ServerPlayer fp = bot.player();
            assertFalse(bot.busy(), "server EscapeProcess did not finish: y=" + fp.getY());
            // Up from the floorY+1 pit bottom to ~the rim.
            assertTrue(fp.getY() >= floor + 3.0, "server EscapeProcess did not climb out: y=" + fp.getY());
        });
    }

    /** Real RunAwayProcess, 2-HP lethal-edge discipline (DEATH #3). */
    @Test
    void serverLowHpEdgePin(Game game, @Plot.Centered @Plot.Size(x = 48, y = 32, z = 16) Plot plot) {
        Settings.pinUntimed(game).set(NAVIGATOR_DEBUG, false).set("lowHealthCareful", 6.0);
        final int floorY = -8;
        // Broad base slab — the lethal landing zone under the lip.
        plot.fill(-6, floorY, -6, 18, floorY, 6, Blocks.STONE);
        // Elevated runway heading +x: flat top floorY+12 (dx -2..6), a 2-step planned descent (dx 7 → +11, dx 8 →
        // +10), a 2-cell flat (dx 9..10 at +10), then the lethal 10-block lip (dx ≥ 11 is open air down to the base).
        for (int dx = -2; dx <= 10; dx++) {
            int top = dx <= 6 ? 12 : dx == 7 ? 11 : 10;
            plot.fill(dx, floorY + 8, -1, dx, floorY + top, 1, Blocks.STONE);
            // Side walls channel the flee along +x so the only route is the staircase.
            plot.fill(dx, floorY + top + 1, -2, dx, floorY + top + 3, -2, Blocks.STONE);
            plot.fill(dx, floorY + top + 1, 2, dx, floorY + top + 3, 2, Blocks.STONE);
        }
        BlockPos from = plot.at(-2, floorY + 13, 0);
        final int minDist = 10; // satisfied at dx ≥ 8, right after the planned descent
        int floor = plot.at(0, floorY, 0).getY();

        SceneBot bot = SceneBot.spawn(game, plot, 0, floorY + 13, 0);
        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            fp.setHealth(2.0f);
            // Hungry enough that natural regeneration stays off (FoodData.tick heals a hurt player only at food >=
            // 18) and fed enough to sprint (> 6). The player runs FoodData.tick every step, and at full food it would
            // heal past lowHealthCareful long before the flee ends, switching off the careful walk this test is about.
            fp.getFoodData().setFoodLevel(17);
            bot.runAway(from, minDist);
            bot.tickUntilIdle(800);

            double ddx = fp.getX() - (from.getX() + 0.5);
            double ddz = fp.getZ() - (from.getZ() + 0.5);
            double dist = Math.sqrt(ddx * ddx + ddz * ddz);
            assertTrue(
                    fp.getHealth() >= 2.0f,
                    "low-HP flee took damage (fell off the planned descent / lip): hp=" + fp.getHealth() + " y="
                            + fp.getY());
            assertTrue(fp.getY() >= floor + 9.0, "low-HP flee left the elevated runway (fell): y=" + fp.getY());
            assertTrue(
                    dist >= minDist - 0.5,
                    "low-HP flee did not reach min distance: dist=" + dist + " (need " + minDist + ")");
            assertFalse(bot.busy(), "low-HP flee did not finish");
        });
    }

    /** Real BunkerProcess must enclose the niche, not punch a cliff face (death#2 enclosure regression). */
    @Test
    void serverBunkerSlope(Game game, @Plot.Centered Plot plot) {
        Settings.pinUntimed(game)
                .set("allowBreak", true)
                .set("allowPlace", true)
                .set(NAVIGATOR_DEBUG, false);
        final int floorY = 0;
        plot.fill(-4, floorY - 4, -4, 4, floorY + 1, 4, Blocks.DIRT);
        // Standing slot at the centre.
        plot.fill(0, floorY + 1, 0, 0, floorY + 2, 0, Blocks.AIR);
        // Open cliff face: everything at dz <= -2 is AIR — a NORTH niche at any depth this arena reaches would open
        // onto it through a 1-thick wall.
        plot.fill(-4, floorY - 4, -4, 4, floorY + 1, -2, Blocks.AIR);

        SceneBot bot = SceneBot.spawn(game, plot, 0, floorY + 1, 0);
        bot.give(Items.DIRT, 64);
        game.runOnServer(s -> {
            bot.bunker(2);
            bot.tickUntilIdle(1200);
            ServerLevel level = bot.level();
            BlockPos foot = bot.player().blockPosition();
            BlockPos head = foot.above();
            int openLateral = 0;
            StringBuilder open = new StringBuilder();
            for (Direction d : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                for (BlockPos cell : new BlockPos[] {foot.relative(d), head.relative(d)}) {
                    if (level.getBlockState(cell).isAir()) {
                        openLateral++;
                        open.append(cell.toShortString()).append(' ');
                    }
                }
            }
            boolean roofOpen = level.getBlockState(head.above()).isAir();
            assertTrue(
                    openLateral == 0 && !roofOpen,
                    "bunker pocket not enclosed at " + foot.toShortString() + ": openLateral=" + openLateral
                            + " roofOpen=" + roofOpen + " cells=" + open);
        });
    }

    /** Gap#70: observe.player carries air / maxAir over a server bot. */
    @Test
    void serverObserveAirSupply(Game game, @Plot.Centered Plot plot) {
        Settings.pin(game).set(NAVIGATOR_DEBUG, false);
        plot.place(0, 0, 0, Blocks.STONE);
        SceneBot bot = SceneBot.spawn(game, plot, 0, 1, 0);
        String name = game.onServer(s -> {
            bot.player().setAirSupply(42);
            return bot.player().getGameProfile().getName();
        });
        int maxAir = game.onServer(s -> bot.player().getMaxAirSupply());

        // The bot's player by its profile name, as an agent asks for any player on the server.
        ObserveContracts.PlayerReading reading = game.onServer(
                s -> game.api(ObserveBlockingApi::of).player(new ObserveContracts.PlayerParams(Optional.of(name))));
        if (reading.player().isEmpty()) {
            fail("gap#70: mc.observe.player found no player named " + name);
        }
        ObserveContracts.Vitals vitals = reading.player().get().vitals();
        game.check(
                vitals.air() == 42,
                "gap#70: observe.player reports the wrong `air` — got " + vitals.air()
                        + ", wanted 42 (setAirSupply(42))");
        game.check(
                vitals.maxAir() == maxAir,
                "gap#70: observe.player reports the wrong `maxAir` — got " + vitals.maxAir() + ", wanted " + maxAir);
    }

    /**
     * An idle server bot with a dirt block in its head cell, on the tick list with no task. Armed, its own brain's
     * {@code AntiSuffocate} must break the block through the server body's {@code continueDestroy}, the only call that
     * reflex makes.
     */
    @Test
    void serverAntiSuffocateBreaksTheHeadBlock(Game game, @Plot.Centered Plot plot, TestReporter reporter) {
        antiSuffocate(game, plot, reporter, true);
    }

    /** The counter-arm: disarmed, the block must stay, which is what says the armed arm's clearing was the reflex. */
    @Test
    void serverAntiSuffocateDisarmedStaysBuried(Game game, @Plot.Centered Plot plot, TestReporter reporter) {
        antiSuffocate(game, plot, reporter, false);
    }

    private static void antiSuffocate(Game game, Plot plot, TestReporter reporter, boolean armed) {
        plot.fill(-1, 1, -1, 1, 1, 1, Blocks.STONE);
        Settings.pin(game).set("antiSuffocate", armed).set("allowBreak", true);
        BlockPos head = plot.at(0, 3, 0);
        SceneBot bot = SceneBot.spawn(game, plot, 0, 2, 0);
        // One server task: idle on the tick list (the server ticks its brain, reflexes included), then the wall.
        game.runOnServer(s -> {
            bot.unpark();
            bot.level().setBlockAndUpdate(head, Blocks.DIRT.defaultBlockState());
        });
        boolean inWall = game.onServer(s -> bot.player().isInWall());
        reporter.publishEntry("setup.inWall", String.valueOf(inWall));
        int[] t = {0};
        game.await()
                .within(SUFFOCATE_BUDGET + 100)
                .as("the head cell clears or the budget runs out")
                .until(() -> bot.level().getBlockState(head).isAir() || ++t[0] >= SUFFOCATE_BUDGET);
        game.runOnServer(s -> {
            boolean cleared = bot.level().getBlockState(head).isAir();
            reporter.publishEntry("head.cleared", cleared + " after " + t[0] + "/" + SUFFOCATE_BUDGET + " ticks");
            if (armed && !cleared) {
                fail("the head cell " + head.toShortString() + " is still "
                        + bot.level().getBlockState(head).getBlock()
                        + " after " + SUFFOCATE_BUDGET
                        + " ticks with antiSuffocate on: the idle server bot's reflex did "
                        + "not break it");
            }
            if (!armed && cleared) {
                fail(
                        "counter-arm failed: antiSuffocate is off, yet the head cell was cleared; something other than the "
                                + "reflex broke it, so the armed arm proves nothing");
            }
        });
    }
}
