package net.magicterra.worlddriver.api.schema.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** How long a string (characters), a list (items) or a map (entries; only {@code max}) may be, inclusive. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE_USE)
public @interface Length {
    /** The smallest allowed length or count. */
    int min() default 0;

    /** The largest allowed length or count. */
    int max() default Integer.MAX_VALUE;
}
