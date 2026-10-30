package net.magicterra.worlddriver.api.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import net.magicterra.worlddriver.api.methods.shared.ActionResults;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.methods.shared.Responses.ActionResponse;
import net.magicterra.worlddriver.api.methods.shared.Responses.RefusalReason;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.minecraft.world.entity.LivingEntity;

/**
 * Body tasks other mods add: a method that starts a {@link TaskStep} on a bot and answers as every {@code mc.bot.*}
 * task does, accepted with an action, or refused. Process-wide and static, like {@link EntityDrivers}, so a mod
 * registers during its own setup, before the driver exists; the driver publishes each one as a method once it does.
 */
public final class BodyTasks {
    private BodyTasks() {}

    /** What a task's parameter record must have: the bot it runs on and how long the call waits for it to end. */
    public interface Params {
        /** A bot ref; declare it with {@link Responses#BOT_REF}, defaulted to {@code self}. */
        String bot();
        /** Declare it with {@link Responses#AWAIT_MS}, defaulted to 0. */
        int awaitMs();
    }

    /** Builds the task for one call, on the body's game thread; throws {@link Refusal} to start nothing. */
    @FunctionalInterface
    public interface Factory<P, R> {
        TaskStep<R> start(P params, LivingEntity body);
    }

    /** A valid request this body cannot take now; the call answers {@code refused} with nothing started. */
    public static final class Refusal extends RuntimeException {
        private final RefusalReason reason;

        public Refusal(RefusalReason reason, String message) {
            super(message);
            this.reason = Objects.requireNonNull(reason);
        }

        public RefusalReason reason() {
            return reason;
        }
    }

    /** One registered task: its method and what starts it. */
    public record Entry<P extends Params, R>(MethodSpec<P, ActionResponse<R>> spec, Factory<P, R> factory) {}

    private static final Map<String, Entry<?, ?>> TASKS = new LinkedHashMap<>();
    private static final List<Consumer<Entry<?, ?>>> LISTENERS = new ArrayList<>();

    /**
     * Register the method {@code name} ({@code <modid>.<verb>}, never {@code mc.}). Its answer is the usual
     * accepted-or-refused union around {@code result}, whose variant tags join every other action result's kinds and
     * must not repeat one. A result is told apart by its class, so each variant needs a class no other action result
     * uses. Returns the method's spec, for calling it through the registry.
     *
     * @throws IllegalArgumentException when the name, a result kind or a result class is already taken
     */
    public static synchronized <P extends Params, R> MethodSpec<P, ActionResponse<R>> register(
            String name, ApiType<P> params, UnionType<R> result, String description, Factory<P, R> factory) {
        Objects.requireNonNull(factory, "factory");
        int dot = name.indexOf('.');
        if (dot <= 0 || dot == name.length() - 1 || name.startsWith("mc.")) {
            throw new IllegalArgumentException(name + ": a registered task is named <modid>.<verb>, outside mc.");
        }
        if (TASKS.containsKey(name))
            throw new IllegalArgumentException("a body task named " + name + " is already registered");
        MethodSpec<P, ActionResponse<R>> spec = MethodSpec.of(
                        name, params, Responses.response(typeName(name), result), description)
                .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.WORLD_WRITE)
                .mayWait();
        ActionResults.add(result);
        Entry<P, R> entry = new Entry<>(spec, factory);
        TASKS.put(name, entry);
        for (Consumer<Entry<?, ?>> l : LISTENERS) l.accept(entry);
        return spec;
    }

    /** {@code mymod.dance} → {@code MymodDance}, the prefix of the answer's type names. */
    private static String typeName(String name) {
        StringBuilder out = new StringBuilder();
        for (String part : name.split("[^A-Za-z0-9]+")) {
            if (!part.isEmpty())
                out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }

    /** Every task registered so far, in registration order. */
    public static synchronized List<Entry<?, ?>> all() {
        return List.copyOf(TASKS.values());
    }

    /** Told of every task registered so far, then of each one as it is registered. */
    public static synchronized void listen(Consumer<Entry<?, ?>> listener) {
        for (Entry<?, ?> e : TASKS.values()) listener.accept(e);
        LISTENERS.add(listener);
    }
}
