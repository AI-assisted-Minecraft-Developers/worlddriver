package net.magicterra.worlddriver.api.schema.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A {@code long} written as a decimal string. JavaScript numbers hold 53 bits, so a sequence number or seed
 * sent as a number arrives rounded; as a string it survives every client. Only {@link Range#min} applies.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE_USE)
public @interface AsString {
}
