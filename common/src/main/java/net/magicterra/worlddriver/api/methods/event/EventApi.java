package net.magicterra.worlddriver.api.methods.event;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.api.Spec;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes;

/** The event stream: where it stands, a page of it, and the watchers and emits of {@code mc.events}. */
public interface EventApi {

    /** A caller that goes through {@code api}'s registry, checked as a request is. */
    static EventApi of(DriverApi api) { return EventApiBinding.calls(api); }

    @Spec(value = EventContracts.class, spec = "CURSOR")
    CompletionStage<EventContracts.Cursor> cursor(CommonTypes.NoParams p, CallContext ctx);

    @Spec(value = EventContracts.class, spec = "EVENTS_SINCE")
    CompletionStage<EventContracts.Page> eventsSince(EventContracts.SinceParams p, CallContext ctx);

    @Spec(value = EventContracts.class, spec = "EVENTS")
    CompletionStage<EventContracts.EventsReply> events(EventContracts.EventsOp op, CallContext ctx);
}
