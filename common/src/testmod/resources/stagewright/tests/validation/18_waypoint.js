// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test("18_waypoint", { scene: ["wd.validation", "wd.validationIntegrated"], isolated: true }, (t) =>
    t.step("wd.validation", {
        script: "18_waypoint",
        DriverApiError: DriverApiError,
        body: (ScriptTest) => {
            Driver.system.rpcRoundtrip = ScriptTest.rpcRoundtrip;
            Driver.system.mcpRoundtrip = ScriptTest.mcpRoundtrip;

            // mc.bot.waypoint: named positions this client keeps. The request checks read nothing and run on every
            // topology; storing needs the bot impl, which a dedicated server does not bind.

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

            ScriptTest.run("18_waypoint: an unknown op names the ones there are", function (t) {
                var e = errorOf(function () {
                    Driver.bot.waypoint({ op: "wiggle" });
                });
                t.assertEqual(e.issues[0].path, "/op", "path");
                t.assertTrue(e.issues[0].message.indexOf("save") >= 0, "names them: " + e.issues[0].message);
            });

            ScriptTest.run("18_waypoint: save needs a name", function (t) {
                var e = errorOf(function () {
                    Driver.bot.waypoint({ op: "save" });
                });
                t.assertEqual(e.issues[0].path, "/name", "path");
                t.assertEqual(e.issues[0].code, "required", "issue code");
            });

            ScriptTest.run("18_waypoint: the same error over in-JVM, RPC and MCP", function (t) {
                var args = { op: "wiggle" };
                var direct = errorOf(function () {
                    Driver.invoke("mc.bot.waypoint", args);
                });
                var viaTcp = errorOf(function () {
                    Driver.system.rpcRoundtrip("mc.bot.waypoint", args);
                });
                var viaMcp = errorOf(function () {
                    Driver.system.mcpRoundtrip("mc.bot.waypoint", args);
                });
                t.assertEqual(JSON.stringify(viaTcp.issues), JSON.stringify(direct.issues), "RPC issues");
                t.assertEqual(JSON.stringify(viaMcp.issues), JSON.stringify(direct.issues), "MCP issues");
            });

            if (!clientAvailable()) {
                ScriptTest.run("18_waypoint: a dedicated server keeps none", function (t) {
                    var e = errorOf(function () {
                        Driver.bot.waypoint({ op: "list" });
                    });
                    t.assertEqual(e.code, "capability_unavailable", "code");
                });
            } else {
                // Ensure clean slate.
                Driver.bot.waypoint({ op: "clear" });

                ScriptTest.run("18_waypoint: save with explicit pos round-trips", function (t) {
                    var s = Driver.bot.waypoint({ op: "save", name: "home", pos: { x: 10, y: 64, z: 20 } });
                    t.assertEqual(s.op, "save", "op");
                    t.assertEqual(s.name, "home", "echoed name");
                    t.assertEqual(s.pos.x, 10, "pos round-trips x");
                    t.assertEqual(s.pos.y, 64, "pos round-trips y");
                    t.assertEqual(s.pos.z, 20, "pos round-trips z");
                    var g = Driver.bot.waypoint({ op: "get", name: "home" });
                    t.assertEqual(g.pos.x, 10, "get returns saved x");
                });

                ScriptTest.run("18_waypoint: list reports stored entries", function (t) {
                    var l = Driver.bot.waypoint({ op: "list" });
                    t.assertTrue(
                        Array.isArray(l.waypoints) && l.waypoints.length >= 1,
                        "stored entries: " + JSON.stringify(l)
                    );
                });

                ScriptTest.run("18_waypoint: delete removes the entry", function (t) {
                    Driver.bot.waypoint({ op: "save", name: "tmp", pos: { x: 1, y: 2, z: 3 } });
                    var d = Driver.bot.waypoint({ op: "delete", name: "tmp" });
                    t.assertEqual(d.existed, true, "must report existed=true");
                    var e = errorOf(function () {
                        Driver.bot.waypoint({ op: "get", name: "tmp" });
                    });
                    t.assertEqual(e.issues[0].path, "/name", "a deleted name is no waypoint");
                });
            }
        },
    })
);
