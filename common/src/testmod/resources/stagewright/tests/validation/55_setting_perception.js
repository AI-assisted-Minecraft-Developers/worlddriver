// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test('55_setting_perception', { scene: ['wd.validation', 'wd.validationIntegrated'], isolated: true }, (t) => t.step('wd.validation', {
    script: '55_setting_perception', DriverApiError: DriverApiError, body: (ScriptTest) => {

// The perception settings (dusk securing, hazard grid, deep water, scene radius, muted events) through
// mc.bot.setting. The settings are this game's, so this runs on every topology.

/** The DriverApiError a call throws, or null when it returned. */
function errorOf(call) {
    try { call(); } catch (e) { return e; }
    return null;
}

function read(keys) { return Driver.bot.setting({ op: "read", keys: keys }).values; }
function patch(values) { return Driver.bot.setting({ op: "patch", values: values }).values; }

ScriptTest.run("55_setting_perception: autoSecureAtDusk toggle round-trip", function(t) {
    var was = read(["autoSecureAtDusk"]);
    t.assertEqual(patch({ autoSecureAtDusk: false }).autoSecureAtDusk, false, "off");
    t.assertEqual(patch({ autoSecureAtDusk: true }).autoSecureAtDusk, true, "on");
    patch(was);
});

ScriptTest.run("55_setting_perception: hazardGridRadius in-range round-trip and out-of-range refusal", function(t) {
    var was = read(["hazardGridRadius"]);
    t.assertEqual(patch({ hazardGridRadius: 16 }).hazardGridRadius, 16, "16 committed");
    var bad = errorOf(function() { patch({ hazardGridRadius: 99 }); });
    t.assertEqual(bad.issues[0].code, "out_of_range", "99 is refused");
    t.assertEqual(read(["hazardGridRadius"]).hazardGridRadius, 16, "the refusal wrote nothing");
    patch(was);
});

ScriptTest.run("55_setting_perception: deepWaterMax and sceneQueryMaxRadius round-trip", function(t) {
    var was = read(["deepWaterMax", "sceneQueryMaxRadius"]);
    var r = patch({ deepWaterMax: 3, sceneQueryMaxRadius: 24 });
    t.assertEqual(r.deepWaterMax, 3, "deepWaterMax round-trips");
    t.assertEqual(r.sceneQueryMaxRadius, 24, "sceneQueryMaxRadius round-trips");
    patch(was);
});

ScriptTest.run("55_setting_perception: mutedEvents round-trip + clear", function(t) {
    var was = read(["mutedEvents"]);
    var m = patch({ mutedEvents: ["entity.death", "item.pickup"] }).mutedEvents;
    t.assertTrue(m.indexOf("entity.death") >= 0 && m.indexOf("item.pickup") >= 0, "both muted types round-trip");
    // Empty list un-mutes everything (the default: push all events)
    t.assertEqual(patch({ mutedEvents: [] }).mutedEvents.length, 0, "empty list clears all mutes");
    patch(was);
});

}}));
