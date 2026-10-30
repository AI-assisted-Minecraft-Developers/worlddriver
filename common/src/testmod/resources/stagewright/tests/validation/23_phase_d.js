// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test("23_phase_d", { scene: ["wd.validation", "wd.validationIntegrated"], isolated: true }, (t) =>
    t.step("wd.validation", {
        script: "23_phase_d",
        DriverApiError: DriverApiError,
        body: (ScriptTest) => {
            Driver.system.rpcRoundtrip = ScriptTest.rpcRoundtrip;
            Driver.system.mcpRoundtrip = ScriptTest.mcpRoundtrip;

            // mc.bot.farm: the box and crop checks answer with paths before any body is read, so they run
            // on every topology; starting the harvest needs this client's own player.

            function clientAvailable() {
                try {
                    Driver.invoke("mc.client.screen.info", {});
                    return true;
                } catch (e) {
                    return false;
                }
            }

            /** The DriverApiError a call throws, or null when it returned. */
            function errorOf(call) {
                try {
                    call();
                } catch (e) {
                    return e;
                }
                return null;
            }

            function paths(e) {
                return e === null
                    ? []
                    : e.issues
                          .map(function (i) {
                              return i.path;
                          })
                          .sort();
            }

            // 100×1×100 = 10000 cells, past the 4096 cap on the cells a search rescans.
            var OVERSIZE = { from: { x: 0, y: 64, z: 0 }, to: { x: 99, y: 64, z: 99 } };

            ScriptTest.run("23_phase_d: farm names both missing corners", function (t) {
                var e = errorOf(function () {
                    Driver.bot.farm({});
                });
                t.assertEqual(e.code, "invalid_params", "code");
                t.assertEqual(JSON.stringify(paths(e)), JSON.stringify(["/from", "/to"]), "paths");
                t.assertEqual(e.issues[0].code, "required", "issue code");
            });

            ScriptTest.run("23_phase_d: farm refuses an oversize box at /to, naming the cap", function (t) {
                var e = errorOf(function () {
                    Driver.bot.farm(OVERSIZE);
                });
                t.assertEqual(e.issues[0].path, "/to", "path");
                t.assertEqual(e.issues[0].code, "out_of_range", "issue code");
                t.assertTrue(
                    e.issues[0].message.indexOf("10000") >= 0 && e.issues[0].message.indexOf("4096") >= 0,
                    "names the cell count and the cap: " + e.issues[0].message,
                );
            });

            ScriptTest.run("23_phase_d: farm refuses a crop it does not know", function (t) {
                var e = errorOf(function () {
                    Driver.bot.farm({
                        from: { x: 0, y: 64, z: 0 },
                        to: { x: 4, y: 64, z: 4 },
                        crops: ["minecraft:not_a_crop"],
                    });
                });
                t.assertEqual(e.issues[0].path, "/crops/0", "path");
                t.assertEqual(e.issues[0].code, "invalid_value", "issue code");
            });

            ScriptTest.run("23_phase_d: farm's error is the same over in-JVM, RPC and MCP", function (t) {
                var direct = errorOf(function () {
                    Driver.invoke("mc.bot.farm", OVERSIZE);
                });
                var viaRpc = errorOf(function () {
                    Driver.system.rpcRoundtrip("mc.bot.farm", OVERSIZE);
                });
                var viaMcp = errorOf(function () {
                    Driver.system.mcpRoundtrip("mc.bot.farm", OVERSIZE);
                });
                [viaRpc, viaMcp].forEach(function (e, i) {
                    var via = i === 0 ? "RPC" : "MCP";
                    t.assertTrue(e !== null, via + " must throw");
                    t.assertEqual(e.code, direct.code, via + " code");
                    t.assertEqual(JSON.stringify(e.issues), JSON.stringify(direct.issues), via + " issues");
                });
            });

            ScriptTest.run("23_phase_d: a valid field starts an action, or self is unavailable", function (t) {
                var r = Driver.bot.farm({ from: { x: 0, y: 64, z: 0 }, to: { x: 4, y: 64, z: 4 } });
                if (!clientAvailable() || r.outcome === "refused") {
                    t.assertEqual(r.outcome, "refused", "outcome");
                    t.assertEqual(r.reason, "bot_unavailable", "only an unavailable body may refuse: " + r.message);
                    return;
                }
                t.assertEqual(r.outcome, "accepted", "outcome");
                t.assertEqual(r.action.bot, "self", "body");
                Driver.invoke("mc.bot.cancel", { process: "builder" });
            });
        },
    }),
);
