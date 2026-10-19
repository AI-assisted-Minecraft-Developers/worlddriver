package net.magicterra.worlddriver.api.schema;

import java.util.Objects;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Exactly one string; see {@link ApiTypes#literal}. */
final class LiteralType extends ApiType<String> {
    private final String value;

    LiteralType(String value) { this.value = Objects.requireNonNull(value); }

    @Override protected String read(JsonElement v, DecodeContext at) {
        String s = Json.string(v);
        if (s == null) return at.mismatch(expected(), v);
        return value.equals(s) ? s : at.fail(ApiIssue.Code.INVALID_VALUE, "expected " + expected() + ", got \"" + s + "\"");
    }
    @Override protected JsonElement write(String v) { return new JsonPrimitive(v); }
    @Override protected JsonObject schemaBody() {
        JsonObject s = new JsonObject();
        s.addProperty("type", "string");
        s.addProperty("const", value);
        return s;
    }
    @Override public String ts(TsContext ts) { return TsContext.literal(value); }
    @Override public String expected() { return "\"" + value + "\""; }
}
