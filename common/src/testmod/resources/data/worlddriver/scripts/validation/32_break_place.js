// The Baritone allowBreak / allowPlace toggles and the danger and mob-avoidance knobs, round-tripped
// through mc.bot.setting on every transport. The functional tunnel/bridge round-trip lives in the
// client scenes; the settings are this game's, so this runs everywhere.

/** The DriverApiError a call throws, or null when it returned. */
function errorOf(call) {
    try { call(); } catch (e) { return e; }
    return null;
}

function read(keys) { return Driver.bot.setting({ op: "read", keys: keys }).values; }
function patch(values) { return Driver.bot.setting({ op: "patch", values: values }).values; }

ScriptTest.run("32_break_place: allowBreak / allowPlace toggles round-trip", function(t) {
    var was = read(["allowBreak", "allowPlace"]);
    var on = patch({ allowBreak: true, allowPlace: true });
    t.assertEqual(on.allowBreak, true, "allowBreak=true");
    t.assertEqual(on.allowPlace, true, "allowPlace=true");
    var off = patch({ allowBreak: false, allowPlace: false });
    t.assertEqual(off.allowBreak, false, "allowBreak=false after disable");
    t.assertEqual(off.allowPlace, false, "allowPlace=false after disable");
    patch(was);
});

ScriptTest.run("32_break_place: avoidDanger toggle + dangerPenalty round-trip", function(t) {
    var was = read(["avoidDanger", "pathfinder.dangerPenalty"]);
    var r = patch({ avoidDanger: false, "pathfinder.dangerPenalty": 50 });
    t.assertEqual(r.avoidDanger, false, "avoidDanger=false");
    t.assertEqual(r["pathfinder.dangerPenalty"], 50, "dangerPenalty=50");
    var bad = errorOf(function() { patch({ "pathfinder.dangerPenalty": 99999 }); });
    t.assertEqual(bad.issues[0].code, "out_of_range", "out-of-range dangerPenalty is refused");
    patch(was);
});

ScriptTest.run("32_break_place: avoidMobs toggle + mob radius/penalty round-trip", function(t) {
    var was = read(["avoidMobs", "pathfinder.mobAvoidRadius", "pathfinder.mobAvoidPenalty"]);
    var r = patch({ avoidMobs: true, "pathfinder.mobAvoidRadius": 8, "pathfinder.mobAvoidPenalty": 60 });
    t.assertEqual(r.avoidMobs, true, "avoidMobs=true");
    t.assertEqual(r["pathfinder.mobAvoidRadius"], 8, "mobAvoidRadius round-trip");
    t.assertEqual(r["pathfinder.mobAvoidPenalty"], 60, "mobAvoidPenalty round-trip");
    var bad = errorOf(function() { patch({ "pathfinder.mobAvoidRadius": 999 }); });
    t.assertEqual(bad.issues[0].code, "out_of_range", "out-of-range mobAvoidRadius is refused");
    patch(was);
});

ScriptTest.run("32_break_place: byte-identical results across in-JVM, RPC, MCP transports", function(t) {
    var was = read(["allowBreak"]);
    var args = { op: "patch", values: { allowBreak: true } };
    var direct = Driver.invoke("mc.bot.setting", args);
    var viaTcp = Driver.system.rpcRoundtrip("mc.bot.setting", args);
    var viaMcp = Driver.system.mcpRoundtrip("mc.bot.setting", args);
    t.assertEqual(JSON.stringify(viaTcp.values), JSON.stringify(direct.values), "RPC values");
    t.assertEqual(JSON.stringify(viaMcp.values), JSON.stringify(direct.values), "MCP values");
    patch(was);
});
