package net.magicterra.worlddriver.bot.movement;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.debug.GridWorldView;
import net.magicterra.worlddriver.bot.pathfinder.PathFinder;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * A quarry pacing inside a pen changes cell several times a second. A re-aim that drops the search in
 * flight means no full search ever lands, so the futile-search judge, which runs only on a landed
 * search, never counts one: the follow of an unreachable target runs forever, and a reachable one too
 * far for the quick stub never gets a route.
 */
class WalkerRetargetTest {

    private static PathFinder.Search footSearch() {
        GridWorldView w = new GridWorldView(-4, 60, -4, 16, 8, 16);
        w.fill(-4, 60, -4, 11, 63, 11, GridWorldView.SOLID);
        return new PathFinder(w).newSearch(new BlockPos(0, 64, 0), new Goal.Near(new BlockPos(5, 64, 0), 1));
    }

    private final Walker walker = new Walker("follow");

    @Test
    void aChaseReAimKeepsTheSearchRunningFromTheFeet() {
        walker.setGoal(new Goal.Near(new BlockPos(5, 64, 0), 1));
        PathFinder.Search s = footSearch();
        walker.seg.activeSearch = s;

        walker.retargetGoal(new Goal.Near(new BlockPos(6, 64, 0), 1));
        assertSame(s, walker.seg.activeSearch, "the search toward the quarry's last cell must be allowed to land");
    }

    @Test
    void aContinuationFromTheSegmentEndGoesWithThePathItWouldExtend() {
        walker.setGoal(new Goal.Near(new BlockPos(5, 64, 0), 1));
        walker.seg.activeSearch = footSearch();
        walker.seg.searchFromEnd = true;
        walker.seg.commitEnd = new BlockPos(3, 64, 0);

        walker.retargetGoal(new Goal.Near(new BlockPos(6, 64, 0), 1));
        assertNull(walker.seg.activeSearch, "the re-aim clears the path, so there is nothing left to splice onto");
    }

    @Test
    void aNewGoalStillDropsTheSearch() {
        walker.setGoal(new Goal.Near(new BlockPos(5, 64, 0), 1));
        walker.seg.activeSearch = footSearch();

        walker.setGoal(new Goal.Near(new BlockPos(6, 64, 0), 1));
        assertNull(walker.seg.activeSearch);
    }
}
