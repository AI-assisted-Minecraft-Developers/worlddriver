// Phase D8 — autoBackfill setting + BackfillProcess. mc.bot.* is only
// available on a client JVM with a LocalPlayer; on dedicated-server GameTest
// CI we PASS-as-skipped.

function botAvailable() {
    try {
        Agent.invoke("mc.bot.setting", {});
        return true;
    } catch (e) {
        return false;
    }
}

if (!botAvailable()) {
    ScriptTest.run("30_backfill: skipped (no mc.bot — dedicated server)",
        function(t) { /* PASS */ });
} else {

    ScriptTest.run("30_backfill: setting snapshot includes autoBackfill keys",
        function(t) {
            var r = Agent.invoke("mc.bot.setting", {});
            t.assertEqual(r.ok, true, "ok");
            t.assertTrue("autoBackfill" in r.settings, "autoBackfill key");
            t.assertTrue("autoBackfillBlock" in r.settings, "autoBackfillBlock key");
            t.assertTrue("autoBackfillRadius" in r.settings, "autoBackfillRadius key");
            t.assertEqual(typeof r.settings.autoBackfill, "boolean", "bool type");
            t.assertEqual(typeof r.settings.autoBackfillBlock, "string", "string type");
            t.assertEqual(typeof r.settings.autoBackfillRadius, "number", "number type");
        });

    ScriptTest.run("30_backfill: autoBackfill toggle round-trips", function(t) {
        var before = Agent.invoke("mc.bot.setting", {}).settings.autoBackfill;
        var r = Agent.invoke("mc.bot.setting", { autoBackfill: !before });
        t.assertEqual(r.ok, true, "ok");
        t.assertTrue((r.applied || []).indexOf("autoBackfill") >= 0, "applied list");
        var after = Agent.invoke("mc.bot.setting", {}).settings.autoBackfill;
        t.assertEqual(after, !before, "toggled");
        Agent.invoke("mc.bot.setting", { autoBackfill: before });
    });

    ScriptTest.run("30_backfill: autoBackfillBlock validation rejects bad id",
        function(t) {
            var r = Agent.invoke("mc.bot.setting", { autoBackfillBlock: "minecraft:not_a_real_block_xyz" });
            t.assertEqual(r.ok, true, "settings call still returns ok");
            t.assertTrue((r.applied || []).indexOf("autoBackfillBlock") < 0, "not applied");
            var rejStr = JSON.stringify(r.rejected || []);
            t.assertTrue(rejStr.indexOf("autoBackfillBlock") >= 0, "rejection mentions field");
            var snap = Agent.invoke("mc.bot.setting", {}).settings;
            t.assertTrue(snap.autoBackfillBlock !== "minecraft:not_a_real_block_xyz",
                    "snapshot unchanged");
        });

    ScriptTest.run("30_backfill: autoBackfillBlock accepts a real block id",
        function(t) {
            var r = Agent.invoke("mc.bot.setting", { autoBackfillBlock: "minecraft:dirt" });
            t.assertEqual(r.ok, true, "ok");
            t.assertTrue((r.applied || []).indexOf("autoBackfillBlock") >= 0, "applied");
            t.assertEqual(r.settings.autoBackfillBlock, "minecraft:dirt", "snapshot");
            var restore = Agent.invoke("mc.bot.setting", { autoBackfillBlock: "minecraft:cobblestone" });
            t.assertEqual(restore.settings.autoBackfillBlock, "minecraft:cobblestone", "restored");
        });

    ScriptTest.run("30_backfill: autoBackfillRadius range bound [1,16]",
        function(t) {
            var lo = Agent.invoke("mc.bot.setting", { autoBackfillRadius: 0 });
            t.assertTrue((lo.applied || []).indexOf("autoBackfillRadius") < 0, "0 rejected");
            var hi = Agent.invoke("mc.bot.setting", { autoBackfillRadius: 17 });
            t.assertTrue((hi.applied || []).indexOf("autoBackfillRadius") < 0, "17 rejected");
            var mid = Agent.invoke("mc.bot.setting", { autoBackfillRadius: 4 });
            t.assertTrue((mid.applied || []).indexOf("autoBackfillRadius") >= 0, "4 applied");
            t.assertEqual(mid.settings.autoBackfillRadius, 4, "snapshot value");
            Agent.invoke("mc.bot.setting", { autoBackfillRadius: 6 });
        });
}
