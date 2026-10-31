// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test("22_phase_c", { scene: ["wd.validation", "wd.validationIntegrated"], isolated: true }, (t) =>
    t.step("wd.validation", {
        script: "22_phase_c",
        DriverApiError: DriverApiError,
        body: (ScriptTest) => {
            Driver.system.rpcRoundtrip = ScriptTest.rpcRoundtrip;
            Driver.system.mcpRoundtrip = ScriptTest.mcpRoundtrip;

            // Client-only — Phase C additions (parkour Move via PathFinder, autoSwim
            // toggle, status.lastPath stats) all need the bot impl + a level. On
            // dedicated-server / GameTest CI the bot impl isn't bound, so the script runs no checks.
            // Live exercise lives in fabric runClient.

            function clientAvailable() {
                try {
                    Driver.invoke("mc.client.screen.info", {});
                    return true;
                } catch (e) {
                    return false;
                }
            }

            if (clientAvailable()) {
                ScriptTest.run("22_phase_c: byte-identical results across in-JVM, RPC, MCP transports", function (t) {
                    var args = { op: "read", keys: ["autoSwim"] };
                    var direct = JSON.stringify(Driver.invoke("mc.bot.setting", args));
                    t.assertEqual(JSON.stringify(Driver.system.rpcRoundtrip("mc.bot.setting", args)), direct, "RPC");
                    t.assertEqual(JSON.stringify(Driver.system.mcpRoundtrip("mc.bot.setting", args)), direct, "MCP");
                });
            }
        },
    })
);
