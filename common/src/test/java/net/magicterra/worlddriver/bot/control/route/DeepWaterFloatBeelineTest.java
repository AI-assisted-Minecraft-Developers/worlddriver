package net.magicterra.worlddriver.bot.control.route;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.debug.GridWorldView;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The adoption's anchor gate and its deep-water-float bee-line exemption ({@code navigatorDeepWaterFloatBeeline}).
 * A bot floating in deep open water commits a continuation whose first node is ~48 blocks ahead over clear water:
 * with the exemption off the gate rejects it as mis-anchored (the live dead-stop), with it on the same segment is
 * adopted and steered toward the far node, and the exemption stays inert for a walled line or a dry foot.
 *
 * <p>The segment deliberately does NOT start at the foot: that gap is what the gate judges, and three of the five
 * cases expect a reject because of it.
 */
class DeepWaterFloatBeelineTest {

    private static final int FLOOR_Y = 60, DEPTH = 8, SURFACE = FLOOR_Y + DEPTH, SPAN_X = 56;

    private final BlockPos foot = new BlockPos(0, SURFACE, 0);
    private final BlockPos farNode = new BlockPos(48, SURFACE, 0);
    private final BlockPos beyond = new BlockPos(52, SURFACE, 0);
    private final BlockPos goal = new BlockPos(SPAN_X, SURFACE, 0);
    private final List<BlockPos> cont = List.of(farNode, beyond, goal);
    private final List<Move.Edge> contEdges = List.of(
            new Move.Edge(farNode, 50, List.of(), List.of(), "walk"),
            new Move.Edge(beyond, 10, List.of(), List.of(), "walk"),
            new Move.Edge(goal, 30, List.of(), List.of(), "walk"));

    private GridWorldView w;
    private boolean was;

    @BeforeEach
    void basin() {
        was = ControlConfig.navigatorDeepWaterFloatBeeline;
        w = new GridWorldView(-6, FLOOR_Y - 1, -5, SPAN_X + 23, DEPTH + 10, 11);
        // A deep basin: stone floor, north and south walls, a west cap, open water up to the surface.
        w.fill(-5, FLOOR_Y, -4, SPAN_X + 16, FLOOR_Y, 4, GridWorldView.SOLID);
        w.fill(-4, FLOOR_Y + 1, -3, SPAN_X + 1, SURFACE + 2, -3, GridWorldView.SOLID);
        w.fill(-4, FLOOR_Y + 1, 3, SPAN_X + 1, SURFACE + 2, 3, GridWorldView.SOLID);
        w.fill(-4, FLOOR_Y + 1, -3, -4, SURFACE + 2, 3, GridWorldView.SOLID);
        w.fill(-3, FLOOR_Y + 1, -2, SPAN_X, SURFACE, 2, GridWorldView.WATER);

        assertTrue(w.isWater(foot) && w.isWater(foot.below()), "the foot floats in deep water");
        assertTrue(w.isWater(farNode), "the far node is open water");
        assertTrue(farNode.distSqr(foot) >= 64, "the far node is past the water reject gate");
    }

    @AfterEach
    void restore() {
        ControlConfig.navigatorDeepWaterFloatBeeline = was;
    }

    private boolean adopts(BlockPos from) {
        return new Navigator().adoptForTest(w, cont, contEdges, from);
    }

    @Test
    void withoutTheExemptionTheFarContinuationIsMisAnchored() {
        ControlConfig.navigatorDeepWaterFloatBeeline = false;
        assertFalse(adopts(foot), "the anchor gate must reject the far open-water continuation (the live dead-stop)");
    }

    @Test
    void theExemptionAdoptsItAndSteersTowardTheFarNode() {
        ControlConfig.navigatorDeepWaterFloatBeeline = true;
        Navigator nav = new Navigator();
        assertTrue(nav.adoptForTest(w, cont, contEdges, foot), "a clear open-water bee-line must be adopted");
        BlockPos drive = nav.inspect().pathNode();
        assertNotNull(drive, "the adopted segment has a node to steer to");
        assertTrue(drive.getX() > foot.getX(), "the step must drive east toward the far node, got " + drive);
    }

    @Test
    void aSunkFootPastTheJumpGateStillSwimsUp() {
        ControlConfig.navigatorDeepWaterFloatBeeline = true;
        BlockPos sunk = foot.below(4);
        assertTrue(w.isWater(sunk) && Math.abs(farNode.getY() - sunk.getY()) > w.maxJumpUpBlocks() + 2,
                "the sunk foot is submerged and past the jump gate");
        assertTrue(adopts(sunk), "a swim-up to the surface node must bypass the |dy| jump gate");
    }

    @Test
    void aWalledLineIsStillRejected() {
        ControlConfig.navigatorDeepWaterFloatBeeline = true;
        w.set(20, SURFACE, 0, GridWorldView.SOLID);
        w.set(20, SURFACE + 1, 0, GridWorldView.SOLID);
        assertFalse(adopts(foot), "the exemption must not bee-line through a wall");
    }

    @Test
    void aDryFootIsStillRejected() {
        ControlConfig.navigatorDeepWaterFloatBeeline = true;
        BlockPos dry = new BlockPos(SPAN_X + 12, SURFACE + 1, 0);
        w.fill(dry.getX() - 1, SURFACE, -1, dry.getX() + 1, SURFACE, 1, GridWorldView.SOLID);
        assertFalse(adopts(dry), "the exemption is for a floating foot only");
    }
}
