// The fields that used to be typeless "accept anything" schema nodes. A live repro showed an MCP
// client treating a typeless field as licence to JSON-stringify the value before tools/call:
// mc.bot.combat {target:99999} arrived as targetType:"minecraft:99999". Those fields are now
// tagged unions or open JSON with every kind spelled out in the schema. This suite exercises the
// typed decoder's acceptance of every declared member and its rejection of a kind outside them;
// only tools/call is reachable from this Rhino suite, so the rendered schemas are not checked here.

function errOf(fn) {
    try { fn(); return null; } catch (e) { return String(e); }
}

// ---- mc.bot.combat target: a tagged union, so a bare number or string is a type error ----
// A bot that does not exist answers the requests that decode, so none of them starts a fight.

var NOBODY = "npc:nobody_65";

ScriptTest.run("65_schema_union: combat.target takes an entity id or an entity type, by kind", function(t) {
    [ { kind: "entity", entityId: 3298 }, { kind: "entityType", type: "minecraft:zombie" } ].forEach(function(target) {
        var r = Driver.bot.combat({ bot: NOBODY, mode: "kill", target: target });
        t.assertEqual(r.reason, "bot_unavailable", target.kind + " decodes and reaches the body: " + JSON.stringify(r));
    });
});

ScriptTest.run("65_schema_union: combat.target is not a bare number, string or array", function(t) {
    [ 3298, "minecraft:zombie", [1, 2, 3] ].forEach(function(target) {
        var p = issuePath(function() { Driver.bot.combat({ bot: NOBODY, mode: "kill", target: target }); });
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
        Driver.invoke("mc.bot.follow", { bot: NOBODY, target: { kind: "player", name: "nobody_65" },
            route: { sight: { observers: ["minecraft:skeleton"] } } });
    });
    t.assertTrue(p === null || p.indexOf("/route") !== 0, "observers must decode, got issue at " + p);
});

// ---- deep-equal / arbitrary-payload sites: ["object","array","string","number","boolean"] ----

ScriptTest.run("65_schema_union: wait.condition.value accepts an object compare target", function(t) {
    var p = issuePath(function() {
        Driver.invoke("mc.wait.condition", { invoke: "mc.observe.cursor", value: { any: 1 }, timeoutMs: 100 });
    });
    t.assertTrue(p === null, "object value must decode, got issue at " + p);
});

ScriptTest.run("65_schema_union: wait.condition.value accepts an array compare target", function(t) {
    var p = issuePath(function() {
        Driver.invoke("mc.wait.condition", { invoke: "mc.observe.cursor", value: [1, 2], timeoutMs: 100 });
    });
    t.assertTrue(p === null, "array value must decode, got issue at " + p);
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
    var w = null;
    var p = issuePath(function() {
        w = Driver.invoke("mc.events", { op: "watch", invoke: "mc.observe.cursor", value: { a: 1 }, once: true });
    });
    if (w !== null) Driver.invoke("mc.events", { op: "unwatch", id: w.id });
    t.assertTrue(p === null, "object value must decode, got issue at " + p);
});
