package net.magicterra.worlddriver.bot.control.route;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.magicterra.worlddriver.bot.planning.PathFinder;
import net.magicterra.worlddriver.bot.rules.CapabilityProfile;
import net.magicterra.worlddriver.bot.rules.CostModifier;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.bot.rules.SearchProfile;
import net.magicterra.worlddriver.bot.rules.constraints.NoWater;
import net.magicterra.worlddriver.bot.rules.modifiers.ShorelineHug;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.debug.GridWorldView;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The smoother regression where {@code stringPull} straightened a bank-hugging bow across the taxed dry interior. An
 * L-shaped water channel whose straight chord crosses only dry slab: smoothing the hugging route WITHOUT the bias
 * collapses out of the shoreline band (the arena discriminates), smoothing WITH the bias keeps every cell of every
 * straight line in the band. Diagonal string-pulling on, as it ships.
 */
class ShorelineSmootherTest {

    private static final int Y = 20;
    private boolean diagonalBefore;

    @BeforeEach
    void pin() {
        diagonalBefore = ControlConfig.navigatorDiagonalStringPull;
        ControlConfig.navigatorDiagonalStringPull = true;
    }

    @AfterEach
    void restore() {
        ControlConfig.navigatorDiagonalStringPull = diagonalBefore;
    }

    /** A stone slab two thick with walking room above; water one deep along z 5..7 and then x 28..30. */
    private static GridWorldView basin() {
        GridWorldView w = new GridWorldView(-3, Y - 1, -3, 38, 9, 38).withPlace(false);
        w.fill(-2, Y, -2, 32, Y + 1, 32, GridWorldView.SOLID);
        w.fill(0, Y + 1, 5, 30, Y + 1, 7, GridWorldView.WATER);
        w.fill(28, Y + 1, 5, 30, Y + 1, 30, GridWorldView.WATER);
        return w;
    }

    @Test
    void biasAwareSmoothingKeepsTheBankHuggingBow() {
        GridWorldView w = basin();
        BlockPos start = new BlockPos(10, Y + 2, 8); // south bank of the horizontal strip
        BlockPos goal = new BlockPos(27, Y + 2, 25); // west bank of the vertical strip
        List<CostModifier> bias = List.of(new ShorelineHug(30.0));
        SearchProfile hug = new SearchProfile(bias, CapabilityProfile.ALL, List.of(new NoWater()));

        PathFinder.Search s = new PathFinder(w, 200_000, 10_000, hug).newSearch(start, new Goal.Block(goal));
        while (!s.advance(10_000)) {}
        PathFinder.Result hugged = s.result();
        assertTrue(hugged.goalReached(), "the hugging plan did not reach the goal: " + hugged.path());
        assertNull(
                firstOutOfBand(hugged.path(), 2, w),
                "the RAW hugging path already leaves the shoreline band — the L bank route should be optimal: "
                        + hugged.path());

        List<BlockPos> noBias =
                PathSmoothing.stringPull(w, hugged.path(), hugged.edges()).path();
        assertNotNull(
                firstOutOfBand(noBias, 2, w),
                "bias-blind smoothing kept every node in the band: the dry chord no longer tempts the collapse: "
                        + noBias);

        List<BlockPos> withBias =
                PathSmoothing.stringPull(w, hugged.path(), hugged.edges(), bias).path();
        BlockPos out = firstOutOfBand(withBias, 2, w);
        assertNull(out, "bias-aware smoothing collapsed out of the shoreline band at " + out + ": " + withBias);
    }

    /**
     * The first cell farther than {@code rad} (Chebyshev, own Y and one below) from any water, walking the STRAIGHT
     * LINES between consecutive waypoints cell by cell — a collapsed chord's violation lives between the endpoints, not
     * at them. Null when the whole path stays in the band.
     */
    private static BlockPos firstOutOfBand(List<BlockPos> path, int rad, WorldView w) {
        for (int i = 0; i < path.size(); i++) {
            BlockPos a = i == 0 ? path.get(i) : path.get(i - 1), b = path.get(i);
            int steps = Math.max(1, Math.max(Math.abs(b.getX() - a.getX()), Math.abs(b.getZ() - a.getZ())));
            for (int k = 0; k <= steps; k++) {
                double t = (double) k / steps;
                BlockPos c = new BlockPos(
                        (int) Math.round(a.getX() + (b.getX() - a.getX()) * t),
                        (int) Math.round(a.getY() + (b.getY() - a.getY()) * t),
                        (int) Math.round(a.getZ() + (b.getZ() - a.getZ()) * t));
                if (!nearWater(c, rad, w)) return c;
            }
        }
        return null;
    }

    private static boolean nearWater(BlockPos p, int rad, WorldView w) {
        for (int dx = -rad; dx <= rad; dx++)
            for (int dz = -rad; dz <= rad; dz++)
                for (int dy = 0; dy >= -1; dy--) if (w.isWater(p.offset(dx, dy, dz))) return true;
        return false;
    }
}
