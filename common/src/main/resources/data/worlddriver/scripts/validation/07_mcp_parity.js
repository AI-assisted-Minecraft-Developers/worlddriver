// Prove the MCP/HTTP path returns the same payload as in-JVM and NDJSON-TCP paths.
// Same DriverApi.route() underneath; the only difference is JSON-RPC wrapping +
// HTTP transport. If parity holds, an external MCP client (Claude, Inspector,
// custom agent) will see identical data to what scripts see in-process.

function jsonStable(v) {
    if (v === null || typeof v !== "object") return JSON.stringify(v);
    if (Array.isArray(v)) {
        var parts = [];
        for (var i = 0; i < v.length; i++) parts.push(jsonStable(v[i]));
        return "[" + parts.join(",") + "]";
    }
    var keys = Object.keys(v).sort();
    var pairs = [];
    for (var i = 0; i < keys.length; i++) {
        pairs.push(JSON.stringify(keys[i]) + ":" + jsonStable(v[keys[i]]));
    }
    return "{" + pairs.join(",") + "}";
}

ScriptTest.run("07_mcp_parity: query q='blocks' identical via in-JVM and MCP/HTTP", function(t) {
    var origin = Driver.system.testOrigin();
    var params = { q: "blocks", center: origin,
                   filter: { in_radius: 4, type: "minecraft:stone" } };
    var direct = Driver.invoke("mc.query", params);
    var viaMcp = Driver.system.mcpRoundtrip("mc.query", params);
    t.assertEqual(jsonStable(direct), jsonStable(viaMcp),
        "in-JVM and MCP block-scan results must be byte-identical");
});

ScriptTest.run("07_mcp_parity: system.version identical via in-JVM and MCP", function(t) {
    var direct = Driver.invoke("mc.system.version", {});
    var viaMcp = Driver.system.mcpRoundtrip("mc.system.version", {});
    delete direct.uptimeMs;
    delete viaMcp.uptimeMs;
    t.assertEqual(jsonStable(direct), jsonStable(viaMcp));
});

ScriptTest.run("07_mcp_parity: TCP and MCP paths agree on query q='blocks'", function(t) {
    var origin = Driver.system.testOrigin();
    var params = { q: "blocks", center: origin, filter: { in_radius: 4 } };
    var viaTcp = Driver.system.rpcRoundtrip("mc.query", params);
    var viaMcp = Driver.system.mcpRoundtrip("mc.query", params);
    t.assertEqual(jsonStable(viaTcp), jsonStable(viaMcp),
        "TCP and MCP paths must produce identical results");
});

ScriptTest.run("07_mcp_parity: unknown tool returns MCP error (not a crash)", function(t) {
    var threw = false;
    var msg = "";
    try {
        Driver.system.mcpRoundtrip("mc.bogus.does_not_exist", {});
    } catch (e) {
        threw = true;
        msg = String(e);
    }
    t.assertTrue(threw, "expected mcpRoundtrip to throw for unknown tool");
    t.assertTrue(msg.indexOf("unknown tool") >= 0 || msg.indexOf("isError") >= 0,
        "error message should mention the bad tool, got: " + msg);
});
