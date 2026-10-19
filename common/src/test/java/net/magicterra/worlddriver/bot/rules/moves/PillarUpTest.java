package net.magicterra.worlddriver.bot.rules.moves;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.WorldConfig;
import net.magicterra.worlddriver.debug.GridWorldView;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A rung's rising head sweeps the cells beside its ceiling when the body stands off-centre, so a
 * breakable block there — a leaf of a canopy — is listed to break before the jump; a block with a
 * wall under it on that side is not, because the body cannot lean toward a wall.
 */
class PillarUpTest {
    private boolean allowBreakBefore;

    @BeforeEach
    void keep() { allowBreakBefore = WorldConfig.allowBreak; WorldConfig.allowBreak = true; }

    @AfterEach
    void restore() { WorldConfig.allowBreak = allowBreakBefore; }

    /** {@code grid} with every solid cell breakable, as a leaf is to a bot with break on. */
    private record Breakable(GridWorldView grid) implements WorldView {
        @Override public boolean isSolid(BlockPos p) { return grid.isSolid(p); }
        @Override public boolean isPassable(BlockPos p) { return grid.isPassable(p); }
        @Override public boolean isHazard(BlockPos p) { return grid.isHazard(p); }
        @Override public boolean isWater(BlockPos p) { return grid.isWater(p); }
        @Override public boolean isClimbable(BlockPos p) { return grid.isClimbable(p); }
        @Override public boolean canPlace() { return grid.canPlace(); }
        @Override public int placeableBlockCount() { return grid.placeableBlockCount(); }
        @Override public double breakCost(BlockPos p) { return grid.isSolid(p) ? 10 : 0; }
    }

    /** A floor at y=0 and two leaves stacked at x=4 at the heights of the second rung's ceiling and the one above. */
    private static GridWorldView canopy() {
        GridWorldView g = new GridWorldView(0, 0, 0, 11, 10, 11);
        g.fill(0, 0, 0, 10, 0, 10, GridWorldView.SOLID);
        g.set(4, 4, 5, GridWorldView.SOLID);
        g.set(4, 5, 5, GridWorldView.SOLID);
        return g;
    }

    @Test
    void aLeafBesideTheCeilingIsListedToBreak() {
        // The second rung: feet at y=2 over the first placed block, ceiling at y=4 beside the leaf.
        Move.Edge rung = new PillarUp().eval(new Breakable(canopy()), new BlockPos(5, 2, 5));
        assertNotNull(rung);
        assertTrue(rung.toBreak.contains(new BlockPos(4, 4, 5)), "toBreak=" + rung.toBreak);
    }

    @Test
    void aBlockAboveAWallBesideTheFeetIsNot() {
        GridWorldView g = canopy();
        g.set(4, 2, 5, GridWorldView.SOLID);    // a wall at the feet on the leaf's side
        Move.Edge rung = new PillarUp().eval(new Breakable(g), new BlockPos(5, 2, 5));
        assertNotNull(rung);
        assertFalse(rung.toBreak.contains(new BlockPos(4, 4, 5)), "toBreak=" + rung.toBreak);
    }
}
