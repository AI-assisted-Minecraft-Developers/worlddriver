package net.magicterra.worlddriver.bot.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.body.Body;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.magicterra.worlddriver.bot.process.BotProcess;
import org.junit.jupiter.api.Test;

/**
 * The movement keybinds are the human's too, so clearing them is only right when the bot pressed
 * them. A process that ends on the very tick it first runs (nothing to backfill, no bed in range)
 * never drove the body, and releasing on its ending dropped whatever the human was holding; so did
 * the idle handover that follows it.
 */
class UserTaskChainKeyReleaseTest {

    /** Finishes on its {@code finishOnTick}-th tick. */
    private static final class Finisher implements BotProcess {
        final int finishOnTick;
        int ticks;
        Finisher(int finishOnTick) { this.finishOnTick = finishOnTick; }
        @Override public String kind() { return "builder"; }
        @Override public void attach(BotState st) { st.builder.active = true; }
        @Override public boolean tick(Body a, WorldView w, BotState st) { return ++ticks >= finishOnTick; }
    }

    private final BotState st = new BotState();
    private int keyReleases;
    private final UserTaskChain chain = new UserTaskChain(st, () -> keyReleases++);

    @Test
    void aProcessThatEndsOnItsFirstTickReleasesNothing() {
        ProcessScheduler sched = new ProcessScheduler();
        sched.register(chain);
        chain.setProcess(new Finisher(1));
        sched.tick(null, null, st);   // runs and ends on its first tick
        sched.tick(null, null, st);   // the chain now bids 0: handover to idle
        assertEquals(0, keyReleases, "the process never took the keys, so neither its ending nor the handover clears them");
    }

    @Test
    void aProcessThatDroveReleasesOnceWhenItEnds() {
        ProcessScheduler sched = new ProcessScheduler();
        sched.register(chain);
        chain.setProcess(new Finisher(3));
        for (int t = 0; t < 4; t++) sched.tick(null, null, st);
        assertEquals(1, keyReleases);
    }

    @Test
    void aPreemptedProcessStillReleases() {
        chain.setProcess(new Finisher(10));
        chain.tick(null, null, st);
        chain.onInterrupt(null);
        assertEquals(1, keyReleases, "a held process that is interrupted mid-run did take the keys");
    }

    @Test
    void cancelStillReleases() {
        chain.setProcess(new Finisher(10));
        chain.tick(null, null, st);
        chain.cancel("user-cancel");
        assertEquals(1, keyReleases);
    }
}
