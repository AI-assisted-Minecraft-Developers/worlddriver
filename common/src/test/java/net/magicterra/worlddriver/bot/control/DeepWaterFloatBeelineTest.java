package net.magicterra.worlddriver.bot.control;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.debug.GridWorldView;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A bot floating in deep open water is handed a continuation whose first node is 48 blocks east: the
 * segment anchor gate rejects it, and with {@code navigatorDeepWaterFloatBeeline} on it is accepted as a
 * straight swim, but only from a floating foot and only over a line that is open water.
 *
 * <p>The continuation is deliberately not anchored at the foot; that gap is what the gate judges.
 */
class DeepWaterFloatBeelineTest {
    private static final int SURFACE = 8, SPAN = 56;
    private static final BlockPos FOOT = new BlockPos(0, SURFACE, 0);
    private static final BlockPos FAR = new BlockPos(48, SURFACE, 0);
    private static final List<BlockPos> CONT = List.of(FAR, new BlockPos(52, SURFACE, 0), new BlockPos(SPAN, SURFACE, 0));
    private static final List<Move.Edge> EDGES = List.of(
            new Move.Edge(CONT.get(0), 50, List.of(), List.of(), "walk"),
            new Move.Edge(CONT.get(1), 10, List.of(), List.of(), "walk"),
            new Move.Edge(CONT.get(2), 30, List.of(), List.of(), "walk"));

    private boolean beelineBefore;

    @BeforeEach
    void keep() { beelineBefore = ControlConfig.navigatorDeepWaterFloatBeeline; }

    @AfterEach
    void restore() { ControlConfig.navigatorDeepWaterFloatBeeline = beelineBefore; }

    /** Stone floor at y=0, an 8-deep channel x -3..56 z -2..2 walled at z ±3 and x -4, air above, and a dry
     *  3x3 stand at x 67..69 whose foot cell is y=9. */
    private static GridWorldView channel() {
        GridWorldView g = new GridWorldView(-6, 0, -5, 80, SURFACE + 9, 11);
        g.fill(-5, 0, -4, 72, 0, 4, GridWorldView.SOLID);
        g.fill(-4, 1, -3, SPAN + 1, SURFACE + 2, -3, GridWorldView.SOLID);
        g.fill(-4, 1, 3, SPAN + 1, SURFACE + 2, 3, GridWorldView.SOLID);
        g.fill(-4, 1, -3, -4, SURFACE + 2, 3, GridWorldView.SOLID);
        g.fill(-3, 1, -2, SPAN, SURFACE, 2, GridWorldView.WATER);
        g.fill(SPAN + 11, SURFACE, -1, SPAN + 13, SURFACE, 1, GridWorldView.SOLID);
        return g;
    }

    private static boolean adopt(GridWorldView w, BlockPos foot) {
        return new Navigator().adoptForTest(w, CONT, EDGES, foot);
    }

    @Test
    void offTheFarContinuationIsRejected() {
        ControlConfig.navigatorDeepWaterFloatBeeline = false;
        assertTrue(FAR.distSqr(FOOT) >= 64, "a long open-water crossing, past the 64 reject gate");
        assertFalse(adopt(channel(), FOOT), "the mis-anchored reject is the dead-stop this flag exists for");
    }

    @Test
    void onTheSameContinuationIsAcceptedAndDrivesEast() {
        ControlConfig.navigatorDeepWaterFloatBeeline = true;
        Navigator on = new Navigator();
        assertTrue(on.adoptForTest(channel(), CONT, EDGES, FOOT), "the bee-line exemption must fire");
        BlockPos drive = on.inspect().pathNode();
        assertNotNull(drive, "an accepted segment has a node to drive at");
        assertTrue(drive.getX() > FOOT.getX(), "the step drives east toward the far node, got " + drive);
    }

    @Test
    void onASunkFootIsAcceptedPastTheJumpGate() {
        ControlConfig.navigatorDeepWaterFloatBeeline = true;
        GridWorldView w = channel();
        BlockPos sunk = FOOT.below(4);
        assertTrue(w.isWater(sunk) && Math.abs(FAR.getY() - sunk.getY()) > w.maxJumpUpBlocks() + 2,
                "the sunk foot is water and past the jump gate");
        assertTrue(adopt(w, sunk), "a swim up to the surface node is not a dry climb");
    }

    @Test
    void onAWalledLineIsStillRejected() {
        ControlConfig.navigatorDeepWaterFloatBeeline = true;
        GridWorldView w = channel();
        w.set(20, SURFACE, 0, GridWorldView.SOLID);
        w.set(20, SURFACE + 1, 0, GridWorldView.SOLID);
        assertFalse(adopt(w, FOOT), "a line with stone across it is not open water");
    }

    @Test
    void onADryFootIsStillRejected() {
        ControlConfig.navigatorDeepWaterFloatBeeline = true;
        assertFalse(adopt(channel(), new BlockPos(SPAN + 12, SURFACE + 1, 0)), "the exemption needs a floating foot");
    }
}
