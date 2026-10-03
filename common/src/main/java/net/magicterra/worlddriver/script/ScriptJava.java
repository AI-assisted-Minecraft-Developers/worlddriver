package net.magicterra.worlddriver.script;

import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.Undefined;

/**
 * Java-side helpers installed as {@code __java} in every script scope; prelude.js wraps them as globals.
 *
 * <p>Scripts cannot call {@code getClass()} themselves: this Rhino fork builds a wrapped object's members
 * by walking its supertypes but stops before {@code java.lang.Object}
 * ({@code CachedClassInfo.getAccessibleMethods}), so a method only {@code Object} declares never appears.
 * {@code toString}/{@code equals}/{@code hashCode} survive wherever a subclass redeclares them;
 * {@code getClass} is final, so it is missing on every object. This hands back the name, not the
 * {@code Class}, so a script gains no reflection it did not have.
 *
 * <p>The name is the runtime one. WorldDriver's own classes and the JDK's read the same everywhere, but
 * a Minecraft class reads {@code net.minecraft.class_1657} in a Fabric production game, where only
 * intermediary names exist, and its Mojang name on NeoForge and in development.
 */
public final class ScriptJava {
    public static final ScriptJava INSTANCE = new ScriptJava();

    private ScriptJava() {}

    /**
     * Binary name of {@code value}'s class, or null for null, undefined and a script object (a JS object,
     * array or function arrives here as Rhino's own Scriptable, not unwrapped).
     *
     * <p>A JS string, number or boolean is NOT null here: it arrives as {@code String}, {@code Double} or
     * {@code Boolean}, exactly as a wrapped Java {@code String} or {@code Long} does once unwrapped, so
     * only the script side can tell them apart. prelude.js's {@code javaClassName} does that by
     * {@code typeof}.
     */
    public String className(Object value) {
        if (value == null || value == Undefined.INSTANCE || value instanceof Scriptable) return null;
        return value.getClass().getName();
    }
}
