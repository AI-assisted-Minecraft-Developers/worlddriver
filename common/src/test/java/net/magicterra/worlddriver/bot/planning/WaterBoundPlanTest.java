package net.magicterra.worlddriver.bot.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.debug.GridWorldView;
import net.magicterra.worlddriver.game.body.Locomotion;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * A body that only swims plans through water and nowhere else: down a flooded shaft and along a flooded tunnel that
 * bends, every node a water cell; and a goal on the bank above is out of reach, since nothing in its catalog leaves the
 * water.
 */
class WaterBoundPlanTest {

    private static final int Y = 20;

    /** Stone everywhere; water in a shaft at x=0 from y=Y down to Y-6, then a tunnel east to x=8 and north to z=6. */
    private static GridWorldView flooded() {
        GridWorldView w = new GridWorldView(-4, Y - 10, -4, 20, 16, 16)
                .withPlace(false)
                .withModes(EnumSet.of(Locomotion.Mode.SWIM))
                .withBody(0.5, 0.3); // a cod
        w.fill(-3, Y - 9, -3, 14, Y + 4, 12, GridWorldView.SOLID);
        w.fill(0, Y - 6, 0, 0, Y, 0, GridWorldView.WATER);
        w.fill(0, Y - 6, 0, 8, Y - 6, 0, GridWorldView.WATER);
        w.fill(8, Y - 6, 0, 8, Y - 6, 6, GridWorldView.WATER);
        w.fill(-3, Y + 1, -3, 14, Y + 4, 12, GridWorldView.AIR);
        return w;
    }

    private static PathFinder.Result plan(GridWorldView w, BlockPos start, BlockPos goal) {
        PathFinder.Search s = new PathFinder(w, 100_000, 5_000).newSearch(start, new Goal.Block(goal));
        while (!s.advance(5_000)) {}
        return s.result();
    }

    @Test
    void swimsDownTheShaftAndAlongTheBend() {
        GridWorldView w = flooded();
        BlockPos start = new BlockPos(0, Y, 0), goal = new BlockPos(8, Y - 6, 6);
        PathFinder.Result r = plan(w, start, goal);
        List<BlockPos> path = r.path();
        assertTrue(r.goalReached(), "goal not reached: " + path);
        assertEquals(goal, path.get(path.size() - 1));
        for (BlockPos n : path) {
            assertTrue(w.isWater(n), "node " + n.toShortString() + " is not water: " + path);
        }
    }

    @Test
    void aGoalOnTheBankIsOutOfReach() {
        GridWorldView w = flooded();
        PathFinder.Result r = plan(w, new BlockPos(0, Y - 6, 0), new BlockPos(2, Y + 1, 0));
        assertFalse(r.goalReached(), "a water-bound body reached the bank: " + r.path());
        for (BlockPos n : r.path()) {
            assertTrue(w.isWater(n), "node " + n.toShortString() + " is not water: " + r.path());
        }
    }
}
