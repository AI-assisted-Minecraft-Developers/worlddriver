package net.magicterra.worlddriver.bot.stagewright.kit;

import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.ToNumberPolicy;
import net.magicterra.worlddriver.api.registry.ApiRegistry;

/**
 * JSON for scenes that call a method by name: parameters written as Java maps go out as Gson trees, and
 * a result comes back as plain maps, lists, strings, booleans and numbers (every integer a Long), which
 * is how those scenes read it. Also the codec of the scene fixture files.
 */
public final class SceneJson {
    private SceneJson() {}

    /** Plain maps and lists, nulls kept, every integer a Long. */
    static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping()
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();

    /** {@code params} as the object a call takes; null is no parameters. */
    public static JsonObject params(Map<String, ?> params) {
        return params == null ? new JsonObject() : GSON.toJsonTree(params).getAsJsonObject();
    }

    /** {@code json} as plain Java values. */
    public static Object plain(JsonElement json) {
        return json == null ? null : GSON.fromJson(json, Object.class);
    }

    /** {@code method} run and waited for, its result as plain Java values. */
    public static Object route(ApiRegistry api, String method, Map<String, ?> params) {
        return plain(api.route(method, params(params)));
    }
}
