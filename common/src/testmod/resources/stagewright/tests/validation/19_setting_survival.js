// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test("19_setting_survival", { scene: ["wd.validation", "wd.validationIntegrated"], isolated: true }, (t) =>
    t.step("wd.validation", {
        script: "19_setting_survival",
        DriverApiError: DriverApiError,
        body: (ScriptTest) => {
            Driver.system.rpcRoundtrip = ScriptTest.rpcRoundtrip;
            Driver.system.mcpRoundtrip = ScriptTest.mcpRoundtrip;

            // mc.bot.setting round-trips: a patch commits its keys together and answers what they now hold; an
            // out-of-range value is invalid_params at its own path and writes nothing. The settings are this
            // game's, held by the client when there is one and by the server otherwise, so this runs everywhere.

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

            ScriptTest.run("19_setting_survival: autoEat / autoRespawn toggles round-trip", function (t) {
                var was = read(["autoEat", "autoRespawn"]);
                var r = Driver.bot.setting({ op: "patch", values: { autoEat: true, autoRespawn: true } });
                t.assertEqual(r.op, "patch", "op");
                t.assertEqual(typeof r.persisted, "boolean", "persisted says whether the file was written");
                t.assertEqual(
                    JSON.stringify(r.values),
                    JSON.stringify({ autoEat: true, autoRespawn: true }),
                    "values in the order sent"
                );
                var off = Driver.bot.setting({ op: "patch", values: { autoEat: false, autoRespawn: false } }).values;
                t.assertEqual(off.autoEat, false, "autoEat=false after disable");
                t.assertEqual(off.autoRespawn, false, "autoRespawn=false after disable");
                t.assertEqual(read(["autoEat"]).autoEat, false, "a fresh read agrees");
                Driver.bot.setting({ op: "patch", values: was });
            });

            ScriptTest.run("19_setting_survival: smoothLook toggle + rate round-trip", function (t) {
                var was = read(["smoothLook", "smoothLookDegPerTick"]);
                var r = Driver.bot.setting({
                    op: "patch",
                    values: { smoothLook: true, smoothLookDegPerTick: 30 },
                }).values;
                t.assertEqual(r.smoothLook, true, "smoothLook=true");
                t.assertEqual(r.smoothLookDegPerTick, 30, "rate=30");
                var e = errorOf(function () {
                    Driver.bot.setting({ op: "patch", values: { smoothLook: false, smoothLookDegPerTick: 999 } });
                });
                t.assertEqual(
                    e.issues[0].path,
                    "/values/smoothLookDegPerTick",
                    "the out-of-range rate is refused at its own path"
                );
                t.assertEqual(e.issues[0].code, "out_of_range", "issue code");
                t.assertEqual(read(["smoothLook"]).smoothLook, true, "a refused patch writes none of its keys");
                Driver.bot.setting({ op: "patch", values: was });
            });

            ScriptTest.run("19_setting_survival: a control is applied, not patched", function (t) {
                var e = errorOf(function () {
                    Driver.bot.setting({ op: "patch", values: { paused: true } });
                });
                t.assertEqual(e.issues[0].path, "/values/paused", "path");
                t.assertTrue(e.issues[0].message.indexOf("apply") >= 0, "names op apply: " + e.issues[0].message);
                var w = errorOf(function () {
                    read(["debugFly"]);
                });
                t.assertEqual(w.issues[0].path, "/keys/0", "debugFly has nothing to read");
            });

            ScriptTest.run("19_setting_survival: the same refusal over in-JVM, RPC and MCP", function (t) {
                var args = { op: "patch", values: { autoEatFoodThreshold: 99 } };
                var errs = [
                    errorOf(function () {
                        Driver.invoke("mc.bot.setting", args);
                    }),
                    errorOf(function () {
                        Driver.system.rpcRoundtrip("mc.bot.setting", args);
                    }),
                    errorOf(function () {
                        Driver.system.mcpRoundtrip("mc.bot.setting", args);
                    }),
                ];
                for (var i = 0; i < errs.length; i++) {
                    t.assertTrue(errs[i] !== null, "transport " + i + " must refuse");
                    t.assertEqual(errs[i].issues[0].path, "/values/autoEatFoodThreshold", "transport " + i + " path");
                    t.assertEqual(errs[i].issues[0].code, "out_of_range", "transport " + i + " code");
                }
            });
        },
    })
);
