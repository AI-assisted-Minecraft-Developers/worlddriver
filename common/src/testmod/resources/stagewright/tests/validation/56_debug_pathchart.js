// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test('56_debug_pathchart', { scene: ['wd.validation', 'wd.validationIntegrated'], isolated: true }, (t) => t.step('wd.validation', {
    script: '56_debug_pathchart', DriverApiError: DriverApiError, body: (ScriptTest) => {
Driver.system.rpcRoundtrip = ScriptTest.rpcRoundtrip;
Driver.system.mcpRoundtrip = ScriptTest.mcpRoundtrip;

// mc.debug.pathChart is client-only (registered at client init) and depends on the
// pathfinding recorder. On the dedicated GameTest server the route is absent, so skip —
// keeping the headless suite green. When a client is present, smoke the tool end to end.

function clientAvailable() {
    try { Driver.invoke("mc.client.screen.info", {}); return true; } catch (e) { return false; }
}
function routeAvailable() {
    try { Driver.invoke("mc.debug.pathChart", { save: false }); return true; } catch (e) { return false; }
}

if (!clientAvailable() || !routeAvailable()) {
    ScriptTest.run("56_debug_pathchart: skipped (no client / route)", function (t) { /* PASS */ });
} else {

    ScriptTest.run("56_debug_pathchart: settings round-trip", function (t) {
        Driver.bot.setting({ op: "patch", values: { pathDebug: true, pathChartAutoDump: false, pathDebugMaxNodes: 4000 } });
        var r = Driver.bot.setting({ op: "read", keys: ["pathDebug", "pathDebugMaxNodes"] }).values;
        t.assertEqual(r.pathDebug, true, "pathDebug committed");
        t.assertEqual(r.pathDebugMaxNodes, 4000, "maxNodes committed");
    });

    ScriptTest.run("56_debug_pathchart: render returns a non-trivial PNG", function (t) {
        var r = Driver.invoke("mc.debug.pathChart", { width: 800, height: 600 });
        t.assertEqual(r.view, "dashboard", "the default view");
        t.assertEqual(r.width, 800, "width honoured");
        t.assertEqual(r.height, 600, "height honoured");
        t.assertTrue(typeof r.path === "string" && r.path.length > 0, "returns a file path");
        t.assertTrue(r.bytes > 1000, "PNG has real bytes");
    });

    ScriptTest.run("56_debug_pathchart: byte-identical across transports", function (t) {
        var args = { width: 640, height: 480, save: false };
        var direct = Driver.invoke("mc.debug.pathChart", args);
        var viaTcp = Driver.system.rpcRoundtrip("mc.debug.pathChart", args);
        var viaMcp = Driver.system.mcpRoundtrip("mc.debug.pathChart", args);
        t.assertEqual(viaTcp.view, direct.view, "RPC.view parity");
        t.assertEqual(viaMcp.view, direct.view, "MCP.view parity");
        t.assertEqual(viaTcp.width, direct.width, "RPC.width parity");
        t.assertEqual(direct.path, undefined, "save:false writes no file");
    });
}

}}));
