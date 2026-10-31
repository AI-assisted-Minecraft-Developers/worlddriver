// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test("32_break_place", { scene: ["wd.validation", "wd.validationIntegrated"], isolated: true }, (t) =>
    t.step("wd.validation", {
        script: "32_break_place",
        DriverApiError: DriverApiError,
        body: (ScriptTest) => {
            Driver.system.rpcRoundtrip = ScriptTest.rpcRoundtrip;
            Driver.system.mcpRoundtrip = ScriptTest.mcpRoundtrip;

            // The Baritone allowBreak / allowPlace toggle, read back through mc.bot.setting on every transport. The
            // functional tunnel/bridge round-trip lives in the client scenes; the settings are this game's, so this
            // runs everywhere.

            function read(keys) {
                return Driver.bot.setting({ op: "read", keys: keys }).values;
            }
            function patch(values) {
                return Driver.bot.setting({ op: "patch", values: values }).values;
            }

            ScriptTest.run("32_break_place: byte-identical results across in-JVM, RPC, MCP transports", function (t) {
                var was = read(["allowBreak"]);
                var args = { op: "patch", values: { allowBreak: true } };
                var direct = Driver.invoke("mc.bot.setting", args);
                var viaTcp = Driver.system.rpcRoundtrip("mc.bot.setting", args);
                var viaMcp = Driver.system.mcpRoundtrip("mc.bot.setting", args);
                t.assertEqual(JSON.stringify(viaTcp.values), JSON.stringify(direct.values), "RPC values");
                t.assertEqual(JSON.stringify(viaMcp.values), JSON.stringify(direct.values), "MCP values");
                patch(was);
            });
        },
    })
);
