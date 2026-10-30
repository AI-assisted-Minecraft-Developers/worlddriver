package net.magicterra.worlddriver.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * JSON-RPC 2.0 response objects, the same on the WebSocket and the MCP endpoint. An error is {@code {code, message,
 * data}}: the numeric code classifies it, the message is for a person, and {@code data} carries the API's own error
 * object when there is one.
 */
public final class JsonRpcFrames {

    // JSON-RPC 2.0 §5.1 reserved codes.
    public static final int PARSE_ERROR = -32_700;
    public static final int INVALID_REQUEST = -32_600;
    public static final int METHOD_NOT_FOUND = -32_601;
    public static final int INVALID_PARAMS = -32_602;
    public static final int INTERNAL_ERROR = -32_603;

    private JsonRpcFrames() {}

    /** A result. A request without an id is answered with {@code id:null}, never a made-up number. */
    public static JsonObject result(JsonElement id, JsonElement result) {
        JsonObject m = new JsonObject();
        m.addProperty("jsonrpc", "2.0");
        m.add("id", orNull(id));
        m.add("result", orNull(result));
        return m;
    }

    /**
     * An error. The {@code id} key is always present, null when the request was too malformed to carry one: a client
     * that tells responses from notifications by "has an id" must be able to classify it.
     */
    public static JsonObject error(JsonElement id, int code, String message, JsonElement data) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message == null ? "error" : message);
        if (data != null) {
            err.add("data", data);
        }
        JsonObject m = new JsonObject();
        m.addProperty("jsonrpc", "2.0");
        m.add("id", orNull(id));
        m.add("error", err);
        return m;
    }

    /** A request id JSON-RPC allows: a string, an integer, or null. */
    public static boolean validId(JsonElement id) {
        if (id == null || id.isJsonNull()) {
            return true;
        }
        if (!(id instanceof JsonPrimitive p)) {
            return false;
        }
        if (p.isString()) {
            return true;
        }
        if (!p.isNumber()) {
            return false;
        }
        String n = p.getAsNumber().toString();
        return n.matches("-?(0|[1-9][0-9]*)");
    }

    private static JsonElement orNull(JsonElement v) {
        return v == null ? JsonNull.INSTANCE : v;
    }
}
