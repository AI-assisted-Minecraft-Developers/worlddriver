package net.magicterra.worlddriver.game.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The chat ring behind {@code mc.client.chat} ({@link ClientChatLog.Buffer}): {@code since()} oldest first,
 * {@code tail()} newest first and bounded by {@code sinceSeq}, eviction drops the oldest without reusing a seq. Runs on
 * a private buffer, never the live global log.
 */
class ClientChatLogBufferTest {

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
                all.get(0).text().equals("feedback-A") && all.get(2).text().equals("feedback-B"),
                "since() must be oldest-first (stable chronological order)");
        assertTrue(
                all.get(0).seq() == base && all.get(2).seq() == base + 2, "seq values are assigned in arrival order");
        assertTrue(
                all.get(1).kind().equals("player") && all.get(0).kind().equals("system"), "kind survives round-trip");
        assertEquals(
                "system", all.get(3).kind(), "null kind normalizes to system — the drain's row encode must never NPE");
        assertTrue(all.get(3).self() && !all.get(1).self(), "self flag survives round-trip");
        assertTrue(log.since(base + 4).isEmpty(), "since(nextSeq) is empty — no phantom lines");

        var t2 = log.tail(2, base);
        assertTrue(
                t2.size() == 2
                        && t2.get(0).text().equals("kindless")
                        && t2.get(1).text().equals("feedback-B"),
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
}
