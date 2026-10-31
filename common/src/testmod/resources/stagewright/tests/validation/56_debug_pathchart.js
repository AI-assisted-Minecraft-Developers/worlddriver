// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test("56_debug_pathchart", { scene: ["wd.validation", "wd.validationIntegrated"], isolated: true }, (t) =>
    t.step("wd.validation", {
        script: "56_debug_pathchart",
        DriverApiError: DriverApiError,
        body: (ScriptTest) => {
            Driver.system.rpcRoundtrip = ScriptTest.rpcRoundtrip;
            Driver.system.mcpRoundtrip = ScriptTest.mcpRoundtrip;

            // mc.debug.pathChart is client-only (registered at client init) and depends on the
            // pathfinding recorder. On the dedicated GameTest server the route is absent, so the script runs
            // no checks. When a client is present, smoke the tool end to end.

            function clientAvailable() {
                try {
                    Driver.invoke("mc.client.screen.info", {});
                    return true;
                } catch (e) {
                    return false;
                }
            }
            function routeAvailable() {
                try {
                    Driver.invoke("mc.debug.pathChart", { save: false });
                    return true;
                } catch (e) {
                    return false;
                }
            }

            if (clientAvailable() && routeAvailable()) {
                ScriptTest.run("56_debug_pathchart: render returns a non-trivial PNG", function (t) {
                    var r = Driver.invoke("mc.debug.pathChart", { width: 800, height: 600 });
                    t.assertTrue(typeof r.path === "string" && r.path.length > 0, "returns a file path");
                    t.assertTrue(r.bytes > 1000, "PNG has real bytes");
                });

                ScriptTest.run("56_debug_pathchart: save:false writes no file", function (t) {
                    var direct = Driver.invoke("mc.debug.pathChart", { width: 640, height: 480, save: false });
                    t.assertEqual(direct.path, undefined, "save:false writes no file");
                });
            }
        },
    })
);
