// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test("11_script_eval", { scene: ["wd.validation", "wd.validationIntegrated"], isolated: true }, (t) =>
    t.step("wd.validation", {
        script: "11_script_eval",
        DriverApiError: DriverApiError,
        body: (ScriptTest) => {
            Driver.system.rpcRoundtrip = ScriptTest.rpcRoundtrip;
            Driver.system.mcpRoundtrip = ScriptTest.mcpRoundtrip;

            // mc.script.eval validation. Each call gets a fresh scope and a wall-clock
            // budget; we prove return value, console.log capture, error propagation,
            // timeout enforcement, scope isolation, and round-trip parity across all three
            // transports (in-JVM, WebSocket, MCP/HTTP).

            function call(source, timeoutMs) {
                var args = { source: source };
                if (typeof timeoutMs === "number") args.timeoutMs = timeoutMs;
                return Driver.invoke("mc.script.eval", args);
            }

            function errorText(out) {
                return out.error === undefined ? "none" : JSON.stringify(out.error);
            }

            ScriptTest.run("11_script_eval: last expression is returned as result", function (t) {
                var out = call("1 + 1");
                t.assertEqual(out.error, undefined, "no error expected, got: " + errorText(out));
                t.assertEqual(out.result, 2, "expected 2, got " + out.result);
                t.assertTrue(typeof out.ms === "number" && out.ms >= 0, "ms must be a non-negative number");
                t.assertTrue(/^eval-\d+$/.test(out.runId), "a run of its own, got " + out.runId);
            });

            ScriptTest.run("11_script_eval: object literals survive the JSON round-trip", function (t) {
                var out = call("({a: 1, b: [2, 3], c: 'hi'})");
                t.assertEqual(out.error, undefined);
                t.assertEqual(out.result.a, 1);
                t.assertEqual(out.result.b.length, 2);
                t.assertEqual(out.result.b[1], 3);
                t.assertEqual(out.result.c, "hi");
            });

            ScriptTest.run("11_script_eval: console.log is captured into log array", function (t) {
                var out = call("console.log('hello'); console.log({k: 1}); 42");
                t.assertEqual(out.error, undefined);
                t.assertEqual(out.result, 42);
                t.assertEqual(out.log.length, 2, "expected 2 log entries, got " + out.log.length);
                t.assertEqual(out.log[0], "hello");
                // JSON.stringify formatting may vary by engine; just confirm key+value appear.
                t.assertTrue(
                    out.log[1].indexOf('"k"') >= 0 && out.log[1].indexOf("1") >= 0,
                    "object log entry should mention key and value, got: " + out.log[1],
                );
            });

            ScriptTest.run("11_script_eval: thrown errors surface in error, not result", function (t) {
                var out = call("1;\nthrow new Error('boom')");
                t.assertEqual(out.result, undefined);
                t.assertEqual(out.error.code, "script_error", errorText(out));
                t.assertTrue(
                    out.error.message.indexOf("boom") >= 0,
                    "expected error to mention 'boom', got: " + errorText(out),
                );
                t.assertEqual(out.error.line, 2, "the line it was thrown at");
            });

            ScriptTest.run("11_script_eval: a Driver call's error keeps its code and issues", function (t) {
                var out = call("Driver.invoke('mc.bot.setting', {op: 'read', keys: ['noSuchKey']})");
                t.assertEqual(out.error.code, "invalid_params", errorText(out));
                t.assertEqual(out.error.execution, "not_started", errorText(out));
                t.assertEqual(out.error.issues[0].path, "/keys/0", errorText(out));
            });

            ScriptTest.run("11_script_eval: scope is isolated between calls", function (t) {
                var first = call("var leaked = 99; leaked");
                t.assertEqual(first.error, undefined);
                t.assertEqual(first.result, 99);
                var second = call("typeof leaked");
                t.assertEqual(second.error, undefined);
                t.assertEqual(second.result, "undefined", "second call must not see first call's vars");
            });

            ScriptTest.run("11_script_eval: snippet can call Driver.invoke recursively", function (t) {
                var out = call("var v = Driver.invoke('mc.system.version', {}); v.modid");
                t.assertEqual(out.error, undefined);
                t.assertEqual(out.result, "worlddriver");
            });

            ScriptTest.run("11_script_eval: a nested eval runs inside its caller's run", function (t) {
                var out = call("Driver.invoke('mc.script.eval', {source: '1'}).runId");
                t.assertEqual(out.error, undefined, errorText(out));
                t.assertEqual(out.result, out.runId, "the nested run is the outer one");
            });

            ScriptTest.run("11_script_eval: Driver.system / observe helpers are wired in the eval scope", function (t) {
                var out = call("var o = Driver.system.testOrigin(); ({x:o.x, y:o.y, z:o.z})");
                t.assertEqual(out.error, undefined);
                t.assertEqual(typeof out.result.x, "number");
                t.assertEqual(typeof out.result.y, "number");
                t.assertEqual(typeof out.result.z, "number");
            });

            ScriptTest.run("11_script_eval: tight loop is interrupted by timeout", function (t) {
                // A CPU-bound infinite loop. observeInstructionCount fires at 10k-instruction
                // intervals and throws once wall-clock passes the deadline.
                var out = call("while (true) {}", 250);
                t.assertEqual(out.result, undefined, "timeouts must not yield a result");
                t.assertEqual(out.error.code, "script_timeout", errorText(out));
            });

            ScriptTest.run("11_script_eval: encoding the result is budgeted too", function (t) {
                // JSON.stringify runs the result's own getters, which are the script's code past its last line.
                var out = call(
                    "Object.defineProperty({}, 'x', {enumerable: true, get: function () { while (true) {} }})",
                    250,
                );
                t.assertEqual(
                    out.result,
                    undefined,
                    "no result from a getter that never returns: " + JSON.stringify(out.result),
                );
                // The watchdog may answer first (script_timeout), or the encoding's own budget (script_error); both bound it.
                t.assertTrue(out.error.code === "script_timeout" || out.error.code === "script_error", errorText(out));
            });

            ScriptTest.run("11_script_eval: source and timeoutMs are checked before anything runs", function (t) {
                var e = null;
                try {
                    Driver.invoke("mc.script.eval", { source: "1", timeoutMs: 30001 });
                } catch (x) {
                    e = x;
                }
                t.assertEqual(e && e.code, "invalid_params", "timeoutMs above 30000");
                t.assertEqual(e.issues[0].path, "/timeoutMs");
                e = null;
                try {
                    Driver.invoke("mc.script.eval", { source: "" });
                } catch (x) {
                    e = x;
                }
                t.assertEqual(e && e.issues[0].path, "/source", "empty source");
            });

            // ⚠️ The name is inherited and overclaims: this proves java.io.File is unreachable
            // from an eval scope, NOT that ScriptClassFilter is what makes it so. The filter is
            // off by default and nothing in the build turns it on, so it allows this name on
            // every gate run and the check passes anyway. See 08_sandbox.js's header for the
            // measurement and for the probe that would identify the real mechanism. Renaming
            // this check is safe; adding or removing one moves the counts at the top of this file.
            ScriptTest.run("11_script_eval: sandbox still applies inside eval", function (t) {
                var out = call("try { new java.io.File('/etc/passwd'); 'NOT BLOCKED' } catch (e) { 'blocked' }");
                t.assertEqual(out.error, undefined);
                t.assertEqual(out.result, "blocked", "java.io.File must be denied inside eval too");
            });

            // obj.getClass() is undefined in this Rhino (it never exposes methods only
            // java.lang.Object declares), so the prelude carries javaClassName. Checked in
            // both scopes that load the prelude: this test's scope and an eval scope.
            ScriptTest.run("11_script_eval: javaClassName names a Java object, null for a JS value", function (t) {
                t.assertEqual(
                    typeof __api.getClass,
                    "undefined",
                    "if getClass is reachable, javaClassName is redundant",
                );
                t.assertEqual(javaClassName(__api), "net.magicterra.worlddriver.script.ScriptCalls");
                t.assertEqual(javaClassName({ a: 1 }), null);
                t.assertEqual(javaClassName("s"), null);
                t.assertEqual(javaClassName(null), null);
                var out = call("[javaClassName(__api), javaClassName([1, 2]), javaClassName(undefined)]");
                t.assertEqual(out.error, undefined, "no error expected, got: " + errorText(out));
                t.assertEqual(out.result[0], "net.magicterra.worlddriver.script.ScriptCalls");
                t.assertEqual(out.result[1], null);
                t.assertEqual(out.result[2], null);
            });

            ScriptTest.run("11_script_eval: reachable via WebSocket and MCP transports", function (t) {
                var args = { source: "Driver.invoke('mc.system.version', {}).modid", timeoutMs: 2000 };
                var direct = Driver.invoke("mc.script.eval", args);
                var viaTcp = Driver.system.rpcRoundtrip("mc.script.eval", args);
                var viaMcp = Driver.system.mcpRoundtrip("mc.script.eval", args);
                t.assertEqual(direct.result, "worlddriver");
                t.assertEqual(viaTcp.result, "worlddriver", "WS path must reach the evaluator");
                t.assertEqual(viaMcp.result, "worlddriver", "MCP path must reach the evaluator");
            });
        },
    }),
);
