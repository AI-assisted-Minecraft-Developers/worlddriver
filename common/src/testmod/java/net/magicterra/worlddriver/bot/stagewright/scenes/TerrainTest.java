package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.fail;

import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.bot.stagewright.kit.NavWalk;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.magicterra.worlddriver.bot.stagewright.kit.SimProbes;
import net.magicterra.worlddriver.bot.stagewright.kit.Targets;
import net.magicterra.worlddriver.testcontent.TestMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Terrain traversal arenas: a bot's {@code mc.bot.goto} down a 1-wide stair over a pit. The test stages its arena in
 * plot coordinates, spawns a parked bot, starts the walk and ticks it one whole bot tick at a time inside one server
 * task, reading the player every tick. How fast and how cleanly the bot crosses walls, gaps, stairs and ledges is the
 * benchmark's to measure (the parkour, dig-and-build and cross-country courses); what stays here is the fall the
 * benchmark has no course for.
 *
 * <p>{@code descentDrift} has no test: its old arena was a proven false green (it passed only because a concurrent
 * arena shoved the shared player out of the wedge; solo it launched off the stair into open void), its RED path is an
 * open-void A* churn needing ~110 s of compute, and its gate is the live A/B. Descent behaviour stays covered by
 * {@link #descent}.
 */
@Scene("wd.scenes")
@Execution(ExecutionMode.SAME_THREAD)
class TerrainTest {

    private static final String STEP = ") step=";

    private static final String COMMA = ",";

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
                fail("descent: crouch-deadlock: did not reach the bottom step: pos=(" + fp.getX() + COMMA + fp.getY()
                        + COMMA + fp.getZ() + STEP + st);
            }
        });
    }
}
