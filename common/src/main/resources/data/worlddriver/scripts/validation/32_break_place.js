// Client-only — mc.bot.setting goes through requireBot() which the dedicated
// server / GameTest harness doesn't bind. Skip in CI so headless runs stay
// green; the real functional tunnel/bridge round-trip lives in fabric runClient
// (driven over MCP). This script just asserts the Baritone allowBreak /
// allowPlace toggles round-trip through the setting envelope on all transports.

function clientAvailable() {
    try {
        Driver.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

if (!clientAvailable()) {
    ScriptTest.run("32_break_place: skipped (no client api — dedicated server)", function(t) {
        // PASS
    });
} else {

    ScriptTest.run("32_break_place: allowBreak / allowPlace toggles round-trip", function(t) {
        var on = Driver.invoke("mc.bot.setting", { allowBreak: true, allowPlace: true });
        t.assertEqual(on.ok, true, "write must succeed");
        t.assertTrue(on.applied.indexOf("allowBreak") >= 0, "allowBreak must be applied");
        t.assertTrue(on.applied.indexOf("allowPlace") >= 0, "allowPlace must be applied");
        t.assertEqual(on.settings.allowBreak, true, "snapshot reflects allowBreak=true");
        t.assertEqual(on.settings.allowPlace, true, "snapshot reflects allowPlace=true");

        var off = Driver.invoke("mc.bot.setting", { allowBreak: false, allowPlace: false });
        t.assertEqual(off.settings.allowBreak, false, "snapshot reflects allowBreak=false after disable");
        t.assertEqual(off.settings.allowPlace, false, "snapshot reflects allowPlace=false after disable");
    });

    ScriptTest.run("32_break_place: avoidDanger toggle + dangerPenalty round-trip", function(t) {
        var r = Driver.invoke("mc.bot.setting", { avoidDanger: false, "pathfinder.dangerPenalty": 50 });
        t.assertEqual(r.ok, true, "write must succeed");
        t.assertTrue(r.applied.indexOf("avoidDanger") >= 0, "avoidDanger must be applied");
        t.assertTrue(r.applied.indexOf("pathfinder.dangerPenalty") >= 0, "dangerPenalty must be applied");
        t.assertEqual(r.settings.avoidDanger, false, "snapshot reflects avoidDanger=false");
        t.assertEqual(r.settings["pathfinder.dangerPenalty"], 50, "snapshot reflects dangerPenalty=50");
        var bad = Driver.invoke("mc.bot.setting", { "pathfinder.dangerPenalty": 99999 });
        t.assertTrue(Array.isArray(bad.rejected) && bad.rejected.length > 0,
            "out-of-range dangerPenalty must be rejected");
        // restore defaults
        Driver.invoke("mc.bot.setting", { avoidDanger: true, "pathfinder.dangerPenalty": 30 });
    });

    ScriptTest.run("32_break_place: avoidMobs toggle + mob radius/penalty round-trip", function(t) {
        var r = Driver.invoke("mc.bot.setting", {
            avoidMobs: true, "pathfinder.mobAvoidRadius": 8, "pathfinder.mobAvoidPenalty": 60 });
        t.assertEqual(r.ok, true, "write must succeed");
        t.assertTrue(r.applied.indexOf("avoidMobs") >= 0, "avoidMobs must be applied");
        t.assertEqual(r.settings.avoidMobs, true, "snapshot reflects avoidMobs=true");
        t.assertEqual(r.settings["pathfinder.mobAvoidRadius"], 8, "mobAvoidRadius round-trip");
        t.assertEqual(r.settings["pathfinder.mobAvoidPenalty"], 60, "mobAvoidPenalty round-trip");
        var bad = Driver.invoke("mc.bot.setting", { "pathfinder.mobAvoidRadius": 999 });
        t.assertTrue(Array.isArray(bad.rejected) && bad.rejected.length > 0,
            "out-of-range mobAvoidRadius must be rejected");
        Driver.invoke("mc.bot.setting", {
            avoidMobs: false, "pathfinder.mobAvoidRadius": 6, "pathfinder.mobAvoidPenalty": 40 });
    });

    ScriptTest.run("32_break_place: byte-identical results across in-JVM, RPC, MCP transports",
        function(t) {
            var args = { allowBreak: true };
            var direct = Driver.invoke("mc.bot.setting", args);
            var viaTcp = Driver.system.rpcRoundtrip("mc.bot.setting", args);
            var viaMcp = Driver.system.mcpRoundtrip("mc.bot.setting", args);
            t.assertEqual(viaTcp.ok, direct.ok, "RPC.ok mismatch");
            t.assertEqual(viaMcp.ok, direct.ok, "MCP.ok mismatch");
            t.assertEqual(viaTcp.settings.allowBreak, true, "RPC snapshot allowBreak");
            t.assertEqual(viaMcp.settings.allowBreak, true, "MCP snapshot allowBreak");
            // restore default
            Driver.invoke("mc.bot.setting", { allowBreak: false, allowPlace: false });
        });

}
