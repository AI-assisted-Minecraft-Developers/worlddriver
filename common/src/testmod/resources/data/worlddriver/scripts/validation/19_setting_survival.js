// mc.bot.setting round-trips: a patch commits its keys together and answers what they now hold; an
// out-of-range value is invalid_params at its own path and writes nothing. The settings are this
// game's, held by the client when there is one and by the server otherwise, so this runs everywhere.

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

ScriptTest.run("19_setting_survival: autoEat / autoRespawn toggles round-trip", function(t) {
    var was = Driver.bot.readSettings(["autoEat", "autoRespawn"]);
    var r = Driver.bot.patchSettings({ autoEat: true, autoRespawn: true });
    t.assertEqual(r.op, "patch", "op");
    t.assertEqual(typeof r.persisted, "boolean", "persisted says whether the file was written");
    t.assertEqual(JSON.stringify(valuesOf(r)), JSON.stringify({ autoEat: true, autoRespawn: true }), "values in the order sent");
    var off = valuesOf(Driver.bot.patchSettings({ autoEat: false, autoRespawn: false }));
    t.assertEqual(off.autoEat, false, "autoEat=false after disable");
    t.assertEqual(off.autoRespawn, false, "autoRespawn=false after disable");
    t.assertEqual(Driver.bot.readSettings(["autoEat"]).autoEat, false, "a fresh read agrees");
    Driver.bot.patchSettings(was);
});

ScriptTest.run("19_setting_survival: pathfinder.maxNodes / maxMs accept tunable values", function(t) {
    var was = Driver.bot.readSettings(["pathfinder.maxNodes", "pathfinder.maxMs"]);
    var r = valuesOf(Driver.bot.patchSettings({ "pathfinder.maxNodes": 50000, "pathfinder.maxMs": 2000 }));
    t.assertEqual(r["pathfinder.maxNodes"], 50000, "maxNodes round-trip");
    t.assertEqual(r["pathfinder.maxMs"], 2000, "maxMs round-trip");
    Driver.bot.patchSettings(was);
});

ScriptTest.run("19_setting_survival: smoothLook toggle + rate round-trip", function(t) {
    var was = Driver.bot.readSettings(["smoothLook", "smoothLookDegPerTick"]);
    var r = valuesOf(Driver.bot.patchSettings({ smoothLook: true, smoothLookDegPerTick: 30 }));
    t.assertEqual(r.smoothLook, true, "smoothLook=true");
    t.assertEqual(r.smoothLookDegPerTick, 30, "rate=30");
    var e = errorOf(function() { Driver.bot.patchSettings({ smoothLook: false, smoothLookDegPerTick: 999 }); });
    t.assertEqual(e.issues[0].path, "/updates/1/value", "the out-of-range rate is refused at its own path");
    t.assertEqual(e.issues[0].code, "out_of_range", "issue code");
    t.assertEqual(Driver.bot.readSettings(["smoothLook"]).smoothLook, true, "a refused patch writes none of its keys");
    Driver.bot.patchSettings(was);
});

ScriptTest.run("19_setting_survival: a control is applied, not patched", function(t) {
    var e = errorOf(function() { Driver.bot.patchSettings({ paused: true }); });
    t.assertEqual(e.issues[0].path, "/updates/0/key", "path");
    t.assertTrue(e.issues[0].message.indexOf("apply") >= 0, "names op apply: " + e.issues[0].message);
    var w = errorOf(function() { Driver.bot.readSettings(["debugFly"]); });
    t.assertEqual(w.issues[0].path, "/keys/0", "debugFly has nothing to read");
});

ScriptTest.run("19_setting_survival: the same refusal over in-JVM, RPC and MCP", function(t) {
    var args = { op: "patch", updates: [{ key: "autoEatFoodThreshold", value: 99 }] };
    var errs = [errorOf(function() { Driver.invoke("mc.bot.setting", args); }),
                errorOf(function() { Driver.system.rpcRoundtrip("mc.bot.setting", args); }),
                errorOf(function() { Driver.system.mcpRoundtrip("mc.bot.setting", args); })];
    for (var i = 0; i < errs.length; i++) {
        t.assertTrue(errs[i] !== null, "transport " + i + " must refuse");
        t.assertEqual(errs[i].issues[0].path, "/updates/0/value", "transport " + i + " path");
        t.assertEqual(errs[i].issues[0].code, "out_of_range", "transport " + i + " code");
    }
});
