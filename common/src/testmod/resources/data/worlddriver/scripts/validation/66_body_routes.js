// The mc.bot.* verbs that take `body` address a body by it. An id nothing is registered under must
// refuse with the same bytes on every transport, and status must list the registered bodies the same
// way on each. Needs no body of its own, so it runs on every topology; driving a registered body is the
// dedicated-server scenes' job.

function jsonStable(v) {
    if (v === null || typeof v !== "object") return JSON.stringify(v);
    if (Array.isArray(v)) {
        var parts = [];
        for (var i = 0; i < v.length; i++) parts.push(jsonStable(v[i]));
        return "[" + parts.join(",") + "]";
    }
    var keys = Object.keys(v).sort();
    var pairs = [];
    for (var i = 0; i < keys.length; i++) {
        pairs.push(JSON.stringify(keys[i]) + ":" + jsonStable(v[keys[i]]));
    }
    return "{" + pairs.join(",") + "}";
}

var NOBODY = "npc:validation-nobody";

function viaEveryTransport(method, params) {
    return [Driver.invoke(method, params),
            Driver.system.rpcRoundtrip(method, params),
            Driver.system.mcpRoundtrip(method, params)];
}

ScriptTest.run("66_body_routes: an unknown body refuses every verb that takes body alike on every transport", function(t) {
    var calls = [["mc.bot.goto", { body: NOBODY, pos: { x: 0, y: 64, z: 0 } }],
                 ["mc.bot.cancel", { body: NOBODY }],
                 ["mc.bot.status", { body: NOBODY }],
                 ["mc.bot.mine", { body: NOBODY, blocks: ["minecraft:stone"] }],
                 ["mc.bot.escape", { body: NOBODY }],
                 ["mc.bot.combat", { body: NOBODY, mode: "defend" }],
                 ["mc.bot.runAway", { body: NOBODY, awaitMs: 1000 }],
                 ["mc.bot.elytraFly", { body: NOBODY }],
                 ["mc.bot.lookAt", { body: NOBODY, yaw: 0, pitch: 0 }],
                 ["mc.bot.useItem", { body: NOBODY }]];
    for (var i = 0; i < calls.length; i++) {
        var method = calls[i][0];
        var r = viaEveryTransport(method, calls[i][1]);
        t.assertEqual(r[0].ok, false, method + " must refuse, got: " + JSON.stringify(r[0]));
        t.assertEqual(r[0].reason, "unknown_body", method + " reason, got: " + JSON.stringify(r[0]));
        t.assertEqual(jsonStable(r[0]), jsonStable(r[1]), method + ": in-JVM and TCP must agree");
        t.assertEqual(jsonStable(r[0]), jsonStable(r[2]), method + ": in-JVM and MCP must agree");
    }
});

ScriptTest.run("66_body_routes: status lists the same bodies on every transport", function(t) {
    var r = viaEveryTransport("mc.bot.status", {});
    for (var i = 0; i < r.length; i++) {
        t.assertTrue(Array.isArray(r[i].bodies),
            "transport " + i + " must answer bodies as an array, got: " + JSON.stringify(r[i].bodies));
    }
    // Ids and kinds only: a body another scene drives may move or finish between the three reads.
    function names(s) {
        return jsonStable(s.bodies.map(function(b) { return [b.id, b.kind]; }));
    }
    t.assertEqual(names(r[0]), names(r[1]), "in-JVM and TCP must list the same bodies");
    t.assertEqual(names(r[0]), names(r[2]), "in-JVM and MCP must list the same bodies");
});

ScriptTest.run("66_body_routes: a verb that does not route by body rejects it as an unknown key", function(t) {
    var msg = null;
    try { Driver.invoke("mc.bot.waypoint", { op: "list", body: NOBODY }); }
    catch (e) { msg = String(e); }
    t.assertTrue(msg !== null && msg.indexOf("unexpected key 'body'") >= 0, "got: " + msg);
});
