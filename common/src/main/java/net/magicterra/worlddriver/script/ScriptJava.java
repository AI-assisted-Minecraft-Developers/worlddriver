package net.magicterra.worlddriver.script;

import dev.latvian.mods.rhino.Scriptable;

/**
 * Java-side helpers installed as {@code __java} in every script scope; prelude.js wraps them as globals.
 *
 * <p>Scripts cannot call {@code getClass()} themselves: this Rhino fork builds a wrapped object's members
 * by walking its supertypes but stops before {@code java.lang.Object}
 * ({@code CachedClassInfo.getAccessibleMethods}), so a method only {@code Object} declares never appears.
 * {@code toString}/{@code equals}/{@code hashCode} survive wherever a subclass redeclares them;
 * {@code getClass} is final, so it is missing on every object.
 */
public final class ScriptJava {
    public static final ScriptJava INSTANCE = new ScriptJava();

    private ScriptJava() {}

    /** Binary name of the Java object a script holds, or null for a script-native value (a JS object,
     *  array or function arrives here as Rhino's own Scriptable, not unwrapped). */
    public String className(Object value) {
        if (value == null || value instanceof Scriptable) return null;
        return value.getClass().getName();
    }
}
