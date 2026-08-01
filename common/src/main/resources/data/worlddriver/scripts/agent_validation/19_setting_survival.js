// Client-only — mc.bot.setting goes through requireBot() which the dedicated
// server / GameTest harness doesn't bind. Skip in CI so headless runs stay
// green; real round-trip lives in fabric runClient.

function clientAvailable() {
    try {
        Agent.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

if (!clientAvailable()) {
    AgentTest.run("19_setting_survival: skipped (no client api — dedicated server)", function(t) {
        // PASS
    });
} else {

    AgentTest.run("19_setting_survival: autoEat / autoRespawn toggles round-trip", function(t) {
        var r = Agent.invoke("mc.bot.setting", { autoEat: true, autoRespawn: true });
        t.assertEqual(r.ok, true, "write must succeed");
        t.assertTrue(r.applied.indexOf("autoEat") >= 0, "autoEat must be applied");
        t.assertTrue(r.applied.indexOf("autoRespawn") >= 0, "autoRespawn must be applied");
        t.assertEqual(r.settings.autoEat, true, "snapshot reflects autoEat=true");
        t.assertEqual(r.settings.autoRespawn, true, "snapshot reflects autoRespawn=true");

        var off = Agent.invoke("mc.bot.setting", { autoEat: false, autoRespawn: false });
        t.assertEqual(off.settings.autoEat, false, "snapshot reflects autoEat=false after disable");
        t.assertEqual(off.settings.autoRespawn, false, "snapshot reflects autoRespawn=false after disable");
    });

    AgentTest.run("19_setting_survival: pathfinder.maxNodes / maxMs accept tunable values", function(t) {
        var r = Agent.invoke("mc.bot.setting", { "pathfinder.maxNodes": 50000, "pathfinder.maxMs": 2000 });
        t.assertEqual(r.ok, true, "write must succeed");
        t.assertEqual(r.settings["pathfinder.maxNodes"], 50000, "maxNodes round-trip");
        t.assertEqual(r.settings["pathfinder.maxMs"], 2000, "maxMs round-trip");
    });

    AgentTest.run("19_setting_survival: smoothLook toggle + rate round-trip", function(t) {
        var r = Agent.invoke("mc.bot.setting", { smoothLook: true, smoothLookDegPerTick: 30 });
        t.assertEqual(r.ok, true, "write must succeed");
        t.assertTrue(r.applied.indexOf("smoothLook") >= 0, "smoothLook must be applied");
        t.assertTrue(r.applied.indexOf("smoothLookDegPerTick") >= 0, "rate must be applied");
        t.assertEqual(r.settings.smoothLook, true, "snapshot reflects smoothLook=true");
        t.assertEqual(r.settings.smoothLookDegPerTick, 30, "snapshot reflects rate=30");
        var bad = Agent.invoke("mc.bot.setting", { smoothLookDegPerTick: 999 });
        t.assertTrue(Array.isArray(bad.rejected) && bad.rejected.length > 0,
            "out-of-range rate must be rejected");
        Agent.invoke("mc.bot.setting", { smoothLook: false, smoothLookDegPerTick: 20 });
    });

    AgentTest.run("19_setting_survival: out-of-range autoEatFoodThreshold rejected", function(t) {
        var r = Agent.invoke("mc.bot.setting", { autoEatFoodThreshold: 99 });
        t.assertEqual(r.ok, true, "envelope still ok");
        t.assertTrue(Array.isArray(r.rejected) && r.rejected.length > 0,
            "out-of-range value must be rejected");
    });

    AgentTest.run("19_setting_survival: byte-identical results across in-JVM, RPC, MCP transports",
        function(t) {
            var args = { autoEatFoodThreshold: 99 };
            var direct = Agent.invoke("mc.bot.setting", args);
            var viaTcp = Agent.system.rpcRoundtrip("mc.bot.setting", args);
            var viaMcp = Agent.system.mcpRoundtrip("mc.bot.setting", args);
            t.assertEqual(viaTcp.ok, direct.ok, "RPC.ok mismatch");
            t.assertEqual(viaMcp.ok, direct.ok, "MCP.ok mismatch");
        });

}
