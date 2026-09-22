ScriptTest.run("05_query: entities + select restricts fields", function(t) {
    var res = Driver.query({
        q: "entities",
        filter: { in_radius: 16, is_hostile: false },
        select: ["pos", "type"]
    });
    t.assertTrue(Array.isArray(res), "query must return array");
    // Count the seeded props, not every row. A player is a non-hostile entity and is returned
    // like any other, so "exactly 2" only held while the player happened to be standing somewhere
    // else — an accidental precondition that broke the moment the suite started running with the
    // player on the seeded pad, where they belong. Excluding players keeps the assertion strict
    // (still exactly the cow and the sheep) without tying it to where the player is.
    var props = res.filter(function (r) { return r.type !== "minecraft:player"; });
    t.assertTrue(props.length === 2,
        "the two seeded props are there, got " + props.length + " non-player rows of " + res.length);
    t.assertHasKey(props[0], "pos");
    t.assertHasKey(props[0], "type");
    t.assertFalse("health" in props[0], "select should drop health field");
});

ScriptTest.run("05_query: blocks q returns at least the seeded stones", function(t) {
    var res = Driver.query({
        q: "blocks",
        filter: { in_radius: 4 },
        select: ["pos", "type"]
    });
    var stones = res.filter(function(r) { return r.type === "minecraft:stone"; });
    t.assertTrue(stones.length >= 1, "expected at least 1 stone via query DSL");
});
