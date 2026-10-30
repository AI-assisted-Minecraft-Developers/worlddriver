// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test("67_mouse_scroll", { scene: ["wd.validation", "wd.validationIntegrated"], isolated: true }, (t) =>
    t.step("wd.validation", {
        script: "67_mouse_scroll",
        DriverApiError: DriverApiError,
        body: (ScriptTest) => {
            Driver.system.rpcRoundtrip = ScriptTest.rpcRoundtrip;
            Driver.system.mcpRoundtrip = ScriptTest.mcpRoundtrip;

            // mc.client.input.mouseMove takes a point, a scroll, or both. The request checks read nothing and run
            // on every topology; the wheel itself is wd.clientScrollStepsTheHotbar, on the joined client.

            /** The DriverApiError a call throws, or null when it returned. */
            function errorOf(call) {
                try {
                    call();
                } catch (e) {
                    return e;
                }
                return null;
            }

            ScriptTest.run("67_mouse_scroll: half a point is refused", function (t) {
                var e = errorOf(function () {
                    Driver.client.input.mouseMove({ x: 10 });
                });
                t.assertEqual(e.code, "invalid_params", "code");
                t.assertEqual(e.issues[0].path, "/y", "path");
            });

            ScriptTest.run("67_mouse_scroll: neither a point nor a scroll is refused", function (t) {
                var e = errorOf(function () {
                    Driver.client.input.mouseMove({});
                });
                t.assertEqual(e.code, "invalid_params", "code");
                t.assertEqual(e.issues[0].path, "/x", "path");
            });

            ScriptTest.run("67_mouse_scroll: the same rejection over in-JVM, RPC and MCP", function (t) {
                var args = { x: 10 };
                var direct = errorOf(function () {
                    Driver.invoke("mc.client.input.mouseMove", args);
                });
                var viaTcp = errorOf(function () {
                    Driver.system.rpcRoundtrip("mc.client.input.mouseMove", args);
                });
                var viaMcp = errorOf(function () {
                    Driver.system.mcpRoundtrip("mc.client.input.mouseMove", args);
                });
                t.assertEqual(JSON.stringify(viaTcp.issues), JSON.stringify(direct.issues), "RPC issues");
                t.assertEqual(JSON.stringify(viaMcp.issues), JSON.stringify(direct.issues), "MCP issues");
            });
        },
    })
);
