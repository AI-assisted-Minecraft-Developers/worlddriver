package net.magicterra.worlddriver.script;

import java.util.LinkedHashMap;
import java.util.Map;

import net.magicterra.worlddriver.api.registry.ApiErrors;
import net.magicterra.worlddriver.api.thread.ServerThreadGuard;
import net.magicterra.worlddriver.api.contract.ApiError;
import net.magicterra.worlddriver.protocol.JsonCodec;
import net.magicterra.worlddriver.rpc.RpcCallException;
import net.magicterra.worlddriver.rpc.RpcClient;

/**
 * The body of {@code Driver.system.rpcRoundtrip(method, params)}: the same call made over a fresh
 * WebSocket connection, for the transport-parity checks. Its answer is the envelope
 * {@code ApiRegistry.invokeJson} gives, so the script throws the same {@code DriverApiError} with the
 * same code and issues whichever way the call went.
 */
public final class RpcBridge {
    private final String host;
    private final int port;

    public RpcBridge(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /** {@code {"result":…}} or {@code {"error":ApiError}}; {@code paramsJson} null sends no params. */
    public String callJson(String method, String paramsJson) {
        // Outside the envelope: a refusal is the script's mistake, not an outcome of the call.
        ServerThreadGuard.refuseBlocking("an RPC round-trip");
        Map<String, Object> envelope = new LinkedHashMap<>();
        try (RpcClient c = new RpcClient(host, port)) {
            Object params = paramsJson == null ? null : JsonCodec.decode(paramsJson);
            envelope.put("result", c.call(method, params, RpcClient.DEFAULT_CALL_TIMEOUT_MS));
        } catch (RpcCallException e) {
            ApiError err = e.apiError() != null ? e.apiError() : ApiError.of(ApiError.Code.INTERNAL_ERROR,
                    "rpc error " + e.rpcCode() + ": " + e.getMessage());
            envelope.put("error", ApiErrors.toTree(err));
        } catch (Exception e) {
            envelope.put("error", ApiErrors.toTree(new ApiError(ApiError.Code.OUTCOME_UNKNOWN,
                    "rpcRoundtrip failed: " + e.getMessage(), java.util.List.of(), ApiError.Execution.UNKNOWN)));
        }
        return JsonCodec.encode(envelope);
    }
}
