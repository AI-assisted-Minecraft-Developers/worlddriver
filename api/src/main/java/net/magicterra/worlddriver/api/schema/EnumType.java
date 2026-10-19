package net.magicterra.worlddriver.api.schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Function;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** A closed set of values, each spelled as a string; see {@link ApiTypes#enumOf} and {@link ApiTypes#stringEnum}. */
final class EnumType<E> extends ApiType<E> {
    private final String name;
    private final Map<String, E> byWire = new LinkedHashMap<>();
    private final Map<E, String> toWire = new LinkedHashMap<>();

    EnumType(String name, List<E> values, Function<E, String> wire) {
        this.name = name;
        for (E v : values) {
            String w = wire.apply(v);
            if (byWire.put(w, v) != null) throw new IllegalArgumentException("enum " + name + " spells " + w + " twice");
            toWire.put(v, w);
        }
        if (byWire.isEmpty()) throw new IllegalArgumentException("enum " + name + " has no values");
    }

    @Override protected E read(JsonElement value, DecodeContext at) {
        String s = Json.string(value);
        if (s == null) return at.mismatch(expected(), value);
        E v = byWire.get(s);
        return v != null ? v : at.fail(ApiIssue.Code.INVALID_VALUE, "expected " + expected() + ", got \"" + s + "\"");
    }

    @Override protected JsonElement write(E value) {
        String w = toWire.get(value);
        if (w == null) throw new IllegalStateException(value + " is not a value of " + expected());
        return new JsonPrimitive(w);
    }

    @Override protected JsonObject schemaBody() {
        JsonObject s = new JsonObject();
        s.addProperty("type", "string");
        s.add("enum", Json.strings(byWire.keySet()));
        return s;
    }

    @Override public String ts(TsContext ts) {
        StringJoiner j = new StringJoiner(" | ");
        for (String w : byWire.keySet()) j.add(TsContext.literal(w));
        if (name == null) return j.toString();
        return ts.declare(name, this, () -> "type " + name + " = " + j + ";");
    }

    @Override public String expected() { return "one of " + String.join(", ", byWire.keySet()); }
}
