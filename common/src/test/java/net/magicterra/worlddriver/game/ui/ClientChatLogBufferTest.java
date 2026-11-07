package net.magicterra.worlddriver.game.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The chat ring behind {@code mc.client.chat} ({@link ClientChatLog.Buffer}): {@code since()} oldest first,
 * {@code tail()} newest first and bounded by {@code sinceSeq}, {@code page()} one read, eviction drops the oldest
 * without reusing a seq. Runs on a private buffer, never the live global log.
 */
class ClientChatLogBufferTest {

    @Test
    void aLineSetAgainWhileShownIsKeptOnce() {
        ClientChatLog.Buffer log = new ClientChatLog.Buffer();
        assertTrue(log.recordUnlessShown("action_bar", "hud", 100L, 60L), "a first line is kept");
        assertTrue(!log.recordUnlessShown("action_bar", "hud", 159L, 60L), "the same line while shown is not");
        assertTrue(log.recordUnlessShown("action_bar", "other", 159L, 60L), "a different line is kept");
        assertTrue(log.recordUnlessShown("action_bar", "hud", 160L, 60L), "the old line after a different one is");
        assertTrue(log.recordUnlessShown("action_bar", "hud", 220L, 60L), "the same line after it faded is kept");
        assertEquals(4, log.since(0).size(), "only the kept lines take a seq");
        log.clear();
        assertTrue(log.recordUnlessShown("action_bar", "hud", 221L, 60L), "a cleared log has no line to repeat");
    }

    @Test
    void ordersBoundsAndEvictsWithoutReusingSeq() {
        ClientChatLog.Buffer log = new ClientChatLog.Buffer();
        long base = log.nextSeq();

        log.record("system", "feedback-A", 100L, false);
        log.record("player", "<bob> hi", 101L, false);
        log.record("system", "feedback-B", 102L, false);
        log.record(null, "kindless", 103L, true);

        assertEquals(base + 4, log.nextSeq(), "nextSeq must advance by exactly one per record");
        var all = log.since(base);
        assertEquals(4, all.size(), "since(base) returns every recorded line");
        assertTrue(
                "feedback-A".equals(all.get(0).text())
                        && "feedback-B".equals(all.get(2).text()),
                "since() must be oldest-first (stable chronological order)");
        assertTrue(
                all.get(0).seq() == base && all.get(2).seq() == base + 2, "seq values are assigned in arrival order");
        assertTrue(
                "player".equals(all.get(1).kind()) && "system".equals(all.get(0).kind()), "kind survives round-trip");
        assertEquals(
                "system", all.get(3).kind(), "null kind normalizes to system — the drain's row encode must never NPE");
        assertTrue(all.get(3).self() && !all.get(1).self(), "self flag survives round-trip");
        assertTrue(log.since(base + 4).isEmpty(), "since(nextSeq) is empty — no phantom lines");

        var t2 = log.tail(2, base);
        assertTrue(
                t2.size() == 2
                        && "kindless".equals(t2.get(0).text())
                        && "feedback-B".equals(t2.get(1).text()),
                "tail(cap) returns the newest cap entries, newest first");
        assertEquals(1, log.tail(10, base + 3).size(), "tail() respects the sinceSeq lower bound");
        assertTrue(log.tail(10, base + 4).isEmpty(), "tail(nextSeq) is empty — no phantom lines");

        for (int i = 0; i < 600; i++) {
            log.record("system", "spam-" + i, 200L, false);
        }
        var tail = log.since(0);
        assertEquals(512, tail.size(), "buffer caps at 512");
        assertEquals("spam-599", tail.get(tail.size() - 1).text(), "newest line survives eviction");
        assertTrue(tail.get(0).seq() > base, "oldest entries evicted, seq never reused");
    }

    /**
     * A page's {@code nextSeq} is the one after its newest line, however fast lines arrive: read apart, a line kept
     * between the two reads was either skipped by a poller or read by it twice.
     */
    @Test
    void aPageReadsItsLinesAndNextSeqTogether() throws InterruptedException {
        ClientChatLog.Buffer log = new ClientChatLog.Buffer();
        log.record("system", "first", 0L, false);
        Thread writer = Thread.ofPlatform().start(() -> {
            for (int i = 0; i < 200_000; i++) {
                log.record("action_bar", "line-" + i, i, false);
            }
        });
        try {
            do { // at least once, should the writer be done before this thread runs
                ClientChatLog.Page page = log.page(8, 0);
                assertEquals(
                        page.nextSeq() - 1,
                        page.lines().getFirst().seq(),
                        "the newest line is the one just before nextSeq");
            } while (writer.isAlive());
        } finally {
            writer.join();
        }
    }
}
