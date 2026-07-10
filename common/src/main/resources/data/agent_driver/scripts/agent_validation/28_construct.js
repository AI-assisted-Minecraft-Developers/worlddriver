// Client-only — Phase D6: mc.bot.construct{mode:"tower"|"bridge"}. Happy
// path (actually pillaring/bridging) needs a live LocalPlayer + a block in
// hotbar + open sky/airspace; the GameTest harness can't reproduce that.
// Schema rejects fire even without a player and are deterministic.

function clientAvailable() {
    try {
        Agent.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

if (!clientAvailable()) {
    AgentTest.run("28_construct: skipped (no client api — dedicated server)", function(t) {
        // PASS
    });
} else {

    AgentTest.run("28_construct: rejects missing mode", function(t) {
        // Route-layer schema validation rejects the missing required key before the tool runs.
        var msg = null;
        try { Agent.invoke("mc.bot.construct", {}); } catch (e) { msg = String(e); }
        t.assertTrue(msg !== null && msg.indexOf("missing required 'mode'") >= 0,
            "missing mode must be rejected by schema validation, got: " + msg);
    });

    AgentTest.run("28_construct: rejects unknown mode", function(t) {
        // Route-layer schema validation rejects the enum violation before the tool runs.
        var msg = null;
        try { Agent.invoke("mc.bot.construct", { mode: "elevator" }); } catch (e) { msg = String(e); }
        t.assertTrue(msg !== null && msg.indexOf("mode") >= 0 && msg.indexOf("must be one of") >= 0,
            "unknown mode must be rejected by schema validation, got: " + msg);
    });

    AgentTest.run("28_construct: tower rejects missing height/targetY", function(t) {
        var r = Agent.invoke("mc.bot.construct", { mode: "tower" });
        t.assertEqual(r.ok, false, "tower without height/targetY → ok:false");
        t.assertTrue(String(r.error).indexOf("height") >= 0 || String(r.error).indexOf("targetY") >= 0,
            "error mentions height/targetY: " + r.error);
    });

    AgentTest.run("28_construct: tower rejects oversize height", function(t) {
        // Route-layer schema validation rejects the bounds violation (height ≤ 256) before the tool runs.
        var msg = null;
        try { Agent.invoke("mc.bot.construct", { mode: "tower", height: 9999 }); }
        catch (e) { msg = String(e); }
        t.assertTrue(msg !== null && msg.indexOf("256") >= 0,
            "9999-block tower must be rejected by schema validation naming the cap, got: " + msg);
    });

    AgentTest.run("28_construct: bridge rejects missing distance", function(t) {
        var r = Agent.invoke("mc.bot.construct", { mode: "bridge", direction: "north" });
        t.assertEqual(r.ok, false, "bridge without distance → ok:false");
        t.assertTrue(String(r.error).indexOf("distance") >= 0,
            "error mentions distance: " + r.error);
    });

    AgentTest.run("28_construct: bridge rejects oversize distance", function(t) {
        // Route-layer schema validation rejects the bounds violation (distance ≤ 64) before the tool runs.
        var msg = null;
        try {
            Agent.invoke("mc.bot.construct", { mode: "bridge", direction: "north", distance: 9999 });
        } catch (e) { msg = String(e); }
        t.assertTrue(msg !== null && msg.indexOf("64") >= 0,
            "9999-block bridge must be rejected by schema validation naming the cap, got: " + msg);
    });

    AgentTest.run("28_construct: bridge rejects unknown direction", function(t) {
        // Route-layer schema validation rejects the enum violation before the tool
        // runs — deterministic regardless of player state.
        var msg = null;
        try {
            Agent.invoke("mc.bot.construct", { mode: "bridge", direction: "diagonalwise", distance: 4 });
        } catch (e) { msg = String(e); }
        t.assertTrue(msg !== null && msg.indexOf("direction") >= 0 && msg.indexOf("must be one of") >= 0,
            "unknown direction must be rejected by schema validation, got: " + msg);
    });

    AgentTest.run("28_construct: schema-reject surfaces identically across in-JVM, RPC, MCP transports", function(t) {
        // The validator runs inside AgentApi.route(), shared by all three
        // transports — each must surface the SAME core violation message
        // (each transport adds its own wrapper prefix, so we compare cores).
        var args = { mode: "elevator" };
        var core = "invalid params for mc.bot.construct:";
        function thrownMsg(fn) { try { fn(); return null; } catch (e) { return String(e); } }
        var direct = thrownMsg(function() { Agent.invoke("mc.bot.construct", args); });
        var viaTcp = thrownMsg(function() { Agent.system.rpcRoundtrip("mc.bot.construct", args); });
        var viaMcp = thrownMsg(function() { Agent.system.mcpRoundtrip("mc.bot.construct", args); });
        t.assertTrue(direct !== null && direct.indexOf(core) >= 0, "in-JVM must throw validator error, got: " + direct);
        t.assertTrue(viaTcp !== null && viaTcp.indexOf(core) >= 0, "RPC must surface the validator error, got: " + viaTcp);
        t.assertTrue(viaMcp !== null && viaMcp.indexOf(core) >= 0, "MCP must surface the validator error, got: " + viaMcp);
    });

}
