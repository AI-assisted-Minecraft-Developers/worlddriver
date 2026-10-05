package net.magicterra.worlddriver.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Optional;

import net.magicterra.worlddriver.api.methods.ClearAreaContract.Direction;
import net.magicterra.worlddriver.api.methods.ClearAreaContract.Tunnel;
import net.magicterra.worlddriver.api.methods.ClearAreaContract.WidthBias;
import org.junit.jupiter.api.Test;

class ClearAreaGeometryTest {

    private static final float SOUTH = 0f, WEST = 90f, NORTH = 180f, EAST = 270f;

    private static Tunnel tunnel(Direction d, int distance, int width, int height) {
        return new Tunnel(d, distance, width, height, Optional.empty());
    }

    private static ClearAreaGeometry.Cells cells(long x0, long y0, long z0, long x1, long y1, long z1) {
        return new ClearAreaGeometry.Cells(x0, y0, z0, x1, y1, z1);
    }

    @Test
    void theContractsExampleFacingSouth() {
        // A builder at (10,64,20) facing south, 12 forward, 1 wide, 2 high.
        assertEquals(cells(10, 64, 21, 10, 65, 32),
                ClearAreaGeometry.tunnel(10, 64, 20, SOUTH, tunnel(Direction.FORWARD, 12, 1, 2), WidthBias.LEFT));
    }

    @Test
    void relativeDirectionsTurnWithTheBody() {
        assertEquals(Direction.EAST, ClearAreaGeometry.compass(Direction.LEFT, SOUTH));
        assertEquals(Direction.WEST, ClearAreaGeometry.compass(Direction.RIGHT, SOUTH));
        assertEquals(Direction.NORTH, ClearAreaGeometry.compass(Direction.BACK, SOUTH));
        assertEquals(Direction.NORTH, ClearAreaGeometry.compass(Direction.FORWARD, NORTH + 30));
        assertEquals(Direction.EAST, ClearAreaGeometry.compass(Direction.FORWARD, -90));
        assertEquals(Direction.UP, ClearAreaGeometry.compass(Direction.UP, WEST));
    }

    @Test
    void anEvenWidthPutsItsExtraColumnOnTheBiasSide() {
        // Facing south, the body's left is east (+X).
        assertEquals(cells(0, 0, 1, 1, 1, 3),
                ClearAreaGeometry.tunnel(0, 0, 0, SOUTH, tunnel(Direction.SOUTH, 3, 2, 2), WidthBias.LEFT));
        assertEquals(cells(-1, 0, 1, 0, 1, 3),
                ClearAreaGeometry.tunnel(0, 0, 0, SOUTH, tunnel(Direction.SOUTH, 3, 2, 2), WidthBias.RIGHT));
        // Facing east, the body's left is north (-Z).
        assertEquals(cells(1, 0, -1, 3, 0, 0),
                ClearAreaGeometry.tunnel(0, 0, 0, EAST, tunnel(Direction.EAST, 3, 2, 1), WidthBias.LEFT));
    }

    @Test
    void aVerticalTunnelIsCentredOnTheColumnAndNeverTakesTheBodysCell() {
        // width along X, height along Z, extra cells towards +X and +Z; Y from the next cell down.
        assertEquals(cells(0, 60, 0, 1, 63, 1),
                ClearAreaGeometry.tunnel(0, 64, 0, SOUTH, tunnel(Direction.DOWN, 4, 2, 2), WidthBias.LEFT));
        assertEquals(cells(-1, 65, -1, 1, 65, 1),
                ClearAreaGeometry.tunnel(0, 64, 0, SOUTH, tunnel(Direction.UP, 1, 3, 3), WidthBias.LEFT));
    }

    @Test
    void aBoxPastTheCellCapIsRejectedBeforeAnyProductOverflows() {
        assertNull(ClearAreaGeometry.box(Integer.MIN_VALUE, 0, 0, Integer.MAX_VALUE, 0, 0, 4096));
        assertNull(ClearAreaGeometry.box(0, 0, 0, 16, 16, 16, 4096));
        assertEquals(4096, ClearAreaGeometry.box(0, 0, 0, 15, 15, 15, 4096).volume());
    }

    @Test
    void negativeCoordinatesWork() {
        assertEquals(cells(-6, -60, -10, -6, -59, -8),
                ClearAreaGeometry.tunnel(-6, -60, -7, NORTH, tunnel(Direction.FORWARD, 3, 1, 2), WidthBias.LEFT));
    }
}
