// Client-only — Phase C additions (parkour Move via PathFinder, autoSwim
// toggle, status.lastPath stats) all need the bot impl + a level. On
// dedicated-server / GameTest CI the bot impl isn't bound; record a
// skipped-PASS. Live exercise lives in fabric runClient.

function clientAvailable() {
    try {
        Driver.invoke("mc.client.screen.info", {});
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
        var was = Driver.bot.setting({ op: "read", keys: ["autoSwim"] }).values;
        t.assertEqual(Driver.bot.setting({ op: "patch", values: { autoSwim: true } }).values.autoSwim, true, "autoSwim=true");
        t.assertEqual(Driver.bot.setting({ op: "patch", values: { autoSwim: false } }).values.autoSwim, false, "autoSwim=false");
        Driver.bot.setting({ op: "patch", values: was });
    });

    ScriptTest.run("22_phase_c: status returns canonical shape (lastPath optional)", function(t) {
        var s = Driver.bot.status({}).status;
        // lastPath is only present after a goto/mine/etc has actually run A*. Don't
        // require it — but if present, must have the documented fields.
        t.assertEqual(s.body, "self", "self's status");
        t.assertTrue(typeof s.reflexes.paused === "boolean", "paused present");
        t.assertTrue("goto" in s.slots, "goto slot present");
        if (s.reflexes.lastPath) {
            t.assertTrue(typeof s.reflexes.lastPath.expanded === "number", "lastPath.expanded is number");
            t.assertTrue(typeof s.reflexes.lastPath.ms === "number", "lastPath.ms is number");
            t.assertTrue(typeof s.reflexes.lastPath.goalReached === "boolean", "lastPath.goalReached is boolean");
        }
    });

    ScriptTest.run("22_phase_c: byte-identical results across in-JVM, RPC, MCP transports",
        function(t) {
            var args = { op: "read", keys: ["autoSwim"] };
            var direct = JSON.stringify(Driver.invoke("mc.bot.setting", args));
            t.assertEqual(JSON.stringify(Driver.system.rpcRoundtrip("mc.bot.setting", args)), direct, "RPC");
            t.assertEqual(JSON.stringify(Driver.system.mcpRoundtrip("mc.bot.setting", args)), direct, "MCP");
        });

}
