package net.magicterra.worlddriver.bot.behavior.reflex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.magicterra.worlddriver.bot.behavior.BotState;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.bot.arbitration.Chain;
import net.magicterra.worlddriver.bot.arbitration.Priorities;
import net.magicterra.worlddriver.bot.arbitration.ProcessScheduler;
import org.junit.jupiter.api.Test;

/**
 * The hotbar picker stands aside whenever something drives the bot, not only when the user task
 * is empty: the bunker reflex picks its own tool and seal block, and dusk shelter and retreat drive
 * processes that do, all while the user slot is empty.
 */
class AutoToolGateTest {

    private static Chain bidding(String name, float bid) {
        return new Chain() {
            @Override public String name() { return name; }
            @Override public float priority(Body body, WorldView w, BotState st) { return bid; }
            @Override public void tick(Body body, WorldView w, BotState st) { }
        };
    }

    @Test
    void aReflexChainDrivingWithTheUserSlotEmptyHoldsThePickerOff() {
        ProcessScheduler sched = new ProcessScheduler();
        sched.register(bidding("bunker", Priorities.BUNKER));
        sched.tick(null, null, new BotState());
        assertFalse(AutoTool.mayRun(null, sched), "the bunker reflex selects its own hotbar slots");
    }

    @Test
    void anIdleChannelLetsThePickerRun() {
        ProcessScheduler sched = new ProcessScheduler();
        sched.register(bidding("bunker", 0f));
        sched.tick(null, null, new BotState());
        assertTrue(AutoTool.mayRun(null, sched));
    }
}
