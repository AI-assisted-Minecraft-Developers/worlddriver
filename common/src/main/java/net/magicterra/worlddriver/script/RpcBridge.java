package net.magicterra.worlddriver.script;

import net.magicterra.worlddriver.rpc.RpcClient;

import java.util.Map;

/**
 * Used as the body of Driver.system.rpcRoundtrip(method, params).
 * Each call opens a fresh WebSocket connection to ws://host:port/rpc, sends one
 * frame, reads one frame. Simple and deterministic for parity validation.
 */
public final class RpcBridge {
    private final String host;
    private final int port;

    public RpcBridge(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @SuppressWarnings("unchecked")
    public Object call(String method, Map<String, Object> params) {
        try (RpcClient c = new RpcClient(host, port)) {
            return c.call(method, params);
        } catch (Exception e) {
            throw new RuntimeException("rpcRoundtrip failed: " + e.getMessage(), e);
        }
    }

    /**
     * Used by JS Driver.invokeRpc — returns the raw JSON response string so the script
     * can JSON.parse it through the same path as the in-JVM result.
     */
    public String callJson(String method, String paramsJson) {
        try (RpcClient c = new RpcClient(host, port)) {
            return c.callJson(method, paramsJson);
        } catch (Exception e) {
            throw new RuntimeException("rpcRoundtripJson failed: " + e.getMessage(), e);
        }
    }
}
