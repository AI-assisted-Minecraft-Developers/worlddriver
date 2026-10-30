package net.magicterra.worlddriver.api.schema.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Use the {@code ApiType} held by the static field {@code field} of {@code value} instead of reading one off the Java
 * type: for the few shapes no annotation describes, such as a union another mod may add variants to or an object whose
 * members are typed by their key. The field must already be set when this type is first built, so declare it above the
 * contract that refers to it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE_USE)
public @interface As {
    Class<?> value();

    String field();
}
