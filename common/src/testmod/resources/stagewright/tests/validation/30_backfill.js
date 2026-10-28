// Run by kit.js's 'wd.validation' step, which says what ScriptTest is and what the check counts are.
test('30_backfill', { scene: ['wd.validation', 'wd.validationIntegrated'], isolated: true }, (t) => t.step('wd.validation', {
    script: '30_backfill', checks: { dedicated: 5, integrated: 5 }, DriverApiError: DriverApiError, body: (ScriptTest) => {

// Phase D8 — the autoBackfill settings behind BackfillProcess. The settings are this game's, so the
// round-trips run on every topology.

/** The DriverApiError a call throws, or null when it returned. */
function errorOf(call) {
    try { call(); } catch (e) { return e; }
    return null;
}

function read(keys) { return Driver.bot.setting(keys === undefined ? { op: "read" } : { op: "read", keys: keys }).values; }
function patch(values) { return Driver.bot.setting({ op: "patch", values: values }).values; }

ScriptTest.run("30_backfill: a full read includes the autoBackfill keys", function(t) {
    var s = read();
    t.assertEqual(typeof s.autoBackfill, "boolean", "autoBackfill is a boolean");
    t.assertEqual(typeof s.autoBackfillBlock, "string", "autoBackfillBlock is a string");
    t.assertEqual(typeof s.autoBackfillRadius, "number", "autoBackfillRadius is a number");
});

ScriptTest.run("30_backfill: autoBackfill toggle round-trips", function(t) {
    var before = read(["autoBackfill"]).autoBackfill;
    patch({ autoBackfill: !before });
    t.assertEqual(read(["autoBackfill"]).autoBackfill, !before, "toggled");
    patch({ autoBackfill: before });
});

ScriptTest.run("30_backfill: autoBackfillBlock refuses an unregistered id", function(t) {
    var before = read(["autoBackfillBlock"]).autoBackfillBlock;
    var e = errorOf(function() { patch({ autoBackfillBlock: "minecraft:not_a_real_block_xyz" }); });
    t.assertEqual(e.issues[0].path, "/values/autoBackfillBlock", "path");
    t.assertTrue(e.issues[0].message.indexOf("not_a_real_block_xyz") >= 0, "names the id: " + e.issues[0].message);
    t.assertEqual(read(["autoBackfillBlock"]).autoBackfillBlock, before, "unchanged");
});

ScriptTest.run("30_backfill: autoBackfillBlock accepts a real block id", function(t) {
    var before = read(["autoBackfillBlock"]);
    t.assertEqual(patch({ autoBackfillBlock: "minecraft:dirt" }).autoBackfillBlock, "minecraft:dirt", "dirt");
    patch(before);
    t.assertEqual(read(["autoBackfillBlock"]).autoBackfillBlock, before.autoBackfillBlock, "restored");
});

ScriptTest.run("30_backfill: autoBackfillRadius range bound [1,16]", function(t) {
    var before = read(["autoBackfillRadius"]);
    t.assertEqual(errorOf(function() { patch({ autoBackfillRadius: 0 }); }).issues[0].code,
        "out_of_range", "0 refused");
    t.assertEqual(errorOf(function() { patch({ autoBackfillRadius: 17 }); }).issues[0].code,
        "out_of_range", "17 refused");
    t.assertEqual(patch({ autoBackfillRadius: 4 }).autoBackfillRadius, 4, "4 committed");
    patch(before);
});

}}));
