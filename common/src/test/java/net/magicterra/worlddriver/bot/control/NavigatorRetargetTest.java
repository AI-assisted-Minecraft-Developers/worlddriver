package net.magicterra.worlddriver.bot.control;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.magicterra.worlddriver.bot.planning.PathFinder;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.debug.GridWorldView;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * A quarry pacing inside a pen changes cell several times a second. A re-aim that drops the search in flight means no
 * full search ever lands, so the futile-search judge, which runs only on a landed search, never counts one: the follow
 * of an unreachable target runs forever, and a reachable one too far for the quick stub never gets a route.
 */
class NavigatorRetargetTest {

    private static PathFinder.Search footSearch() {
        GridWorldView w = new GridWorldView(-4, 60, -4, 16, 8, 16);
        w.fill(-4, 60, -4, 11, 63, 11, GridWorldView.SOLID);
        return new PathFinder(w).newSearch(new BlockPos(0, 64, 0), new Goal.Near(new BlockPos(5, 64, 0), 1));
    }

    private final Navigator navigator = new Navigator(NavigatorSettings.named("follow"));

    @Test
    void aChaseReAimKeepsTheSearchRunningFromTheFeet() {
        navigator.start(new Goal.Near(new BlockPos(5, 64, 0), 1));
        PathFinder.Search s = footSearch();
        navigator.route.searchFromFoot(s, false);

        navigator.retarget(new Goal.Near(new BlockPos(6, 64, 0), 1));
        assertSame(
                s,
                navigator.route.seg.activeSearch,
                "the search toward the quarry's last cell must be allowed to land");
    }

    @Test
    void aContinuationFromTheSegmentEndGoesWithThePathItWouldExtend() {
        navigator.start(new Goal.Near(new BlockPos(5, 64, 0), 1));
        navigator.route.install(
                java.util.List.of(new BlockPos(0, 64, 0), new BlockPos(3, 64, 0)),
                java.util.Arrays.asList(null, null),
                1,
                true);
        navigator.route.searchFromEnd(navigator, new GridWorldView(-4, 60, -4, 16, 8, 16));

        navigator.retarget(new Goal.Near(new BlockPos(6, 64, 0), 1));
        assertNull(
                navigator.route.seg.activeSearch,
                "the re-aim clears the path, so there is nothing left to splice onto");
    }

    @Test
    void aNewGoalStillDropsTheSearch() {
        navigator.start(new Goal.Near(new BlockPos(5, 64, 0), 1));
        navigator.route.searchFromFoot(footSearch(), false);

        navigator.start(new Goal.Near(new BlockPos(6, 64, 0), 1));
        assertNull(navigator.route.seg.activeSearch);
    }
}
