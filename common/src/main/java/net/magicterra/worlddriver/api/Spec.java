package net.magicterra.worlddriver.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a method of a Java-facing interface to the {@code MethodSpec} it calls. Every abstract method of
 * such an interface carries one, and its signature is {@code CompletionStage<R> name(P params, CallContext ctx)}
 * for the spec's {@code MethodSpec<P, R>}.
 *
 * <p>For an interface {@code XApi} the annotation processor generates {@code XApiBinding}: a caller
 * that sends every call through the registry, so it is checked and refused exactly as a request is, and
 * the registry entries an implementation publishes. A missing annotation, a missing spec, or a signature
 * that disagrees with the spec fails the compile, not the game.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface Spec {
    /** The class declaring the spec. */
    Class<?> value();

    /** The spec's {@code public static final} field in {@link #value}. */
    String spec() default "SPEC";
}
