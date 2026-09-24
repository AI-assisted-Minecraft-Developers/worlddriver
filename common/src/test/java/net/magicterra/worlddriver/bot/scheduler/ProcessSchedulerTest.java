package net.magicterra.worlddriver.bot.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.body.Body;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import org.junit.jupiter.api.Test;

/**
 * The bail contract: a chain that gives up sits out the bid for the cooldown it asked for, so the
 * next chain down the ladder gets control of the bot instead of the same chain re-bidding its band
 * next tick. The scheduler is ticked with no {@code Body}, as the headless matrix scenes do; the fake
 * chains read none.
 */
class ProcessSchedulerTest {

    /** A chain that bids a fixed band and records what the scheduler did with it. */
    private static final class FakeChain implements Chain {
        final String name;
        final float bid;
        ProcessScheduler scheduler;
        int priorityCalls;
        int ticks;
        final List<String> interruptedBy = new ArrayList<>();
        /** When set, the chain gives up on the tick it runs, for this many ticks. */
        int bailFor = -1;

        FakeChain(String name, float bid) {
            this.name = name;
            this.bid = bid;
        }

        @Override public String name() { return name; }

        @Override public float priority(Body body, WorldView w, BotState st) {
            priorityCalls++;
            return bid;
        }

        @Override public void tick(Body body, WorldView w, BotState st) {
            ticks++;
            if (bailFor >= 0) {
                scheduler.bail(this, "site unsafe", bailFor);
                bailFor = -1;
            }
        }

        @Override public void onInterrupt(Chain by) { interruptedBy.add(by == null ? "idle" : by.name()); }

        @Override public void registeredWith(ProcessScheduler s) { scheduler = s; }
    }

    private final BotState st = new BotState();

    @Test
    void aBailedChainIsNotReselectedDuringItsCooldownAndTheLowerChainWins() {
        ProcessScheduler sched = new ProcessScheduler();
        FakeChain bunker = new FakeChain("bunker", Priorities.BUNKER);
        FakeChain retreat = new FakeChain("retreat", Priorities.SURVIVAL);
        sched.register(bunker);
        sched.register(retreat);

        bunker.bailFor = 5;
        sched.tick(null, null, st);
        assertEquals("bunker", sched.currentName(), "the higher band wins before it gives up");
        assertEquals(1, bunker.ticks);

        for (int t = 1; t <= 5; t++) {
            sched.tick(null, null, st);
            assertEquals("retreat", sched.currentName(), "cooldown tick " + t + ": the bailed chain must sit out");
            assertEquals(0f, sched.lastPriorities().get("bunker"), "cooldown tick " + t + ": its bid reads 0");
        }
        assertEquals(1, bunker.ticks, "the bailed chain never ran during its cooldown");
        assertEquals(5, retreat.ticks);
        assertEquals(List.of("retreat"), bunker.interruptedBy, "the handover interrupts the chain that bailed");
    }

    @Test
    void theBidComesBackTheTickAfterTheCooldownEnds() {
        ProcessScheduler sched = new ProcessScheduler();
        FakeChain bunker = new FakeChain("bunker", Priorities.BUNKER);
        FakeChain retreat = new FakeChain("retreat", Priorities.SURVIVAL);
        sched.register(bunker);
        sched.register(retreat);

        bunker.bailFor = 3;
        sched.tick(null, null, st);
        for (int t = 0; t < 3; t++) sched.tick(null, null, st);
        assertEquals("retreat", sched.currentName());
        sched.tick(null, null, st);
        assertEquals("bunker", sched.currentName(), "cooldown of 3 ticks: back in the bid on the 4th");
        assertEquals(Priorities.BUNKER, sched.lastPriorities().get("bunker"));
        assertNull(sched.bailOf(bunker), "an expired bail is gone from the status view");
    }

    @Test
    void aChainSittingOutIsNotAskedForItsPriority() {
        ProcessScheduler sched = new ProcessScheduler();
        FakeChain dusk = new FakeChain("duskSecure", Priorities.DUSK_URGENT);
        sched.register(dusk);

        dusk.bailFor = 10;
        sched.tick(null, null, st);
        int before = dusk.priorityCalls;
        for (int t = 0; t < 10; t++) sched.tick(null, null, st);
        assertEquals(before, dusk.priorityCalls,
                "a chain's own debounce must not run on while the scheduler holds it out");
        assertNull(sched.currentName(), "nothing else bids: the channel goes idle");
        assertEquals(List.of("idle"), dusk.interruptedBy);
    }

    @Test
    void theBailIsVisibleWithItsReasonAndRemainingTicks() {
        ProcessScheduler sched = new ProcessScheduler();
        FakeChain bunker = new FakeChain("bunker", Priorities.BUNKER);
        sched.register(bunker);

        bunker.bailFor = 4;
        sched.tick(null, null, st);
        ProcessScheduler.Bail b = sched.bailOf(bunker);
        assertEquals("site unsafe", b.reason());
        assertEquals(4, b.ticksLeft());
        sched.tick(null, null, st);
        assertEquals(3, sched.bailOf(bunker).ticksLeft());
    }

    @Test
    void cancellingEveryEpisodeAlsoLiftsEveryBail() {
        ProcessScheduler sched = new ProcessScheduler();
        FakeChain bunker = new FakeChain("bunker", Priorities.BUNKER);
        sched.register(bunker);

        bunker.bailFor = 100;
        sched.tick(null, null, st);
        sched.tick(null, null, st);
        assertNull(sched.currentName());
        sched.cancelAllEpisodes("player-death");
        assertNull(sched.bailOf(bunker));
        sched.tick(null, null, st);
        assertEquals("bunker", sched.currentName(), "a respawn starts from a clean table");
    }

    @Test
    void aBailDoesNotTouchAnyOtherChain() {
        ProcessScheduler sched = new ProcessScheduler();
        FakeChain bunker = new FakeChain("bunker", Priorities.BUNKER);
        FakeChain user = new FakeChain("user", Priorities.USER);
        sched.register(bunker);
        sched.register(user);

        bunker.bailFor = 2;
        sched.tick(null, null, st);
        sched.tick(null, null, st);
        assertTrue(user.priorityCalls >= 2);
        assertEquals(Priorities.USER, sched.lastPriorities().get("user"));
        assertNull(sched.bailOf(user));
    }
}
