package net.magicterra.worlddriver.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link BotState#processSlots()} is written out by hand, and a slot claim only sees the slots it
 * lists: a slot added to {@link BotState} but not to that list is one a process can switch on and
 * no ending will ever switch off. The field list is the truth; this reads it.
 */
class BotStateProcessSlotsTest {

    /** Names of the {@link BotState.ProcessSlot} fields whose instance is absent from {@code listed}. */
    private static List<String> unlisted(BotState st, BotState.ProcessSlot[] listed) throws IllegalAccessException {
        List<String> missing = new ArrayList<>();
        for (Field f : BotState.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType() != BotState.ProcessSlot.class) continue;
            Object slot = f.get(st);
            boolean found = false;
            for (BotState.ProcessSlot s : listed) found |= s == slot;
            if (!found) missing.add(f.getName());
        }
        return missing;
    }

    @Test
    void everyProcessSlotFieldIsListed() throws IllegalAccessException {
        BotState st = new BotState();
        assertEquals(List.of(), unlisted(st, st.processSlots()),
                "BotState.processSlots() must list every ProcessSlot field, or a slot claim cannot release it");
    }

    /** The check must be able to fail: drop one slot from the list and it has to name that field. */
    @Test
    void aSlotLeftOffTheListIsNamed() throws IllegalAccessException {
        BotState st = new BotState();
        List<BotState.ProcessSlot> shortList = new ArrayList<>(List.of(st.processSlots()));
        assertTrue(shortList.remove(st.bunker));
        assertEquals(List.of("bunker"), unlisted(st, shortList.toArray(new BotState.ProcessSlot[0])));
    }
}
