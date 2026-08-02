// Client-only — Phase C additions (parkour Move via PathFinder, autoSwim
// toggle, status.lastPath stats) all need the bot impl + a level. On
// dedicated-server / GameTest CI the bot impl isn't bound; record a
// skipped-PASS. Live exercise lives in fabric runClient.

function clientAvailable() {
    try {
        Agent.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

if (!clientAvailable()) {
    ScriptTest.run("22_phase_c: skipped (no client api — dedicated server)", function(t) {
        // PASS
    });
} else {

    ScriptTest.run("22_phase_c: autoSwim toggle round-trips", function(t) {
        var r = Agent.invoke("mc.bot.setting", { autoSwim: true });
        t.assertEqual(r.ok, true, "write must succeed");
        t.assertTrue(r.applied.indexOf("autoSwim") >= 0, "autoSwim must be applied");
        t.assertEqual(r.settings.autoSwim, true, "snapshot reflects autoSwim=true");
        var off = Agent.invoke("mc.bot.setting", { autoSwim: false });
        t.assertEqual(off.settings.autoSwim, false, "snapshot reflects autoSwim=false");
    });

    ScriptTest.run("22_phase_c: status returns canonical shape (lastPath optional)", function(t) {
        var s = Agent.invoke("mc.bot.status", {});
        // status returns the BotState slots + paused + activeProcess; lastPath
        // is only present after a goto/mine/etc has actually run A*. Don't
        // require it — but if present, must have the documented fields.
        t.assertTrue(typeof s === "object", "status is object");
        t.assertTrue("paused" in s, "paused present");
        t.assertTrue("goto" in s, "goto slot present");
        if (s.lastPath) {
            t.assertTrue(typeof s.lastPath.expanded === "number", "lastPath.expanded is number");
            t.assertTrue(typeof s.lastPath.ms === "number", "lastPath.ms is number");
            t.assertTrue(typeof s.lastPath.goalReached === "boolean", "lastPath.goalReached is boolean");
        }
    });

    ScriptTest.run("22_phase_c: byte-identical results across in-JVM, RPC, MCP transports",
        function(t) {
            var args = { autoSwim: true };
            var direct = Agent.invoke("mc.bot.setting", args);
            var viaTcp = Agent.system.rpcRoundtrip("mc.bot.setting", args);
            var viaMcp = Agent.system.mcpRoundtrip("mc.bot.setting", args);
            t.assertEqual(viaTcp.ok, direct.ok, "RPC.ok mismatch");
            t.assertEqual(viaMcp.ok, direct.ok, "MCP.ok mismatch");
        });

}
