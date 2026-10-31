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

            ScriptTest.run("25_phase_d3: setting reads identical across in-JVM/RPC/MCP", function (t) {
                var args = { op: "read" };
                var direct = JSON.stringify(Driver.invoke("mc.bot.setting", args));
                t.assertEqual(JSON.stringify(Driver.system.rpcRoundtrip("mc.bot.setting", args)), direct, "RPC");
                t.assertEqual(JSON.stringify(Driver.system.mcpRoundtrip("mc.bot.setting", args)), direct, "MCP");
            });
        },
    })
);
