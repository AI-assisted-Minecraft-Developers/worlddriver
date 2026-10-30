// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test("16_attack_entity", { scene: ["wd.validation", "wd.validationIntegrated"], isolated: true }, (t) =>
    t.step("wd.validation", {
        script: "16_attack_entity",
        DriverApiError: DriverApiError,
        body: (ScriptTest) => {
            Driver.system.rpcRoundtrip = ScriptTest.rpcRoundtrip;
            Driver.system.mcpRoundtrip = ScriptTest.mcpRoundtrip;

            // mc.bot.attackEntity: one left click on an entity. The request checks read no body and run on every
            // topology; a click needs this client's own player, so a dedicated server only sees self refused.

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

            ScriptTest.run("16_attack_entity: entityId is required", function (t) {
                var e = errorOf(function () {
                    Driver.invoke("mc.bot.attackEntity", {});
                });
                t.assertEqual(e.issues[0].path, "/entityId", "path");
                t.assertEqual(e.issues[0].code, "required", "issue code");
            });

            ScriptTest.run("16_attack_entity: entityId is an integer", function (t) {
                var e = errorOf(function () {
                    Driver.invoke("mc.bot.attackEntity", { entityId: "abc" });
                });
                t.assertEqual(e.issues[0].path, "/entityId", "path");
            });

            ScriptTest.run("16_attack_entity: the same answer over in-JVM, RPC and MCP", function (t) {
                var args = { entityId: 1073741824 };
                var direct = Driver.invoke("mc.bot.attackEntity", args);
                var viaTcp = Driver.system.rpcRoundtrip("mc.bot.attackEntity", args);
                var viaMcp = Driver.system.mcpRoundtrip("mc.bot.attackEntity", args);
                t.assertEqual(JSON.stringify(viaTcp), JSON.stringify(direct), "RPC");
                t.assertEqual(JSON.stringify(viaMcp), JSON.stringify(direct), "MCP");
            });

            if (!clientAvailable()) {
                ScriptTest.run("16_attack_entity: self is unavailable on a dedicated server", function (t) {
                    var r = Driver.bot.attackEntity({ entityId: 1 });
                    t.assertEqual(r.outcome, "refused", "outcome");
                    t.assertEqual(r.reason, "bot_unavailable", "reason");
                });
            } else {
                ScriptTest.run("16_attack_entity: an entity that does not exist is refused", function (t) {
                    // 2^30 is well past any real entity id in a fresh world
                    var r = Driver.bot.attackEntity({ entityId: 1073741824 });
                    t.assertEqual(r.outcome, "refused", "outcome");
                    t.assertEqual(r.reason, "invalid_world_target", "reason");
                    t.assertTrue(r.message.indexOf("1073741824") >= 0, "names the id: " + r.message);
                });
            }
        },
    })
);
