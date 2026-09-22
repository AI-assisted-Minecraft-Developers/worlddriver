// Client-only — mc.bot.goto needs Minecraft.player and a loaded level for the
// new Baritone-style selectors (block / entity / entityId / direction /
// waypoint). On dedicated-server / GameTest CI the client classes aren't
// loaded; record a skipped-PASS so headless runs stay green. Live exercise
// lives in fabric runClient sessions.

function clientAvailable() {
    try {
        Driver.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

if (!clientAvailable()) {
    ScriptTest.run("17_goto_selectors: skipped (no client api — dedicated server)", function(t) {
        // PASS
    });
} else {

    ScriptTest.run("17_goto_selectors: rejects when no selector provided", function(t) {
        var r = Driver.invoke("mc.bot.goto", {});
        t.assertEqual(r.ok, false, "empty params must be ok:false");
        t.assertTrue(typeof r.error === "string" && r.error.indexOf("pos") >= 0,
            "error must list the accepted selector shapes");
    });

    ScriptTest.run("17_goto_selectors: rejects unknown direction name", function(t) {
        // Route-layer schema validation rejects the enum violation before the tool runs.
        var msg = null;
        try { Driver.invoke("mc.bot.goto", { direction: "wiggle", distance: 4 }); }
        catch (e) { msg = String(e); }
        t.assertTrue(msg !== null && msg.indexOf("direction") >= 0 && msg.indexOf("must be one of") >= 0,
            "unknown direction must be rejected by schema validation, got: " + msg);
    });

    ScriptTest.run("17_goto_selectors: rejects missing waypoint", function(t) {
        var r = Driver.invoke("mc.bot.goto", { waypoint: "no-such-waypoint" });
        t.assertEqual(r.ok, false, "missing waypoint must be ok:false");
        t.assertTrue(r.error.indexOf("waypoint") >= 0, "error must mention 'waypoint'");
    });

    ScriptTest.run("17_goto_selectors: rejects nonexistent entity id", function(t) {
        var r = Driver.invoke("mc.bot.goto", { entityId: 1073741824 });
        t.assertEqual(r.ok, false, "missing entity must be ok:false");
        t.assertTrue(r.error.indexOf("entity") >= 0, "error must mention 'entity'");
    });

    ScriptTest.run("17_goto_selectors: schema-reject surfaces identically across in-JVM, RPC, MCP transports",
        function(t) {
            // The validator runs inside DriverApi.route(), shared by all three
            // transports — each must surface the SAME core violation message
            // (each transport adds its own wrapper prefix, so we compare cores).
            var args = { direction: "wiggle", distance: 4 };
            var core = "invalid params for mc.bot.goto:";
            function thrownMsg(fn) { try { fn(); return null; } catch (e) { return String(e); } }
            var direct = thrownMsg(function() { Driver.invoke("mc.bot.goto", args); });
            var viaTcp = thrownMsg(function() { Driver.system.rpcRoundtrip("mc.bot.goto", args); });
            var viaMcp = thrownMsg(function() { Driver.system.mcpRoundtrip("mc.bot.goto", args); });
            t.assertTrue(direct !== null && direct.indexOf(core) >= 0, "in-JVM must throw validator error, got: " + direct);
            t.assertTrue(viaTcp !== null && viaTcp.indexOf(core) >= 0, "RPC must surface the validator error, got: " + viaTcp);
            t.assertTrue(viaMcp !== null && viaMcp.indexOf(core) >= 0, "MCP must surface the validator error, got: " + viaMcp);
        });

}
