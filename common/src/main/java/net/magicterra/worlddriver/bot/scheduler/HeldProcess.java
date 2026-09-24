package net.magicterra.worlddriver.bot.scheduler;

import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.process.BotProcess;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The single process a server-side player runs, and the status slots it switched on: the part of
 * {@link UserTaskChain} a driver without a scheduler needs, so that both release exactly the slots
 * a process claimed (see {@link SlotClaim} for why a lookup by kind cannot). {@code SlotClaim} itself
 * stays package-private: a claim kept past its process's ending would switch off a slot that a later
 * process has claimed since, and only this class and the chain decide when a process ends.
 *
 * <p>Like {@link UserTaskChain}, it is driven from one thread, the one that ticks the player.
 */
public final class HeldProcess {
    private final BotState state;
    private volatile BotProcess process;
    private SlotClaim claim = SlotClaim.NONE;

    public HeldProcess(BotState state) {
        this.state = state;
    }

    /** The held process, or null. */
    public BotProcess process() { return process; }

    /** Attach and hold {@code next}, cancelling whatever was held before. */
    public void start(BotProcess next) {
        cancel("superseded");
        claim = SlotClaim.attach(next, state);
        process = next;
    }

    /**
     * Stop the held process: it hears {@code onCancelled}, and the slots it switched on keep
     * {@code reason} as their error and go inactive. Returns the process, or null when none was held.
     */
    public BotProcess cancel(String reason) {
        BotProcess prev = process;
        if (prev == null) return null;
        process = null;
        prev.onCancelled(reason);
        release(reason);
        end = new End(prev.kind(), reason);
        return prev;
    }

    /** The held process reported itself finished: its slots go inactive, keeping what it wrote, and
     *  its own verdict becomes the last ending. */
    public void finished() {
        BotProcess prev = process;
        process = null;
        release(null);
        if (prev != null) end = new End(prev.kind(), prev.failure());
    }

    // One immutable value, for the same reason as UserTaskChain's: a status read may run on
    // another thread, and must not pair one ending's kind with another's error.
    private record End(String kind, String error) {}

    private volatile End end;

    /** {@code {kind, error}} of the last ending, the shape of {@code UserTaskChain.lastEnd()}, or
     *  null if nothing has ended. {@code error} is null only when the process did what it was asked. */
    public Map<String, Object> lastEnd() {
        End e = end;
        if (e == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", e.kind());
        m.put("error", e.error());
        return m;
    }

    private void release(String error) {
        claim.release(error);
        claim = SlotClaim.NONE;
    }
}
