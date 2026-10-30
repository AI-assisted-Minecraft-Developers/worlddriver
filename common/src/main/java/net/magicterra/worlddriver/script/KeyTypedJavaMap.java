package net.magicterra.worlddriver.script;

import java.lang.reflect.Method;
import java.util.Map;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.NativeJavaMap;
import dev.latvian.mods.rhino.NativeJavaObject;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.type.TypeInfo;
import dev.latvian.mods.rhino.util.Deletable;

/**
 * A Java map as a script sees it, whose methods stay reachable when its keys are not of the name's type, and whose
 * entries are read and written with the key and value types the map itself takes.
 *
 * <p>Rhino's map wrapper asks the map for every property name as a key before it looks for a member, and a typed
 * map such as fastutil's {@code Int2ObjectMap} answers a {@code String} key with a {@code ClassCastException}, so
 * {@code map.size()} threw. A name the map cannot hold as a key is looked up as a member instead.
 *
 * <p>Rhino also hands a map the script's own values: an index as an {@code Integer}, a number as a {@code Double}.
 * A fastutil map built for primitives refuses both where it wants a {@code long} key or an {@code int} value, so
 * {@code longMap[5]} read nothing and {@code object2IntMap.a = 3} threw. Where the map's own {@code put} takes a
 * primitive, a key or value is converted to it first; any other map gets what Rhino gave it, as before.
 */
final class KeyTypedJavaMap extends NativeJavaMap {

    /** The same object without the map behaviour: where a name that cannot be a key is looked up. */
    private final NativeJavaObject members;
    /** The primitive the map's own {@code put} takes for a key, or null when it takes an object. */
    private final TypeInfo primitiveKey;
    /** The type a written value is converted to: the map's primitive, or else what Rhino read off its type. */
    private final TypeInfo valueType;

    KeyTypedJavaMap(Context cx, Scriptable scope, Map<?, ?> map, TypeInfo type) {
        super(cx, scope, map, map, type);
        members = new NativeJavaObject(scope, map, type, cx);
        Method put = primitivePut(map.getClass());
        primitiveKey = put != null && put.getParameterTypes()[0].isPrimitive()
                ? TypeInfo.of(put.getParameterTypes()[0]) : null;
        valueType = put != null && put.getParameterTypes()[1].isPrimitive()
                ? TypeInfo.of(put.getParameterTypes()[1]) : mapValueType;
    }

    /** The map's own two-argument {@code put} that takes a primitive, or null for a map of objects. */
    private static Method primitivePut(Class<?> type) {
        for (Method m : type.getMethods()) {
            if (!m.getName().equals("put") || m.getParameterCount() != 2 || m.isBridge()
                    || m.isAnnotationPresent(Deprecated.class)) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p[0].isPrimitive() || p[1].isPrimitive()) return m;
        }
        return null;
    }

    private boolean refusesKey(Object key) {
        try {
            map.containsKey(key);
            return false;
        } catch (ClassCastException | NullPointerException e) {
            return true;
        }
    }

    /** The key an index stands for in this map: the map's primitive where it takes one, else the index. */
    private Object key(Context cx, int index) {
        return primitiveKey == null ? index : cx.jsToJava(index, primitiveKey);
    }

    @SuppressWarnings("unchecked")
    private void write(Context cx, Object key, Object value) {
        map.put(key, cx.jsToJava(value, valueType));
    }

    @Override
    public boolean has(Context cx, String name, Scriptable start) {
        return refusesKey(name) ? members.has(cx, name, start) : super.has(cx, name, start);
    }

    @Override
    public boolean has(Context cx, int index, Scriptable start) {
        Object key = key(cx, index);
        return !refusesKey(key) && map.containsKey(key) || members.has(cx, index, start);
    }

    @Override
    public Object get(Context cx, String name, Scriptable start) {
        return refusesKey(name) ? members.get(cx, name, start) : super.get(cx, name, start);
    }

    @Override
    public Object get(Context cx, int index, Scriptable start) {
        Object key = key(cx, index);
        if (!refusesKey(key) && map.containsKey(key)) return cx.javaToJS(map.get(key), start, mapValueType);
        return members.get(cx, index, start);
    }

    @Override
    public void put(Context cx, String name, Scriptable start, Object value) {
        if (refusesKey(name)) {
            members.put(cx, name, start, value);
        } else {
            write(cx, name, value);
        }
    }

    @Override
    public void put(Context cx, int index, Scriptable start, Object value) {
        write(cx, key(cx, index), value);
    }

    @Override
    public void delete(Context cx, int index) {
        Object key = key(cx, index);
        if (!refusesKey(key)) Deletable.deleteObject(map.remove(key));
    }
}
