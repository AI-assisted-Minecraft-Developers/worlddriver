// Union-type schema nodes for the 7 any() sites (gap#67-④). Root cause: a typeless
// Schema.Any node renders NO "type" key at all (JSON-Schema's own "accept anything"
// form) — live repro showed at least one MCP client treats a typeless field as license
// to JSON-stringify the value before tools/call: mc.bot.combat {target:99999} (a JSON
// number) arrived server-side as targetType:"minecraft:99999", and {"id":99999}
// arrived as the literal string '{"id": 99999}'. raw RPC (no schema lookup) was
// unaffected. Fix: Schema.Union renders "type":[...] (a JSON array) so the client has
// something to preserve. This suite exercises SchemaValidator's acceptance of every
// declared union member (still permissive, like Any) and its rejection of a type
// outside the union (same strictness as the six concrete kinds). The rendering side
// (tools/list inputSchema actually carrying the "type" array) is asserted separately as
// a Java-level matrix — AgentGameTest.schemaUnionRendering — since no JS bridge reaches
// raw MCP tools/list (only tools/call is exposed to this Rhino suite).

function errOf(fn) {
    try { fn(); return null; } catch (e) { return String(e); }
}

// ---- mc.bot.combat target: ["integer","string","object"] ----------------------

AgentTest.run("65_schema_union: combat.target accepts integer", function(t) {
    var msg = errOf(function() { Agent.invoke("mc.bot.combat", { target: 3298 }); });
    t.assertTrue(msg === null || msg.indexOf("must be one of types") < 0,
        "integer target must not be rejected by the union, got: " + msg);
});

AgentTest.run("65_schema_union: combat.target accepts string", function(t) {
    var msg = errOf(function() { Agent.invoke("mc.bot.combat", { target: "minecraft:zombie" }); });
    t.assertTrue(msg === null || msg.indexOf("must be one of types") < 0,
        "string target must not be rejected by the union, got: " + msg);
});

AgentTest.run("65_schema_union: combat.target accepts object", function(t) {
    var msg = errOf(function() { Agent.invoke("mc.bot.combat", { target: { id: 3298 } }); });
    t.assertTrue(msg === null || msg.indexOf("must be one of types") < 0,
        "object target must not be rejected by the union, got: " + msg);
});

AgentTest.run("65_schema_union: combat.target rejects array", function(t) {
    var msg = errOf(function() { Agent.invoke("mc.bot.combat", { target: [1, 2, 3] }); });
    t.assertTrue(msg !== null && msg.indexOf("must be one of types") >= 0,
        "array target must be rejected by the union, got: " + msg);
});

// ---- mc.bot.goto / mc.bot.explore hugShore: ["boolean","object"] ---------------

AgentTest.run("65_schema_union: goto.hugShore accepts bare true", function(t) {
    var msg = errOf(function() { Agent.invoke("mc.bot.goto", { hugShore: true }); });
    t.assertTrue(msg === null || msg.indexOf("hugShore") < 0,
        "bare true hugShore must not be rejected by the union, got: " + msg);
});

AgentTest.run("65_schema_union: goto.hugShore accepts {weight}", function(t) {
    var msg = errOf(function() { Agent.invoke("mc.bot.goto", { hugShore: { weight: 40 } }); });
    t.assertTrue(msg === null || msg.indexOf("hugShore") < 0,
        "object hugShore must not be rejected by the union, got: " + msg);
});

AgentTest.run("65_schema_union: goto.hugShore rejects string", function(t) {
    var msg = errOf(function() { Agent.invoke("mc.bot.goto", { hugShore: "yes" }); });
    t.assertTrue(msg !== null && msg.indexOf("must be one of types") >= 0,
        "string hugShore must be rejected by the union, got: " + msg);
});

AgentTest.run("65_schema_union: follow.hugShore accepts bare true", function(t) {
    var msg = errOf(function() { Agent.invoke("mc.bot.follow", { hugShore: true }); });
    t.assertTrue(msg === null || msg.indexOf("hugShore") < 0,
        "bare true hugShore must not be rejected by the union, got: " + msg);
});

// ---- deep-equal / arbitrary-payload sites: ["object","array","string","number","boolean"] ----

AgentTest.run("65_schema_union: wait.condition.value accepts an object compare target", function(t) {
    var msg = errOf(function() {
        Agent.invoke("mc.wait.condition", { invoke: "mc.system.version", value: { any: 1 }, timeoutMs: 100 });
    });
    t.assertTrue(msg === null || msg.indexOf("'value'") < 0,
        "object value must not be rejected by the union, got: " + msg);
});

AgentTest.run("65_schema_union: wait.condition.value accepts an array compare target", function(t) {
    var msg = errOf(function() {
        Agent.invoke("mc.wait.condition", { invoke: "mc.system.version", value: [1, 2], timeoutMs: 100 });
    });
    t.assertTrue(msg === null || msg.indexOf("'value'") < 0,
        "array value must not be rejected by the union, got: " + msg);
});

AgentTest.run("65_schema_union: skill.args accepts an array", function(t) {
    var msg = errOf(function() {
        Agent.invoke("mc.skill", { op: "run", name: "does_not_exist_65", args: [1, 2, 3] });
    });
    t.assertTrue(msg === null || msg.indexOf("'args'") < 0,
        "array args must not be rejected by the union, got: " + msg);
});

AgentTest.run("65_schema_union: skill.args accepts a scalar", function(t) {
    var msg = errOf(function() {
        Agent.invoke("mc.skill", { op: "run", name: "does_not_exist_65", args: 42 });
    });
    t.assertTrue(msg === null || msg.indexOf("'args'") < 0,
        "scalar args must not be rejected by the union, got: " + msg);
});

AgentTest.run("65_schema_union: events.data accepts an object (emit)", function(t) {
    var msg = errOf(function() {
        Agent.invoke("mc.events", { op: "emit", type: "custom.65test", data: { a: 1 } });
    });
    t.assertTrue(msg === null || msg.indexOf("'data'") < 0,
        "object data must not be rejected by the union, got: " + msg);
});

AgentTest.run("65_schema_union: events.data accepts an array (emit)", function(t) {
    var msg = errOf(function() {
        Agent.invoke("mc.events", { op: "emit", type: "custom.65test", data: [1, 2, 3] });
    });
    t.assertTrue(msg === null || msg.indexOf("'data'") < 0,
        "array data must not be rejected by the union, got: " + msg);
});

AgentTest.run("65_schema_union: events.value accepts an object (watch predicate)", function(t) {
    var msg = errOf(function() {
        Agent.invoke("mc.events", { op: "watch", invoke: "mc.system.version", value: { a: 1 }, once: true });
    });
    t.assertTrue(msg === null || msg.indexOf("'value'") < 0,
        "object value must not be rejected by the union, got: " + msg);
});
