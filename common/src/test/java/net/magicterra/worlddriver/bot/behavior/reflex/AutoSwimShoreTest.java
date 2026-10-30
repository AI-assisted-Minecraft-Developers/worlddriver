package net.magicterra.worlddriver.bot.behavior.reflex;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.Map;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * The idle beach step steers a drowning bot at whatever {@link AutoSwim#nearestShore} returns, so a bank whose foot or
 * head cell is a hazard must not be returned: lava does not block motion and is not water, so over stone it passes
 * every other clause of the scan.
 */
class AutoSwimShoreTest {

    /** Cells by a single letter: {@code S} solid, {@code W} water, {@code L} lava (passable hazard); air by default. */
    private static final class CharView implements WorldView {
        final Map<BlockPos, Character> cells = new HashMap<>();

        char at(BlockPos p) {
            return cells.getOrDefault(p, ' ');
        }

        void set(int x, int y, int z, char c) {
            cells.put(new BlockPos(x, y, z), c);
        }

        @Override
        public boolean isSolid(BlockPos p) {
            return at(p) == 'S';
        }

        @Override
        public boolean isPassable(BlockPos p) {
            return at(p) != 'S';
        }

        @Override
        public boolean isHazard(BlockPos p) {
            return at(p) == 'L';
        }

        @Override
        public boolean isWater(BlockPos p) {
            return at(p) == 'W';
        }

        @Override
        public boolean isClimbable(BlockPos p) {
            return false;
        }
    }

    /** The bot at (0,0,0) in a 7x7 pool three deep; a bank one ring east and a plain bank two rings west. */
    private static CharView pool(char eastFoot, char eastHead, boolean westBank) {
        CharView w = new CharView();
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) for (int y = -1; y <= 1; y++) w.set(x, y, z, 'W');
        w.set(1, -1, 0, 'S');
        w.set(1, 0, 0, eastFoot);
        w.set(1, 1, 0, eastHead);
        if (westBank) {
            w.set(-2, -1, 0, 'S');
            w.set(-2, 0, 0, ' ');
            w.set(-2, 1, 0, ' ');
        }
        return w;
    }

    @Test
    void lavaInTheFootCellIsNoShore() {
        assertArrayEquals(new int[] {-2, 0}, AutoSwim.nearestShore(pool('L', ' ', true), 0, 0, 0));
    }

    @Test
    void lavaInTheHeadCellIsNoShore() {
        assertArrayEquals(new int[] {-2, 0}, AutoSwim.nearestShore(pool(' ', 'L', true), 0, 0, 0));
    }

    @Test
    void theSameBankWithoutLavaIsTheNearestShore() {
        assertArrayEquals(new int[] {1, 0}, AutoSwim.nearestShore(pool(' ', ' ', true), 0, 0, 0));
    }

    @Test
    void aPoolWhoseOnlyBankIsLavaHasNoShore() {
        assertNull(AutoSwim.nearestShore(pool('L', ' ', false), 0, 0, 0));
    }
}
