// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test("25_phase_d3", { scene: ["wd.validation", "wd.validationIntegrated"], isolated: true }, (t) =>
    t.step("wd.validation", {
        script: "25_phase_d3",
        DriverApiError: DriverApiError,
        body: (ScriptTest) => {
            Driver.system.rpcRoundtrip = ScriptTest.rpcRoundtrip;
            Driver.system.mcpRoundtrip = ScriptTest.mcpRoundtrip;

            // Phase D3 additions (Parkour3Diagonal Move gated under allowParkour4, and the tunnel target of
            // clearArea). The behaviour needs a world; the setting and the request checks run everywhere.

            ScriptTest.run("25_phase_d3: allowParkour4 still round-trips (gates parkour3d too)", function (t) {
                // Parkour3Diagonal shares the allowParkour4 gate — verify the toggle
                // still works after the new Move class is registered.
                var was = Driver.bot.setting({ op: "read", keys: ["allowParkour4"] }).values;
                t.assertEqual(
                    Driver.bot.setting({ op: "patch", values: { allowParkour4: true } }).values.allowParkour4,
                    true,
                    "allowParkour4 on"
                );
                t.assertEqual(
                    Driver.bot.setting({ op: "patch", values: { allowParkour4: false } }).values.allowParkour4,
                    false,
                    "allowParkour4 off"
                );
                Driver.bot.setting({ op: "patch", values: was });
            });

            ScriptTest.run("25_phase_d3: an unknown tunnel direction is invalid_params", function (t) {
                // The tunnel is a clearArea target now; its direction is a closed enum.
                var e = null;
                try {
                    Driver.bot.clearArea({ target: { kind: "tunnel", direction: "banana", distance: 5 } });
                } catch (err) {
                    e = err;
                }
                t.assertTrue(e !== null && e.code === "invalid_params", "must be invalid_params: " + e);
                t.assertEqual(e.issues[0].path, "/target/direction", "path");
            });

            ScriptTest.run("25_phase_d3: setting reads identical across in-JVM/RPC/MCP", function (t) {
                var args = { op: "read" };
                var direct = JSON.stringify(Driver.invoke("mc.bot.setting", args));
                t.assertEqual(JSON.stringify(Driver.system.rpcRoundtrip("mc.bot.setting", args)), direct, "RPC");
                t.assertEqual(JSON.stringify(Driver.system.mcpRoundtrip("mc.bot.setting", args)), direct, "MCP");
            });
        },
    })
);
