package net.magicterra.worlddriver.bot.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.magicterra.worlddriver.bot.movement.ClientIntents;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code autoBackfill} puts back only what the bot itself broke, as Baritone's BackfillProcess
 * does. A cell the body merely walked through was natural air — a cave, a cliff edge, the tunnel
 * a human dug — and filling it plugs the bot's own path with cobblestone.
 */
class BackfillOwnBreaksTest {

    private final BackfillTracker tracker = new BackfillTracker();
    private final BlockPos stone = new BlockPos(3, 64, 0);

    @BeforeEach
    void noBreaksLeftOverFromAnotherTest() {
        ClientIntents.takeOwnBreaks();
    }

    /** One client tick with the switch on. The recorder is not told where the body is. */
    private void tick() {
        tracker.onClientTick(true, ClientIntents.takeOwnBreaks());
    }

    @Test
    void walkingThroughAirRecordsNothing() {
        for (int x = 0; x < 8; x++) tick();
        assertEquals(0, tracker.size(), "every cell the body passed was air it did not open");
    }

    @Test
    void aBreakTheBotsDriveCompletedIsRecorded() {
        ClientIntents.noteDrive(stone, false, true);
        tick();
        assertEquals(List.of(stone), tracker.snapshot());
    }

    @Test
    void aDriveOnACellThatWasAlreadyAirRecordsNothing() {
        ClientIntents.noteDrive(stone, true, true);
        tick();
        assertEquals(0, tracker.size());
    }

    @Test
    void aDriveThatHasNotFinishedTheBreakRecordsNothing() {
        ClientIntents.noteDrive(stone, false, false);
        tick();
        assertEquals(0, tracker.size());
    }

    @Test
    void aCellSomeoneElseBrokeRecordsNothing() {
        // A human's left click runs vanilla's own attack pass and never a bot drive, so the cell
        // simply turns up air; a bot drive that reaches it afterwards finds it already open.
        tick();
        ClientIntents.noteDrive(stone, true, true);
        tick();
        assertEquals(0, tracker.size());
    }

    @Test
    void aBreakWithTheSwitchOffIsNotKeptForLater() {
        ClientIntents.noteDrive(stone, false, true);
        tracker.onClientTick(false, ClientIntents.takeOwnBreaks());
        tick();
        assertEquals(0, tracker.size(), "the switch was off when the block broke");
    }

    @Test
    void eachBreakIsHandedOverOnce() {
        ClientIntents.noteDrive(stone, false, true);
        assertEquals(List.of(stone), ClientIntents.takeOwnBreaks());
        assertTrue(ClientIntents.takeOwnBreaks().isEmpty());
    }
}
