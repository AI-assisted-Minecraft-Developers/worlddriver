package net.magicterra.worlddriver.script;

import java.util.Map;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.NativeJavaMap;
import dev.latvian.mods.rhino.NativeJavaObject;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.type.TypeInfo;

/**
 * A Java map as a script sees it, whose methods stay reachable when its keys are not of the name's type. Rhino's map
 * wrapper asks the map for every property name as a key before it looks for a member, and a typed map such as
 * fastutil's {@code Int2ObjectMap} answers a {@code String} key with a {@code ClassCastException}, so
 * {@code map.size()} threw. A name the map cannot hold as a key is looked up as a member instead.
 */
final class KeyTypedJavaMap extends NativeJavaMap {

    /** The same object without the map behaviour: where a name that cannot be a key is looked up. */
    private final NativeJavaObject members;

    KeyTypedJavaMap(Context cx, Scriptable scope, Map<?, ?> map, TypeInfo type) {
        super(cx, scope, map, map, type);
        members = new NativeJavaObject(scope, map, type, cx);
    }

    private boolean refusesKey(Object key) {
        try {
            map.containsKey(key);
            return false;
        } catch (ClassCastException | NullPointerException e) {
            return true;
        }
    }

    @Override
    public boolean has(Context cx, String name, Scriptable start) {
        return refusesKey(name) ? members.has(cx, name, start) : super.has(cx, name, start);
    }

    @Override
    public boolean has(Context cx, int index, Scriptable start) {
        return refusesKey(index) ? members.has(cx, index, start) : super.has(cx, index, start);
    }

    @Override
    public Object get(Context cx, String name, Scriptable start) {
        return refusesKey(name) ? members.get(cx, name, start) : super.get(cx, name, start);
    }

    @Override
    public Object get(Context cx, int index, Scriptable start) {
        return refusesKey(index) ? members.get(cx, index, start) : super.get(cx, index, start);
    }
}
