package net.magicterra.worlddriver.api.schema;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.Collection;

/** The scalar kinds of a Gson tree, each null when the value is another kind. */
final class Json {
    private Json() {}

    static String string(JsonElement v) {
        return v instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }

    static Boolean bool(JsonElement v) {
        return v instanceof JsonPrimitive p && p.isBoolean() ? p.getAsBoolean() : null;
    }

    static Number number(JsonElement v) {
        return v instanceof JsonPrimitive p && p.isNumber() ? p.getAsNumber() : null;
    }

    static JsonArray strings(Collection<String> values) {
        JsonArray a = new JsonArray(values.size());
        for (String s : values) a.add(s);
        return a;
    }
}
