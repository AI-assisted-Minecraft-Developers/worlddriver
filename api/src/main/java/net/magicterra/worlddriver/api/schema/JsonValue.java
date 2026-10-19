package net.magicterra.worlddriver.api.schema;

import java.util.Objects;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;

/**
 * An open JSON value: a payload whose shape belongs to the caller (a skill's arguments, a script's
 * result, a custom event's data), not to this API. A Gson tree is mutable, so this holds its own copy
 * and hands out copies, which keeps a DTO holding one immutable.
 */
public final class JsonValue {
    public static final JsonValue NULL = new JsonValue(JsonNull.INSTANCE);

    private final JsonElement json;

    private JsonValue(JsonElement json) { this.json = json; }

    /** A copy of {@code json}; Java null and JSON null are both {@link #NULL}. */
    public static JsonValue of(JsonElement json) {
        return json == null || json.isJsonNull() ? NULL : new JsonValue(json.deepCopy());
    }

    /** A copy of the value, free to change. */
    public JsonElement json() { return json.deepCopy(); }

    public boolean isNull() { return json.isJsonNull(); }

    @Override public boolean equals(Object o) { return o instanceof JsonValue j && Objects.equals(json, j.json); }
    @Override public int hashCode() { return json.hashCode(); }
    @Override public String toString() { return json.toString(); }
}
