package net.magicterra.worlddriver.script;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ContextFactory;
import dev.latvian.mods.rhino.NativeJavaClass;
import dev.latvian.mods.rhino.ScriptableObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.test.ScriptTest;

/**
 * Tiny Rhino-backed script manager.
 *
 * <p>Canonical call form, used by all scripts: Driver.invoke(method, params) // in-JVM via ScriptCalls.invokeJson
 * Driver.invokeRpc(method, params) // TCP round-trip via RpcBridge.callJson
 *
 * <p>Both paths write the same Gson tree, so results are byte-identical on success.
 */
public final class ScriptManager {
    private final ApiRegistry api;

    private final Path scriptsDir;

    private final RpcBridge bridge; // may be null if RPC server isn't up

    private final McpBridge mcpBridge; // may be null if MCP server isn't up

    public ScriptManager(ApiRegistry api, Path scriptsDir, RpcBridge bridge) {
        this(api, scriptsDir, bridge, null);
    }

    public ScriptManager(ApiRegistry api, Path scriptsDir, RpcBridge bridge, McpBridge mcpBridge) {
        this.api = api;
        this.scriptsDir = scriptsDir;
        this.bridge = bridge;
        this.mcpBridge = mcpBridge;
    }

    public int loadAll() throws IOException {
        ScriptEvents.clear();
        ContextFactory factory = new ScriptContextFactory();
        Context cx = factory.enter();
        ScriptableObject scope = cx.initStandardObjects();
        ScriptEvents.install(factory, scope);

        ScriptableObject.putProperty(scope, "ScriptTest", new NativeJavaClass(cx, scope, ScriptTest.class), cx);
        ScriptableObject.putProperty(scope, "ScriptEvents", new NativeJavaClass(cx, scope, ScriptEvents.class), cx);
        if (bridge != null) {
            ScriptableObject.putProperty(scope, "__rpc", cx.javaToJS(bridge, scope), cx);
        }
        if (mcpBridge != null) {
            ScriptableObject.putProperty(scope, "__mcp", cx.javaToJS(mcpBridge, scope), cx);
        }

        // Prelude — the CANONICAL prelude.js (the SAME Agent surface the live
        // mc.script.eval scope loads), read from the classpath so the validation
        // harness can never drift from the runtime prelude again. task#92: the
        // hand-inlined copy that used to live here had gone STALE — it lacked
        // Driver.observe.player() and other wrappers prelude.js had grown since
        // (that hand-written sugar is gone; wrappers are generated now). On the dedicated
        // path every client-face script self-skips (no client api), so they were never
        // exercised and the drift stayed hidden; the first time the suite ran on
        // an integrated (client-hosted) topology those scripts took their REAL
        // branch, called the missing wrappers, and threw "… of undefined". Loading the
        // real prelude fixes the whole family at the source (single source of truth).
        ScriptPrelude.install(cx, scope, ScriptCalls.standalone(api));

        // Harness-only extras the runtime prelude has no reason to carry: the
        // RPC/MCP transport round-trip bridges the transport-parity checks call
        // (Driver.invokeRpc/invokeMcp + Driver.system.rpcRoundtrip/mcpRoundtrip)
        // and a console that prints to the
        // server log instead of prelude.js's in-scope __log buffer.
        String harnessExtras = "var console = {" + "  log:   function(m){ System.out.println('[js] ' + m); },"
                + "  error: function(m){ System.err.println('[js] ' + m); }"
                + "};"
                +
                // Same envelope as Driver.invoke, so a transport-parity check sees the same
                // DriverApiError (code, issues, execution) whichever way the call went.
                "function __viaBridge(bridge, method, params) {"
                + "  var reply = JSON.parse(String(bridge.callJson(method, params === undefined ? null : JSON.stringify(params))));"
                + "  if (reply.error) throw new DriverApiError(reply.error);"
                + "  return reply.result;"
                + "}"
                + "Driver.invokeRpc = function(method, params) {"
                + "  if (typeof __rpc === 'undefined') throw new Error('RPC bridge not installed');"
                + "  return __viaBridge(__rpc, method, params);"
                + "};"
                + "Driver.invokeMcp = function(method, params) {"
                + "  if (typeof __mcp === 'undefined') throw new Error('MCP bridge not installed');"
                + "  return __viaBridge(__mcp, method, params);"
                + "};"
                + "Driver.system.rpcRoundtrip = function(m, p) { return Driver.invokeRpc(m, p); };"
                + "Driver.system.mcpRoundtrip = function(m, p) { return Driver.invokeMcp(m, p); };";
        cx.evaluateString(scope, harnessExtras, "<harness-extras>", 1, null);

        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(scriptsDir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".js"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(files::add);
        }
        int loaded = 0;
        for (Path p : files) {
            String src = Files.readString(p);
            try {
                cx.evaluateString(scope, src, p.getFileName().toString(), 1, null);
                loaded++;
            } catch (Throwable e) {
                System.err.println("[script] FAILED " + p.getFileName() + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
        // Now that all scripts have registered their callbacks, signal attach.
        ScriptEvents.fireAttach();
        return loaded;
    }
}
