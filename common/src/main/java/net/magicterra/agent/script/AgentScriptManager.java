package net.magicterra.agent.script;

import net.magicterra.agent.api.AgentApi;
import net.magicterra.agent.test.AgentTest;
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

        // Prelude: defines Agent global + invoke/invokeRpc + convenience accessors
        String prelude =
            "var console = {" +
            "  log:   function(m){ System.out.println('[js] ' + m); }," +
            "  error: function(m){ System.err.println('[js] ' + m); }" +
            "};" +
            "var Agent = {};" +
            "Agent.invoke = function(method, params) {" +
            "  var json = __api.invokeJson(method, JSON.stringify(params || {}));" +
            "  return JSON.parse(json);" +
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
            "Agent.system = {" +
            "  version:    function()        { return Agent.invoke('mc.system.version',    {}); }," +
            "  testOrigin: function()        { return Agent.invoke('mc.system.testOrigin', {}); }," +
            "  waitTicks:  function(n)       { return Agent.invoke('mc.system.waitTicks',  {ticks: n}); }," +
            "  rpcRoundtrip: function(m, p)  { return Agent.invokeRpc(m, p); }," +
            "  mcpRoundtrip: function(m, p)  { return Agent.invokeMcp(m, p); }" +
            "};" +
            "Agent.observe = {" +
            // area is sugar over mc.query q='blocks' — returns {blocks:[...]}
            // to match the legacy shape callers expect.
            "  area: function (p) {" +
            "    var q = { q:'blocks', filter:{ in_radius:(p && p.radius)|0 } };" +
            "    if (p && p.center) q.center = p.center;" +
            "    if (p && p.filter && p.filter.type) q.filter.type = p.filter.type;" +
            "    var rows = Agent.invoke('mc.query', q);" +
            "    return { blocks: rows || [] };" +
            "  }," +
            "  cursor:       function()      { return Agent.invoke('mc.observe.cursor', {}); }," +
            "  eventsSince:  function(c)     { return Agent.invoke('mc.observe.eventsSince', {cursor: c}); }" +
            "};" +
            "Agent.action = {" +
            // placeBlock is sugar over mc.action.placeMany with a single entry.
            "  placeBlock: function (p) {" +
            "    var args = { blocks: [{ pos: p.pos, type: p.type }] };" +
            "    if (p && p.returnEvents) args.returnEvents = true;" +
            "    return Agent.invoke('mc.action.placeMany', args);" +
            "  }," +
            "  runCommand:   function(c)     { return Agent.invoke('mc.action.runCommand', {cmd: c}); }" +
            "};" +
            "Agent.query = function(p)      { return Agent.invoke('mc.query', p); };" +
            // Read-only world inspection + snapshot/restore, so scripts can verify
            // their own edits (blockstate, light, BE NBT) without scratch-cell hacks.
            "Agent.world = {" +
            "  block:    function(p)        { return Agent.invoke('mc.world.block',    p); }," +
            "  snapshot: function(p)        { return Agent.invoke('mc.world.snapshot', p); }," +
            "  restore:  function(p)        { return Agent.invoke('mc.world.restore',  p); }" +
            "};";
        cx.evaluateString(scope, prelude, "<prelude>", 1, null);

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
