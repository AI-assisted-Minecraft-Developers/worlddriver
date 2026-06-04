// Client-only — mc.bot.setting goes through requireBot() which isn't bound on
// dedicated server / GameTest CI. Skip in headless mode; live round-trip lives
// in fabric runClient.

function clientAvailable() {
    try {
        Agent.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

if (!clientAvailable()) {
    AgentTest.run("55_setting_perception: skipped (no client api — dedicated server)", function(t) {
        // PASS
    });
} else {

    AgentTest.run("55_setting_perception: autoSecureAtDusk toggle round-trip", function(t) {
        // Toggle false, read back
        var r = Agent.invoke("mc.bot.setting", { autoSecureAtDusk: false });
        t.assertEqual(r.ok, true, "write must succeed");
        t.assertTrue(r.applied.indexOf("autoSecureAtDusk") >= 0, "must be in applied");
        t.assertEqual(r.settings.autoSecureAtDusk, false, "snapshot reflects autoSecureAtDusk=false");

        // Toggle back to default (true)
        var r2 = Agent.invoke("mc.bot.setting", { autoSecureAtDusk: true });
        t.assertEqual(r2.settings.autoSecureAtDusk, true, "snapshot reflects autoSecureAtDusk=true");
    });

    AgentTest.run("55_setting_perception: hazardGridRadius in-range round-trip and out-of-range clamp", function(t) {
        // In-range: set 16
        var r = Agent.invoke("mc.bot.setting", { hazardGridRadius: 16 });
        t.assertEqual(r.ok, true, "write must succeed");
        t.assertTrue(r.applied.indexOf("hazardGridRadius") >= 0, "must be in applied");
        t.assertEqual(r.settings.hazardGridRadius, 16, "snapshot reflects hazardGridRadius=16");

        // Out-of-range: 99 must be rejected
        var bad = Agent.invoke("mc.bot.setting", { hazardGridRadius: 99 });
        t.assertEqual(bad.ok, true, "envelope still ok");
        t.assertTrue(Array.isArray(bad.rejected) && bad.rejected.length > 0,
            "out-of-range hazardGridRadius must be rejected");

        // Restore default
        Agent.invoke("mc.bot.setting", { hazardGridRadius: 12 });
    });

    AgentTest.run("55_setting_perception: deepWaterMax and sceneQueryMaxRadius round-trip", function(t) {
        var r = Agent.invoke("mc.bot.setting", { deepWaterMax: 3, sceneQueryMaxRadius: 24 });
        t.assertEqual(r.ok, true, "write must succeed");
        t.assertEqual(r.settings.deepWaterMax, 3, "deepWaterMax round-trips");
        t.assertEqual(r.settings.sceneQueryMaxRadius, 24, "sceneQueryMaxRadius round-trips");

        // Restore defaults
        Agent.invoke("mc.bot.setting", { deepWaterMax: 2, sceneQueryMaxRadius: 32 });
    });

}
