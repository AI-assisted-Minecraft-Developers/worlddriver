// Client-only — mc.bot.waypoint storage lives in the bot impl, which the
// dedicated server / GameTest harness doesn't bind. Record a skipped-PASS
// so headless runs stay green; the round-trip lives in fabric runClient.

function clientAvailable() {
    try {
        Agent.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

if (!clientAvailable()) {
    AgentTest.run("18_waypoint: skipped (no client api — dedicated server)", function(t) {
        // PASS
    });
} else {

    // Ensure clean slate.
    try { Agent.invoke("mc.bot.waypoint", { op: "clear" }); } catch (e) {}

    AgentTest.run("18_waypoint: save with explicit pos round-trips", function(t) {
        var pos = { x: 10, y: 64, z: 20 };
        var s = Agent.invoke("mc.bot.waypoint", { op: "save", name: "home", pos: pos });
        t.assertEqual(s.ok, true, "save must succeed");
        t.assertEqual(s.name, "home", "echoed name");
        t.assertEqual(s.pos.x, 10, "pos round-trips x");
        t.assertEqual(s.pos.y, 64, "pos round-trips y");
        t.assertEqual(s.pos.z, 20, "pos round-trips z");

        var g = Agent.invoke("mc.bot.waypoint", { op: "get", name: "home" });
        t.assertEqual(g.ok, true, "get must succeed");
        t.assertEqual(g.pos.x, 10, "get returns saved x");
    });

    AgentTest.run("18_waypoint: list reports stored entries", function(t) {
        var l = Agent.invoke("mc.bot.waypoint", { op: "list" });
        t.assertEqual(l.ok, true, "list must succeed");
        t.assertTrue(Array.isArray(l.waypoints), "waypoints must be array");
        t.assertTrue(l.count >= 1, "count must reflect stored entries");
    });

    AgentTest.run("18_waypoint: delete removes the entry", function(t) {
        Agent.invoke("mc.bot.waypoint", { op: "save", name: "tmp", pos: { x: 1, y: 2, z: 3 } });
        var d = Agent.invoke("mc.bot.waypoint", { op: "delete", name: "tmp" });
        t.assertEqual(d.ok, true, "delete must succeed");
        t.assertEqual(d.existed, true, "must report existed=true");
        var g = Agent.invoke("mc.bot.waypoint", { op: "get", name: "tmp" });
        t.assertEqual(g.ok, false, "get after delete must fail");
    });

    AgentTest.run("18_waypoint: save without name is rejected", function(t) {
        var r = Agent.invoke("mc.bot.waypoint", { op: "save" });
        t.assertEqual(r.ok, false, "missing name must be ok:false");
        t.assertTrue(r.error.indexOf("name") >= 0, "error must mention 'name'");
    });

    AgentTest.run("18_waypoint: unknown op is rejected", function(t) {
        // Route-layer schema validation rejects the enum violation before the tool
        // runs; the validator message echoes the offending value.
        var msg = null;
        try { Agent.invoke("mc.bot.waypoint", { op: "wiggle" }); } catch (e) { msg = String(e); }
        t.assertTrue(msg !== null && msg.indexOf("wiggle") >= 0,
            "unknown op must be rejected by schema validation echoing the bad op, got: " + msg);
    });

    AgentTest.run("18_waypoint: schema-reject surfaces identically across in-JVM, RPC, MCP transports",
        function(t) {
            // The validator runs inside AgentApi.route(), shared by all three
            // transports — each must surface the SAME core violation message
            // (each transport adds its own wrapper prefix, so we compare cores).
            var args = { op: "wiggle" };
            var core = "invalid params for mc.bot.waypoint:";
            function thrownMsg(fn) { try { fn(); return null; } catch (e) { return String(e); } }
            var direct = thrownMsg(function() { Agent.invoke("mc.bot.waypoint", args); });
            var viaTcp = thrownMsg(function() { Agent.system.rpcRoundtrip("mc.bot.waypoint", args); });
            var viaMcp = thrownMsg(function() { Agent.system.mcpRoundtrip("mc.bot.waypoint", args); });
            t.assertTrue(direct !== null && direct.indexOf(core) >= 0, "in-JVM must throw validator error, got: " + direct);
            t.assertTrue(viaTcp !== null && viaTcp.indexOf(core) >= 0, "RPC must surface the validator error, got: " + viaTcp);
            t.assertTrue(viaMcp !== null && viaMcp.indexOf(core) >= 0, "MCP must surface the validator error, got: " + viaMcp);
        });

}
