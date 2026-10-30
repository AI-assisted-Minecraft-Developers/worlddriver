package net.magicterra.worlddriver.api.schema.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The value a missing field takes. For a field written as a JSON string (a string, an enum, an id) the text is the
 * string itself: {@code @Default("self")}. For any other field it is JSON: {@code @Default("0")},
 * {@code @Default("true")}, {@code @Default("[]")}. It is checked against the field's type when the type is built, so a
 * default the type refuses fails at startup, not on the first request that omits it.
 *
 * <p>A component with neither this nor an {@code Optional} type is required.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Default {
    /** The default, written as the class comment above describes: plain text for a string, JSON otherwise. */
    String value();
}
