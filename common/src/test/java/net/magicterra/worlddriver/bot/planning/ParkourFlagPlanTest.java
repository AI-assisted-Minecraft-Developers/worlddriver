package net.magicterra.worlddriver.bot.planning;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.magicterra.worlddriver.VanillaBoot;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.bot.rules.moves.ParkourPlace;
import net.magicterra.worlddriver.config.BotConfig;
import net.magicterra.worlddriver.config.PlanningConfig;
import net.magicterra.worlddriver.config.RulesConfig;
import net.magicterra.worlddriver.config.WorldConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The two opt-in leap moves, each an A/B on its own setting over a gap with nothing under it: {@code allowParkour4} and
 * {@code allowParkourPlace}. Each gap is staged so the gated move is the only way across, which makes the plan's reach
 * the reading: off, the goal is out of reach; on, it is reached through an edge of that move. The rest of the settings
 * are the test baseline, so breaking and placing stay off and no bridge or pillar can stand in.
 */
class ParkourFlagPlanTest {

    private static final int FLOOR = 64;
    private static final int STAND = FLOOR + 1;

    private BotConfig.ConfigPin pin;

    @BeforeAll
    static void boot() {
        VanillaBoot.boot();
    }

    @BeforeEach
    void pinBaseline() {
        pin = BotConfig.pinnedBaseline();
        PlanningConfig.pathfinderMaxMs = Long.MAX_VALUE / 4; // each search runs to completion (deterministic)
        PlanningConfig.pathfinderMaxNodes = 200_000;
    }

    @AfterEach
    void restore() {
        pin.close();
    }

    private static List<String> moves(PathFinder.Result r) {
        return r.edges().stream().map(e -> e == null ? "-" : e.move).toList();
    }

    /**
     * A leap of four cells (three of void between the lip and the far deck). {@code Parkour4} is in the search only
     * while {@code allowParkour4} is on ({@code availableInSearch}); {@code Parkour3} reaches three cells, one short.
     */
    @Test
    void parkour4CrossesTheGapOnlyWhenAllowed() {
        BlockMapView w = new BlockMapView();
        w.fill(-6, FLOOR, -1, 0, FLOOR, 1, Blocks.STONE); // launch deck, its lip at x=0, run-up behind it
        w.fill(4, FLOOR, -1, 10, FLOOR, 1, Blocks.STONE); // far deck; x=1..3 is void to the world floor
        BlockPos start = new BlockPos(-3, STAND, 0);
        Goal goal = new Goal.Block(new BlockPos(8, STAND, 0));

        RulesConfig.allowParkour4 = false;
        PathFinder.Result off = PlanProbe.plan(w, start, goal);
        RulesConfig.allowParkour4 = true;
        PathFinder.Result on = PlanProbe.plan(w, start, goal);

        assertFalse(
                off.goalReached(), "with allowParkour4 off the planner still crossed a four-cell leap: " + moves(off));
        assertTrue(on.goalReached(), "with allowParkour4 on the planner did not cross: " + moves(on));
        assertTrue(moves(on).contains("parkour4"), "the crossing did not use a parkour4 edge: " + moves(on));
    }

    /**
     * A two-cell leap onto a cell with no floor, whose floor has the far deck beside it to place against
     * ({@code Move.hasPlaceSupport}). {@code ParkourPlace.eval} returns null unless {@code canParkourPlace()}, which is
     * {@code allowParkourPlace} and a placeable block in the bag. The goal is that floorless cell. With the move off
     * the planner still gets there the slow way (a {@code parkour3} past it and a {@code bridgePlace} back, since the
     * bag holds blocks), so the reading is which edges the plan uses, not whether it reaches.
     */
    @Test
    void parkourPlaceIsOfferedOnlyWhenAllowed() {
        BlockMapView w = new BlockMapView();
        w.fill(-6, FLOOR, -1, 0, FLOOR, 1, Blocks.STONE); // launch deck, lip at x=0
        w.fill(3, FLOOR, -1, 8, FLOOR, 1, Blocks.STONE); // far deck from x=3: the support of the landing floor at x=2
        w.give(new ItemStack(Items.COBBLESTONE, 16));
        BlockPos lip = new BlockPos(0, STAND, 0);
        BlockPos start = new BlockPos(-3, STAND, 0);
        Goal goal = new Goal.Block(new BlockPos(2, STAND, 0));
        ParkourPlace east = new ParkourPlace(1, 0);

        WorldConfig.allowParkourPlace = false;
        boolean offeredOff = east.eval(w, lip) != null;
        PathFinder.Result off = PlanProbe.plan(w, start, goal);
        WorldConfig.allowParkourPlace = true;
        boolean offeredOn = east.eval(w, lip) != null;
        PathFinder.Result on = PlanProbe.plan(w, start, goal);

        assertFalse(offeredOff, "with allowParkourPlace off the move still offered an edge from the lip");
        assertFalse(
                moves(off).contains("parkourPlace2"),
                "with allowParkourPlace off the plan used parkourPlace2: " + moves(off));
        assertTrue(offeredOn, "with allowParkourPlace on the move offered no edge from the lip");
        assertTrue(
                on.goalReached(),
                "with allowParkourPlace on the planner did not reach the floorless cell: " + moves(on));
        assertTrue(
                moves(on).contains("parkourPlace2"), "the plan reached it without a parkourPlace2 edge: " + moves(on));
    }
}
