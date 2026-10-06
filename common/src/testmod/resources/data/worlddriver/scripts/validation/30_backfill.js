// Phase D8 — the autoBackfill settings behind BackfillProcess. The settings are this game's, so the
// round-trips run on every topology.

/** The DriverApiError a call throws, or null when it returned. */
function errorOf(call) {
    try { call(); } catch (e) { return e; }
    return null;
}

ScriptTest.run("30_backfill: a full read includes the autoBackfill keys", function(t) {
    var s = Driver.bot.readSettings();
    t.assertEqual(typeof s.autoBackfill, "boolean", "autoBackfill is a boolean");
    t.assertEqual(typeof s.autoBackfillBlock, "string", "autoBackfillBlock is a string");
    t.assertEqual(typeof s.autoBackfillRadius, "number", "autoBackfillRadius is a number");
});

ScriptTest.run("30_backfill: autoBackfill toggle round-trips", function(t) {
    var before = Driver.bot.readSettings(["autoBackfill"]).autoBackfill;
    Driver.bot.patchSettings({ autoBackfill: !before });
    t.assertEqual(Driver.bot.readSettings(["autoBackfill"]).autoBackfill, !before, "toggled");
    Driver.bot.patchSettings({ autoBackfill: before });
});

ScriptTest.run("30_backfill: autoBackfillBlock refuses an unregistered id", function(t) {
    var before = Driver.bot.readSettings(["autoBackfillBlock"]).autoBackfillBlock;
    var e = errorOf(function() { Driver.bot.patchSettings({ autoBackfillBlock: "minecraft:not_a_real_block_xyz" }); });
    t.assertEqual(e.issues[0].path, "/updates/0/value", "path");
    t.assertTrue(e.issues[0].message.indexOf("not_a_real_block_xyz") >= 0, "names the id: " + e.issues[0].message);
    t.assertEqual(Driver.bot.readSettings(["autoBackfillBlock"]).autoBackfillBlock, before, "unchanged");
});

ScriptTest.run("30_backfill: autoBackfillBlock accepts a real block id", function(t) {
    var before = Driver.bot.readSettings(["autoBackfillBlock"]);
    t.assertEqual(Driver.bot.patchSettings({ autoBackfillBlock: "minecraft:dirt" }).values[0].value, "minecraft:dirt", "dirt");
    Driver.bot.patchSettings(before);
    t.assertEqual(Driver.bot.readSettings(["autoBackfillBlock"]).autoBackfillBlock, before.autoBackfillBlock, "restored");
});

ScriptTest.run("30_backfill: autoBackfillRadius range bound [1,16]", function(t) {
    var before = Driver.bot.readSettings(["autoBackfillRadius"]);
    t.assertEqual(errorOf(function() { Driver.bot.patchSettings({ autoBackfillRadius: 0 }); }).issues[0].code,
        "out_of_range", "0 refused");
    t.assertEqual(errorOf(function() { Driver.bot.patchSettings({ autoBackfillRadius: 17 }); }).issues[0].code,
        "out_of_range", "17 refused");
    t.assertEqual(Driver.bot.patchSettings({ autoBackfillRadius: 4 }).values[0].value, 4, "4 committed");
    Driver.bot.patchSettings(before);
});
