package net.magicterra.worlddriver.bot.rules.moves;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.debug.GridWorldView;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** A stroke checks every cell the body's box covers on the way, in its own medium only. */
class MediumMoveTest {

    private static Move stroke(java.util.List<Move> family, int dx, int dy, int dz) {
        for (Move m : family) if (m.dx == dx && m.dy == dy && m.dz == dz) return m;
        throw new AssertionError("no stroke " + dx + "," + dy + "," + dz);
    }

    /** A wall across x = 5 with a hole from (y0, z0) to (y1, z1). */
    private static GridWorldView wall(int y0, int z0, int y1, int z1) {
        GridWorldView g = new GridWorldView(0, 0, 0, 20, 20, 20);
        g.fill(5, 0, 0, 5, 19, 19, GridWorldView.SOLID);
        g.fill(5, y0, z0, 5, y1, z1, GridWorldView.AIR);
        return g;
    }

    @Test
    void theBoxCoversTheCellsAVanillaFlyerNeeds() {
        assertEquals(0, MediumMove.cellsBeside(0.5)); // parrot
        assertEquals(0, MediumMove.cellsBeside(0.9)); // phantom
        assertEquals(1, MediumMove.cellsBeside(1.9975)); // elder guardian
        assertEquals(2, MediumMove.cellsBeside(4.0)); // ghast
        assertEquals(0, MediumMove.cellsAbove(0.9));
        assertEquals(1, MediumMove.cellsAbove(1.8)); // blaze
        assertEquals(2, MediumMove.cellsAbove(1.9975));
        assertEquals(4, MediumMove.cellsAbove(4.0));
    }

    @Test
    void aSmallFlyerPassesAOneCellHoleAndAGhastDoesNot() {
        Move east = stroke(MediumMove.FLY, 1, 0, 0);
        // Each from the node whose box ends just short of the wall.
        BlockPos small = new BlockPos(4, 10, 10), ghast = new BlockPos(2, 10, 10);
        assertTrue(east.valid(wall(10, 10, 10, 10).withBody(0.5, 0.9), small));
        assertFalse(east.valid(wall(10, 10, 10, 10).withBody(4.0, 4.0), ghast));
        assertFalse(east.valid(wall(10, 9, 13, 11).withBody(4.0, 4.0), ghast)); // three wide, four high
        assertTrue(east.valid(wall(10, 8, 14, 12).withBody(4.0, 4.0), ghast)); // five wide, five high
    }

    @Test
    void aDiagonalCutsNoCorner() {
        GridWorldView g = new GridWorldView(0, 0, 0, 20, 20, 20).withBody(0.5, 0.9);
        g.set(5, 10, 10, GridWorldView.SOLID);
        assertFalse(stroke(MediumMove.FLY, 1, 0, 1).valid(g, new BlockPos(4, 10, 10)));
        assertTrue(stroke(MediumMove.FLY, -1, 0, 1).valid(g, new BlockPos(4, 10, 10)));
    }

    @Test
    void eachMediumKeepsOutOfTheOther() {
        GridWorldView g = new GridWorldView(0, 0, 0, 20, 20, 20).withBody(0.5, 0.5);
        g.fill(0, 0, 0, 19, 9, 19, GridWorldView.WATER);
        BlockPos surface = new BlockPos(4, 9, 4);
        assertFalse(stroke(MediumMove.FLY, 0, -1, 0).valid(g, surface.above()));
        assertTrue(stroke(MediumMove.FLY, 0, 1, 0).valid(g, surface)); // a flyer in water may rise out
        assertFalse(stroke(MediumMove.SWIM, 0, 1, 0).valid(g, surface));
        assertTrue(stroke(MediumMove.SWIM, 1, -1, 0).valid(g, surface));
    }
}
