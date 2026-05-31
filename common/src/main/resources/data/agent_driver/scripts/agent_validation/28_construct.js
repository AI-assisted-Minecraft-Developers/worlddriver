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
        var r = Agent.invoke("mc.bot.construct", {});
        t.assertEqual(r.ok, false, "missing mode → ok:false");
        t.assertTrue(String(r.error).indexOf("mode") >= 0, "error mentions mode: " + r.error);
    });

    AgentTest.run("28_construct: rejects unknown mode", function(t) {
        var r = Agent.invoke("mc.bot.construct", { mode: "elevator" });
        t.assertEqual(r.ok, false, "unknown mode → ok:false");
        t.assertTrue(String(r.error).indexOf("mode") >= 0, "error mentions mode: " + r.error);
    });

    AgentTest.run("28_construct: tower rejects missing height/targetY", function(t) {
        var r = Agent.invoke("mc.bot.construct", { mode: "tower" });
        t.assertEqual(r.ok, false, "tower without height/targetY → ok:false");
        t.assertTrue(String(r.error).indexOf("height") >= 0 || String(r.error).indexOf("targetY") >= 0,
            "error mentions height/targetY: " + r.error);
    });

    AgentTest.run("28_construct: tower rejects oversize height", function(t) {
        var r = Agent.invoke("mc.bot.construct", { mode: "tower", height: 9999 });
        t.assertEqual(r.ok, false, "9999-block tower → ok:false");
        t.assertTrue(String(r.error).indexOf("256") >= 0 || String(r.error).indexOf("too large") >= 0,
            "error mentions cap: " + r.error);
    });

    AgentTest.run("28_construct: bridge rejects missing distance", function(t) {
        var r = Agent.invoke("mc.bot.construct", { mode: "bridge", direction: "north" });
        t.assertEqual(r.ok, false, "bridge without distance → ok:false");
        t.assertTrue(String(r.error).indexOf("distance") >= 0,
            "error mentions distance: " + r.error);
    });

    AgentTest.run("28_construct: bridge rejects oversize distance", function(t) {
        var r = Agent.invoke("mc.bot.construct", {
            mode: "bridge", direction: "north", distance: 9999
        });
        t.assertEqual(r.ok, false, "9999-block bridge → ok:false");
        t.assertTrue(String(r.error).indexOf("64") >= 0 || String(r.error).indexOf("too large") >= 0,
            "error mentions cap: " + r.error);
    });

    AgentTest.run("28_construct: bridge rejects unknown direction", function(t) {
        var r = Agent.invoke("mc.bot.construct", {
            mode: "bridge", direction: "diagonalwise", distance: 4
        });
        // Either no-player rejection (title screen) or unknown-direction
        // rejection (in world). Both confirm dispatch.
        t.assertEqual(r.ok, false, "unknown direction → ok:false");
        t.assertTrue(String(r.error).indexOf("direction") >= 0
                  || String(r.error).indexOf("no player") >= 0,
            "error mentions direction or no-player: " + r.error);
    });

    AgentTest.run("28_construct: byte-identical schema-reject across transports", function(t) {
        // The mode-missing path is checked before onClient — deterministic.
        var args = { mode: "elevator" };
        var direct = Agent.invoke("mc.bot.construct", args);
        var viaTcp = Agent.system.rpcRoundtrip("mc.bot.construct", args);
        var viaMcp = Agent.system.mcpRoundtrip("mc.bot.construct", args);
        t.assertEqual(viaTcp.ok,    direct.ok,    "RPC.ok mismatch");
        t.assertEqual(viaMcp.ok,    direct.ok,    "MCP.ok mismatch");
        t.assertEqual(viaTcp.error, direct.error, "RPC.error mismatch");
        t.assertEqual(viaMcp.error, direct.error, "MCP.error mismatch");
    });

}
