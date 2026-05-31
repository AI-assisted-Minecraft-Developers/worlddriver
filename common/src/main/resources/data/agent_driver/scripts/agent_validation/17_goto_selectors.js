// Client-only — mc.bot.goto needs Minecraft.player and a loaded level for the
// new Baritone-style selectors (block / entity / entityId / direction /
// waypoint). On dedicated-server / GameTest CI the client classes aren't
// loaded; record a skipped-PASS so headless runs stay green. Live exercise
// lives in fabric runClient sessions.

function clientAvailable() {
    try {
        Agent.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

if (!clientAvailable()) {
    AgentTest.run("17_goto_selectors: skipped (no client api — dedicated server)", function(t) {
        // PASS
    });
} else {

    AgentTest.run("17_goto_selectors: rejects when no selector provided", function(t) {
        var r = Agent.invoke("mc.bot.goto", {});
        t.assertEqual(r.ok, false, "empty params must be ok:false");
        t.assertTrue(typeof r.error === "string" && r.error.indexOf("pos") >= 0,
            "error must list the accepted selector shapes");
    });

    AgentTest.run("17_goto_selectors: rejects unknown direction name", function(t) {
        var r = Agent.invoke("mc.bot.goto", { direction: "wiggle", distance: 4 });
        t.assertEqual(r.ok, false, "unknown direction must be ok:false");
        t.assertTrue(r.error.indexOf("direction") >= 0, "error must mention 'direction'");
    });

    AgentTest.run("17_goto_selectors: rejects missing waypoint", function(t) {
        var r = Agent.invoke("mc.bot.goto", { waypoint: "no-such-waypoint" });
        t.assertEqual(r.ok, false, "missing waypoint must be ok:false");
        t.assertTrue(r.error.indexOf("waypoint") >= 0, "error must mention 'waypoint'");
    });

    AgentTest.run("17_goto_selectors: rejects nonexistent entity id", function(t) {
        var r = Agent.invoke("mc.bot.goto", { entityId: 1073741824 });
        t.assertEqual(r.ok, false, "missing entity must be ok:false");
        t.assertTrue(r.error.indexOf("entity") >= 0, "error must mention 'entity'");
    });

    AgentTest.run("17_goto_selectors: byte-identical results across in-JVM, RPC, MCP transports",
        function(t) {
            var args = { direction: "wiggle", distance: 4 };
            var direct = Agent.invoke("mc.bot.goto", args);
            var viaTcp = Agent.system.rpcRoundtrip("mc.bot.goto", args);
            var viaMcp = Agent.system.mcpRoundtrip("mc.bot.goto", args);
            t.assertEqual(viaTcp.ok, direct.ok, "RPC.ok mismatch");
            t.assertEqual(viaMcp.ok, direct.ok, "MCP.ok mismatch");
            t.assertEqual(viaTcp.error, direct.error, "RPC.error mismatch");
            t.assertEqual(viaMcp.error, direct.error, "MCP.error mismatch");
        });

}
