package net.magicterra.worlddriver.api.schema.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declare a string to TypeScript as {@code type <name> = <type>}, usually a template literal type close to its
 * {@link Pattern}. TypeScript cannot check the whole pattern; the server does.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE_USE)
public @interface TsAlias {
    String name();

    String type();
}
