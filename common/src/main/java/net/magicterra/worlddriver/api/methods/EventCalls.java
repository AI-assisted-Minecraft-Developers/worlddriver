package net.magicterra.worlddriver.api.methods;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.Api;
import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.DriverApi;

/** The event stream: where it stands, a page of it, and the watchers and emits of {@code mc.events}. */
public interface EventCalls {

    /** A caller that goes through {@code api}'s registry, checked as a request is. */
    static EventCalls of(DriverApi api) { return EventCallsBinding.calls(api); }

    @Api(value = EventContracts.class, spec = "CURSOR")
    CompletionStage<EventContracts.Cursor> cursor(CommonTypes.NoParams p, CallContext ctx);

    @Api(value = EventContracts.class, spec = "EVENTS_SINCE")
    CompletionStage<EventContracts.Page> eventsSince(EventContracts.SinceParams p, CallContext ctx);

    @Api(value = EventContracts.class, spec = "EVENTS")
    CompletionStage<EventContracts.EventsReply> events(EventContracts.EventsOp op, CallContext ctx);
}
