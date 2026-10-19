package net.magicterra.worlddriver;

import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.ToNumberPolicy;
import net.magicterra.worlddriver.api.registry.ApiRegistry;

/**
 * JSON for tests that write a call's parameters as Java maps and read its result as plain maps, lists,
 * strings, booleans and numbers (every integer a Long, every other number a Double).
 */
public final class TestJson {
    private TestJson() {}

    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping()
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();

    /** {@code value} (maps, lists, strings, numbers, booleans) as a Gson tree. */
    public static JsonElement json(Object value) { return GSON.toJsonTree(value); }

    /** {@code params} as the object a call takes. */
    public static JsonObject obj(Map<String, ?> params) { return json(params).getAsJsonObject(); }

    /** {@code json} as plain Java values. */
    public static Object plain(JsonElement json) { return json == null ? null : GSON.fromJson(json, Object.class); }

    /** JSON text as plain Java values. */
    public static Object plain(String json) { return plain(JsonParser.parseString(json)); }

    /** {@code method} run and waited for, its result as plain Java values. */
    public static Object route(ApiRegistry api, String method, Map<String, ?> params) {
        return plain(api.route(method, params == null ? null : obj(params)));
    }
}
