// YAML GameTest transpiler (docs/yaml-gametest.md). Exercises the full pipeline
// in-JVM via the mc.test.yaml route: parse inline YAML -> snapshot -> setup ->
// asserts -> restore. Coordinates are injected from testOrigin so the spec lands
// in the air above the seeded arena (ORIGIN y=200) regardless of where it sits.
// The @GameTestGenerator hook (step 4) exercises the file-loading path separately.

function yamlFor(o, y, type, badType) {
    var p = "[" + o.x + ", " + y + ", " + o.z + "]";
    return [
        "- name: inline-place-and-observe",
        "  region:",
        "    from: " + p,
        "    to: " + p,
        "  setup:",
        "    - place: { pos: " + p + ", type: " + type + " }",
        "  asserts:",
        "    - block_present: { pos: " + p + ", type: " + type + " }",
        "    - block_present: { pos: " + p + ", type: minecraft:* }",
        "    - block_absent: { pos: " + p + ", type: " + badType + " }"
    ].join("\n");
}

ScriptTest.run("34_yaml_gametest: inline spec runs setup+asserts and passes", function (t) {
    var o = Agent.system.testOrigin();
    var y = o.y + 6;
    var res = Agent.invoke("mc.test.yaml", {
        inline: yamlFor(o, y, "minecraft:cobblestone", "minecraft:diamond_block")
    });
    t.assertEqual(res.passed, 1, "exactly one spec passed");
    t.assertEqual(res.failed, 0, "no spec failed; failures=" + JSON.stringify(res.results[0].failures));
    t.assertEqual(res.results[0].pass, true, "spec marked pass");
});

ScriptTest.run("34_yaml_gametest: region is restored after the run", function (t) {
    var o = Agent.system.testOrigin();
    var y = o.y + 7;
    var cell = { x: o.x, y: y, z: o.z };
    // Pre-condition: the target cell starts as air.
    var before = Agent.observe.area({ center: cell, radius: 0 });
    t.assertEqual(before.blocks.length, 0, "target cell is air before the run");

    var res = Agent.invoke("mc.test.yaml", {
        inline: yamlFor(o, y, "minecraft:gold_block", "minecraft:diamond_block")
    });
    t.assertEqual(res.failed, 0, "spec passed; failures=" + JSON.stringify(res.results[0].failures));

    // The spec snapshotted the cell (air) then placed gold; restore must revert it.
    Agent.invoke("mc.system.waitTicks", { ticks: 1 });
    var after = Agent.observe.area({ center: cell, radius: 0 });
    t.assertEqual(after.blocks.length, 0, "target cell is air again after restore");
});

ScriptTest.run("34_yaml_gametest: loads a classpath file via file:", function (t) {
    // Exercises the resource-loading path (YamlTestLoader.loadFile + snakeyaml).
    var res = Agent.invoke("mc.test.yaml", { file: "smoke_place_observe.yaml" });
    t.assertEqual(res.failed, 0, "smoke file passed; failures="
        + JSON.stringify(res.results.map(function (r) { return r.failures; })));
    t.assertEqual(res.passed, 1, "one spec in the smoke file");
    t.assertEqual(res.results[0].name, "smoke-place-observe", "spec name from the file");
});

ScriptTest.run("34_yaml_gametest: all:true runs the index.txt manifest", function (t) {
    // Exercises YamlTestLoader.loadAll (reads index.txt, then each listed file).
    var res = Agent.invoke("mc.test.yaml", { all: true });
    t.assertTrue(res.results.length >= 1, "index.txt lists at least one file");
    t.assertEqual(res.failed, 0, "all registered yaml tests pass; failures="
        + JSON.stringify(res.results.map(function (r) { return r.failures; })));
});

ScriptTest.run("34_yaml_gametest: a failing assert is reported, not swallowed", function (t) {
    var o = Agent.system.testOrigin();
    var y = o.y + 8;
    var p = "[" + o.x + ", " + y + ", " + o.z + "]";
    // No setup; assert a block is present where there is only air -> must fail.
    var yaml = [
        "- name: expect-missing-block",
        "  region:",
        "    from: " + p,
        "    to: " + p,
        "  asserts:",
        "    - block_present: { pos: " + p + ", type: minecraft:beacon }"
    ].join("\n");
    var res = Agent.invoke("mc.test.yaml", { inline: yaml });
    t.assertEqual(res.passed, 0, "no spec should pass");
    t.assertEqual(res.failed, 1, "the spec must be reported as failed");
    t.assertTrue(res.results[0].failures.length >= 1, "at least one failure message");
});
