package net.magicterra.worlddriver.bot.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.debug.GridWorldView;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * A string-pulled path is a few long legs, and the bot spends most of a leg far from the node it is
 * walking to. Projected only onto the legs past that node, it reads as far off the path, and the
 * pursuit bearing cuts straight for the leg after, across whatever the pulled leg went around.
 */
class PathProjectionTest {

    // R1 1426,64,-399 → 1411,64,-414 → 1411,64,-416, moved to the origin: a 15-block diagonal and a 2-block stub.
    private static final List<BlockPos> PATH = List.of(
            new BlockPos(15, 64, 0), new BlockPos(0, 64, 15), new BlockPos(0, 64, 17));

    private static PathProjection projectMidLeg() {
        PathProjection proj = new PathProjection();
        // Walking to node 1, a third of the way down the diagonal and 0.1 off it.
        proj.compute(PATH, null, new GridWorldView(-2, 60, -2, 20, 8, 22), 1, 64, 10.57, 5.57, 16, 2.5);
        return proj;
    }

    @Test
    void theBotIsProjectedOntoTheLegItWalks() {
        boolean was = BotConfig.walkerProjectCurrentLeg;
        BotConfig.walkerProjectCurrentLeg = true;
        try {
            PathProjection proj = projectMidLeg();
            assertEquals(0, proj.segIdx, "the bot is on the diagonal, not the stub past its node");
            assertTrue(proj.perp < 0.2, "0.1 off the diagonal, got perp=" + proj.perp);
            assertEquals(45f, proj.tangentYaw, 0.01f, "the heading is the diagonal's");
            assertEquals(45f, proj.pursuitYaw, 3f, "the pursuit closes the 0.1 along the diagonal, got " + proj.pursuitYaw);
        } finally {
            BotConfig.walkerProjectCurrentLeg = was;
        }
    }

    @Test
    void withoutTheWalkedLegThePursuitCutsForTheStub() {
        boolean was = BotConfig.walkerProjectCurrentLeg;
        BotConfig.walkerProjectCurrentLeg = false;
        try {
            PathProjection proj = projectMidLeg();
            assertEquals(1, proj.segIdx);
            assertTrue(proj.perp > 7, "read as 7+ blocks off the path, got perp=" + proj.perp);
            assertEquals(0.5, proj.aheadX, 1e-9);
            assertEquals(17.5, proj.aheadZ, 1e-9);
        } finally {
            BotConfig.walkerProjectCurrentLeg = was;
        }
    }
}
