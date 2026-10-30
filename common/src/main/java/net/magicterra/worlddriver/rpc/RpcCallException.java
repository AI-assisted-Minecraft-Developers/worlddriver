package net.magicterra.worlddriver.rpc;

import java.io.IOException;
import net.magicterra.worlddriver.api.schema.ApiError;

/**
 * The server answered with a JSON-RPC error. {@link #apiError()} is the API's own error when the frame carried one in
 * {@code data}, so a bridge can pass code, issues and execution on instead of a message string; {@link #rpcCode()} is
 * the JSON-RPC code either way.
 */
public final class RpcCallException extends IOException {
    private static final long serialVersionUID = 1L;

    private final int rpcCode;

    private final ApiError apiError;

    public RpcCallException(int rpcCode, String message, ApiError apiError) {
        super(message);
        this.rpcCode = rpcCode;
        this.apiError = apiError;
    }

    public int rpcCode() {
        return rpcCode;
    }

    /** The API's error, or null for a protocol-level error (a frame the server could not parse). */
    public ApiError apiError() {
        return apiError;
    }
}
