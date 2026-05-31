AgentTest.run("05_query: entities + select restricts fields", function(t) {
    var res = Agent.query({
        q: "entities",
        filter: { in_radius: 16, is_hostile: false },
        select: ["pos", "type"]
    });
    t.assertTrue(Array.isArray(res), "query must return array");
    t.assertTrue(res.length === 2, "mock world has exactly 2 entities, got " + res.length);
    t.assertHasKey(res[0], "pos");
    t.assertHasKey(res[0], "type");
    t.assertFalse("health" in res[0], "select should drop health field");
});

AgentTest.run("05_query: blocks q returns at least the seeded stones", function(t) {
    var res = Agent.query({
        q: "blocks",
        filter: { in_radius: 4 },
        select: ["pos", "type"]
    });
    var stones = res.filter(function(r) { return r.type === "minecraft:stone"; });
    t.assertTrue(stones.length >= 1, "expected at least 1 stone via query DSL");
});
