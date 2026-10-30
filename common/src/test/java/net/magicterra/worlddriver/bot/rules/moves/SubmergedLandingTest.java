package net.magicterra.worlddriver.bot.rules.moves;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.magicterra.worlddriver.debug.GridWorldView;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * A floating bot is carried back up to the surface, so a fall whose landing has more water above it lands on a riverbed
 * cell the bot can never hold. Neither fall may plan one: an 8-deep channel, the bot floating on its surface.
 */
class SubmergedLandingTest {
    private static final int DEPTH = 8;
    private static final int SURFACE = DEPTH;

    /** Stone floor at y=0, water y=1..{@link #SURFACE} over x 0..15, z -2..2, air above. */
    private static GridWorldView channel() {
        GridWorldView g = new GridWorldView(-2, 0, -3, 20, DEPTH + 6, 7);
        g.fill(-2, 0, -3, 17, 0, 3, GridWorldView.SOLID);
        g.fill(0, 1, -2, 15, SURFACE, 2, GridWorldView.WATER);
        return g;
    }

    private static final BlockPos SURFACE_CELL = new BlockPos(4, SURFACE, 0);

    @Test
    void aWaterFallToTheBedIsNotAMove() {
        assertFalse(
                new FallIntoWater(1, 0, DEPTH - 1).valid(channel(), SURFACE_CELL),
                "the bed under seven cells of water is not where a floating bot rests");
    }

    /**
     * The control: the same channel does admit a water fall, onto its surface, so the two refusals are about depth and
     * not about a grid no fall can use.
     */
    @Test
    void aWaterFallOntoTheSurfaceIsAMove() {
        assertTrue(
                new FallIntoWater(1, 0, 3).valid(channel(), new BlockPos(4, SURFACE + 3, 0)),
                "the surface cell, air above it, is where a floating bot rests");
    }

    @Test
    void aDryFallIntoSubmergedWaterIsNotAMove() {
        assertFalse(new Fall(1, 0, 2).valid(channel(), SURFACE_CELL), "a landing with water above it is not a stand");
    }
}
