package net.magicterra.worlddriver.api.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.thread.ServerThreadHop;
import net.magicterra.worlddriver.protocol.ServerBusyException;

/** One translation from whatever a call threw to the {@link ApiError} every entry point reports. */
public final class ApiErrors {
    private ApiErrors() {}

    public static ApiError from(Throwable t) {
        Throwable c = unwrap(t);
        ApiException api = ApiException.find(c);
        if (api != null) return api.error();
        ServerThreadHop.HopTimeoutException hop = ServerThreadHop.find(c);
        if (hop != null) return ApiError.of(hop.code(), hop.getMessage());
        ServerBusyException busy = ServerBusyException.find(c);
        if (busy != null) return ApiError.of(ApiError.Code.SERVER_BUSY, busy.getMessage());
        String msg = c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
        // A service rejecting a value the type could not judge (an unknown block id) before it acted.
        if (c instanceof IllegalArgumentException) {
            return new ApiError(ApiError.Code.INVALID_PARAMS, msg, List.of(new ApiIssue("", ApiIssue.Code.INVALID_VALUE, msg)),
                    ApiError.Execution.NOT_STARTED);
        }
        // "No server attached", "no client registered": the entry cannot serve this call here and now.
        if (c instanceof IllegalStateException) return ApiError.of(ApiError.Code.CAPABILITY_UNAVAILABLE, msg);
        return ApiError.of(ApiError.Code.INTERNAL_ERROR, c.getClass().getSimpleName() + ": " + msg);
    }

    /** The cause a future or reflection wrapper hides. */
    public static Throwable unwrap(Throwable t) {
        Throwable c = t;
        while ((c instanceof CompletionException || c instanceof ExecutionException) && c.getCause() != null) {
            c = c.getCause();
        }
        return c;
    }

    /** {@code {code, message, issues, execution}}, the wire form every entry point sends. */
    public static JsonObject toTree(ApiError e) {
        JsonObject m = new JsonObject();
        m.addProperty("code", e.code().wireName());
        m.addProperty("message", e.message());
        JsonArray issues = new JsonArray();
        for (ApiIssue i : e.issues()) {
            JsonObject im = new JsonObject();
            im.addProperty("path", i.path());
            im.addProperty("code", i.code().wireName());
            im.addProperty("message", i.message());
            issues.add(im);
        }
        m.add("issues", issues);
        m.addProperty("execution", e.execution().wireName());
        return m;
    }

    /** The inverse of {@link #toTree}, for a bridge that received one; null when {@code tree} is not one. */
    public static ApiError fromTree(JsonElement tree) {
        if (!(tree instanceof JsonObject m)) return null;
        String code = string(m.get("code")), message = string(m.get("message"));
        if (code == null || message == null) return null;
        ApiError.Code c = null;
        for (ApiError.Code k : ApiError.Code.values()) if (k.wireName().equals(code)) c = k;
        if (c == null) return null;
        List<ApiIssue> issues = new ArrayList<>();
        if (m.get("issues") instanceof JsonArray l) {
            for (JsonElement o : l) {
                if (!(o instanceof JsonObject im)) continue;
                String p = string(im.get("path")), ic = string(im.get("code")), msg = string(im.get("message"));
                if (p == null || ic == null || msg == null) continue;
                for (ApiIssue.Code k : ApiIssue.Code.values()) {
                    if (k.wireName().equals(ic)) issues.add(new ApiIssue(p, k, msg));
                }
            }
        }
        ApiError.Execution ex = ApiError.Execution.UNKNOWN;
        String es = string(m.get("execution"));
        if (es != null) {
            for (ApiError.Execution k : ApiError.Execution.values()) if (k.wireName().equals(es)) ex = k;
        }
        return new ApiError(c, message, issues, ex);
    }

    private static String string(JsonElement v) {
        return v instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }
}
