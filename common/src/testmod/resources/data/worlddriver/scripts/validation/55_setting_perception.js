// The perception settings (dusk securing, hazard grid, deep water, scene radius, muted events) through
// mc.bot.setting. The settings are this game's, so this runs on every topology.

/** The DriverApiError a call throws, or null when it returned. */
function errorOf(call) {
    try { call(); } catch (e) { return e; }
    return null;
}

/** {key: value} from a reply's values list. */
function valuesOf(reply) {
    var out = {};
    for (var i = 0; i < reply.values.length; i++) out[reply.values[i].key] = reply.values[i].value;
    return out;
}

ScriptTest.run("55_setting_perception: autoSecureAtDusk toggle round-trip", function(t) {
    var was = Driver.bot.readSettings(["autoSecureAtDusk"]);
    t.assertEqual(valuesOf(Driver.bot.patchSettings({ autoSecureAtDusk: false })).autoSecureAtDusk, false, "off");
    t.assertEqual(valuesOf(Driver.bot.patchSettings({ autoSecureAtDusk: true })).autoSecureAtDusk, true, "on");
    Driver.bot.patchSettings(was);
});

ScriptTest.run("55_setting_perception: hazardGridRadius in-range round-trip and out-of-range refusal", function(t) {
    var was = Driver.bot.readSettings(["hazardGridRadius"]);
    t.assertEqual(valuesOf(Driver.bot.patchSettings({ hazardGridRadius: 16 })).hazardGridRadius, 16, "16 committed");
    var bad = errorOf(function() { Driver.bot.patchSettings({ hazardGridRadius: 99 }); });
    t.assertEqual(bad.issues[0].code, "out_of_range", "99 is refused");
    t.assertEqual(Driver.bot.readSettings(["hazardGridRadius"]).hazardGridRadius, 16, "the refusal wrote nothing");
    Driver.bot.patchSettings(was);
});

ScriptTest.run("55_setting_perception: deepWaterMax and sceneQueryMaxRadius round-trip", function(t) {
    var was = Driver.bot.readSettings(["deepWaterMax", "sceneQueryMaxRadius"]);
    var r = valuesOf(Driver.bot.patchSettings({ deepWaterMax: 3, sceneQueryMaxRadius: 24 }));
    t.assertEqual(r.deepWaterMax, 3, "deepWaterMax round-trips");
    t.assertEqual(r.sceneQueryMaxRadius, 24, "sceneQueryMaxRadius round-trips");
    Driver.bot.patchSettings(was);
});

ScriptTest.run("55_setting_perception: mutedEvents round-trip + clear", function(t) {
    var was = Driver.bot.readSettings(["mutedEvents"]);
    var m = valuesOf(Driver.bot.patchSettings({ mutedEvents: ["entity.death", "item.pickup"] })).mutedEvents;
    t.assertTrue(m.indexOf("entity.death") >= 0 && m.indexOf("item.pickup") >= 0, "both muted types round-trip");
    // Empty list un-mutes everything (the default: push all events)
    t.assertEqual(valuesOf(Driver.bot.patchSettings({ mutedEvents: [] })).mutedEvents.length, 0, "empty list clears all mutes");
    Driver.bot.patchSettings(was);
});
