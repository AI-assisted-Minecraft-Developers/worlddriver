// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test("20_clearArea_modes", { scene: ["wd.validation", "wd.validationIntegrated"], isolated: true }, (t) =>
    t.step("wd.validation", {
        script: "20_clearArea_modes",
        DriverApiError: DriverApiError,
        body: (ScriptTest) => {
            Driver.system.rpcRoundtrip = ScriptTest.rpcRoundtrip;
            Driver.system.mcpRoundtrip = ScriptTest.mcpRoundtrip;

            // mc.bot.clearArea: the typed request (box/tunnel target, clear/fill/replace edit), its errors with
            // their paths, the action it answers with, and the same error over every transport. Errors need no
            // player; starting the action needs this client's own player, so that part is client-only.

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

            ScriptTest.run("20_clearArea_modes: a string distance is invalid_params at its path", function (t) {
                var e = errorOf(function () {
                    Driver.bot.clearArea({ target: { kind: "tunnel", distance: "12" } });
                });
                t.assertTrue(e !== null, "must throw");
                t.assertEqual(e.code, "invalid_params", "code");
                t.assertEqual(e.execution, "not_started", "execution");
                t.assertEqual(e.issues[0].path, "/target/distance", "path");
                t.assertEqual(e.issues[0].code, "type_mismatch", "issue code");
            });

            ScriptTest.run("20_clearArea_modes: replace without to is a missing field", function (t) {
                var e = errorOf(function () {
                    Driver.bot.clearArea({
                        target: { kind: "box", from: { x: 0, y: 64, z: 0 }, to: { x: 1, y: 64, z: 1 } },
                        edit: { kind: "replace", from: "minecraft:dirt" },
                    });
                });
                t.assertEqual(e.issues[0].path, "/edit/to", "path");
                t.assertEqual(e.issues[0].code, "required", "issue code");
            });

            ScriptTest.run("20_clearArea_modes: another variant's field is unknown", function (t) {
                var e = errorOf(function () {
                    Driver.bot.clearArea({ target: { kind: "tunnel", distance: 3, from: { x: 1, y: 2, z: 3 } } });
                });
                t.assertEqual(e.issues[0].path, "/target/from", "path");
                t.assertEqual(e.issues[0].code, "unknown_field", "issue code");
            });

            ScriptTest.run("20_clearArea_modes: a box over 4096 cells is refused before anything starts", function (t) {
                var e = errorOf(function () {
                    Driver.bot.clearArea({
                        target: { kind: "box", from: { x: 0, y: 64, z: 0 }, to: { x: 50, y: 80, z: 50 } },
                    });
                });
                t.assertEqual(e.code, "invalid_params", "code");
                t.assertEqual(e.issues[0].path, "/target", "path");
            });

            ScriptTest.run("20_clearArea_modes: a vertical tunnel takes no widthBias", function (t) {
                var e = errorOf(function () {
                    Driver.bot.clearArea({
                        target: { kind: "tunnel", direction: "down", distance: 2, widthBias: "left" },
                    });
                });
                t.assertEqual(e.issues[0].path, "/target/widthBias", "path");
            });

            ScriptTest.run("20_clearArea_modes: an unknown block id is invalid_params at its path", function (t) {
                var e = errorOf(function () {
                    Driver.bot.clearArea({
                        target: { kind: "box", from: { x: 0, y: 64, z: 0 }, to: { x: 0, y: 64, z: 0 } },
                        edit: { kind: "fill", block: "minecraft:no_such_block" },
                    });
                });
                t.assertEqual(e.issues[0].path, "/edit/block", "path");
            });

            ScriptTest.run("20_clearArea_modes: the same error over in-JVM, RPC and MCP", function (t) {
                var args = { target: { kind: "tunnel", distance: 65 } };
                var direct = errorOf(function () {
                    Driver.invoke("mc.bot.clearArea", args);
                });
                var viaRpc = errorOf(function () {
                    Driver.system.rpcRoundtrip("mc.bot.clearArea", args);
                });
                var viaMcp = errorOf(function () {
                    Driver.system.mcpRoundtrip("mc.bot.clearArea", args);
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
                    "20_clearArea_modes: action part skipped (no client player — dedicated server)",
                    function (t) {
                        // self means this client's player; a dedicated server has none and refuses it.
                        var r = Driver.bot.clearArea({ target: { kind: "tunnel", distance: 1 } });
                        t.assertEqual(r.outcome, "refused", "outcome");
                        t.assertEqual(r.reason, "bot_unavailable", "reason");
                    }
                );
            } else {
                ScriptTest.run(
                    "20_clearArea_modes: a tunnel is accepted, readable by id, and cancellable",
                    function (t) {
                        var r = Driver.bot.clearArea({ target: { kind: "tunnel", direction: "north", distance: 3 } });
                        if (r.outcome === "refused") {
                            t.assertEqual(
                                r.reason,
                                "bot_unavailable",
                                "only an unavailable body may refuse: " + r.message
                            );
                            return;
                        }
                        t.assertEqual(r.outcome, "accepted", "outcome");
                        t.assertEqual(r.waitTimedOut, false, "awaitMs 0 never times out");
                        var id = r.action.actionId;
                        t.assertTrue(typeof id === "string" && id.length > 0, "actionId");
                        t.assertEqual(r.action.bot, "self", "body");

                        var read = Driver.invoke("mc.wait.result", { kind: "action", actionId: id });
                        t.assertEqual(read.kind, "action", "kind");
                        t.assertEqual(read.action.actionId, id, "same action");

                        Driver.invoke("mc.bot.cancel", { process: "builder" });
                        var ended = null;
                        for (var i = 0; i < 50 && ended === null; i++) {
                            var now = Driver.invoke("mc.wait.result", { kind: "action", actionId: id }).action;
                            if (now.state !== "running") ended = now;
                            else Driver.invoke("mc.system.waitTicks", { ticks: 1 });
                        }
                        t.assertTrue(ended !== null, "the action ended after the cancel");
                        t.assertTrue(
                            ended.state === "cancelled" || ended.state === "succeeded" || ended.state === "failed",
                            "a cancel or an early finish, got " + JSON.stringify(ended)
                        );
                        if (ended.state === "succeeded") t.assertEqual(ended.result.kind, "clearArea", "result kind");
                    }
                );

                ScriptTest.run("20_clearArea_modes: a new task supersedes the running one by id", function (t) {
                    var first = Driver.bot.clearArea({ target: { kind: "tunnel", direction: "north", distance: 3 } });
                    if (first.outcome !== "accepted") return;
                    var second = Driver.bot.clearArea({ target: { kind: "tunnel", direction: "south", distance: 3 } });
                    t.assertEqual(second.outcome, "accepted", "second outcome");
                    var old = Driver.invoke("mc.wait.result", {
                        kind: "action",
                        actionId: first.action.actionId,
                    }).action;
                    if (old.state === "superseded") {
                        t.assertEqual(old.replacementActionId, second.action.actionId, "replacement id");
                    } else {
                        t.assertTrue(
                            old.state === "succeeded",
                            "only an early finish may precede the replacement: " + JSON.stringify(old)
                        );
                    }
                    Driver.invoke("mc.bot.cancel", { process: "builder" });
                });
            }
        },
    })
);
