// Regression guard for two feedback-driven fixes (docs/feedback/):
//  - 9f5af00: mc.action.runCommand returns the command's own outcome
//    (success/value via CommandResultCallback, feedback[] collected)
//    instead of suppressing output.
//  - 7592620: mc.query q:"entities" honors filter.type (exact id).
// Both were shipped without tests; this script is their guard.

ScriptTest.run("58_cmd_result: seed returns success + feedback text", function(t) {
    var r = Driver.invoke("mc.action.runCommand", { cmd: "seed" });
    t.assertEqual(r.ok, true, "seed should dispatch");
    t.assertEqual(r.success, true, "seed should report success");
    t.assertHasKey(r, "value");
    t.assertHasKey(r, "feedback");
    t.assertTrue(r.feedback.length >= 1, "seed should emit one feedback line");
    t.assertTrue(String(r.feedback[0]).toLowerCase().indexOf("seed") >= 0,
        "feedback should contain the seed text, got: " + r.feedback[0]);
});

ScriptTest.run("58_cmd_result: execute-if count lands in value", function(t) {
    var origin = Driver.system.testOrigin();
    var x = origin.x + 6, y = origin.y + 1, z = origin.z + 6;
    // The gametest world persists across runs (run-gametest/world), and an
    // aborted earlier run can leave tagged strays behind — kill defensively so
    // the counts below are hermetic. (This "entity soup" gotcha was first written
    // up in the retired docs/yaml-gametest.md §12.5; it is a property of the
    // persistent world, not of that harness, so it outlived the doc.)
    Driver.invoke("mc.action.runCommand", { cmd: "kill @e[tag=t58]" });
    Driver.invoke("mc.action.runCommand",
        { cmd: "summon minecraft:armor_stand " + x + " " + y + " " + z + " {Tags:[\"t58\"]}" });
    Driver.invoke("mc.action.runCommand",
        { cmd: "summon minecraft:armor_stand " + (x + 1) + " " + y + " " + z + " {Tags:[\"t58\"]}" });

    var hit = Driver.invoke("mc.action.runCommand", { cmd: "execute if entity @e[tag=t58]" });
    t.assertEqual(hit.ok, true);
    t.assertEqual(hit.success, true, "matching selector should succeed");
    t.assertEqual(hit.value, 2, "value should be the match count, got " + hit.value);

    var miss = Driver.invoke("mc.action.runCommand", { cmd: "execute if entity @e[tag=t58_nope]" });
    t.assertEqual(miss.ok, true, "dispatch is still ok on a no-match predicate");
    t.assertEqual(miss.success, false, "no-match predicate should report failure");
});

ScriptTest.run("58_cmd_result: data get surfaces NBT text in feedback", function(t) {
    var r = Driver.invoke("mc.action.runCommand",
        { cmd: "data get entity @e[tag=t58,limit=1] Tags" });
    t.assertEqual(r.ok, true);
    t.assertEqual(r.success, true, "data get on an existing entity should succeed");
    t.assertTrue(r.feedback.length >= 1, "data get should emit feedback");
    t.assertTrue(String(r.feedback[0]).indexOf("t58") >= 0,
        "feedback should contain the tag NBT, got: " + r.feedback[0]);
});

ScriptTest.run("58_query_type: entities filter.type restricts rows", function(t) {
    var origin = Driver.system.testOrigin();
    var rows = Driver.query({
        q: "entities",
        center: { x: origin.x + 6, y: origin.y + 1, z: origin.z + 6 },
        filter: { in_radius: 8, type: "minecraft:armor_stand" }
    });
    t.assertTrue(Array.isArray(rows), "query must return array");
    t.assertEqual(rows.length, 2, "exactly the 2 tagged stands, got " + rows.length);
    for (var i = 0; i < rows.length; i++) {
        t.assertEqual(rows[i].type, "minecraft:armor_stand");
    }
    // Bare path gets the minecraft: namespace prepended.
    var bare = Driver.query({
        q: "entities",
        center: { x: origin.x + 6, y: origin.y + 1, z: origin.z + 6 },
        filter: { in_radius: 8, type: "armor_stand" }
    });
    t.assertEqual(bare.length, 2, "bare id should namespace to minecraft:");

    // Cleanup so entity-count-sensitive tests stay deterministic on reruns.
    var kill = Driver.invoke("mc.action.runCommand", { cmd: "kill @e[tag=t58]" });
    t.assertEqual(kill.ok, true);
    t.assertEqual(kill.success, true, "cleanup kill should succeed");
});
