// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test("26_schematic_loader", { scene: ["wd.validation", "wd.validationIntegrated"], isolated: true }, (t) =>
    t.step("wd.validation", {
        script: "26_schematic_loader",
        DriverApiError: DriverApiError,
        body: (ScriptTest) => {
            Driver.system.rpcRoundtrip = ScriptTest.rpcRoundtrip;
            Driver.system.mcpRoundtrip = ScriptTest.mcpRoundtrip;

            // mc.bot.build: a schematic is a palette with rows ({kind:'blocks'}) or Sponge .schem bytes
            // ({kind:'sponge', base64}). Both are read before any body is, so a bad one is invalid_params on
            // every topology; placing the blocks needs this client's own player.

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

            var ORIGIN = { x: 0, y: 64, z: 0 };

            ScriptTest.run("26_schematic_loader: build needs a schematic", function (t) {
                var e = errorOf(function () {
                    Driver.bot.build({ origin: ORIGIN });
                });
                t.assertEqual(e.issues[0].path, "/schematic", "path");
                t.assertEqual(e.issues[0].code, "required", "issue code");
            });

            ScriptTest.run("26_schematic_loader: one schematic kind's field is unknown in the other", function (t) {
                var e = errorOf(function () {
                    Driver.bot.build({
                        origin: ORIGIN,
                        schematic: { kind: "sponge", base64: "AAAA", palette: ["minecraft:stone"] },
                    });
                });
                t.assertEqual(e.issues[0].path, "/schematic/palette", "path");
                t.assertEqual(e.issues[0].code, "unknown_field", "issue code");
            });

            ScriptTest.run("26_schematic_loader: text that is not base64 is refused at its path", function (t) {
                var e = errorOf(function () {
                    Driver.bot.build({
                        origin: ORIGIN,
                        schematic: { kind: "sponge", base64: "this is not base64!!! ###" },
                    });
                });
                t.assertEqual(e.code, "invalid_params", "code");
                t.assertEqual(e.issues[0].path, "/schematic/base64", "path");
            });

            ScriptTest.run("26_schematic_loader: base64 that is not a schematic is refused at its path", function (t) {
                // "Hello, world!": decodes, but is neither gzipped nor raw NBT.
                var e = errorOf(function () {
                    Driver.bot.build({ origin: ORIGIN, schematic: { kind: "sponge", base64: "SGVsbG8sIHdvcmxkIQ==" } });
                });
                t.assertEqual(e.issues[0].path, "/schematic/base64", "path");
                t.assertTrue(e.issues[0].message.indexOf("schematic") >= 0, "says why: " + e.issues[0].message);
            });

            ScriptTest.run("26_schematic_loader: a row past its palette is refused at its index", function (t) {
                var e = errorOf(function () {
                    Driver.bot.build({
                        origin: ORIGIN,
                        schematic: {
                            kind: "blocks",
                            w: 1,
                            h: 1,
                            d: 1,
                            palette: ["minecraft:stone"],
                            data: [[0, 0, 0, 1]],
                        },
                    });
                });
                t.assertEqual(e.issues[0].path, "/schematic/data/0/3", "path");
            });

            ScriptTest.run("26_schematic_loader: the same error over in-JVM, RPC and MCP", function (t) {
                var args = { origin: ORIGIN, schematic: { kind: "sponge", base64: "SGVsbG8sIHdvcmxkIQ==" } };
                var direct = errorOf(function () {
                    Driver.invoke("mc.bot.build", args);
                });
                var viaRpc = errorOf(function () {
                    Driver.system.rpcRoundtrip("mc.bot.build", args);
                });
                var viaMcp = errorOf(function () {
                    Driver.system.mcpRoundtrip("mc.bot.build", args);
                });
                [viaRpc, viaMcp].forEach(function (e, i) {
                    var via = i === 0 ? "RPC" : "MCP";
                    t.assertTrue(e !== null, via + " must throw");
                    t.assertEqual(JSON.stringify(e.issues), JSON.stringify(direct.issues), via + " issues");
                });
            });

            ScriptTest.run(
                "26_schematic_loader: a one-block schematic starts an action, or self is unavailable",
                function (t) {
                    var r = Driver.bot.build({
                        origin: ORIGIN,
                        schematic: {
                            kind: "blocks",
                            w: 1,
                            h: 1,
                            d: 1,
                            palette: ["minecraft:stone"],
                            data: [[0, 0, 0, 0]],
                        },
                    });
                    if (!clientAvailable() || r.outcome === "refused") {
                        t.assertEqual(r.outcome, "refused", "outcome");
                        t.assertEqual(r.reason, "bot_unavailable", "only an unavailable body may refuse: " + r.message);
                        return;
                    }
                    t.assertEqual(r.outcome, "accepted", "outcome");
                    Driver.invoke("mc.bot.cancel", { process: "builder" });
                }
            );
        },
    })
);
