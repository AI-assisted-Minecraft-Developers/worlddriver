package net.magicterra.worlddriver.script;

import net.magicterra.worlddriver.api.AgentApi;
import net.magicterra.worlddriver.test.AgentTest;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ContextFactory;
import dev.latvian.mods.rhino.NativeJavaClass;
import dev.latvian.mods.rhino.ScriptableObject;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Tiny Rhino-backed script manager.
 *
 * Canonical call form, used by all scripts:
 *   Agent.invoke(method, params)     // in-JVM via AgentApi.invokeJson
 *   Agent.invokeRpc(method, params)  // TCP round-trip via RpcBridge.callJson
 *
 * Both paths use the same JsonCodec, so results are byte-identical on success.
 */
public final class AgentScriptManager {
    private final AgentApi api;
    private final Path scriptsDir;
    private final RpcBridge bridge;     // may be null if RPC server isn't up
    private final McpBridge mcpBridge;  // may be null if MCP server isn't up

    public AgentScriptManager(AgentApi api, Path scriptsDir, RpcBridge bridge) {
        this(api, scriptsDir, bridge, null);
    }

    public AgentScriptManager(AgentApi api, Path scriptsDir, RpcBridge bridge, McpBridge mcpBridge) {
        this.api = api;
        this.scriptsDir = scriptsDir;
        this.bridge = bridge;
        this.mcpBridge = mcpBridge;
    }

    public int loadAll() throws IOException {
        AgentEvents.clear();
        ContextFactory factory = new AgentContextFactory();
        Context cx = factory.enter();
        ScriptableObject scope = cx.initStandardObjects();
        AgentEvents.install(factory, scope);

        ScriptableObject.putProperty(scope, "__api", cx.javaToJS(api, scope), cx);
        ScriptableObject.putProperty(scope, "AgentTest",
                new NativeJavaClass(cx, scope, AgentTest.class), cx);
        ScriptableObject.putProperty(scope, "AgentEvents",
                new NativeJavaClass(cx, scope, AgentEvents.class), cx);
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
        // Agent.observe.player() and the entire Agent.bot.* sugar (tunnel/…) that
        // prelude.js grew over time. On the dedicated GameTest path every
        // client-face script self-skips (no client api), so the sugar was never
        // exercised and the drift stayed hidden; the first time the suite ran on
        // an integrated (client-hosted) topology those scripts took their REAL
        // branch, called the missing sugar, and threw "… of undefined". Loading the
        // real prelude fixes the whole family at the source (single source of truth).
        String canonical;
        try (java.io.InputStream in = AgentScriptManager.class.getResourceAsStream(
                "/data/worlddriver/scripts/prelude.js")) {
            if (in == null) throw new IOException("prelude.js missing from classpath");
            canonical = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        cx.evaluateString(scope, canonical, "prelude.js", 1, null);

        // Harness-only extras the runtime prelude has no reason to carry: the
        // RPC/MCP transport round-trip bridges the transport-parity checks call
        // (Agent.invokeRpc/invokeMcp + Agent.system.rpcRoundtrip/mcpRoundtrip),
        // Agent.world.* read/snapshot/restore, and a console that prints to the
        // server log instead of prelude.js's in-scope __log buffer.
        String harnessExtras =
            "var console = {" +
            "  log:   function(m){ System.out.println('[js] ' + m); }," +
            "  error: function(m){ System.err.println('[js] ' + m); }" +
            "};" +
            "Agent.invokeRpc = function(method, params) {" +
            "  if (typeof __rpc === 'undefined') throw 'RPC bridge not installed';" +
            "  var json = __rpc.callJson(method, JSON.stringify(params || {}));" +
            "  return JSON.parse(json);" +
            "};" +
            "Agent.invokeMcp = function(method, params) {" +
            "  if (typeof __mcp === 'undefined') throw 'MCP bridge not installed';" +
            "  var json = __mcp.callJson(method, JSON.stringify(params || {}));" +
            "  return JSON.parse(json);" +
            "};" +
            "Agent.system.rpcRoundtrip = function(m, p) { return Agent.invokeRpc(m, p); };" +
            "Agent.system.mcpRoundtrip = function(m, p) { return Agent.invokeMcp(m, p); };" +
            "Agent.world = {" +
            "  block:    function(p) { return Agent.invoke('mc.world.block',    p); }," +
            "  snapshot: function(p) { return Agent.invoke('mc.world.snapshot', p); }," +
            "  restore:  function(p) { return Agent.invoke('mc.world.restore',  p); }" +
            "};";
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
        AgentEvents.fireAttach();
        return loaded;
    }
}
