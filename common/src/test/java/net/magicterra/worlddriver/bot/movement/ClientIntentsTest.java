package net.magicterra.worlddriver.bot.movement;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract between the bot's drives and {@code MinecraftMixin}: how many vanilla attack
 * passes one drive keeps at bay, and that a released hold ends it at once. Pure state, no game.
 */
class ClientIntentsTest {

    private static final BlockPos CELL = new BlockPos(1, 2, 3);

    @BeforeEach
    void clean() {
        ClientIntents.holdDig(false);
        ClientIntents.holdUse(false);
    }

    @Test
    void oneDriveBuysExactlyTheBudgetedPasses() {
        ClientIntents.assertDig(CELL);
        for (int i = 0; i < ClientIntents.STAND_ASIDE_PASSES; i++)
            assertTrue(ClientIntents.standAside(), "pass " + i + " after a drive must stand aside");
        assertFalse(ClientIntents.standAside(),
                "a bot that stopped driving must hand the tick back after the budgeted passes — "
                + "otherwise a human's held click is ignored for as long as the latch leaks");
        assertEquals(CELL, ClientIntents.digPos());
    }

    @Test
    void reassertingEveryTickNeverHandsTheTickBack() {
        for (int tick = 0; tick < 50; tick++) {
            ClientIntents.assertDig(CELL);
            assertTrue(ClientIntents.standAside(), "tick " + tick);
        }
    }

    @Test
    void oneSkippedDriveDoesNotCostTheBreak() {
        ClientIntents.assertDig(CELL);
        assertTrue(ClientIntents.standAside());   // the pass after the drive
        assertTrue(ClientIntents.standAside());   // the pass after a tick the driver skipped
        ClientIntents.assertDig(CELL);            // the driver is back
        assertTrue(ClientIntents.standAside());
    }

    @Test
    void releasingTheHoldEndsTheStandAsideAtOnce() {
        ClientIntents.holdDig(true);
        ClientIntents.assertDig(CELL);
        ClientIntents.holdDig(false);
        assertFalse(ClientIntents.standAside(),
                "breakHold(false) must let vanilla's very next pass abort the break, as a "
                + "released attack key did");
        assertFalse(ClientIntents.digHeld());
        assertNull(ClientIntents.digPos());
    }

    @Test
    void theDigLatchIsBookkeepingOnly() {
        ClientIntents.holdDig(true);
        assertTrue(ClientIntents.digHeld());
        assertFalse(ClientIntents.standAside(),
                "the latch alone gates nothing — a leaked latch must not block the human's click");
    }

    @Test
    void theUseLatchIsAPlainLatch() {
        assertFalse(ClientIntents.useHeld());
        ClientIntents.holdUse(true);
        assertTrue(ClientIntents.useHeld());
        assertTrue(ClientIntents.useHeld(), "reading does not consume it — vanilla reads it twice a tick");
        ClientIntents.holdUse(false);
        assertFalse(ClientIntents.useHeld());
    }
}
