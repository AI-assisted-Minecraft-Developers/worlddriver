package net.magicterra.worlddriver.api.registry;

import java.util.Objects;
import net.magicterra.worlddriver.api.schema.MethodSpec;

/** A {@link MethodSpec} bound to its handler: the one registry entry the dispatcher reads both from. */
public record ApiMethod<P, R>(MethodSpec<P, R> spec, ApiHandler<P, R> handler) {
    public ApiMethod {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(handler, "handler");
    }

    public String name() {
        return spec.name();
    }
}
