// Phase D3 additions (Parkour3Diagonal Move gated under allowParkour4, and the tunnel target of
// clearArea). The behaviour needs a world; the setting and the request checks run everywhere.

ScriptTest.run("25_phase_d3: allowParkour4 still round-trips (gates parkour3d too)", function(t) {
    // Parkour3Diagonal shares the allowParkour4 gate — verify the toggle
    // still works after the new Move class is registered.
    var was = Driver.bot.readSettings(["allowParkour4"]);
    t.assertEqual(Driver.bot.patchSettings({ allowParkour4: true }).values[0].value, true, "allowParkour4 on");
    t.assertEqual(Driver.bot.patchSettings({ allowParkour4: false }).values[0].value, false, "allowParkour4 off");
    Driver.bot.patchSettings(was);
});

ScriptTest.run("25_phase_d3: an unknown tunnel direction is invalid_params", function(t) {
    // The tunnel is a clearArea target now; its direction is a closed enum.
    var e = null;
    try { Driver.bot.clearArea({ target: { kind: "tunnel", direction: "banana", distance: 5 } }); }
    catch (err) { e = err; }
    t.assertTrue(e !== null && e.code === "invalid_params", "must be invalid_params: " + e);
    t.assertEqual(e.issues[0].path, "/target/direction", "path");
});

ScriptTest.run("25_phase_d3: setting reads identical across in-JVM/RPC/MCP", function(t) {
    var args = { op: "read" };
    var direct = JSON.stringify(Driver.invoke("mc.bot.setting", args));
    t.assertEqual(JSON.stringify(Driver.system.rpcRoundtrip("mc.bot.setting", args)), direct, "RPC");
    t.assertEqual(JSON.stringify(Driver.system.mcpRoundtrip("mc.bot.setting", args)), direct, "MCP");
});
