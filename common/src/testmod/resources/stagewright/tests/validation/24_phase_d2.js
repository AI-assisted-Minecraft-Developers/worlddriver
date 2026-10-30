// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test("24_phase_d2", { scene: ["wd.validation", "wd.validationIntegrated"], isolated: true }, (t) =>
    t.step("wd.validation", {
        script: "24_phase_d2",
        DriverApiError: DriverApiError,
        body: (ScriptTest) => {
            Driver.system.rpcRoundtrip = ScriptTest.rpcRoundtrip;
            Driver.system.mcpRoundtrip = ScriptTest.mcpRoundtrip;

            // Phase D2 settings (allowParkour4 PathFinder gate, autoTool hotbar swap), through mc.bot.setting.
            // Their behaviour needs a world; the round-trips run everywhere, since the settings are this game's.

            /** The DriverApiError a call throws, or null when it returned. */
            function errorOf(call) {
                try {
                    call();
                } catch (e) {
                    return e;
                }
                return null;
            }

            function read(keys) {
                return Driver.bot.setting({ op: "read", keys: keys }).values;
            }
            function patch(values) {
                return Driver.bot.setting({ op: "patch", values: values }).values;
            }

            ScriptTest.run("24_phase_d2: allowParkour4 toggle round-trips", function (t) {
                var was = read(["allowParkour4"]);
                t.assertEqual(patch({ allowParkour4: true }).allowParkour4, true, "allowParkour4=true");
                t.assertEqual(patch({ allowParkour4: false }).allowParkour4, false, "allowParkour4=false");
                patch(was);
            });

            ScriptTest.run("24_phase_d2: autoTool toggle round-trips", function (t) {
                var was = read(["autoTool"]);
                t.assertEqual(patch({ autoTool: true }).autoTool, true, "autoTool=true");
                t.assertEqual(patch({ autoTool: false }).autoTool, false, "autoTool=false");
                patch(was);
            });

            ScriptTest.run("24_phase_d2: non-boolean for toggle leaves state unchanged", function (t) {
                var before = read(["autoTool"]).autoTool;
                var e = errorOf(function () {
                    patch({ autoTool: "yes" });
                });
                t.assertEqual(e.issues[0].path, "/values/autoTool", "path");
                t.assertEqual(e.issues[0].code, "type_mismatch", "a string for a boolean is a type mismatch");
                t.assertEqual(read(["autoTool"]).autoTool, before, "the refused write left autoTool as it was");
            });

            ScriptTest.run("24_phase_d2: byte-identical setting reads across in-JVM/RPC/MCP", function (t) {
                var args = { op: "read", keys: ["autoTool", "allowParkour4"] };
                var direct = JSON.stringify(Driver.invoke("mc.bot.setting", args));
                t.assertEqual(JSON.stringify(Driver.system.rpcRoundtrip("mc.bot.setting", args)), direct, "RPC");
                t.assertEqual(JSON.stringify(Driver.system.mcpRoundtrip("mc.bot.setting", args)), direct, "MCP");
            });
        },
    }),
);
