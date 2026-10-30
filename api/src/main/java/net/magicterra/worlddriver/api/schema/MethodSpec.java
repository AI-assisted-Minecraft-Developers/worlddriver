package net.magicterra.worlddriver.api.schema;

import com.google.gson.JsonObject;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Everything about one method that is not its implementation: name, description, parameter and result types, and how it
 * behaves beside other calls. Pure data, so the artifact generator can load every core spec without starting Minecraft.
 * Immutable; the {@code with…} methods return copies.
 *
 * @param <P> the decoded parameters
 * @param <R> the result
 */
public final class MethodSpec<P, R> {

    private final String name;

    private final String description;

    private final ApiType<P> input;

    private final ApiType<R> output;

    private final boolean listed;

    private final Set<Effect> effects;

    private final ExecutionClass executionClass;

    private final Blocking blocking;

    private final boolean pollable;

    private final ImageHint image;

    /** What a call may touch. A method lists every effect any of its variants can have. */
    public enum Effect {
        SNAPSHOT_READ,
        BODY_TASK,
        CLIENT_INPUT,
        INVENTORY_WRITE,
        CONFIG_WRITE,
        WORLD_WRITE,
        SCRIPT_RUN,
        WAIT
    }

    /** Which execution budget a call draws from, independent of the transport it came in on. */
    public enum ExecutionClass {
        SHORT,
        BULK,
        WAIT,
        SCRIPT
    }

    /**
     * Whether a call may hold its caller while something else makes progress: such a call is refused on a game thread
     * before it starts, because that thread is what it would be waiting for.
     */
    public enum Blocking {
        NEVER,
        MAY_WAIT
    }

    /** Where an image's bytes and format sit in the encoded result, so MCP can send a native image block. */
    public record ImageHint(String dataField, String formatField) {}

    private MethodSpec(
            String name,
            String description,
            ApiType<P> input,
            ApiType<R> output,
            boolean listed,
            Set<Effect> effects,
            ExecutionClass executionClass,
            Blocking blocking,
            boolean pollable,
            ImageHint image) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = Objects.requireNonNull(description, "description");
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        this.listed = listed;
        this.effects = Set.copyOf(effects);
        this.executionClass = Objects.requireNonNull(executionClass);
        this.blocking = Objects.requireNonNull(blocking);
        this.pollable = pollable;
        this.image = image;
        if (pollable && !this.effects.equals(Set.of(Effect.SNAPSHOT_READ))) {
            throw new IllegalArgumentException(
                    name + ": only a method whose sole effect is a snapshot read may be pollable");
        }
        if (pollable && blocking != Blocking.NEVER) {
            throw new IllegalArgumentException(name + ": a pollable method cannot wait");
        }
        if (!name.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) {
            throw new IllegalArgumentException("method name '" + name + "' is not dot-separated identifiers");
        }
    }

    /** A listed method with a short, side-effect-free default profile; adjust with the {@code with…} methods. */
    public static <P, R> MethodSpec<P, R> of(String name, ApiType<P> input, ApiType<R> output, String description) {
        return new MethodSpec<>(
                name,
                description,
                input,
                output,
                true,
                EnumSet.of(Effect.SNAPSHOT_READ),
                ExecutionClass.SHORT,
                Blocking.NEVER,
                false,
                null);
    }

    /** As {@link #of(String, ApiType, ApiType, String)}, both types read off their classes ({@link ApiTypes#of}). */
    public static <P, R> MethodSpec<P, R> of(String name, Class<P> input, Class<R> output, String description) {
        return of(name, ApiTypes.of(input), ApiTypes.of(output), description);
    }

    /** As {@link #of(String, ApiType, ApiType, String)}, the parameters read off their class. */
    public static <P, R> MethodSpec<P, R> of(String name, Class<P> input, ApiType<R> output, String description) {
        return of(name, ApiTypes.of(input), output, description);
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public ApiType<P> input() {
        return input;
    }

    public ApiType<R> output() {
        return output;
    }

    /** Whether MCP {@code tools/list} shows it. Hidden is not unreachable: every entry point can still call it. */
    public boolean listedInMcp() {
        return listed;
    }

    public Set<Effect> effects() {
        return effects;
    }

    public ExecutionClass executionClass() {
        return executionClass;
    }

    public Blocking blocking() {
        return blocking;
    }

    /** Whether {@code mc.wait.condition} and event watchers may call it repeatedly. */
    public boolean pollable() {
        return pollable;
    }

    public ImageHint image() {
        return image;
    }

    /** No effect beyond reading a snapshot. */
    public boolean readOnly() {
        return effects.equals(Set.of(Effect.SNAPSHOT_READ));
    }

    public MethodSpec<P, R> hidden() {
        return new MethodSpec<>(
                name, description, input, output, false, effects, executionClass, blocking, pollable, image);
    }

    public MethodSpec<P, R> effects(Effect first, Effect... rest) {
        return new MethodSpec<>(
                name,
                description,
                input,
                output,
                listed,
                EnumSet.of(first, rest),
                executionClass,
                blocking,
                pollable,
                image);
    }

    public MethodSpec<P, R> execution(ExecutionClass c) {
        return new MethodSpec<>(name, description, input, output, listed, effects, c, blocking, pollable, image);
    }

    public MethodSpec<P, R> mayWait() {
        return new MethodSpec<>(
                name, description, input, output, listed, effects, executionClass, Blocking.MAY_WAIT, pollable, image);
    }

    /** Mark it safe to call repeatedly from a wait condition or watcher. */
    public MethodSpec<P, R> asPollable() {
        return new MethodSpec<>(
                name, description, input, output, listed, effects, executionClass, blocking, true, image);
    }

    public MethodSpec<P, R> image(String dataField, String formatField) {
        return new MethodSpec<>(
                name,
                description,
                input,
                output,
                listed,
                effects,
                executionClass,
                blocking,
                pollable,
                new ImageHint(dataField, formatField));
    }

    /** The MCP annotations: hints for a client, derived from the effects rather than declared twice. */
    public JsonObject mcpAnnotations() {
        JsonObject a = new JsonObject();
        boolean ro = readOnly();
        a.addProperty("readOnlyHint", ro);
        a.addProperty(
                "destructiveHint",
                !ro
                        && (effects.contains(Effect.WORLD_WRITE)
                                || effects.contains(Effect.BODY_TASK)
                                || effects.contains(Effect.INVENTORY_WRITE)));
        a.addProperty("idempotentHint", ro);
        return a;
    }

    @Override
    public String toString() {
        return "MethodSpec[" + name + "]";
    }
}
