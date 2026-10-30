package net.magicterra.worlddriver.api.schema;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Any JSON value, or any JSON object, kept as given; see {@link ApiTypes#json} and {@link ApiTypes#jsonObject}. */
final class JsonType extends ApiType<JsonValue> {
    private final boolean objectOnly;

    JsonType(boolean objectOnly) {
        this.objectOnly = objectOnly;
    }

    @Override
    protected JsonValue read(JsonElement value, DecodeContext at) {
        if (objectOnly && !value.isJsonObject()) return at.mismatch(expected(), value);
        return JsonValue.of(value);
    }

    @Override
    protected JsonElement write(JsonValue value) {
        return value.json();
    }

    /**
     * Every non-null kind spelled out rather than an empty schema: an MCP client was seen to read a typeless field as
     * licence to send the value JSON-stringified.
     */
    @Override
    protected JsonObject schemaBody() {
        JsonObject s = new JsonObject();
        if (objectOnly) {
            s.addProperty("type", "object");
        } else {
            JsonArray kinds = new JsonArray();
            for (String k : new String[] {"object", "array", "string", "number", "boolean"}) kinds.add(k);
            s.add("type", kinds);
        }
        return s;
    }

    @Override
    public String ts(TsContext ts) {
        String json = ts.declare(
                "Json",
                JsonType.class,
                () -> "type Json = null | boolean | number | string | Json[] | { [key: string]: Json };");
        return objectOnly ? "{ [key: string]: " + json + " }" : json;
    }

    @Override
    public String expected() {
        return objectOnly ? "a JSON object" : "a JSON value";
    }
}
