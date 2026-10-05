package net.magicterra.worlddriver.api.methods;

import java.util.List;
import java.util.Optional;

import net.magicterra.worlddriver.api.contract.ApiField;
import net.magicterra.worlddriver.api.contract.ApiType;
import net.magicterra.worlddriver.api.contract.ApiTypes;
import net.magicterra.worlddriver.api.contract.RecordType;

/**
 * The WebSocket's two connection-control calls. They set which events THIS socket is pushed, so the
 * transport answers them itself and they are not business methods; their shapes are still contracts.
 */
public final class EventStreamContract {
    private EventStreamContract() {}

    public static final String SUBSCRIBE = "mc.events.subscribe";
    public static final String UNSUBSCRIBE = "mc.events.unsubscribe";

    /** {@code types} omitted or empty: every type. Event types are open-world, so any non-blank name is allowed. */
    public record SubscribeParams(Optional<List<String>> types) {}

    public record Subscription(boolean subscribed, List<String> types, boolean allTypes) {}

    public record Unsubscribed(boolean subscribed) {}

    public record NoParams() {}

    public static final RecordType<SubscribeParams> SUBSCRIBE_PARAMS = ApiTypes.record("SubscribeParams",
            ApiField.field("types", ApiTypes.list(ApiTypes.string(1, 128), 256))
                    .describe("Event types to receive; omit or [] for every type.")
                    .optional().forGetter(SubscribeParams::types),
            SubscribeParams::new);

    public static final ApiType<Subscription> SUBSCRIPTION = ApiTypes.record("Subscription",
            ApiField.field("subscribed", ApiTypes.trueOnly()).required().forGetter(Subscription::subscribed),
            ApiField.field("types", ApiTypes.list(ApiTypes.string(1, 128), 256)).required().forGetter(Subscription::types),
            ApiField.field("allTypes", ApiTypes.bool())
                    .describe("True when types is empty: every type is pushed, not none.")
                    .required().forGetter(Subscription::allTypes),
            Subscription::new);

    public static final RecordType<NoParams> NO_PARAMS = ApiTypes.record("NoParams", NoParams::new);

    public static final ApiType<Unsubscribed> UNSUBSCRIBED = ApiTypes.record("Unsubscribed",
            ApiField.field("subscribed", ApiTypes.bool()).required().forGetter(Unsubscribed::subscribed),
            Unsubscribed::new);
}
