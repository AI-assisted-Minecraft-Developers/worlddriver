package net.magicterra.worlddriver.bot.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.process.BunkerProcess;
import net.magicterra.worlddriver.bot.world.WorldModel;
import org.junit.jupiter.api.Test;

/**
 * Dusk shelter at an unsafe site: the bunker process bails with {@code unsafe-site} on its first
 * tick without changing anything, the bot is still exposed at night, and the chain's debounce had
 * already run out, so it bid 90 again on the next tick and restarted the process every tick all
 * night, above the user task. A failed verdict must take the chain out of the bid.
 */
class DuskSecureChainBailTest {

    private final BotState st = new BotState();
    private final ProcessScheduler sched = new ProcessScheduler();
    private final DuskSecureChain dusk = new DuskSecureChain(st, new WorldModel());

    DuskSecureChainBailTest() {
        sched.register(dusk);
    }

    @Test
    void aFailedShelterTakesTheChainOutOfTheBid() {
        dusk.adoptProcessForTest(new BunkerProcess(2));
        st.bunker.endReason = "unsafe-site";
        st.bunker.goalReached = false;

        dusk.processEnded();

        ProcessScheduler.Bail b = sched.bailOf(dusk);
        assertNotNull(b, "a shelter that failed must not be retried on the next tick");
        assertTrue(b.reason().contains("unsafe-site"), b.reason());
        assertEquals(DuskSecureChain.BAIL_COOLDOWN_TICKS, b.ticksLeft());
        assertNull(dusk.heldProcessForTest());
        assertEquals(0, dusk.idleTicksForTest(), "the debounce starts over once the cooldown ends");
    }

    @Test
    void aShelterThatEnclosedTheBotIsNotABail() {
        dusk.adoptProcessForTest(new BunkerProcess(2));
        st.bunker.endReason = "DONE";
        st.bunker.goalReached = true;

        dusk.processEnded();

        assertNull(sched.bailOf(dusk));
    }
}
