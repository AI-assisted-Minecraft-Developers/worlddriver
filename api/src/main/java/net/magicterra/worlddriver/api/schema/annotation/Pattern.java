package net.magicterra.worlddriver.api.schema.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A string matching {@code value} entirely, and so never empty. {@code expected} is how an error names what was wanted:
 * "a namespaced id such as minecraft:stone".
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE_USE)
public @interface Pattern {
    /** The regular expression the whole string must match. */
    String value();

    /** How an error names the shape that was wanted. */
    String expected();
}
