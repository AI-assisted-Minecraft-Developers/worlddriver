package net.magicterra.worlddriver.bot;

import net.magicterra.worlddriver.api.action.ActionHandle;
import net.magicterra.worlddriver.api.methods.shared.Responses.CancelReason;
import net.magicterra.worlddriver.bot.scheduler.Ticket;

/** The scheduler's {@link Ticket} for an API {@link ActionHandle}: the one place the two meet. */
public final class ActionTickets {
    private ActionTickets() {}

    /** {@code handle}'s ticket, or null for none; tickets for the same handle are equal. */
    public static Ticket of(ActionHandle handle) {
        return handle == null ? null : new Of(handle);
    }

    public static CancelReason reason(Ticket.Stop why) {
        return switch (why) {
            case REQUESTED -> CancelReason.REQUESTED;
            case WORLD_CLOSED -> CancelReason.WORLD_CLOSED;
            case BODY_REMOVED -> CancelReason.BODY_REMOVED;
            case TEST_RESET -> CancelReason.TEST_RESET;
            case CONNECTION_CLOSED -> CancelReason.CONNECTION_CLOSED;
        };
    }

    private record Of(ActionHandle handle) implements Ticket {
        @Override public String id() { return handle.id(); }
        @Override public boolean running() { return handle.running(); }
        @Override public boolean succeed() { return handle.succeed(); }
        @Override public boolean succeed(Object result) { return handle.succeed(result); }
        @Override public boolean fail(String message) { return handle.fail(message); }
        @Override public boolean cancelled(Stop why) { return handle.cancelled(reason(why)); }
        @Override public boolean supersede(String replacementId) { return handle.supersede(replacementId); }
    }
}
