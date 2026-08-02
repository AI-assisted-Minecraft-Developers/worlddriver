// Crucial: prove in-JVM path and TCP path are structurally identical.

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

ScriptTest.run("06_rpc_parity: query q='blocks' identical via in-JVM and TCP", function(t) {
    var origin = Agent.system.testOrigin();
    var params = { q: "blocks", center: origin,
                   filter: { in_radius: 4, type: "minecraft:stone" } };
    var direct = Agent.invoke("mc.query", params);
    var viaTcp = Agent.system.rpcRoundtrip("mc.query", params);
    t.assertEqual(jsonStable(direct), jsonStable(viaTcp),
        "in-JVM and TCP block-scan results must be byte-identical");
});

ScriptTest.run("06_rpc_parity: system.version identical via in-JVM and TCP", function(t) {
    var direct = Agent.invoke("mc.system.version", {});
    var viaTcp = Agent.system.rpcRoundtrip("mc.system.version", {});
    // uptimeMs is time-sensitive — strip before compare
    delete direct.uptimeMs;
    delete viaTcp.uptimeMs;
    t.assertEqual(jsonStable(direct), jsonStable(viaTcp),
        "version metadata must match across paths");
});

ScriptTest.run("06_rpc_parity: query.entities identical via both paths", function(t) {
    var params = { q: "entities", filter: { in_radius: 16, is_hostile: false }, select: ["pos","type","health"] };
    var direct = Agent.invoke("mc.query", params);
    var viaTcp = Agent.system.rpcRoundtrip("mc.query", params);
    t.assertEqual(jsonStable(direct), jsonStable(viaTcp));
});
