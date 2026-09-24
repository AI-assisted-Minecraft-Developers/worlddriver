// Client-only — mc.bot.setting goes through requireBot() which isn't bound on
// dedicated server / GameTest CI. Skip in headless mode; live round-trip lives
// in fabric runClient.

function clientAvailable() {
    try {
        Driver.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

if (!clientAvailable()) {
    ScriptTest.run("55_setting_perception: skipped (no client api — dedicated server)", function(t) {
        // PASS
    });
} else {

    ScriptTest.run("55_setting_perception: autoSecureAtDusk toggle round-trip", function(t) {
        // Toggle false, read back
        var r = Driver.invoke("mc.bot.setting", { autoSecureAtDusk: false });
        t.assertEqual(r.ok, true, "write must succeed");
        t.assertTrue(r.applied.indexOf("autoSecureAtDusk") >= 0, "must be in applied");
        t.assertEqual(r.settings.autoSecureAtDusk, false, "snapshot reflects autoSecureAtDusk=false");

        // Toggle on, read back
        var r2 = Driver.invoke("mc.bot.setting", { autoSecureAtDusk: true });
        t.assertEqual(r2.settings.autoSecureAtDusk, true, "snapshot reflects autoSecureAtDusk=true");

        // Restore default (OFF — the "dig three, fill one" bunker is Agent-invoked by default; auto-dig only
        // when explicitly enabled, and it pushes duskSecure.triggered when it fires)
        Driver.invoke("mc.bot.setting", { autoSecureAtDusk: false });
    });

    ScriptTest.run("55_setting_perception: hazardGridRadius in-range round-trip and out-of-range clamp", function(t) {
        // In-range: set 16
        var r = Driver.invoke("mc.bot.setting", { hazardGridRadius: 16 });
        t.assertEqual(r.ok, true, "write must succeed");
        t.assertTrue(r.applied.indexOf("hazardGridRadius") >= 0, "must be in applied");
        t.assertEqual(r.settings.hazardGridRadius, 16, "snapshot reflects hazardGridRadius=16");

        // Out-of-range: 99 must be rejected
        var bad = Driver.invoke("mc.bot.setting", { hazardGridRadius: 99 });
        t.assertEqual(bad.ok, true, "envelope still ok");
        t.assertTrue(Array.isArray(bad.rejected) && bad.rejected.length > 0,
            "out-of-range hazardGridRadius must be rejected");

        // Restore default
        Driver.invoke("mc.bot.setting", { hazardGridRadius: 12 });
    });

    ScriptTest.run("55_setting_perception: deepWaterMax and sceneQueryMaxRadius round-trip", function(t) {
        var r = Driver.invoke("mc.bot.setting", { deepWaterMax: 3, sceneQueryMaxRadius: 24 });
        t.assertEqual(r.ok, true, "write must succeed");
        t.assertEqual(r.settings.deepWaterMax, 3, "deepWaterMax round-trips");
        t.assertEqual(r.settings.sceneQueryMaxRadius, 24, "sceneQueryMaxRadius round-trips");

        // Restore defaults
        Driver.invoke("mc.bot.setting", { deepWaterMax: 2, sceneQueryMaxRadius: 32 });
    });

    ScriptTest.run("55_setting_perception: mutedEvents round-trip + clear", function(t) {
        // Mute two event types from the push channel
        var r = Driver.invoke("mc.bot.setting", { mutedEvents: ["entity.death", "item.pickup"] });
        t.assertEqual(r.ok, true, "write must succeed");
        t.assertTrue(r.applied.indexOf("mutedEvents") >= 0, "mutedEvents must be in applied");
        var m = r.settings.mutedEvents;
        t.assertTrue(m.indexOf("entity.death") >= 0 && m.indexOf("item.pickup") >= 0,
            "both muted types round-trip in the snapshot");

        // Empty list un-mutes everything (the default: push all events)
        var r2 = Driver.invoke("mc.bot.setting", { mutedEvents: [] });
        t.assertEqual(r2.settings.mutedEvents.length, 0, "empty list clears all mutes");
    });

}
