// mc.script.eval validation. Each call gets a fresh scope and a wall-clock
// budget; we prove return value, console.log capture, error propagation,
// timeout enforcement, scope isolation, and round-trip parity across all three
// transports (in-JVM, WebSocket, MCP/HTTP).

function call(source, timeoutMs) {
    var args = { source: source };
    if (typeof timeoutMs === "number") args.timeoutMs = timeoutMs;
    return Driver.invoke("mc.script.eval", args);
}

ScriptTest.run("11_script_eval: last expression is returned as result", function(t) {
    var out = call("1 + 1");
    t.assertEqual(out.error, null, "no error expected, got: " + out.error);
    t.assertEqual(out.result, 2, "expected 2, got " + out.result);
    t.assertTrue(typeof out.ms === "number" && out.ms >= 0, "ms must be a non-negative number");
});

ScriptTest.run("11_script_eval: object literals survive the JSON round-trip", function(t) {
    var out = call("({a: 1, b: [2, 3], c: 'hi'})");
    t.assertEqual(out.error, null);
    t.assertEqual(out.result.a, 1);
    t.assertEqual(out.result.b.length, 2);
    t.assertEqual(out.result.b[1], 3);
    t.assertEqual(out.result.c, "hi");
});

ScriptTest.run("11_script_eval: console.log is captured into log array", function(t) {
    var out = call("console.log('hello'); console.log({k: 1}); 42");
    t.assertEqual(out.error, null);
    t.assertEqual(out.result, 42);
    t.assertEqual(out.log.length, 2, "expected 2 log entries, got " + out.log.length);
    t.assertEqual(out.log[0], "hello");
    // JSON.stringify formatting may vary by engine; just confirm key+value appear.
    t.assertTrue(out.log[1].indexOf("\"k\"") >= 0 && out.log[1].indexOf("1") >= 0,
        "object log entry should mention key and value, got: " + out.log[1]);
});

ScriptTest.run("11_script_eval: thrown errors surface in error, not result", function(t) {
    var out = call("throw new Error('boom')");
    t.assertEqual(out.result, null);
    t.assertTrue(out.error && out.error.indexOf("boom") >= 0,
        "expected error to mention 'boom', got: " + out.error);
});

ScriptTest.run("11_script_eval: scope is isolated between calls", function(t) {
    var first = call("var leaked = 99; leaked");
    t.assertEqual(first.error, null);
    t.assertEqual(first.result, 99);
    var second = call("typeof leaked");
    t.assertEqual(second.error, null);
    t.assertEqual(second.result, "undefined", "second call must not see first call's vars");
});

ScriptTest.run("11_script_eval: snippet can call Driver.invoke recursively", function(t) {
    var out = call("var v = Driver.invoke('mc.system.version', {}); v.modid");
    t.assertEqual(out.error, null);
    t.assertEqual(out.result, "worlddriver");
});

ScriptTest.run("11_script_eval: Driver.system / observe helpers are wired in the eval scope", function(t) {
    var out = call("var o = Driver.system.testOrigin(); ({x:o.x, y:o.y, z:o.z})");
    t.assertEqual(out.error, null);
    t.assertEqual(typeof out.result.x, "number");
    t.assertEqual(typeof out.result.y, "number");
    t.assertEqual(typeof out.result.z, "number");
});

ScriptTest.run("11_script_eval: tight loop is interrupted by timeout", function(t) {
    // A CPU-bound infinite loop. observeInstructionCount fires at 10k-instruction
    // intervals and throws once wall-clock passes the deadline. Whether the
    // exception is caught inside the JS wrapper or propagates as ExecutionException
    // depends on Rhino internals — either form returns an error-shaped envelope
    // with "timeout" in the message.
    var out = call("while (true) {}", 250);
    t.assertEqual(out.result, null, "timeouts must not yield a result");
    t.assertTrue(out.error && out.error.indexOf("timeout") >= 0,
        "error should mention timeout, got: " + out.error);
});

// ⚠️ The name is inherited and overclaims: this proves java.io.File is unreachable
// from an eval scope, NOT that ScriptClassFilter is what makes it so. The filter is
// off by default and nothing in the build turns it on, so it allows this name on
// every gate run and the check passes anyway. See 08_sandbox.js's header for the
// measurement and for the probe that would identify the real mechanism. Renaming
// this check is safe (the count is what wd.agentRpcSmoke pins, not the names);
// adding or removing one is not.
ScriptTest.run("11_script_eval: sandbox still applies inside eval", function(t) {
    var out = call("try { new java.io.File('/etc/passwd'); 'NOT BLOCKED' } catch (e) { 'blocked' }");
    t.assertEqual(out.error, null);
    t.assertEqual(out.result, "blocked", "java.io.File must be denied inside eval too");
});

ScriptTest.run("11_script_eval: reachable via WebSocket and MCP transports", function(t) {
    var args = { source: "Driver.invoke('mc.system.version', {}).modid", timeoutMs: 2000 };
    var direct = Driver.invoke("mc.script.eval", args);
    var viaTcp = Driver.system.rpcRoundtrip("mc.script.eval", args);
    var viaMcp = Driver.system.mcpRoundtrip("mc.script.eval", args);
    t.assertEqual(direct.result, "worlddriver");
    t.assertEqual(viaTcp.result, "worlddriver", "WS path must reach the evaluator");
    t.assertEqual(viaMcp.result, "worlddriver", "MCP path must reach the evaluator");
});
