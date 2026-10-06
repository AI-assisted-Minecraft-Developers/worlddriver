// Route-layer schema validation (feedback 2026-07-10 §4) — the validator runs
// inside DriverApi.route(), so violations surface as thrown errors (wrapped by
// Rhino), NOT as {ok:false} results. Server-side routes work headless; no
// clientAvailable guard needed for runCommand/query cases.

/** The issues a call is refused with, as "path code" strings; null when it was not refused. */
function issuesOf(fn) {
    try { fn(); return null; } catch (e) {
        return (e.issues || []).map(function (i) { return i.path + " " + i.code; });
    }
}

ScriptTest.run("64_schema_validation: wrong field name names both the missing and the unexpected key", function(t) {
    var got = issuesOf(function() { Driver.invoke("mc.action.runCommand", { command: "time query daytime" }); });
    t.assertTrue(got !== null, "must reject");
    t.assertTrue(got.indexOf("/cmd required") >= 0, "must name missing 'cmd', got: " + got);
    t.assertTrue(got.indexOf("/command unknown_field") >= 0, "must name unexpected 'command', got: " + got);
});

ScriptTest.run("64_schema_validation: type violation is rejected with both types named", function(t) {
    var e = null;
    try { Driver.invoke("mc.action.runCommand", { cmd: 42 }); } catch (x) { e = x; }
    t.assertEqual(e && e.issues[0].path + " " + e.issues[0].code, "/cmd type_mismatch", "cmd:42 must be a type error");
    t.assertTrue(e.issues[0].message.indexOf("string") >= 0, "names the wanted type: " + e.issues[0].message);
});

ScriptTest.run("64_schema_validation: unknown key on a valid call is rejected", function(t) {
    var got = issuesOf(function() { Driver.invoke("mc.system.waitTicks", { ticks: 1, bogus: true }); });
    t.assertTrue(got !== null && got.indexOf("/bogus unknown_field") >= 0, "got: " + got);
});

ScriptTest.run("64_schema_validation: enum violation names the allowed set", function(t) {
    // mc.bot.useItem hand is enum ["main","off"]
    var issue = null;
    try { Driver.invoke("mc.bot.useItem", { hand: "left" }); } catch (e) { issue = e.issues[0]; }
    t.assertEqual(issue.path, "/hand", "path");
    t.assertTrue(issue.message.indexOf("main") >= 0 && issue.message.indexOf("off") >= 0, "got: " + issue.message);
});

ScriptTest.run("64_schema_validation: integral double passes an integer slot", function(t) {
    // Driver.invoke round-trips params through JS JSON.stringify first, which
    // renders 1.0 as the token "1" — so this only ever exercises Long 1
    // through the integer slot, not JsonCodec's Double branch. Go under
    // Driver.invoke straight to __api.invokeJson with a hand-written JSON
    // body so the "1.0" token survives to JsonCodec.decode as a real Double
    // (JsonCodec parses any token containing '.' as Double.parseDouble).
    var json = __api.invokeJson("mc.system.waitTicks", '{"ticks":1.0}');
    var res = JSON.parse(json);
    t.assertTrue(res.error === undefined && res.result !== undefined,
        "waitTicks{ticks:1.0} (real Double) must be accepted, got: " + json);
});

ScriptTest.run("64_schema_validation: non-integral double on an integer slot is rejected", function(t) {
    // invokeJson answers with an envelope, {result} or {error}, instead of throwing.
    var res = JSON.parse(__api.invokeJson("mc.system.waitTicks", '{"ticks":1.5}'));
    var issue = res.error ? res.error.issues[0] : null;
    t.assertTrue(issue !== null && issue.path === "/ticks" && issue.code === "type_mismatch",
        "ticks:1.5 must reject as non-integer, got: " + JSON.stringify(res));
});

ScriptTest.run("64_schema_validation: free-form tuning rides in args, not beside it", function(t) {
    // A playbook's free-form tuning keys go in args, which is declared open JSON and becomes the
    // PLAYBOOK global; the params object itself is closed, so a stray key is refused where it sits.
    var e = null;
    try { Driver.invoke("mc.bot.playbook", { op: "status", freeform: 1 }); } catch (x) { e = x; }
    t.assertEqual(e && e.issues[0].code, "unknown_field", "a stray key is refused");
    t.assertEqual(e.issues[0].path, "/freeform", "at the key");
});
