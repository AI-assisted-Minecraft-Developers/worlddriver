package net.magicterra.worlddriver.script;

import dev.latvian.mods.rhino.BaseFunction;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.NativeJavaClass;
import dev.latvian.mods.rhino.ScriptRuntime;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.ScriptableObject;
import dev.latvian.mods.rhino.util.ClassVisibilityContext;

/**
 * The {@code Java} global hosted scripts hold, and {@code mc.script.eval} with the validation harness unless
 * {@code -Dworlddriver.sandbox=on} (see {@link ScriptPrelude}): {@code Java.type(name)} answers the class with that
 * binary name as a script sees a Java class (its static members, {@code new}), and {@code Java.loadClass(name)} is the
 * same under the name KubeJS-style scripts use. An unknown name throws an error a script can catch. This Rhino fork has
 * no {@code Packages} or {@code java} global, so without this a script reaches no class at all.
 *
 * <p>Classes load through this mod's class loader, so a Minecraft class is named as the running game names it:
 * intermediary ({@code net.minecraft.class_2338}) in a Fabric production game, Mojang names on NeoForge and in
 * development (see {@link ScriptJava}). {@link ScriptClassFilter} decides which names a script may load: all of them
 * unless {@code -Dworlddriver.sandbox=on}.
 */
final class ScriptJavaGlobal {

    private ScriptJavaGlobal() {}

    static void install(Context cx, ScriptableObject scope) {
        Scriptable java = cx.newObject(scope);
        BaseFunction type = new JavaType();
        ScriptableObject.putProperty(java, "type", type, cx);
        ScriptableObject.putProperty(java, "loadClass", type, cx);
        ScriptableObject.putProperty(scope, "Java", java, cx);
    }

    /** {@code Java.type(name)}. */
    private static final class JavaType extends BaseFunction {
        // The script gets a JS Error naming the class; the class loader's Java stack means nothing to it.
        @Override
        @SuppressWarnings("PMD.PreserveStackTrace")
        public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
            String name = args.length == 0 ? "undefined" : ScriptRuntime.toString(cx, args[0]);
            if (!cx.visibleToScripts(name, ClassVisibilityContext.CLASS_IN_PACKAGE)) {
                throw ScriptRuntime.constructError(
                        cx, "Error", "the Java class " + name + " is not visible to scripts");
            }
            Class<?> cls;
            try {
                // Not initialized here: a class whose static setup fails (a client class on a server) fails when the
                // script uses it, saying why, instead of reading as a name that does not exist.
                cls = Class.forName(name, false, ScriptJavaGlobal.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw ScriptRuntime.constructError(cx, "Error", "no Java class named " + name);
            } catch (LinkageError | RuntimeException e) {
                // A loader may refuse a class with an exception of its own (a mixin class, a class of the other side).
                throw ScriptRuntime.constructError(
                        cx, "Error", "the Java class " + name + " could not be loaded: " + e);
            }
            return new NativeJavaClass(cx, getTopLevelScope(scope), cls);
        }
    }
}
