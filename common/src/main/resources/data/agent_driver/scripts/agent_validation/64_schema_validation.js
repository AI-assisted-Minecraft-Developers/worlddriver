// Route-layer schema validation (feedback 2026-07-10 §4) — the validator runs
// inside AgentApi.route(), so violations surface as thrown errors (wrapped by
// Rhino), NOT as {ok:false} results. Server-side routes work headless; no
// clientAvailable guard needed for runCommand/query cases.

function errOf(fn) {
    try { fn(); return null; } catch (e) { return String(e); }
}

AgentTest.run("64_schema_validation: wrong field name names both the missing and the unexpected key", function(t) {
    var msg = errOf(function() { Agent.invoke("mc.action.runCommand", { command: "time query daytime" }); });
    t.assertTrue(msg !== null, "must reject");
    t.assertTrue(msg.indexOf("missing required 'cmd'") >= 0, "must name missing 'cmd', got: " + msg);
    t.assertTrue(msg.indexOf("unexpected key 'command'") >= 0, "must name unexpected 'command', got: " + msg);
});

AgentTest.run("64_schema_validation: type violation is rejected with both types named", function(t) {
    var msg = errOf(function() { Agent.invoke("mc.action.runCommand", { cmd: 42 }); });
    t.assertTrue(msg !== null && msg.indexOf("must be string") >= 0, "cmd:42 must be a type error, got: " + msg);
});

AgentTest.run("64_schema_validation: unknown key on a valid call is rejected", function(t) {
    var msg = errOf(function() { Agent.invoke("mc.system.waitTicks", { ticks: 1, bogus: true }); });
    t.assertTrue(msg !== null && msg.indexOf("unexpected key 'bogus'") >= 0, "got: " + msg);
});

AgentTest.run("64_schema_validation: enum violation names the allowed set", function(t) {
    // mc.bot.useItem hand is enum ["main","off"]
    var msg = errOf(function() { Agent.invoke("mc.bot.useItem", { hand: "left" }); });
    t.assertTrue(msg !== null && msg.indexOf("must be one of") >= 0, "got: " + msg);
});

AgentTest.run("64_schema_validation: integral double passes an integer slot", function(t) {
    // JSON decoders routinely hand integers over as doubles — 1.0 must be accepted.
    var res = Agent.invoke("mc.system.waitTicks", { ticks: 1.0 });
    t.assertTrue(res !== null && res !== undefined, "waitTicks{ticks:1.0} must be accepted");
});

AgentTest.run("64_schema_validation: additionalProperties(true) tool accepts unknown keys", function(t) {
    // mc.test.yaml is declared additionalProperties(true); calling with an unknown
    // key must NOT be a schema rejection. all:false is a no-op run request shape;
    // any non-validation outcome (ok or business error) passes.
    var msg = errOf(function() { Agent.invoke("mc.test.yaml", { freeform: 1, all: false }); });
    t.assertTrue(msg === null || msg.indexOf("unexpected key") < 0,
        "additionalProperties(true) must not reject unknown keys, got: " + msg);
});
