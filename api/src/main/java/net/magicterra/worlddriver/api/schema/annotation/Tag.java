package net.magicterra.worlddriver.api.schema.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Makes a sealed interface a discriminated union: the member named here picks the variant. Its variants are the
 * permitted records, in the order the {@code permits} clause lists them, each marked {@link Variant}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Tag {
    String value();
}
