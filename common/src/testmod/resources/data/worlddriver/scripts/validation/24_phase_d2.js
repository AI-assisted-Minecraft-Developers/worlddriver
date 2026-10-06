// Phase D2 settings (allowParkour4 PathFinder gate, autoTool hotbar swap), through mc.bot.setting.
// Their behaviour needs a world; the round-trips run everywhere, since the settings are this game's.

/** The DriverApiError a call throws, or null when it returned. */
function errorOf(call) {
    try { call(); } catch (e) { return e; }
    return null;
}

/** Patch one key and answer what the reply says it now holds. */
function patchOne(key, value) {
    var p = {};
    p[key] = value;
    return Driver.bot.patchSettings(p).values[0].value;
}

ScriptTest.run("24_phase_d2: allowParkour4 toggle round-trips", function(t) {
    var was = Driver.bot.readSettings(["allowParkour4"]);
    t.assertEqual(patchOne("allowParkour4", true), true, "allowParkour4=true");
    t.assertEqual(patchOne("allowParkour4", false), false, "allowParkour4=false");
    Driver.bot.patchSettings(was);
});

ScriptTest.run("24_phase_d2: autoTool toggle round-trips", function(t) {
    var was = Driver.bot.readSettings(["autoTool"]);
    t.assertEqual(patchOne("autoTool", true), true, "autoTool=true");
    t.assertEqual(patchOne("autoTool", false), false, "autoTool=false");
    Driver.bot.patchSettings(was);
});

ScriptTest.run("24_phase_d2: non-boolean for toggle leaves state unchanged", function(t) {
    var before = Driver.bot.readSettings(["autoTool"]).autoTool;
    var e = errorOf(function() { Driver.bot.patchSettings({ autoTool: "yes" }); });
    t.assertEqual(e.issues[0].path, "/updates/0/value", "path");
    t.assertEqual(e.issues[0].code, "type_mismatch", "a string for a boolean is a type mismatch");
    t.assertEqual(Driver.bot.readSettings(["autoTool"]).autoTool, before, "the refused write left autoTool as it was");
});

ScriptTest.run("24_phase_d2: byte-identical setting reads across in-JVM/RPC/MCP", function(t) {
    var args = { op: "read", keys: ["autoTool", "allowParkour4"] };
    var direct = JSON.stringify(Driver.invoke("mc.bot.setting", args));
    t.assertEqual(JSON.stringify(Driver.system.rpcRoundtrip("mc.bot.setting", args)), direct, "RPC");
    t.assertEqual(JSON.stringify(Driver.system.mcpRoundtrip("mc.bot.setting", args)), direct, "MCP");
});
