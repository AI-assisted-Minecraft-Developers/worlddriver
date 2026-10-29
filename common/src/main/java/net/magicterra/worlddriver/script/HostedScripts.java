package net.magicterra.worlddriver.script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.latvian.mods.rhino.BaseFunction;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ContextFactory;
import dev.latvian.mods.rhino.JavaScriptException;
import dev.latvian.mods.rhino.NativeArray;
import dev.latvian.mods.rhino.NativeJavaClass;
import dev.latvian.mods.rhino.NativeJavaObject;
import dev.latvian.mods.rhino.RhinoException;
import dev.latvian.mods.rhino.ScriptRuntime;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.ScriptableObject;
import dev.latvian.mods.rhino.Undefined;
import dev.latvian.mods.rhino.WrappedException;
import dev.latvian.mods.rhino.util.ClassVisibilityContext;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.registry.Scripting;

/**
 * {@link Scripting} over the engine {@code mc.script.eval} uses, without its run, budget or result envelope: the
 * caller owns the thread and decides how long a script may take. Each function call enters a context of its own,
 * since a script's function is called later and from whichever thread the caller is on. Every scope also holds the
 * {@code Java} global ({@link #installJava}).
 */
public final class HostedScripts implements Scripting {
    private final ApiRegistry api;
    private final ContextFactory factory = new ScriptContextFactory();

    public HostedScripts(ApiRegistry api) {
        this.api = api;
    }

    @Override
    public Object evaluate(String name, String source, Map<String, ?> globals) {
        Context cx = factory.enter();
        try {
            ScriptableObject scope = cx.initStandardObjects();
            ScriptPrelude.install(cx, scope, ScriptCalls.standalone(api));
            installJava(cx, scope);
            for (Map.Entry<String, ?> g : globals.entrySet()) {
                ScriptableObject.putProperty(scope, g.getKey(), toJs(g.getValue(), cx, scope), cx);
            }
            return toJava(cx.evaluateString(scope, source, name, 1, null), scope);
        } catch (RuntimeException e) {
            throw unwrap(e, cx);
        }
    }

    /**
     * The {@code Java} global: {@code Java.type(name)} answers the class with that binary name as a script sees a
     * Java class (its static members, {@code new}), and {@code Java.loadClass(name)} is the same under the name
     * KubeJS-style scripts use. An unknown name throws an error a script can catch.
     *
     * <p>Classes load through this mod's class loader, so a Minecraft class is named as the running game names it:
     * intermediary ({@code net.minecraft.class_2338}) in a Fabric production game, Mojang names on NeoForge and in
     * development (see {@link ScriptJava}). The class filter applies as it does to {@code mc.script.eval}: off unless
     * {@code -Dworlddriver.sandbox=on}.
     */
    private static void installJava(Context cx, ScriptableObject scope) {
        Scriptable java = cx.newObject(scope);
        BaseFunction type = new JavaType();
        ScriptableObject.putProperty(java, "type", type, cx);
        ScriptableObject.putProperty(java, "loadClass", type, cx);
        ScriptableObject.putProperty(scope, "Java", java, cx);
    }

    /** {@code Java.type(name)}. */
    private static final class JavaType extends BaseFunction {
        @Override
        public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
            String name = args.length == 0 ? "undefined" : ScriptRuntime.toString(cx, args[0]);
            if (!cx.visibleToScripts(name, ClassVisibilityContext.CLASS_IN_PACKAGE)) {
                throw ScriptRuntime.constructError(cx, "Error", "the Java class " + name + " is not visible to scripts");
            }
            Class<?> cls;
            try {
                cls = Class.forName(name, true, HostedScripts.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                throw ScriptRuntime.constructError(cx, "Error", "no Java class named " + name);
            }
            return new NativeJavaClass(cx, getTopLevelScope(scope), cls);
        }
    }

    private Object toJava(Object js, Scriptable scope) {
        if (js == null || js instanceof Undefined) return null;
        if (js instanceof Boolean || js instanceof Double) return js;
        if (js instanceof Number n) return n.doubleValue();
        if (js instanceof CharSequence cs) return cs.toString();
        if (js instanceof JavaFunction f) return f.target;
        // A Java object the script got from Java (a Java.type, what a Java function or method returned) goes back as
        // itself. Before the array, function and map checks: a wrapped Java list, map or class is one of those too.
        if (js instanceof NativeJavaObject o) return o.unwrap();
        if (js instanceof NativeArray array) {
            List<Object> out = new ArrayList<>();
            for (Object o : array) out.add(toJava(o, scope));
            return out;
        }
        // Spelled out: inside this class the bare name is the inherited Scripting.Function.
        if (js instanceof dev.latvian.mods.rhino.Function f) return new ScriptFunction(f, scope);
        if (js instanceof Map<?, ?> map) {           // NativeObject implements Map in this fork
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), toJava(v, scope)));
            return out;
        }
        throw new IllegalArgumentException("a script handed out " + describe(js)
                + ", which is not a plain value (null, boolean, number, string, array, object or function)");
    }

    private Object toJs(Object java, Context cx, Scriptable scope) {
        if (java == null || java instanceof Boolean || java instanceof String) return java;
        if (java instanceof Number n) return n.doubleValue();
        if (java instanceof ScriptFunction f) return f.target;
        if (java instanceof Scripting.Function f) return new JavaFunction(f);
        if (java instanceof Map<?, ?> map) {
            Scriptable out = cx.newObject(scope);
            map.forEach((k, v) -> ScriptableObject.putProperty(out, String.valueOf(k), toJs(v, cx, scope), cx));
            return out;
        }
        if (java instanceof List<?> list) {
            Object[] items = new Object[list.size()];
            for (int i = 0; i < items.length; i++) items[i] = toJs(list.get(i), cx, scope);
            return cx.newArray(scope, items);
        }
        // Any other Java object: the script sees it as a Java object, and handing it back gives Java the same object.
        return cx.javaToJS(java, scope);
    }

    /**
     * The Java exception the engine wrapped in {@code t}, or null. Statements, not one conditional expression: the
     * Eclipse compiler inside loom's source remapper takes exponential time over pattern variables chained with
     * {@code &&} inside a {@code ?:}, and the published sources jar never finished.
     */
    private static Throwable wrapped(Throwable t, Context cx) {
        if (t instanceof WrappedException w) return w.getWrappedException();
        if (!(t instanceof JavaScriptException js)) return null;
        Object value = js.getValue();
        // A script that caught a Java exception and threw it again: the catch value is the engine's error object,
        // which keeps the Java exception under javaException.
        if (value instanceof Scriptable s && !(value instanceof NativeJavaObject)
                && ScriptableObject.hasProperty(s, "javaException", cx)) {
            value = ScriptableObject.getProperty(s, "javaException", cx);
        }
        if (!(value instanceof NativeJavaObject o)) return null;
        return o.unwrap() instanceof Throwable inner ? inner : null;
    }

    /**
     * What the Java code that started the script should see: a Java exception as itself, however many times the
     * engine wrapped it on the way out (including a script that caught and rethrew it), else the script's error.
     */
    private static RuntimeException unwrap(RuntimeException e, Context cx) {
        Throwable current = e;
        for (int hops = 0; current != null && hops < 8; hops++) {
            Throwable inner = wrapped(current, cx);
            if (inner == null) break;
            current = inner;
        }
        if (current instanceof Error error) throw error;
        if (current instanceof RuntimeException own && !(own instanceof RhinoException)) return own;
        if (current instanceof RhinoException r) {
            String message = r.details() != null ? r.details() : String.valueOf(r.getMessage());
            return new ScriptFailure(message, r.sourceName(), Math.max(0, r.lineNumber()), r.getScriptStackTrace());
        }
        return new ScriptFailure(String.valueOf(current), null, 0, null);
    }

    private static String describe(Object js) {
        return js instanceof Scriptable s ? "a " + s.getClassName() : "a " + js.getClass().getName();
    }

    /** A script's function, held for Java to call. */
    private final class ScriptFunction implements Scripting.Function {
        final dev.latvian.mods.rhino.Function target;
        private final Scriptable scope;

        ScriptFunction(dev.latvian.mods.rhino.Function target, Scriptable scope) {
            this.target = target;
            this.scope = scope;
        }

        @Override
        public Object call(Object... args) {
            Context cx = factory.enter();
            try {
                Object[] in = new Object[args.length];
                for (int i = 0; i < args.length; i++) in[i] = toJs(args[i], cx, scope);
                return toJava(target.call(cx, scope, scope, in), scope);
            } catch (RuntimeException e) {
                throw unwrap(e, cx);
            }
        }
    }

    /**
     * A Java function, handed to a script. An exception it throws goes through the engine as a {@link WrappedException},
     * which a script's {@code catch} receives as an error whose message carries the Java one; thrown raw, Rhino would
     * run only the script's {@code finally} blocks. An {@link Error} stays raw and so stays uncatchable. {@link #unwrap}
     * hands the Java caller the original either way.
     */
    private final class JavaFunction extends BaseFunction {
        final Scripting.Function target;

        JavaFunction(Scripting.Function target) {
            this.target = target;
        }

        @Override
        public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
            Object[] in = new Object[args.length];
            for (int i = 0; i < args.length; i++) in[i] = toJava(args[i], scope);
            try {
                return toJs(target.call(in), cx, scope);
            } catch (RuntimeException e) {
                throw Context.throwAsScriptRuntimeEx(e, cx);
            }
        }
    }
}
