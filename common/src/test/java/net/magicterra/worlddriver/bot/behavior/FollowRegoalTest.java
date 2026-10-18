package net.magicterra.worlddriver.bot.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.magicterra.worlddriver.bot.control.Walker;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * A follow re-aims its walker every time the target changes block. If that re-aim is a fresh goal,
 * the futile-search counter goes back to zero each time, so a target the bot cannot reach never
 * trips the cap and the walker runs a full search per tick for as long as the follow lasts.
 */
class FollowRegoalTest {

    private static final int TARGET = 42;

    private static int futileSearches(Walker w) {
        return w.search.gov.futileSearches;
    }

    private static void setFutileSearches(Walker w, int n) {
        w.search.gov.futileSearches = n;
    }

    @Test
    void theSameTargetMovingABlockKeepsTheFutileCount() {
        FollowProcess follow = new FollowProcess("minecraft:player", null, 3, 0);
        follow.regoal(TARGET, new BlockPos(10, 64, 10));
        setFutileSearches(follow.walker(), 3);

        follow.regoal(TARGET, new BlockPos(11, 64, 10));
        assertEquals(3, futileSearches(follow.walker()),
                "a quarry that moved is the same pursuit; zeroing the counter hides an unreachable target");
    }

    @Test
    void holdingStillPastTheOldReplanIntervalKeepsTheFutileCount() {
        FollowProcess follow = new FollowProcess("minecraft:player", null, 3, 0);
        BlockPos cell = new BlockPos(10, 64, 10);
        follow.regoal(TARGET, cell);
        setFutileSearches(follow.walker(), 3);

        for (int i = 0; i < 40; i++) follow.regoal(TARGET, cell);
        assertEquals(3, futileSearches(follow.walker()),
                "a target standing still gives the walker nothing new to aim at");
    }

    @Test
    void aDifferentTargetIsANewPursuit() {
        FollowProcess follow = new FollowProcess("minecraft:player", null, 3, 0);
        follow.regoal(TARGET, new BlockPos(10, 64, 10));
        setFutileSearches(follow.walker(), 3);

        follow.regoal(TARGET + 1, new BlockPos(11, 64, 10));
        assertEquals(0, futileSearches(follow.walker()),
                "a count earned against one entity must not carry over to another");
    }
}
