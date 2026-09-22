// Union-type schema nodes for the catalog's former any() sites (gap#67-④). Root cause: a typeless
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

ScriptTest.run("65_schema_union: combat.target accepts integer", function(t) {
    var msg = errOf(function() { Driver.invoke("mc.bot.combat", { target: 3298 }); });
    t.assertTrue(msg === null || msg.indexOf("must be one of types") < 0,
        "integer target must not be rejected by the union, got: " + msg);
});

ScriptTest.run("65_schema_union: combat.target accepts string", function(t) {
    var msg = errOf(function() { Driver.invoke("mc.bot.combat", { target: "minecraft:zombie" }); });
    t.assertTrue(msg === null || msg.indexOf("must be one of types") < 0,
        "string target must not be rejected by the union, got: " + msg);
});

ScriptTest.run("65_schema_union: combat.target accepts object", function(t) {
    var msg = errOf(function() { Driver.invoke("mc.bot.combat", { target: { id: 3298 } }); });
    t.assertTrue(msg === null || msg.indexOf("must be one of types") < 0,
        "object target must not be rejected by the union, got: " + msg);
});

ScriptTest.run("65_schema_union: combat.target rejects array", function(t) {
    var msg = errOf(function() { Driver.invoke("mc.bot.combat", { target: [1, 2, 3] }); });
    t.assertTrue(msg !== null && msg.indexOf("must be one of types") >= 0,
        "array target must be rejected by the union, got: " + msg);
});

// ---- mc.bot.goto / mc.bot.follow route.leash.entity: ["string","integer"] --------
// The route object's unions are NESTED (route → leash → entity), so these also prove the
// validator descends into object props: the old top-level hugShore union went away with
// the route hard cut, and a union two levels down is the one an LLM client now sees.

ScriptTest.run("65_schema_union: goto route.leash.entity accepts a name", function(t) {
    var msg = errOf(function() { Driver.invoke("mc.bot.goto", { route: { leash: { entity: "PlayerB", radius: 8 } } }); });
    t.assertTrue(msg === null || msg.indexOf("must be one of types") < 0,
        "string leash.entity must not be rejected by the union, got: " + msg);
});

ScriptTest.run("65_schema_union: goto route.leash.entity accepts an id", function(t) {
    var msg = errOf(function() { Driver.invoke("mc.bot.goto", { route: { leash: { entity: 3298, radius: 8 } } }); });
    t.assertTrue(msg === null || msg.indexOf("must be one of types") < 0,
        "integer leash.entity must not be rejected by the union, got: " + msg);
});

ScriptTest.run("65_schema_union: goto route.leash.entity rejects an object", function(t) {
    var msg = errOf(function() { Driver.invoke("mc.bot.goto", { route: { leash: { entity: { id: 3298 }, radius: 8 } } }); });
    t.assertTrue(msg !== null && msg.indexOf("must be one of types") >= 0,
        "object leash.entity must be rejected by the union, got: " + msg);
});

ScriptTest.run("65_schema_union: follow route.sight.of accepts a list", function(t) {
    var msg = errOf(function() { Driver.invoke("mc.bot.follow", { name: "nobody_65", route: { sight: { of: ["minecraft:skeleton"] } } }); });
    t.assertTrue(msg === null || msg.indexOf("must be one of types") < 0,
        "array sight.of must not be rejected by the union, got: " + msg);
});

// ---- deep-equal / arbitrary-payload sites: ["object","array","string","number","boolean"] ----

ScriptTest.run("65_schema_union: wait.condition.value accepts an object compare target", function(t) {
    var msg = errOf(function() {
        Driver.invoke("mc.wait.condition", { invoke: "mc.system.version", value: { any: 1 }, timeoutMs: 100 });
    });
    t.assertTrue(msg === null || msg.indexOf("'value'") < 0,
        "object value must not be rejected by the union, got: " + msg);
});

ScriptTest.run("65_schema_union: wait.condition.value accepts an array compare target", function(t) {
    var msg = errOf(function() {
        Driver.invoke("mc.wait.condition", { invoke: "mc.system.version", value: [1, 2], timeoutMs: 100 });
    });
    t.assertTrue(msg === null || msg.indexOf("'value'") < 0,
        "array value must not be rejected by the union, got: " + msg);
});

ScriptTest.run("65_schema_union: skill.args accepts an array", function(t) {
    var msg = errOf(function() {
        Driver.invoke("mc.skill", { op: "run", name: "does_not_exist_65", args: [1, 2, 3] });
    });
    t.assertTrue(msg === null || msg.indexOf("'args'") < 0,
        "array args must not be rejected by the union, got: " + msg);
});

ScriptTest.run("65_schema_union: skill.args accepts a scalar", function(t) {
    var msg = errOf(function() {
        Driver.invoke("mc.skill", { op: "run", name: "does_not_exist_65", args: 42 });
    });
    t.assertTrue(msg === null || msg.indexOf("'args'") < 0,
        "scalar args must not be rejected by the union, got: " + msg);
});

ScriptTest.run("65_schema_union: events.data accepts an object (emit)", function(t) {
    var msg = errOf(function() {
        Driver.invoke("mc.events", { op: "emit", type: "custom.65test", data: { a: 1 } });
    });
    t.assertTrue(msg === null || msg.indexOf("'data'") < 0,
        "object data must not be rejected by the union, got: " + msg);
});

ScriptTest.run("65_schema_union: events.data accepts an array (emit)", function(t) {
    var msg = errOf(function() {
        Driver.invoke("mc.events", { op: "emit", type: "custom.65test", data: [1, 2, 3] });
    });
    t.assertTrue(msg === null || msg.indexOf("'data'") < 0,
        "array data must not be rejected by the union, got: " + msg);
});

ScriptTest.run("65_schema_union: events.value accepts an object (watch predicate)", function(t) {
    var msg = errOf(function() {
        Driver.invoke("mc.events", { op: "watch", invoke: "mc.system.version", value: { a: 1 }, once: true });
    });
    t.assertTrue(msg === null || msg.indexOf("'value'") < 0,
        "object value must not be rejected by the union, got: " + msg);
});
