package net.magicterra.worlddriver.api.schema;

import java.util.function.Function;

/**
 * One member of a record type, read off a record component: its wire name, its type, whether it may be missing and what
 * that means, and the getter that reads it from the record.
 *
 * @param <O> the record
 * @param <F> the record component's type: the value type, or {@code Optional} of it for an optional member
 */
public final class ApiField<O, F> {

    /** How a missing key is handled. A present {@code null} is an error for all three. */
    public enum Presence {
        REQUIRED,
        DEFAULTED,
        OPTIONAL
    }

    final String name;
    final ApiType<?> type;
    final Presence presence;
    final Object defaultValue;
    final Function<O, F> getter;
    final String description;

    private ApiField(
            String name,
            ApiType<?> type,
            Presence presence,
            Object defaultValue,
            Function<O, F> getter,
            String description) {
        this.name = name;
        this.type = type;
        this.presence = presence;
        this.defaultValue = defaultValue;
        this.getter = getter;
        this.description = description;
    }

    public String name() {
        return name;
    }

    public ApiType<?> type() {
        return type;
    }

    public Presence presence() {
        return presence;
    }

    public String description() {
        return description;
    }
    /** What an absent field decodes to; null unless the field is {@link Presence#DEFAULTED}. */
    public Object defaultValue() {
        return defaultValue;
    }

    /** A field read off a record component. */
    static <O> ApiField<O, Object> of(
            String name,
            ApiType<?> type,
            Presence presence,
            Object defaultValue,
            Function<O, Object> getter,
            String description) {
        return new ApiField<>(name, type, presence, defaultValue, getter, description);
    }
}
