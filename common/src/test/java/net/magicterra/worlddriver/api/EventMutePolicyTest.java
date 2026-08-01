package net.magicterra.worlddriver.api;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.model.AgentEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code mc.bot.setting{mutedEvents}} is applied once, by {@link AgentApi}, before
 * any listener runs.
 *
 * <p>It used to be applied by each transport instead — {@code RpcServer.onEvent} and
 * {@code McpServer.onEvent} each carried their own copy of the same line. Two
 * transports independently re-implementing one policy is the arrangement where a
 * third is muted only if its author remembers to be, and where the two can drift
 * apart without anything failing. AGENTS.md hard rule #1 already says behavior does
 * not live in a transport handler; this is that rule applied to the push path.
 */
class EventMutePolicyTest {

    /** Emissions are dispatched FIFO on AgentApi's single-thread executor, so once a
     *  later event has been delivered every earlier one has already been processed —
     *  no sleep needed to conclude that a muted event was dropped rather than late. */
    private static List<String> deliveredAfterEmitting(Set<String> muted, String... types)
            throws Exception {
        Set<String> saved = BotConfig.mutedEvents;
        BotConfig.mutedEvents = muted;
        try {
            AgentApi api = new AgentApi();
            List<String> seen = new CopyOnWriteArrayList<>();
            CountDownLatch sentinel = new CountDownLatch(1);
            api.addEventListener((AgentEvent e) -> {
                if ("sentinel".equals(e.type)) sentinel.countDown();
                else seen.add(e.type);
            });
            for (String t : types) api.emitExternal(t, null, "payload");
            api.emitExternal("sentinel", null, "");
            assertTrue(sentinel.await(10, TimeUnit.SECONDS), "dispatch never ran");
            return seen;
        } finally {
            BotConfig.mutedEvents = saved;
        }
    }

    @Test
    void mutedTypesNeverReachAListener() throws Exception {
        assertEquals(List.of("kept"),
                deliveredAfterEmitting(Set.of("noisy"), "noisy", "kept"));
    }

    @Test
    void nothingIsMutedByDefault() throws Exception {
        assertEquals(List.of("a", "b"), deliveredAfterEmitting(Set.of(), "a", "b"));
    }

    @Test
    void mutingSuppressesThePushOnly() throws Exception {
        // The event is appended to the replay ring BEFORE the dispatch that applies the
        // mute, so mc.observe.eventsSince still returns it — muting is an opt-out from
        // the live push, not from history. Reading the ring back needs a running server
        // (ObserveApi.eventsSince calls api.level()), so what is asserted here is that
        // emit still assigns a sequence number to a muted event: if the filter had been
        // hoisted above the append, the event would not exist at all and the seq would
        // not advance.
        Set<String> saved = BotConfig.mutedEvents;
        BotConfig.mutedEvents = Set.of("noisy");
        try {
            AgentApi api = new AgentApi();
            long before = api.eventSeq.get();
            api.emitExternal("noisy", null, "payload");
            assertEquals(before + 1, api.eventSeq.get(),
                    "a muted event must still be recorded and numbered");
        } finally {
            BotConfig.mutedEvents = saved;
        }
    }
}
