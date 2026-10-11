package net.magicterra.worlddriver.api.methods.event;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.registry.Spec;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes;

/** The event stream: where it stands, a page of it, and the watchers and emits of {@code mc.events}. */
public interface EventApi {

    /** A caller that goes through {@code registry}, checked as a request is. */
    static EventStubApi of(ApiRegistry registry) { return EventApiBinding.calls(registry); }

    @Spec(value = EventContracts.class, spec = "CURSOR")
    CompletionStage<EventContracts.Cursor> cursor(CommonTypes.NoParams p, CallContext ctx);

    @Spec(value = EventContracts.class, spec = "EVENTS_SINCE")
    CompletionStage<EventContracts.Page> eventsSince(EventContracts.SinceParams p, CallContext ctx);

    @Spec(value = EventContracts.class, spec = "EVENTS")
    CompletionStage<EventContracts.EventsReply> events(EventContracts.EventsOp op, CallContext ctx);
}
