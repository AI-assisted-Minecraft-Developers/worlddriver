package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.fail;

import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneArena;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.magicterra.worlddriver.bot.stagewright.kit.Targets;
import net.magicterra.worlddriver.testcontent.TestMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
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
 * {@code mc.debug.navigator}. The goto and the whole tick loop run inside one {@code game.runOnServer}, so the test can
 * drive the bot's air supply between two of its ticks. Arenas are built at absolute coordinates taken from the plot
 * ({@code cx}, {@code cz}, {@code floorY}).
 */
@Scene("wd.scenes")
class CoverageTest {

    private static final String POS = " pos=";

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
            final ServerLevel level = s.overworld();
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
            final boolean reached =
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
                        + POS + fp.position() + " eyeInWater=" + fp.isEyeInFluid(FluidTags.WATER)
                        + " blockAtFeet=" + level.getBlockState(fp.blockPosition()));
            }
            if (!reached) {
                fail("drownEscapeSurface: navigator did not finish the crossing after surfacing: step=" + end + POS
                        + fp.position());
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

    /** {@code walking} while the goto runs; after, how it ended and its last error. */
    private static String ending(SceneBot bot) {
        if (bot.busy()) {
            return "walking";
        }
        ControlContracts.Slot g = bot.gotoSlot();
        return g.endReason().orElse("no end reason")
                + g.lastError().map(e -> " (" + e + ")").orElse("");
    }
}
