package net.magicterra.worlddriver.api.schema.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declare this record, enum or union to TypeScript under its own name. Without it the type is written inline wherever
 * it is used. Two types may not share a name.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Named {
    String value();
}
