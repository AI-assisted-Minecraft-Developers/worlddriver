package net.magicterra.worlddriver.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON-RPC 2.0 response objects, the same on the WebSocket and the MCP endpoint. An error is
 * {@code {code, message, data}}: the numeric code classifies it, the message is for a person, and
 * {@code data} carries the API's own error object when there is one.
 */
public final class JsonRpcFrames {
    private JsonRpcFrames() {}

    // JSON-RPC 2.0 §5.1 reserved codes.
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    /** A result. A request without an id is answered with {@code id:null}, never a made-up number. */
    public static Map<String, Object> result(Object id, Object result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        return m;
    }

    /**
     * An error. The {@code id} key is always present, null when the request was too malformed to carry
     * one: a client that tells responses from notifications by "has an id" must be able to classify it.
     */
    public static Map<String, Object> error(Object id, int code, String message, Object data) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message == null ? "error" : message);
        if (data != null) err.put("data", data);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("error", err);
        return m;
    }

    /** A request id JSON-RPC allows: a string, an integer, or null. */
    public static boolean validId(Object id) {
        return id == null || id instanceof String || id instanceof Long || id instanceof Integer
                || id instanceof java.math.BigInteger;
    }
}
