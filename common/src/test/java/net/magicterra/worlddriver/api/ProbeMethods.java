package net.magicterra.worlddriver.api;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import net.magicterra.worlddriver.api.contract.ApiField;
import net.magicterra.worlddriver.api.contract.ApiTypes;
import net.magicterra.worlddriver.api.contract.MethodSpec;
import net.magicterra.worlddriver.api.contract.RecordType;
import net.magicterra.worlddriver.api.registry.ApiBatch;
import net.magicterra.worlddriver.api.registry.ApiHandler;
import net.magicterra.worlddriver.api.registry.ApiHandlers;
import net.magicterra.worlddriver.api.registry.ApiMethod;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.application.DriverApplication;

/**
 * Two methods a JVM test can call without a world: {@code probe.echo}, a pollable read answering
 * {@code {value}}, and {@code probe.slow}, a request that holds its thread for {@code ms} the way a
 * slow read on a game thread would.
 */
public final class ProbeMethods {
    private ProbeMethods() {}

    public record Echo(int value) {}

    public record Slow(int ms) {}

    private static final RecordType<Echo> ECHO_TYPE = ApiTypes.record("ProbeEcho",
            ApiField.field("value", ApiTypes.integer(0, 1_000_000)).defaulted(1).forGetter(Echo::value),
            Echo::new);

    public static final MethodSpec<Echo, Echo> ECHO = MethodSpec.of("probe.echo", ECHO_TYPE, ECHO_TYPE,
            "Answers its own value.").asPollable();

    public static final MethodSpec<Slow, Echo> SLOW = MethodSpec.of("probe.slow",
            ApiTypes.record("ProbeSlow",
                    ApiField.field("ms", ApiTypes.integer(0, 60_000)).required().forGetter(Slow::ms),
                    Slow::new),
            ECHO_TYPE, "Holds the calling thread for ms, then answers {value: ms}.");

    public record None() {}

    private static final RecordType<None> NONE = ApiTypes.record("ProbeNone", None::new);

    /** Publish {@code name} on {@code api}: no parameters, and every call fails with what {@code failure} makes. */
    public static void failing(ApiRegistry api, String name, Supplier<? extends RuntimeException> failure) {
        publish(api, name, ApiHandlers.immediate((None p) -> { throw failure.get(); }));
    }

    /** Publish {@code name} on {@code api}: no parameters; each call counts into {@code calls} and answers the count. */
    public static void counting(ApiRegistry api, String name, AtomicInteger calls) {
        publish(api, name, ApiHandlers.immediate((None p) -> new Echo(calls.incrementAndGet())));
    }

    /** Publish {@code name} on {@code api} as its own batch: no parameters, answers {@code {value: 1}}. */
    public static ApiBatch publish(ApiRegistry api, String name, boolean hidden) {
        return publish(api, name, hidden, ApiHandlers.immediate((None p) -> new Echo(1)));
    }

    private static void publish(ApiRegistry api, String name, ApiHandler<None, Echo> handler) {
        publish(api, name, false, handler);
    }

    private static ApiBatch publish(ApiRegistry api, String name, boolean hidden, ApiHandler<None, Echo> handler) {
        MethodSpec<None, Echo> spec = MethodSpec.of(name, NONE, ECHO_TYPE, "A test probe.");
        ApiBatch batch = new ApiBatch("probe", name, List.of(new ApiMethod<>(hidden ? spec.hidden() : spec, handler)));
        api.registerBatch(batch);
        return batch;
    }

    /** {@code app} with both probes registered. */
    public static DriverApplication install(DriverApplication app) {
        install(app.registry());
        return app;
    }

    /** {@code api} with both probes registered. */
    public static ApiRegistry install(ApiRegistry api) {
        api.registerBatch(new ApiBatch("probe", "probe", List.of(
                new ApiMethod<>(ECHO, ApiHandlers.immediate(p -> p)),
                new ApiMethod<>(SLOW, ApiHandlers.immediate(p -> {
                    try {
                        Thread.sleep(p.ms());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return new Echo(p.ms());
                })))));
        return api;
    }
}
