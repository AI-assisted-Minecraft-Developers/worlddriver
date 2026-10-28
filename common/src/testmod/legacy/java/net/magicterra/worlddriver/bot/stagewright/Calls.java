package net.magicterra.worlddriver.bot.stagewright;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import net.magicterra.stagewright.contract.SceneFailure;
import net.magicterra.worlddriver.api.game.WorldDriver;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.schema.ApiException;

/**
 * Calls a scene makes on the server thread and reads at once. A call that names a server bot, a debug reading
 * or a {@code mc.test.*} method runs inline on the server thread, so its answer is there when the call
 * returns; {@link #now} reads it and fails the scene if it is not. self's calls hop to the client thread and
 * never answer within the call: a scene waits for those with {@code SceneCalls.ask}.
 */
public final class Calls {
    private Calls() {}

    /** The registry every call goes through, as a request's does. */
    public static ApiRegistry api() {
        ApiRegistry registry = WorldDriver.registry();
        if (registry == null) throw new SceneFailure("the driver is not up: no registry to call through");
        return registry;
    }

    /** The registry that serves {@code bot}: self's is the client's ({@link ClientRegistry#self}), every other bot's is this JVM's. */
    public static ApiRegistry api(String bot) {
        return ClientHelm.SELF.equals(bot) ? ClientRegistry.self() : api();
    }

    /** The answer of a call that answers within itself; its failure, rethrown as the scene's. */
    public static <R> R now(CompletionStage<R> stage) {
        CompletableFuture<R> f = stage.toCompletableFuture();
        if (!f.isDone()) throw new SceneFailure("a call that should answer within itself did not: was it made off the server thread?");
        try {
            return ApiRegistry.await(f, 0);
        } catch (ApiException e) {
            throw new SceneFailure("the call failed: " + e.error());
        }
    }

    /**
     * The answer of a call to the joined client, waited for on this thread: a few milliseconds on loopback. Only
     * for staging, such as pinning the client's settings; a scene waits for a walk with {@code SceneCalls.ask}.
     */
    public static <R> R waitFor(CompletionStage<R> stage) {
        try {
            return ApiRegistry.await(stage, 5_000);
        } catch (ApiException e) {
            throw new SceneFailure("the client's driver did not answer: " + e.error());
        }
    }

    /** The action a verb started; a refusal fails the scene with the refusal's own words. */
    public static <R> Responses.ActionSnapshot<R> started(String what, Responses.ActionResponse<R> response) {
        return switch (response) {
            case Responses.Accepted<R> a -> a.action();
            case Responses.Refused<R> r -> throw new SceneFailure(what + " was refused: " + r.reason().wireName() + ": " + r.message());
            default -> throw new SceneFailure(what + " answered " + response + ", not an action");
        };
    }
}
