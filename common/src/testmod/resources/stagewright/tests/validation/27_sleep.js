// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test("27_sleep", { scene: ["wd.validation", "wd.validationIntegrated"], isolated: true }, (t) =>
    t.step("wd.validation", {
        script: "27_sleep",
        DriverApiError: DriverApiError,
        body: (ScriptTest) => {
            Driver.system.rpcRoundtrip = ScriptTest.rpcRoundtrip;
            Driver.system.mcpRoundtrip = ScriptTest.mcpRoundtrip;

            // mc.bot.sleep: the radius is a checked range rather than a clamp, the bed search is the body's,
            // and the same answer comes back over every transport. Sleeping itself needs a bed, night and this
            // client's own player, which a scene does not stage here.

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

            /** Accepted, then cancelled so the next test starts from an idle body; or refused because self is unavailable. */
            function startedOrUnavailable(t, r) {
                if (!clientAvailable() || r.outcome === "refused") {
                    t.assertEqual(r.outcome, "refused", "outcome");
                    t.assertEqual(r.reason, "bot_unavailable", "only an unavailable body may refuse: " + r.message);
                    return;
                }
                t.assertEqual(r.outcome, "accepted", "outcome");
                Driver.invoke("mc.bot.cancel", { process: "sleep" });
            }

            ScriptTest.run("27_sleep: no params starts a search with the default radius", function (t) {
                startedOrUnavailable(t, Driver.bot.sleep());
            });

            ScriptTest.run("27_sleep: an explicit bed and radius are taken", function (t) {
                startedOrUnavailable(t, Driver.bot.sleep({ pos: { x: 0, y: 64, z: 0 }, radius: 8 }));
            });

            ScriptTest.run("27_sleep: a radius past 64 is out of range, not clamped", function (t) {
                var e = errorOf(function () {
                    Driver.bot.sleep({ radius: 9999 });
                });
                t.assertEqual(e.issues[0].path, "/radius", "path");
                t.assertEqual(e.issues[0].code, "out_of_range", "issue code");
            });

            ScriptTest.run("27_sleep: the same error over in-JVM, RPC and MCP", function (t) {
                var args = { radius: 0 };
                var direct = errorOf(function () {
                    Driver.invoke("mc.bot.sleep", args);
                });
                var viaRpc = errorOf(function () {
                    Driver.system.rpcRoundtrip("mc.bot.sleep", args);
                });
                var viaMcp = errorOf(function () {
                    Driver.system.mcpRoundtrip("mc.bot.sleep", args);
                });
                [viaRpc, viaMcp].forEach(function (e, i) {
                    var via = i === 0 ? "RPC" : "MCP";
                    t.assertTrue(e !== null, via + " must throw");
                    t.assertEqual(JSON.stringify(e.issues), JSON.stringify(direct.issues), via + " issues");
                });
            });
        },
    })
);
