// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test('02_observe_area', { scene: ['wd.validation', 'wd.validationIntegrated'], isolated: true }, (t) => t.step('wd.validation', {
    script: '02_observe_area', DriverApiError: DriverApiError, body: (ScriptTest) => {

// mc.observe.area was folded into mc.query — these tests exercise the q='blocks'
// shape ({q, blocks}), through Driver.invoke and the generated Driver.query.

ScriptTest.run("02_observe_area: query q='blocks' returns stones, respects filter.type", function(t) {
    var origin = Driver.system.testOrigin();
    var reply = Driver.invoke("mc.query", {
        q: "blocks",
        center: origin,
        filter: { in_radius: 4, type: "minecraft:stone" }
    });
    t.assertEqual(reply.q, "blocks", "the reply names its variant");
    var rows = reply.blocks;
    t.assertTrue(Array.isArray(rows), "blocks must be an array");
    t.assertTrue(rows.length > 0, "expected at least one stone in 4-radius around origin");
    var allStone = true;
    for (var i = 0; i < rows.length; i++) {
        if (rows[i].type !== "minecraft:stone") { allStone = false; break; }
    }
    t.assertTrue(allStone, "filter.type should restrict to minecraft:stone");
});

ScriptTest.run("02_observe_area: oak_log found without a type filter", function(t) {
    var origin = Driver.system.testOrigin();
    var result = Driver.query({ q: "blocks", center: origin, filter: { in_radius: 2 } });
    t.assertTrue(Array.isArray(result.blocks), "rows come in {blocks}");
    var hasLog = false;
    for (var i = 0; i < result.blocks.length; i++) {
        if (result.blocks[i].type === "minecraft:oak_log") { hasLog = true; break; }
    }
    t.assertTrue(hasLog, "expected oak_log in seeded world");
});

}}));
