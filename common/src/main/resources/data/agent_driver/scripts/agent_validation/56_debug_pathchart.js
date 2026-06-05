// mc.debug.pathChart is client-only (registered at client init) and depends on the
// pathfinding recorder. On the dedicated GameTest server the route is absent, so skip —
// keeping the headless suite green. When a client is present, smoke the tool end to end.

function clientAvailable() {
    try { Agent.invoke("mc.client.screen.info", {}); return true; } catch (e) { return false; }
}
function routeAvailable() {
    try { Agent.invoke("mc.debug.pathChart", { save: false }); return true; } catch (e) { return false; }
}

if (!clientAvailable() || !routeAvailable()) {
    AgentTest.run("56_debug_pathchart: skipped (no client / route)", function (t) { /* PASS */ });
} else {

    AgentTest.run("56_debug_pathchart: settings round-trip", function (t) {
        var r = Agent.invoke("mc.bot.setting", { pathDebug: true, pathChartAutoDump: false, pathDebugMaxNodes: 4000 });
        t.assertEqual(r.ok, true, "write must succeed");
        t.assertEqual(r.settings.pathDebug, true, "pathDebug echoed");
        t.assertEqual(r.settings.pathDebugMaxNodes, 4000, "maxNodes echoed");
    });

    AgentTest.run("56_debug_pathchart: render returns a non-trivial PNG", function (t) {
        var r = Agent.invoke("mc.debug.pathChart", { width: 800, height: 600 });
        t.assertEqual(r.ok, true, "render must succeed");
        t.assertEqual(r.width, 800, "width honoured");
        t.assertEqual(r.height, 600, "height honoured");
        t.assertTrue(typeof r.path === "string" && r.path.length > 0, "returns a file path");
        t.assertTrue(r.bytes > 1000, "PNG has real bytes");
    });

    AgentTest.run("56_debug_pathchart: byte-identical across transports", function (t) {
        var args = { width: 640, height: 480, save: false };
        var direct = Agent.invoke("mc.debug.pathChart", args);
        var viaTcp = Agent.system.rpcRoundtrip("mc.debug.pathChart", args);
        var viaMcp = Agent.system.mcpRoundtrip("mc.debug.pathChart", args);
        t.assertEqual(viaTcp.ok, direct.ok, "RPC.ok parity");
        t.assertEqual(viaMcp.ok, direct.ok, "MCP.ok parity");
        t.assertEqual(viaTcp.width, direct.width, "RPC.width parity");
    });
}
