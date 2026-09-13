package net.magicterra.worlddriver.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Which {@code mc.bot.status} slot an {@code awaitMs} call polls: the reply's own {@code slot}
 * when the verb delegated (a goto with {@code route.mode:["fly"]} runs as an elytra flight and
 * answers {@code slot:"elytra"}), else the route table's literal. A blank or missing slot in the
 * reply must not hide the literal, or the await polls a slot that is never active and returns at
 * once as "completed".
 */
class AwaitSlotTest {
    @Test
    void theReplySlotWins() {
        assertEquals("elytra", DriverApi.slotToAwait(Map.of("ok", true, "slot", "elytra"), "mc_goto"));
    }

    @Test
    void theRouteLiteralIsTheFallback() {
        assertEquals("mc_goto", DriverApi.slotToAwait(Map.of("ok", true), "mc_goto"));
        assertEquals("mc_goto", DriverApi.slotToAwait(Map.of("slot", "  "), "mc_goto"));
        assertEquals("mc_goto", DriverApi.slotToAwait(Map.of("slot", 7), "mc_goto"));
        assertEquals("mc_goto", DriverApi.slotToAwait(null, "mc_goto"));
        Map<String, Object> nullSlot = new HashMap<>();
        nullSlot.put("slot", null);
        assertEquals("mc_goto", DriverApi.slotToAwait(nullSlot, "mc_goto"));
    }
}
