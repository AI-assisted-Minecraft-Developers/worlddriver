package net.magicterra.worlddriver.api;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * {@code mc.query q=blocks} scans a whole {@code (2r+1)^3} cube in one server-thread task and
 * builds a row per non-air cell, so it gets the same cell budget as {@code mc.action.fill} and
 * {@code mc.world.snapshot}. The check runs before the server is needed, which is what lets a
 * detached {@link DriverApi} tell a refused radius from an accepted one.
 */
class BlockQueryCapTest {

    private static RuntimeException query(int radius) {
        return assertThrows(RuntimeException.class, () -> new DriverApi().query(QueryParams.from(
                Map.of("q", "blocks", "filter", Map.of("in_radius", radius)))));
    }

    @Test
    void aRadiusPastTheCellBudgetIsRefusedAndNamesTheLimit() {
        RuntimeException e = query(BlockQuery.MAX_RADIUS + 1);
        assertTrue(e instanceof IllegalArgumentException, "expected a refused parameter, got " + e);
        assertTrue(e.getMessage().contains("max " + BlockQuery.MAX_RADIUS), e.getMessage());
        assertTrue(e.getMessage().contains(String.valueOf(WorldApi.MAX_VOLUME)), e.getMessage());

        RuntimeException far = query(64);
        assertTrue(far instanceof IllegalArgumentException, "the old silent clamp at 64 answered " + far);
    }

    @Test
    void theLargestAllowedRadiusFitsTheBudgetAndPassesValidation() {
        long side = 2L * BlockQuery.MAX_RADIUS + 1;
        assertTrue(side * side * side <= WorldApi.MAX_VOLUME);
        long over = side + 2;
        assertTrue(over * over * over > WorldApi.MAX_VOLUME, "MAX_RADIUS is the largest radius that fits");
        // Accepted: what stops it here is only the missing server.
        RuntimeException e = query(BlockQuery.MAX_RADIUS);
        assertTrue(e instanceof IllegalStateException && e.getMessage().contains("not attached"), String.valueOf(e));
    }
}
