package net.magicterra.worlddriver.bot.pathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.debug.GridWorldView;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The lake of {@code wd.clientOpenWaterCross}, planned offline: the bot on one bank, a cell on the far
 * bank flush with the water, deep open water between. Under the surface water model the plan is the
 * straight swim along the surface layer — every node on the goal's z, x never turning back, one node per
 * block — at both the default horizon and with the horizon off. The rim is two cells wider than the lake
 * on each side, so a planner that still prices deep water as lethal (or over-taxes it) shows up here as
 * a route that leaves the goal's z to hug the rim.
 */
class OpenWaterLakeTest {
    private static final int GROUND = 20, LEN = 48, HALF = 6, DEPTH = 6;

    private int horizonBefore;

    @BeforeEach
    void keepHorizon() { horizonBefore = BotConfig.pathfinderHorizonBlocks; }

    @AfterEach
    void restoreHorizon() { BotConfig.pathfinderHorizonBlocks = horizonBefore; }

    @Test
    void straightSwimAtBothHorizons() {
        GridWorldView w = new GridWorldView(-10, GROUND - 12, -12, LEN + 24, 24, 24);
        w.fill(-4, GROUND - DEPTH - 2, -HALF - 2, LEN + 6, GROUND, HALF + 2, GridWorldView.SOLID);
        w.fill(-2, GROUND - DEPTH + 1, -HALF, LEN + 2, GROUND, HALF, GridWorldView.WATER);
        BlockPos start = new BlockPos(0, GROUND - 1, 0);
        BlockPos goal = new BlockPos(LEN + 4, GROUND + 1, 0);
        for (int hz : new int[] { 48, 0 }) {
            BotConfig.pathfinderHorizonBlocks = hz;
            PathFinder.Search s = new PathFinder(w, 100_000, 5_000).newSearch(start, new Goal.Block(goal));
            while (!s.advance(5_000)) { }
            PathFinder.Result r = s.result();
            List<BlockPos> path = r.path();
            if (hz == 0) {
                assertTrue(r.goalReached(), "goal not reached with the horizon off");
                assertEquals(goal, path.get(path.size() - 1), "last node with the horizon off");
            } else {
                // The horizon cuts the 52-block line into a committed partial; it still runs straight.
                assertTrue(path.size() >= hz - 8, "horizon " + hz + ": only " + path.size() + " nodes");
            }
            int prevX = Integer.MIN_VALUE;
            for (BlockPos n : path) {
                assertEquals(0, n.getZ(), "horizon " + hz + ": node " + n.toShortString() + " left the goal's z");
                assertTrue(n.getX() >= prevX, "horizon " + hz + ": node " + n.toShortString() + " turned back");
                prevX = n.getX();
            }
            assertTrue(path.size() <= LEN + 8, "horizon " + hz + ": " + path.size() + " nodes for a " + (LEN + 4) + "-block line");
        }
    }
}
