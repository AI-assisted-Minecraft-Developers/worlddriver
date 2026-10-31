// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test("66_body_routes", { scene: ["wd.validation", "wd.validationIntegrated"], isolated: true }, (t) =>
    t.step("wd.validation", {
        script: "66_body_routes",
        DriverApiError: DriverApiError,
        body: (ScriptTest) => {
            Driver.system.rpcRoundtrip = ScriptTest.rpcRoundtrip;
            Driver.system.mcpRoundtrip = ScriptTest.mcpRoundtrip;

            // The mc.bot.* verbs that take `bot` use it to address a bot. An id nothing is registered under must
            // refuse with the same bytes on every transport, and status must list the registered bots the same
            // way on each. Needs no bot of its own, so it runs on every topology; driving a registered bot is the
            // dedicated-server scenes' job.

            function jsonStable(v) {
                if (v === null || typeof v !== "object") return JSON.stringify(v);
                if (Array.isArray(v)) {
                    var parts = [];
                    for (var i = 0; i < v.length; i++) parts.push(jsonStable(v[i]));
                    return "[" + parts.join(",") + "]";
                }
                var keys = Object.keys(v).sort();
                var pairs = [];
                for (var i = 0; i < keys.length; i++) {
                    pairs.push(JSON.stringify(keys[i]) + ":" + jsonStable(v[keys[i]]));
                }
                return "{" + pairs.join(",") + "}";
            }

            var NOBODY = "npc:validation-nobody";

            function viaEveryTransport(method, params) {
                return [
                    Driver.invoke(method, params),
                    Driver.system.rpcRoundtrip(method, params),
                    Driver.system.mcpRoundtrip(method, params),
                ];
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

            ScriptTest.run(
                "66_body_routes: every verb that takes bot refuses an unknown one as bot_unavailable on every transport",
                function (t) {
                    var calls = [
                        ["mc.bot.cancel", { bot: NOBODY }],
                        ["mc.bot.status", { bot: NOBODY }],
                        ["mc.bot.clearArea", { bot: NOBODY, target: { kind: "tunnel", distance: 1 } }],
                        ["mc.bot.goto", { bot: NOBODY, target: { kind: "position", pos: { x: 0, y: 64, z: 0 } } }],
                        ["mc.bot.mine", { bot: NOBODY, blocks: ["minecraft:stone"] }],
                        ["mc.bot.escape", { bot: NOBODY }],
                        ["mc.bot.combat", { bot: NOBODY, mode: "defend" }],
                        ["mc.bot.runAway", { bot: NOBODY }],
                        ["mc.bot.elytraFly", { bot: NOBODY }],
                        ["mc.bot.lookAt", { bot: NOBODY, yaw: 0, pitch: 0 }],
                        ["mc.bot.holdItem", { bot: NOBODY, item: "minecraft:stone" }],
                        ["mc.bot.useItem", { bot: NOBODY }],
                        ["mc.bot.attackEntity", { bot: NOBODY, entityId: 1 }],
                    ];
                    for (var i = 0; i < calls.length; i++) {
                        var method = calls[i][0];
                        var r = viaEveryTransport(method, calls[i][1]);
                        t.assertEqual(r[0].outcome, "refused", method + " outcome, got: " + JSON.stringify(r[0]));
                        t.assertEqual(r[0].reason, "bot_unavailable", method + " reason");
                        t.assertEqual(jsonStable(r[0]), jsonStable(r[1]), method + ": in-JVM and TCP must agree");
                        t.assertEqual(jsonStable(r[0]), jsonStable(r[2]), method + ": in-JVM and MCP must agree");
                    }
                }
            );

            ScriptTest.run(
                "66_body_routes: another bot takes no waypoint, preview or plan on every transport",
                function (t) {
                    var r = viaEveryTransport("mc.bot.goto", {
                        bot: NOBODY,
                        target: { kind: "waypoint", name: "home" },
                    });
                    // No bot is registered under NOBODY, so the body is what refuses first; the self-only refusal
                    // itself is the dedicated-server scenes' (wd.bodyRoute*), which have a bot to address.
                    t.assertEqual(r[0].outcome, "refused", "outcome, got: " + JSON.stringify(r[0]));
                    t.assertEqual(jsonStable(r[0]), jsonStable(r[1]), "in-JVM and TCP must agree");
                    t.assertEqual(jsonStable(r[0]), jsonStable(r[2]), "in-JVM and MCP must agree");
                }
            );

            ScriptTest.run("66_body_routes: status lists the same bots on every transport", function (t) {
                var r = viaEveryTransport("mc.bot.status", {});
                for (var i = 0; i < r.length; i++) {
                    t.assertTrue(
                        Array.isArray(r[i].bots),
                        "transport " + i + " must answer bots as an array, got: " + JSON.stringify(r[i].bots)
                    );
                }
                // Ids and kinds only: a bot another scene drives may move or finish between the three reads.
                function names(s) {
                    return jsonStable(
                        s.bots.map(function (b) {
                            return [b.id, b.kind];
                        })
                    );
                }
                t.assertEqual(names(r[0]), names(r[1]), "in-JVM and TCP must list the same bots");
                t.assertEqual(names(r[0]), names(r[2]), "in-JVM and MCP must list the same bots");
            });

            ScriptTest.run(
                "66_body_routes: a verb that does not route by bot rejects it as an unknown field",
                function (t) {
                    var e = errorOf(function () {
                        Driver.invoke("mc.bot.waypoint", { op: "list", bot: NOBODY });
                    });
                    t.assertTrue(e !== null && Array.isArray(e.issues), "must throw a validation error, got: " + e);
                    t.assertEqual(e.issues[0].path, "/bot", "path");
                    t.assertEqual(e.issues[0].code, "unknown_field", "issue code");
                }
            );
        },
    })
);
