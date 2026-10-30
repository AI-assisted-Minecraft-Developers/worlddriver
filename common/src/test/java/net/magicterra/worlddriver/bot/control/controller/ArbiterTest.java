package net.magicterra.worlddriver.bot.control.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.bot.control.ResetScope;
import net.magicterra.worlddriver.bot.control.TickCtx;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.game.body.Body;
import org.junit.jupiter.api.Test;

/** The arbiter's rules, on controllers that only record what they were asked to do. */
class ArbiterTest {

    private static final class Fake implements Controller {
        final String name;
        final Set<Channel> channels;
        final int priority;
        final int maxTicks;
        int wanted;
        int ticked;
        boolean letGo;
        final List<String> stops = new ArrayList<>();

        Fake(String name, int priority, int maxTicks, Channel... channels) {
            this.name = name;
            this.priority = priority;
            this.maxTicks = maxTicks;
            this.channels = EnumSet.of(channels[0], channels);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Set<Channel> channels() {
            return channels;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public int maxTicks() {
            return maxTicks;
        }

        @Override
        public String whyNot(Navigator nav, TickCtx cx, WorldView world) {
            return wanted > 0 ? null : "not wanted";
        }

        @Override
        public boolean canContinue(Navigator nav, TickCtx cx, WorldView world) {
            return wanted > 0;
        }

        @Override
        public boolean tick(Navigator nav, TickCtx cx, Body a, WorldView world) {
            if (letGo) {
                return false;
            }
            ticked++;
            wanted--;
            return true;
        }

        @Override
        public void stop(String why) {
            stops.add(why);
            wanted = 0;
        }

        final List<ResetScope> resets = new ArrayList<>();

        @Override
        public void reset(ResetScope scope) {
            resets.add(scope);
        }
    }

    private static Set<Channel> tick(Arbiter arb) {
        return arb.tick(null, null, null, null);
    }

    @Test
    void aControllerRunsWhileItCanAndLeavesItsChannelsAfter() {
        Fake f = new Fake("f", 2, 10, Channel.MOVE, Channel.JUMP);
        Arbiter arb = new Arbiter(List.of(f));
        assertTrue(tick(arb).isEmpty());
        f.wanted = 2;
        assertEquals(EnumSet.of(Channel.MOVE, Channel.JUMP), tick(arb));
        assertEquals(EnumSet.of(Channel.MOVE, Channel.JUMP), tick(arb));
        assertTrue(tick(arb).isEmpty());
        assertEquals(2, f.ticked);
        assertEquals(List.of("cannot continue"), f.stops);
    }

    @Test
    void aRunIsCutAtItsLongest() {
        Fake f = new Fake("f", 2, 3, Channel.MOVE);
        Arbiter arb = new Arbiter(List.of(f));
        f.wanted = 100;
        for (int i = 0; i < 5; i++) {
            tick(arb);
        }
        assertEquals(3, f.ticked);
        assertEquals(List.of("ran its 3 ticks"), f.stops);
    }

    @Test
    void aHigherPriorityTakesASharedChannelAndALowerOneWaits() {
        Fake low = new Fake("low", 5, 50, Channel.LOOK, Channel.HANDS);
        Fake high = new Fake("high", 1, 50, Channel.MOVE, Channel.LOOK);
        Arbiter arb = new Arbiter(List.of(low, high));
        low.wanted = 10;
        tick(arb);
        high.wanted = 2;
        assertEquals(EnumSet.of(Channel.MOVE, Channel.LOOK), tick(arb));
        assertEquals(List.of("preempted by high"), low.stops);
        assertTrue(arb.snapshot().startsWith("MOVE=high LOOK=high JUMP=- HANDS=-"), arb.snapshot());

        low.wanted = 10;
        tick(arb);
        assertTrue(arb.snapshot().contains("low idle (channel held by high)"), arb.snapshot());
    }

    @Test
    void aControllerThatLetsGoLeavesItsChannelsToTheNextOneThisTick() {
        Fake high = new Fake("high", 1, 50, Channel.MOVE, Channel.LOOK);
        Fake low = new Fake("low", 5, 50, Channel.MOVE);
        Arbiter arb = new Arbiter(List.of(high, low));
        high.wanted = 5;
        low.wanted = 5;
        assertEquals(EnumSet.of(Channel.MOVE, Channel.LOOK), tick(arb));
        assertEquals(0, low.ticked);

        high.letGo = true;
        assertEquals(EnumSet.of(Channel.MOVE), tick(arb));
        assertEquals(List.of("let go"), high.stops);
        assertEquals(1, low.ticked);
    }

    @Test
    void controllersOnDifferentChannelsRunSideBySide() {
        Fake a = new Fake("a", 1, 50, Channel.MOVE);
        Fake b = new Fake("b", 2, 50, Channel.HANDS);
        Arbiter arb = new Arbiter(List.of(a, b));
        a.wanted = 5;
        b.wanted = 5;
        assertEquals(EnumSet.of(Channel.MOVE, Channel.HANDS), tick(arb));
        assertEquals(1, a.ticked);
        assertEquals(1, b.ticked);
    }

    @Test
    void aNewGoalStopsEveryRunnerAndEveryControllerForgets() {
        Fake f = new Fake("f", 2, 50, Channel.MOVE);
        Fake idle = new Fake("idle", 3, 50, Channel.HANDS);
        Arbiter arb = new Arbiter(List.of(f, idle));
        f.wanted = 5;
        tick(arb);
        arb.reset(ResetScope.GOAL, "new goal");
        assertEquals(List.of("new goal"), f.stops);
        assertEquals(List.of(), idle.stops);
        assertEquals(List.of(ResetScope.GOAL), f.resets);
        assertEquals(List.of(ResetScope.GOAL), idle.resets);
        assertTrue(tick(arb).isEmpty());
    }

    @Test
    void aReplanLeavesARunnerRunning() {
        Fake f = new Fake("f", 2, 50, Channel.MOVE);
        Arbiter arb = new Arbiter(List.of(f));
        f.wanted = 5;
        tick(arb);
        arb.reset(ResetScope.REPLAN, "replan");
        assertEquals(List.of(), f.stops);
        assertEquals(List.of(ResetScope.REPLAN), f.resets);
        assertEquals(EnumSet.of(Channel.MOVE), tick(arb));
    }
}
