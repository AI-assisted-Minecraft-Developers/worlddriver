// Client-only — the new Baritone-parity goals (axis / goalMode / strict /
// invert) and the pathfinder.axisHeight setting both go through requireBot(),
// which the dedicated-server / GameTest harness doesn't bind. Skip in CI so
// headless runs stay green; the real round-trip is exercised in fabric
// runClient. Mirrors 17_goto_selectors / 19_setting_survival.

function clientAvailable() {
    try {
        Agent.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

if (!clientAvailable()) {
    AgentTest.run("31_goal_types: skipped (no client api — dedicated server)", function(t) {
        // PASS
    });
} else {

    AgentTest.run("31_goal_types: pathfinder.axisHeight round-trips and rejects out-of-range", function(t) {
        var r = Agent.invoke("mc.bot.setting", { "pathfinder.axisHeight": 64 });
        t.assertEqual(r.ok, true, "write must succeed");
        t.assertTrue(r.applied.indexOf("pathfinder.axisHeight") >= 0, "axisHeight must be applied");
        t.assertEqual(r.settings["pathfinder.axisHeight"], 64, "snapshot reflects axisHeight=64");

        var bad = Agent.invoke("mc.bot.setting", { "pathfinder.axisHeight": 9999 });
        t.assertTrue(Array.isArray(bad.rejected) && bad.rejected.length > 0,
            "out-of-range axisHeight must be rejected");
    });

    AgentTest.run("31_goal_types: strict direction must be horizontal", function(t) {
        var r = Agent.invoke("mc.bot.goto", { direction: "up", strict: true });
        t.assertEqual(r.ok, false, "strict + vertical direction must be ok:false");
        t.assertTrue(r.error.indexOf("horizontal") >= 0, "error must mention 'horizontal'");
    });

    AgentTest.run("31_goal_types: invert with no base selector is rejected", function(t) {
        var r = Agent.invoke("mc.bot.goto", { invert: true });
        t.assertEqual(r.ok, false, "invert with nothing to invert must be ok:false");
        t.assertTrue(typeof r.error === "string" && r.error.indexOf("pos") >= 0,
            "error must list the accepted selector shapes");
    });

    AgentTest.run("31_goal_types: axis goal starts a pathing process", function(t) {
        var r = Agent.invoke("mc.bot.goto", { axis: true });
        t.assertEqual(r.ok, true, "axis goal must be accepted");
        t.assertEqual(r.started, true, "axis goal must start a process");
        Agent.invoke("mc.bot.cancel", {});
    });

    AgentTest.run("31_goal_types: goalMode 'adjacent' accepted for a positional target", function(t) {
        var r = Agent.invoke("mc.bot.goto", { pos: { x: 0, y: 64, z: 0 }, goalMode: "adjacent" });
        t.assertEqual(r.ok, true, "adjacent goalMode must be accepted");
        t.assertEqual(r.started, true, "must start a process");
        Agent.invoke("mc.bot.cancel", {});
    });

    AgentTest.run("31_goal_types: byte-identical results across in-JVM, RPC, MCP transports",
        function(t) {
            var args = { direction: "up", strict: true };
            var direct = Agent.invoke("mc.bot.goto", args);
            var viaTcp = Agent.system.rpcRoundtrip("mc.bot.goto", args);
            var viaMcp = Agent.system.mcpRoundtrip("mc.bot.goto", args);
            t.assertEqual(viaTcp.ok, direct.ok, "RPC.ok mismatch");
            t.assertEqual(viaMcp.ok, direct.ok, "MCP.ok mismatch");
            t.assertEqual(viaTcp.error, direct.error, "RPC.error mismatch");
            t.assertEqual(viaMcp.error, direct.error, "MCP.error mismatch");
        });

}
