package net.magicterra.worlddriver.api.schema.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A description. On a record component it describes that field, in the JSON Schema and as the field's TypeScript
 * comment. On a record, enum, sealed interface or constraint annotation it describes the type itself, wherever the type
 * appears; the JSON Schema shows it, TypeScript does not.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.TYPE})
public @interface Doc {
    String value();
}
