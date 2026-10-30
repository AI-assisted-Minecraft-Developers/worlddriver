package net.magicterra.worlddriver.api.methods.shared;

import java.util.List;
import java.util.Optional;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.RecordType;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.OnlyTrue;

/**
 * The WebSocket's two connection-control calls. They set which events THIS socket is pushed, so the transport answers
 * them itself and they are not business methods; their shapes are still contracts.
 */
public final class EventStreamContracts {

    public static final String SUBSCRIBE = "mc.events.subscribe";
    public static final String UNSUBSCRIBE = "mc.events.unsubscribe";

    public static final RecordType<SubscribeParams> SUBSCRIBE_PARAMS = ApiTypes.record(SubscribeParams.class);
    public static final ApiType<Subscription> SUBSCRIPTION = ApiTypes.record(Subscription.class);
    public static final ApiType<Unsubscribed> UNSUBSCRIBED = ApiTypes.record(Unsubscribed.class);

    private EventStreamContracts() {}

    /** {@code types} omitted or empty: every type. Event types are open-world, so any non-blank name is allowed. */
    @Named("SubscribeParams")
    public record SubscribeParams(
            @Doc("Event types to receive; omit or [] for every type.")
            Optional<@Length(max = 256) List<@Length(min = 1, max = 128) String>> types) {}

    @Named("Subscription")
    public record Subscription(
            @OnlyTrue boolean subscribed,
            @Length(max = 256) List<@Length(min = 1, max = 128) String> types,

            @Doc("True when types is empty: every type is pushed, not none.")
            boolean allTypes) {}

    @Named("Unsubscribed")
    public record Unsubscribed(boolean subscribed) {}
}
