// The Baritone allowBreak / allowPlace toggles and the danger and mob-avoidance knobs, round-tripped
// through mc.bot.setting on every transport. The functional tunnel/bridge round-trip lives in the
// client scenes; the settings are this game's, so this runs everywhere.

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

ScriptTest.run("32_break_place: allowBreak / allowPlace toggles round-trip", function(t) {
    var was = Driver.bot.readSettings(["allowBreak", "allowPlace"]);
    var on = valuesOf(Driver.bot.patchSettings({ allowBreak: true, allowPlace: true }));
    t.assertEqual(on.allowBreak, true, "allowBreak=true");
    t.assertEqual(on.allowPlace, true, "allowPlace=true");
    var off = valuesOf(Driver.bot.patchSettings({ allowBreak: false, allowPlace: false }));
    t.assertEqual(off.allowBreak, false, "allowBreak=false after disable");
    t.assertEqual(off.allowPlace, false, "allowPlace=false after disable");
    Driver.bot.patchSettings(was);
});

ScriptTest.run("32_break_place: avoidDanger toggle + dangerPenalty round-trip", function(t) {
    var was = Driver.bot.readSettings(["avoidDanger", "pathfinder.dangerPenalty"]);
    var r = valuesOf(Driver.bot.patchSettings({ avoidDanger: false, "pathfinder.dangerPenalty": 50 }));
    t.assertEqual(r.avoidDanger, false, "avoidDanger=false");
    t.assertEqual(r["pathfinder.dangerPenalty"], 50, "dangerPenalty=50");
    var bad = errorOf(function() { Driver.bot.patchSettings({ "pathfinder.dangerPenalty": 99999 }); });
    t.assertEqual(bad.issues[0].code, "out_of_range", "out-of-range dangerPenalty is refused");
    Driver.bot.patchSettings(was);
});

ScriptTest.run("32_break_place: avoidMobs toggle + mob radius/penalty round-trip", function(t) {
    var was = Driver.bot.readSettings(["avoidMobs", "pathfinder.mobAvoidRadius", "pathfinder.mobAvoidPenalty"]);
    var r = valuesOf(Driver.bot.patchSettings({
        avoidMobs: true, "pathfinder.mobAvoidRadius": 8, "pathfinder.mobAvoidPenalty": 60 }));
    t.assertEqual(r.avoidMobs, true, "avoidMobs=true");
    t.assertEqual(r["pathfinder.mobAvoidRadius"], 8, "mobAvoidRadius round-trip");
    t.assertEqual(r["pathfinder.mobAvoidPenalty"], 60, "mobAvoidPenalty round-trip");
    var bad = errorOf(function() { Driver.bot.patchSettings({ "pathfinder.mobAvoidRadius": 999 }); });
    t.assertEqual(bad.issues[0].code, "out_of_range", "out-of-range mobAvoidRadius is refused");
    Driver.bot.patchSettings(was);
});

ScriptTest.run("32_break_place: byte-identical results across in-JVM, RPC, MCP transports", function(t) {
    var was = Driver.bot.readSettings(["allowBreak"]);
    var args = { op: "patch", updates: [{ key: "allowBreak", value: true }] };
    var direct = Driver.invoke("mc.bot.setting", args);
    var viaTcp = Driver.system.rpcRoundtrip("mc.bot.setting", args);
    var viaMcp = Driver.system.mcpRoundtrip("mc.bot.setting", args);
    t.assertEqual(JSON.stringify(viaTcp.values), JSON.stringify(direct.values), "RPC values");
    t.assertEqual(JSON.stringify(viaMcp.values), JSON.stringify(direct.values), "MCP values");
    Driver.bot.patchSettings(was);
});
