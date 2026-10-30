package net.magicterra.worlddriver.api.schema.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A string that must be one of {@code value}, declared to TypeScript as {@code name} when one is given. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE_USE)
public @interface OneOf {
    /** The allowed strings. */
    String[] value();

    /** The TypeScript name of the union; empty to write it inline. */
    String name() default "";
}
