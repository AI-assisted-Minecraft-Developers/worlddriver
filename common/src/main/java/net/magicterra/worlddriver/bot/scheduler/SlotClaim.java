package net.magicterra.worlddriver.bot.scheduler;

import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.process.BotProcess;

import java.util.ArrayList;
import java.util.List;

/**
 * The status slots a process switched on in its {@link BotProcess#attach}, read off the slots
 * themselves rather than looked up from {@link BotProcess#kind()}.
 *
 * <p>A kind does not name a slot: {@code sleep} and {@code replay} report into the goto slot, five
 * {@code builder} kinds share one, and {@code RunAwayProcess} writes a different slot depending on who
 * started it. A lookup by kind therefore misses exactly the processes that borrow a slot, and a missed
 * slot stays {@code active} with nothing behind it. Only an off-to-on flip during attach counts: a slot
 * already on belongs to another owner (one owner per slot), and is that owner's to switch off.
 */
final class SlotClaim {
    static final SlotClaim NONE = new SlotClaim(List.of());

    private final List<BotState.ProcessSlot> slots;

    private SlotClaim(List<BotState.ProcessSlot> slots) {
        this.slots = slots;
    }

    /** Attach {@code process} and record every slot its attach switched on. */
    static SlotClaim attach(BotProcess process, BotState st) {
        BotState.ProcessSlot[] all = st.processSlots();
        boolean[] wasOn = new boolean[all.length];
        for (int i = 0; i < all.length; i++) wasOn[i] = all[i].active;
        process.attach(st);
        List<BotState.ProcessSlot> claimed = new ArrayList<>();
        for (int i = 0; i < all.length; i++) {
            if (!wasOn[i] && all[i].active) claimed.add(all[i]);
        }
        return claimed.isEmpty() ? NONE : new SlotClaim(List.copyOf(claimed));
    }

    /**
     * The process ended: switch its slots off. {@code error} is stamped when non-null, and a null
     * leaves whatever the process wrote itself, because a natural ending is the process's to describe.
     */
    void release(String error) {
        for (BotState.ProcessSlot s : slots) {
            if (error != null) s.lastError = error;
            if (s.active) s.reset();
        }
    }
}
