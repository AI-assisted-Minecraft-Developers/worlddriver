// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test("28_construct", { scene: ["wd.validation", "wd.validationIntegrated"], isolated: true }, (t) =>
    t.step("wd.validation", {
        script: "28_construct",
        DriverApiError: DriverApiError,
        body: (ScriptTest) => {
            Driver.system.rpcRoundtrip = ScriptTest.rpcRoundtrip;
            Driver.system.mcpRoundtrip = ScriptTest.mcpRoundtrip;

            // mc.bot.construct{structure:{kind:'tower'|'bridge', …}}: every request check answers with a path
            // before any body is read, so these run on every topology. Pillaring or bridging itself needs this
            // client's own player, a block in the hotbar and open air, which a scene does not stage here.

            /** The DriverApiError a call throws, or null when it returned. */
            function errorOf(call) {
                try {
                    call();
                } catch (e) {
                    return e;
                }
                return null;
            }

            function firstIssue(params) {
                var e = errorOf(function () {
                    Driver.bot.construct(params);
                });
                return e === null ? null : e.issues[0];
            }

            ScriptTest.run("28_construct: the structure is required", function (t) {
                var i = firstIssue({});
                t.assertEqual(i.path, "/structure", "path");
                t.assertEqual(i.code, "required", "issue code");
            });

            ScriptTest.run("28_construct: an unknown kind names the kinds there are", function (t) {
                var i = firstIssue({ structure: { kind: "elevator" } });
                t.assertEqual(i.path, "/structure/kind", "path");
                t.assertTrue(
                    i.message.indexOf("tower") >= 0 && i.message.indexOf("bridge") >= 0,
                    "names both: " + i.message
                );
            });

            ScriptTest.run("28_construct: a tower needs a height or a targetY", function (t) {
                var i = firstIssue({ structure: { kind: "tower" } });
                t.assertEqual(i.path, "/structure/height", "path");
                t.assertEqual(i.code, "required", "issue code");
            });

            ScriptTest.run("28_construct: a tower takes one of them, not both", function (t) {
                t.assertEqual(
                    firstIssue({ structure: { kind: "tower", height: 3, targetY: 90 } }).path,
                    "/structure/targetY",
                    "path"
                );
            });

            ScriptTest.run("28_construct: a tower over 256 is out of range", function (t) {
                var i = firstIssue({ structure: { kind: "tower", height: 9999 } });
                t.assertEqual(i.path, "/structure/height", "path");
                t.assertEqual(i.code, "out_of_range", "issue code");
            });

            ScriptTest.run("28_construct: a bridge needs a distance up to 64", function (t) {
                t.assertEqual(
                    firstIssue({ structure: { kind: "bridge", direction: "north" } }).path,
                    "/structure/distance",
                    "missing"
                );
                t.assertEqual(
                    firstIssue({ structure: { kind: "bridge", distance: 9999 } }).code,
                    "out_of_range",
                    "oversize"
                );
            });

            ScriptTest.run("28_construct: a bridge direction is a compass or a facing word", function (t) {
                var i = firstIssue({ structure: { kind: "bridge", direction: "diagonalwise", distance: 4 } });
                t.assertEqual(i.path, "/structure/direction", "path");
                t.assertEqual(i.code, "invalid_value", "issue code");
            });

            ScriptTest.run("28_construct: the same error over in-JVM, RPC and MCP", function (t) {
                var args = { structure: { kind: "elevator" } };
                var direct = errorOf(function () {
                    Driver.invoke("mc.bot.construct", args);
                });
                var viaRpc = errorOf(function () {
                    Driver.system.rpcRoundtrip("mc.bot.construct", args);
                });
                var viaMcp = errorOf(function () {
                    Driver.system.mcpRoundtrip("mc.bot.construct", args);
                });
                [viaRpc, viaMcp].forEach(function (e, i) {
                    var via = i === 0 ? "RPC" : "MCP";
                    t.assertTrue(e !== null, via + " must throw");
                    t.assertEqual(e.code, direct.code, via + " code");
                    t.assertEqual(JSON.stringify(e.issues), JSON.stringify(direct.issues), via + " issues");
                });
            });
        },
    })
);
