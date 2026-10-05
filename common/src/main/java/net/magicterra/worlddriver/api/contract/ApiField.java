package net.magicterra.worlddriver.api.contract;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * One member of a record type: its wire name, its type, whether it may be missing and what that
 * means, and the getter that binds it to the record component. Built with {@link #field}; the
 * getter's type has to match what the presence makes of the value, so a field bound to the wrong
 * component does not compile.
 *
 * @param <O> the record
 * @param <F> the record component's type: the value type, or {@code Optional} of it for {@link Spec#optional}
 */
public final class ApiField<O, F> {

    /** How a missing key is handled. A present {@code null} is an error for all three. */
    public enum Presence { REQUIRED, DEFAULTED, OPTIONAL }

    final String name;
    final ApiType<?> type;
    final Presence presence;
    final Object defaultValue;
    final Function<O, F> getter;
    final String description;

    private ApiField(String name, ApiType<?> type, Presence presence, Object defaultValue,
                     Function<O, F> getter, String description) {
        this.name = name;
        this.type = type;
        this.presence = presence;
        this.defaultValue = defaultValue;
        this.getter = getter;
        this.description = description;
    }

    public String name() { return name; }
    public ApiType<?> type() { return type; }
    public Presence presence() { return presence; }
    public String description() { return description; }

    /** Start a field named {@code name} holding a {@code V}. */
    public static <V> Spec<V> field(String name, ApiType<V> type) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("field name must not be empty");
        return new Spec<>(name, Objects.requireNonNull(type, "type"), null);
    }

    /** A field before its presence is chosen. */
    public static final class Spec<V> {
        private final String name;
        private final ApiType<V> type;
        private final String description;

        private Spec(String name, ApiType<V> type, String description) {
            this.name = name;
            this.type = type;
            this.description = description;
        }

        public Spec<V> describe(String text) { return new Spec<>(name, type, text); }

        /** Missing is an error. */
        public Bound<V> required() { return new Bound<>(name, type, Presence.REQUIRED, null, description); }

        /**
         * Missing means {@code value}. The default has to satisfy the type itself, so a bad default is
         * caught when the type is defined rather than when someone omits the field.
         */
        public Bound<V> defaulted(V value) {
            Objects.requireNonNull(value, "default");
            try {
                type.encode(value);
            } catch (IllegalStateException e) {
                throw new IllegalArgumentException("default of field " + name + " violates its type: " + e.getMessage(), e);
            }
            return new Bound<>(name, type, Presence.DEFAULTED, value, description);
        }

        /** Missing is {@code Optional.empty()}, decided later by whoever reads the record. */
        public Bound<Optional<V>> optional() {
            return new Bound<>(name, type, Presence.OPTIONAL, null, description);
        }
    }

    /** A field with its presence chosen; {@link #forGetter} binds it to a record component. */
    public static final class Bound<F> {
        private final String name;
        private final ApiType<?> type;
        private final Presence presence;
        private final Object defaultValue;
        private final String description;

        private Bound(String name, ApiType<?> type, Presence presence, Object defaultValue, String description) {
            this.name = name;
            this.type = type;
            this.presence = presence;
            this.defaultValue = defaultValue;
            this.description = description;
        }

        public Bound<F> describe(String text) { return new Bound<>(name, type, presence, defaultValue, text); }

        public <O> ApiField<O, F> forGetter(Function<O, F> getter) {
            return new ApiField<>(name, type, presence, defaultValue, Objects.requireNonNull(getter, "getter"), description);
        }
    }
}
