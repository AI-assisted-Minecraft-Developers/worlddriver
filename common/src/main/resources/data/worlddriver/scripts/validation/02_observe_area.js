// mc.observe.area was folded into mc.query — these tests now exercise the
// q='blocks' shape directly (flat array) and the prelude's back-compat
// helper (Driver.observe.area returning {blocks:[...]}).

ScriptTest.run("02_observe_area: query q='blocks' returns stones, respects filter.type", function(t) {
    var origin = Driver.system.testOrigin();
    var rows = Driver.invoke("mc.query", {
        q: "blocks",
        center: origin,
        filter: { in_radius: 4, type: "minecraft:stone" }
    });
    t.assertTrue(Array.isArray(rows), "result must be array");
    t.assertTrue(rows.length > 0, "expected at least one stone in 4-radius around origin");
    var allStone = true;
    for (var i = 0; i < rows.length; i++) {
        if (rows[i].type !== "minecraft:stone") { allStone = false; break; }
    }
    t.assertTrue(allStone, "filter.type should restrict to minecraft:stone");
});

ScriptTest.run("02_observe_area: oak_log found without filter (back-compat helper shape)", function(t) {
    var origin = Driver.system.testOrigin();
    // Use the prelude helper to assert it still returns the legacy {blocks:[]} shape.
    var result = Driver.observe.area({ center: origin, radius: 2 });
    t.assertTrue(Array.isArray(result.blocks), "back-compat helper must wrap rows in {blocks}");
    var hasLog = false;
    for (var i = 0; i < result.blocks.length; i++) {
        if (result.blocks[i].type === "minecraft:oak_log") { hasLog = true; break; }
    }
    t.assertTrue(hasLog, "expected oak_log in seeded world");
});
