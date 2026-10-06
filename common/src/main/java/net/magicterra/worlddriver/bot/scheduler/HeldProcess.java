package net.magicterra.worlddriver.bot.scheduler;

import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.process.BotProcess;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The single process a server-side player runs, and the status slots it switched on: the part of
 * {@link UserTaskChain} a driver without a scheduler needs, so that both release exactly the slots
 * a process claimed (see {@link SlotClaim} for why a lookup by kind cannot). {@code SlotClaim} itself
 * stays package-private: a claim kept past its process's ending would switch off a slot that a later
 * process has claimed since, and only this class and the chain decide when a process ends.
 *
 * <p>Like {@link UserTaskChain}, it is driven from one thread, the one that ticks the player, and it
 * writes the ending of the accepted action the held process carries out, if it carries one.
 */
public final class HeldProcess {
    private final BotState state;
    private volatile BotProcess process;
    private volatile Ticket action;
    private SlotClaim claim = SlotClaim.NONE;

    public HeldProcess(BotState state) {
        this.state = state;
    }

    /** The held process, or null. */
    public BotProcess process() { return process; }

    /** The action the held process carries out, or null. */
    public Ticket action() { return action; }

    /** Attach and hold {@code next}, cancelling whatever was held before. */
    public void start(BotProcess next) {
        start(next, null);
    }

    /**
     * Attach and hold {@code next} for {@code nextAction} (null: none), ending the held action as
     * superseded by it, or as cancelled when {@code next} carries no action.
     */
    public void start(BotProcess next, Ticket nextAction) {
        stop("superseded", nextAction != null ? h -> h.supersede(nextAction.id())
                : h -> h.cancelled(Ticket.Stop.REQUESTED));
        claim = SlotClaim.attach(next, state);
        action = nextAction;
        process = next;
    }

    /**
     * Stop the held process: it hears {@code onCancelled}, and the slots it switched on keep
     * {@code reason} as their error and go inactive. Returns the process, or null when none was held.
     */
    public BotProcess cancel(String reason) {
        return stop(reason, h -> h.cancelled(Ticket.Stop.REQUESTED));
    }

    /** {@link #cancel} ending the held action with {@code ending} rather than as requested. */
    public BotProcess cancel(String reason, Consumer<Ticket> ending) {
        return stop(reason, ending);
    }

    /** Stop the held process if it is still {@code which}'s; false when that action has left. */
    public boolean cancelAction(Ticket which, String reason) {
        if (which == null || !which.equals(action)) return false;
        stop(reason, h -> {});
        return true;
    }

    private BotProcess stop(String reason, Consumer<Ticket> ending) {
        BotProcess prev = process;
        if (prev == null) return null;
        Ticket a = action;
        process = null;
        action = null;
        prev.onCancelled(reason);
        release(reason);
        end = new End(prev.kind(), reason);
        if (a != null) ending.accept(a);
        return prev;
    }

    /** The held process reported itself finished: its slots go inactive, keeping what it wrote, and
     *  its own verdict becomes the last ending. */
    public void finished() {
        BotProcess prev = process;
        Ticket a = action;
        process = null;
        action = null;
        release(null);
        if (prev == null) return;
        String failure = prev.failure();
        end = new End(prev.kind(), failure);
        if (a != null) {
            if (failure == null) a.succeed();
            else a.fail(failure);
        }
    }

    /** The held process ticked and runs on: its action succeeds if it says it {@linkplain BotProcess#achieved achieved} its goal. */
    public void ranOn() {
        Ticket a = action;
        BotProcess p = process;
        if (a != null && p != null && a.running() && p.achieved()) a.succeed();
    }

    /** The held process threw out of its tick: it is dropped with the error as its ending. */
    public void crashed(Throwable t) {
        String err = t.getClass().getSimpleName() + ": " + t.getMessage();
        if (process == null) return;
        Ticket a = action;
        try {
            stop(err, h -> h.fail(err));
        } catch (RuntimeException finalizeFailed) {
            // onCancelled threw as well; the action still has to end.
            process = null;
            action = null;
            if (a != null) a.fail(err);
        }
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
