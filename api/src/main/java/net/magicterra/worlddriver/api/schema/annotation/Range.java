package net.magicterra.worlddriver.api.schema.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The inclusive bounds of a number. On an {@code int} or {@code long} both bounds must be whole, and an omitted or
 * out-of-reach bound is the type's own limit.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE_USE)
public @interface Range {
    /** The inclusive lower bound; unbounded when omitted. */
    double min() default Double.NEGATIVE_INFINITY;

    /** The inclusive upper bound; unbounded when omitted. */
    double max() default Double.POSITIVE_INFINITY;
}
