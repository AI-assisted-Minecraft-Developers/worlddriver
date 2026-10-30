package net.magicterra.worlddriver.bot.stagewright.kit;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.Map;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;

/**
 * Reading the answers of worlddriver's methods in a test. Typed calls go through the generated blocking callers —
 * {@code game.api(BotTaskBlockingApi::of).goTo(…)}, {@code client.api(ControlBlockingApi::of).status(…)} — which answer
 * on the calling thread and throw an {@link ApiException} when the call fails. This adds what they leave to the test:
 * the action a verb started ({@link #started}), the error a call failed with ({@link #errorOf}), and a call by method
 * name for the tests whose subject is the request itself ({@link #byName}).
 */
public final class Calls {
    private Calls() {}

    /** The action a verb started; a refusal fails the test with the refusal's own words. */
    public static <R> Responses.ActionSnapshot<R> started(String what, Responses.ActionResponse<R> response) {
        return switch (response) {
            case Responses.Accepted<R> a -> a.action();
            case Responses.Refused<R> r ->
                fail(what + " was refused: " + r.reason().wireName() + ": " + r.message());
            default -> fail(what + " answered " + response + ", not an action");
        };
    }

    /** The error {@code call} failed with, or null when it answered. */
    public static ApiError errorOf(Runnable call) {
        try {
            call.run();
            return null;
        } catch (RuntimeException e) {
            ApiException a = ApiException.find(e);
            if (a == null) {
                throw e;
            }
            return a.error();
        }
    }

    /**
     * {@code method} called by name through {@code registry} and waited for, its result as plain Java values (maps,
     * lists, strings, booleans, numbers with every integer a Long); a failed call throws its {@link ApiException}.
     * {@code client.registry()} reaches the real client's methods, {@code game.registry()} the server's.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> byName(ApiRegistry registry, String method, Map<String, ?> params) {
        Object reply = SceneJson.plain(ApiRegistry.await(
                registry.dispatch(method, SceneJson.params(params), CallContext.standalone()),
                CallContext.SERVER_CAP_MS));
        return reply instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of("value", reply);
    }
}
