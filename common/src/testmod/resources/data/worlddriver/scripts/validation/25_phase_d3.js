// Client-only — Phase D3 additions (Parkour3Diagonal Move gated under
// allowParkour4, and the tunnel target of clearArea).
// Both observable only with a real client+world. Dedicated-server CI: skip.

function clientAvailable() {
    try {
        Driver.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

if (!clientAvailable()) {
    ScriptTest.run("25_phase_d3: skipped (no client api — dedicated server)", function(t) {
        // PASS
    });
} else {

    ScriptTest.run("25_phase_d3: allowParkour4 still round-trips (gates parkour3d too)",
        function(t) {
            // Parkour3Diagonal shares the allowParkour4 gate — verify the toggle
            // still works after the new Move class is registered.
            var on = Driver.invoke("mc.bot.setting", { allowParkour4: true });
            t.assertEqual(on.settings.allowParkour4, true, "allowParkour4 on");
            var off = Driver.invoke("mc.bot.setting", { allowParkour4: false });
            t.assertEqual(off.settings.allowParkour4, false, "allowParkour4 off");
        });

    ScriptTest.run("25_phase_d3: an unknown tunnel direction is invalid_params",
        function(t) {
            // The tunnel is a clearArea target now; its direction is a closed enum.
            var e = null;
            try { Driver.bot.clearArea({ target: { kind: "tunnel", direction: "banana", distance: 5 } }); }
            catch (err) { e = err; }
            t.assertTrue(e !== null && e.code === "invalid_params", "must be invalid_params: " + e);
            t.assertEqual(e.issues[0].path, "/target/direction", "path");
        });

    ScriptTest.run("25_phase_d3: setting reads identical across in-JVM/RPC/MCP",
        function(t) {
            var direct = Driver.invoke("mc.bot.setting", {});
            var viaTcp = Driver.system.rpcRoundtrip("mc.bot.setting", {});
            var viaMcp = Driver.system.mcpRoundtrip("mc.bot.setting", {});
            t.assertEqual(viaTcp.settings.allowParkour4, direct.settings.allowParkour4,
                "allowParkour4 mismatch RPC");
            t.assertEqual(viaMcp.settings.allowParkour4, direct.settings.allowParkour4,
                "allowParkour4 mismatch MCP");
        });

}
