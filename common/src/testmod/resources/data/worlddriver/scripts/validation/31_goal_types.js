// The Baritone-parity goals: axis, goalMode, heading (strict direction) and invert, and the
// pathfinder.axisHeight setting the axis goal reads. The shape checks and the setting need no player;
// starting a walk needs this client's bot, so that part is client-only.

function clientAvailable() {
    try {
        Driver.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

function errorOf(call) {
    try { call(); } catch (e) { return e; }
    return null;
}

ScriptTest.run("31_goal_types: a heading must be horizontal", function(t) {
    var e = errorOf(function() { Driver.bot.goto({ target: { kind: "heading", direction: "up" } }); });
    t.assertEqual(e.code, "invalid_params", "code");
    t.assertEqual(e.issues[0].path, "/target/direction", "path");
    t.assertTrue(e.issues[0].message.indexOf("horizontal") >= 0, "message: " + e.issues[0].message);
});

ScriptTest.run("31_goal_types: invert with nothing to invert is a missing target", function(t) {
    var e = errorOf(function() { Driver.bot.goto({ invert: true }); });
    t.assertEqual(e.issues[0].path, "/target", "path");
    t.assertEqual(e.issues[0].code, "required", "issue code");
});

ScriptTest.run("31_goal_types: the same error over in-JVM, RPC and MCP", function(t) {
    var args = { target: { kind: "heading", direction: "up" } };
    var direct = errorOf(function() { Driver.invoke("mc.bot.goto", args); });
    var viaRpc = errorOf(function() { Driver.system.rpcRoundtrip("mc.bot.goto", args); });
    var viaMcp = errorOf(function() { Driver.system.mcpRoundtrip("mc.bot.goto", args); });
    t.assertEqual(JSON.stringify(viaRpc.issues), JSON.stringify(direct.issues), "RPC issues");
    t.assertEqual(JSON.stringify(viaMcp.issues), JSON.stringify(direct.issues), "MCP issues");
});

ScriptTest.run("31_goal_types: pathfinder.axisHeight round-trips and rejects out-of-range", function(t) {
    var was = Driver.bot.setting({ op: "read", keys: ["pathfinder.axisHeight"] }).values;
    t.assertEqual(Driver.bot.setting({ op: "patch", values: { "pathfinder.axisHeight": 64 } }).values["pathfinder.axisHeight"],
        64, "axisHeight=64");
    var bad = errorOf(function() { Driver.bot.setting({ op: "patch", values: { "pathfinder.axisHeight": 9999 } }); });
    t.assertEqual(bad.issues[0].code, "out_of_range", "out-of-range axisHeight is refused");
    Driver.bot.setting({ op: "patch", values: was });
});

if (!clientAvailable()) {
    ScriptTest.run("31_goal_types: walks skipped (no client api — dedicated server)", function(t) {
        // PASS
    });
} else {

    ScriptTest.run("31_goal_types: an axis goal starts a walk", function(t) {
        var r = Driver.bot.goto({ target: { kind: "axis" } });
        if (r.outcome === "refused") { t.assertEqual(r.reason, "body_unavailable", "only an unavailable body refuses"); return; }
        t.assertEqual(r.action.state === "running" || r.action.state === "succeeded", true, "state " + r.action.state);
        Driver.invoke("mc.bot.cancel", {});
    });

    ScriptTest.run("31_goal_types: goalMode 'adjacent' is accepted for a positional target", function(t) {
        var r = Driver.bot.goto({ target: { kind: "position", pos: { x: 0, y: 64, z: 0 }, goalMode: "adjacent" } });
        if (r.outcome === "refused") { t.assertEqual(r.reason, "body_unavailable", "only an unavailable body refuses"); return; }
        t.assertEqual(r.outcome, "accepted", "outcome");
        Driver.invoke("mc.bot.cancel", {});
    });

    ScriptTest.run("31_goal_types: a heading starts a walk with no endpoint", function(t) {
        var r = Driver.bot.goto({ target: { kind: "heading", direction: "north" } });
        if (r.outcome === "refused") { t.assertEqual(r.reason, "body_unavailable", "only an unavailable body refuses"); return; }
        t.assertEqual(r.outcome, "accepted", "outcome");
        Driver.invoke("mc.bot.cancel", {});
    });
}
