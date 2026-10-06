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

// ---- mc.bot.combat target: a tagged union, so a bare number or string is a type error ----
// A bot that does not exist answers the requests that decode, so none of them starts a fight.

var NOBODY = "npc:nobody_65";

ScriptTest.run("65_schema_union: combat.target takes an entity id or an entity type, by kind", function(t) {
    [ { kind: "entity", entityId: 3298 }, { kind: "entityType", type: "minecraft:zombie" } ].forEach(function(target) {
        var r = Driver.bot.combat({ body: NOBODY, mode: "kill", target: target });
        t.assertEqual(r.reason, "body_unavailable", target.kind + " decodes and reaches the body: " + JSON.stringify(r));
    });
});

ScriptTest.run("65_schema_union: combat.target is not a bare number, string or array", function(t) {
    [ 3298, "minecraft:zombie", [1, 2, 3] ].forEach(function(target) {
        var p = issuePath(function() { Driver.bot.combat({ body: NOBODY, mode: "kill", target: target }); });
        t.assertEqual(p, "/target", JSON.stringify(target) + " path");
    });
});

// ---- the route object's tagged unions, two levels down (route → leash → anchor) ----
// A typed route has no untagged unions left: a leash anchor names its kind, and an entity
// anchor is a string even when it holds an id. The paths prove the decoder descends.

function issuePath(call) {
    try { call(); } catch (e) { return e.issues && e.issues.length ? e.issues[0].path : String(e); }
    return null;
}

var AXIS = { kind: "axis" };

ScriptTest.run("65_schema_union: goto route.leash entity anchor takes a name or an id as a string", function(t) {
    [ "PlayerB", "3298" ].forEach(function(who) {
        var p = issuePath(function() {
            Driver.invoke("mc.bot.goto", { target: AXIS, route: { leash: { anchor: { kind: "entity", entity: who }, radius: 8 } } });
        });
        t.assertTrue(p === null, "entity anchor " + who + " must decode, got issue at " + p);
    });
});

ScriptTest.run("65_schema_union: goto route.leash entity anchor rejects a number", function(t) {
    var p = issuePath(function() {
        Driver.invoke("mc.bot.goto", { target: AXIS, route: { leash: { anchor: { kind: "entity", entity: 3298 }, radius: 8 } } });
    });
    t.assertEqual(p, "/route/leash/anchor/entity", "path");
});

ScriptTest.run("65_schema_union: goto route.leash anchor needs its kind", function(t) {
    var p = issuePath(function() {
        Driver.invoke("mc.bot.goto", { target: AXIS, route: { leash: { anchor: { entity: "PlayerB" }, radius: 8 } } });
    });
    t.assertEqual(p, "/route/leash/anchor/kind", "path");
});

ScriptTest.run("65_schema_union: follow route.sight takes a list of observers", function(t) {
    var p = issuePath(function() {
        Driver.invoke("mc.bot.follow", { body: NOBODY, target: { kind: "player", name: "nobody_65" },
            route: { sight: { observers: ["minecraft:skeleton"] } } });
    });
    t.assertTrue(p === null || p.indexOf("/route") !== 0, "observers must decode, got issue at " + p);
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
