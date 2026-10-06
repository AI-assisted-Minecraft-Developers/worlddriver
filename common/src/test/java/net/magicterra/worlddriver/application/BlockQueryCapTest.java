package net.magicterra.worlddriver.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.ExecutionException;

import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.contract.ApiError;
import net.magicterra.worlddriver.api.contract.ApiException;
import org.junit.jupiter.api.Test;

/**
 * {@code mc.query q=blocks} scans a whole {@code (2r+1)^3} cube in one server-thread task and
 * builds a row per non-air cell, so it gets the same cell budget as {@code mc.action.fill} and
 * {@code mc.world.snapshot}. The check is the contract's, so it runs before the server is needed,
 * which is what lets a detached {@link DriverApplication} tell a refused radius from an accepted one.
 */
class BlockQueryCapTest {

    private static ApiError query(int radius) {
        try {
            new DriverApplication().dispatch("mc.query", Map.of("q", "blocks", "filter", Map.of("in_radius", radius)),
                    CallContext.standalone()).toCompletableFuture().get();
        } catch (ExecutionException e) {
            return ((ApiException) e.getCause()).error();
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("a detached application answered a block query");
    }

    @Test
    void aRadiusPastTheCellBudgetIsRefusedAndNamesTheLimit() {
        ApiError e = query(BlockQuery.MAX_RADIUS + 1);
        assertEquals(ApiError.Code.INVALID_PARAMS, e.code(), String.valueOf(e));
        assertEquals("/filter/in_radius", e.issues().get(0).path());
        assertTrue(e.issues().get(0).message().contains(String.valueOf(BlockQuery.MAX_RADIUS)), String.valueOf(e));

        assertEquals(ApiError.Code.INVALID_PARAMS, query(64).code(), "the old silent clamp at 64");
    }

    @Test
    void theLargestAllowedRadiusFitsTheBudgetAndPassesValidation() {
        long side = 2L * BlockQuery.MAX_RADIUS + 1;
        assertTrue(side * side * side <= WorldApi.MAX_VOLUME);
        long over = side + 2;
        assertTrue(over * over * over > WorldApi.MAX_VOLUME, "MAX_RADIUS is the largest radius that fits");
        // Accepted: what stops it here is only the missing server.
        ApiError e = query(BlockQuery.MAX_RADIUS);
        assertEquals(ApiError.Code.CAPABILITY_UNAVAILABLE, e.code(), String.valueOf(e));
        assertTrue(e.message().contains("not attached"), e.message());
    }
}
