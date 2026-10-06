package net.magicterra.worlddriver.api;

import java.util.List;

import net.magicterra.worlddriver.api.contract.ApiField;
import net.magicterra.worlddriver.api.contract.ApiTypes;
import net.magicterra.worlddriver.api.contract.MethodSpec;
import net.magicterra.worlddriver.api.contract.RecordType;

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

    /** {@code api} with both probes registered. */
    public static <T extends DriverApi> T install(T api) {
        api.registry().registerBatch(new ApiBatch("probe", "probe", List.of(
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
