// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test("17_goto_selectors", { scene: ["wd.validation", "wd.validationIntegrated"], isolated: true }, (t) =>
    t.step("wd.validation", {
        script: "17_goto_selectors",
        DriverApiError: DriverApiError,
        body: (ScriptTest) => {
            Driver.system.rpcRoundtrip = ScriptTest.rpcRoundtrip;
            Driver.system.mcpRoundtrip = ScriptTest.mcpRoundtrip;

            // mc.bot.goto's targets: a malformed one is invalid_params at its path on every topology, the same
            // over every transport; a well-formed one the world cannot satisfy (no such waypoint, no such
            // entity) is refused before anything starts. The refusals read the client's own player, so that
            // part is client-only.

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

            ScriptTest.run("17_goto_selectors: a walk without a target is a missing field", function (t) {
                var e = errorOf(function () {
                    Driver.bot.goto({});
                });
                t.assertTrue(e !== null, "must throw");
                t.assertEqual(e.code, "invalid_params", "code");
                t.assertEqual(e.issues[0].path, "/target", "path");
                t.assertEqual(e.issues[0].code, "required", "issue code");
            });

            ScriptTest.run("17_goto_selectors: an unknown direction is invalid at its path", function (t) {
                var e = errorOf(function () {
                    Driver.bot.goto({ target: { kind: "direction", direction: "wiggle", distance: 4 } });
                });
                t.assertEqual(e.issues[0].path, "/target/direction", "path");
                t.assertEqual(e.issues[0].code, "invalid_value", "issue code");
            });

            ScriptTest.run("17_goto_selectors: an unknown entity type is invalid at its path", function (t) {
                var e = errorOf(function () {
                    Driver.bot.goto({ target: { kind: "entityType", type: "minecraft:no_such_mob" } });
                });
                t.assertEqual(e.issues[0].path, "/target/type", "path");
            });

            ScriptTest.run("17_goto_selectors: the old top-level selectors are unknown fields", function (t) {
                var e = errorOf(function () {
                    Driver.bot.goto({ pos: { x: 0, y: 64, z: 0 } });
                });
                t.assertEqual(e.issues[0].path, "/pos", "path");
                t.assertEqual(e.issues[0].code, "unknown_field", "issue code");
            });

            ScriptTest.run("17_goto_selectors: the same error over in-JVM, RPC and MCP", function (t) {
                var args = { target: { kind: "direction", direction: "wiggle", distance: 4 } };
                var direct = errorOf(function () {
                    Driver.invoke("mc.bot.goto", args);
                });
                var viaRpc = errorOf(function () {
                    Driver.system.rpcRoundtrip("mc.bot.goto", args);
                });
                var viaMcp = errorOf(function () {
                    Driver.system.mcpRoundtrip("mc.bot.goto", args);
                });
                [viaRpc, viaMcp].forEach(function (e, i) {
                    var via = i === 0 ? "RPC" : "MCP";
                    t.assertTrue(e !== null, via + " must throw");
                    t.assertEqual(e.code, direct.code, via + " code");
                    t.assertEqual(JSON.stringify(e.issues), JSON.stringify(direct.issues), via + " issues");
                });
            });

            if (!clientAvailable()) {
                ScriptTest.run(
                    "17_goto_selectors: refusals skipped (no client player — dedicated server)",
                    function (t) {
                        var r = Driver.bot.goto({ target: { kind: "axis" } });
                        t.assertEqual(r.outcome, "refused", "outcome");
                        t.assertEqual(r.reason, "bot_unavailable", "reason");
                    }
                );
            } else {
                ScriptTest.run(
                    "17_goto_selectors: a missing waypoint is refused as an invalid world target",
                    function (t) {
                        var r = Driver.bot.goto({ target: { kind: "waypoint", name: "no-such-waypoint" } });
                        t.assertEqual(r.outcome, "refused", "outcome");
                        if (r.reason === "bot_unavailable") return;
                        t.assertEqual(r.reason, "invalid_world_target", "reason");
                        t.assertTrue(r.message.indexOf("waypoint") >= 0, "message names the waypoint: " + r.message);
                    }
                );

                ScriptTest.run(
                    "17_goto_selectors: a nonexistent entity id is refused as an invalid world target",
                    function (t) {
                        var r = Driver.bot.goto({ target: { kind: "entity", entityId: 1073741824 } });
                        t.assertEqual(r.outcome, "refused", "outcome");
                        if (r.reason === "bot_unavailable") return;
                        t.assertEqual(r.reason, "invalid_world_target", "reason");
                        t.assertTrue(r.message.indexOf("entity") >= 0, "message names the entity: " + r.message);
                    }
                );
            }
        },
    })
);
