// Guard for mc.world.block — the read-only single-cell accessor requested in
// docs/feedback/2026-06-08 ("no first-class way to read a blockstate, a light
// level, or a command's diagnostic output"). The reporter's exact pain was
// ars_nouveau:source_lamp[lit=true] (extends CopperBulbBlock) with no way to
// confirm `lit` persisted; vanilla copper_bulb reproduces that shape 1:1.

AgentTest.run("61_world_block: blockstate + light round-trip (lit copper bulb)", function(t) {
    var origin = Agent.system.testOrigin();
    var x = origin.x + 24, y = origin.y + 4, z = origin.z + 24;
    var s = Agent.invoke("mc.action.runCommand",
        { cmd: "setblock " + x + " " + y + " " + z + " minecraft:copper_bulb[lit=true]" });
    t.assertEqual(s.success, true, "setblock copper_bulb[lit=true] should succeed");

    var cell = Agent.world.block({ pos: { x: x, y: y, z: z } });
    t.assertEqual(cell.type, "minecraft:copper_bulb");
    t.assertHasKey(cell, "state");
    t.assertEqual(cell.state.lit, "true", "lit=true must persist and be readable");
    t.assertHasKey(cell, "light");
    // A lit copper bulb emits light 15; assert on the neighbour cell so we read
    // propagated block light, not a source-cell special case. The light engine
    // is ASYNC — a same-tick read after setblock returns 0 (round-4 lesson) —
    // so poll a few ticks before asserting.
    var beside = { light: { block: 0 } };
    for (var attempt = 0; attempt < 20; attempt++) {
        beside = Agent.world.block({ pos: { x: x + 1, y: y, z: z } });
        if (beside.light.block >= 13) break;
        Agent.system.waitTicks(2);
    }
    t.assertTrue(beside.light.block >= 13,
        "neighbour block light should be lit-bulb bright, got " + beside.light.block);
    t.assertFalse("blockEntity" in cell, "nbt defaults to false");
});

AgentTest.run("61_world_block: block-entity NBT via nbt:true", function(t) {
    var origin = Agent.system.testOrigin();
    var x = origin.x + 24, y = origin.y + 6, z = origin.z + 24;
    Agent.invoke("mc.action.runCommand",
        { cmd: "setblock " + x + " " + y + " " + z + " minecraft:chest" });
    var fill = Agent.invoke("mc.action.runCommand",
        { cmd: "item replace block " + x + " " + y + " " + z + " container.0 with minecraft:diamond 3" });
    t.assertEqual(fill.success, true, "item replace should succeed");

    var cell = Agent.world.block({ pos: { x: x, y: y, z: z }, nbt: true });
    t.assertEqual(cell.type, "minecraft:chest");
    t.assertHasKey(cell, "blockEntity");
    t.assertTrue(String(cell.blockEntity).indexOf("minecraft:diamond") >= 0,
        "SNBT should contain the chest contents, got: " + cell.blockEntity);

    // A plain cell reports null blockEntity (key present, value null) with nbt:true.
    var air = Agent.world.block({ pos: { x: x, y: y + 1, z: z }, nbt: true });
    t.assertEqual(air.type, "minecraft:air");
    t.assertTrue(air.blockEntity === null || air.blockEntity === undefined,
        "no block entity at air cell");

    // Cleanup the scratch cells.
    Agent.invoke("mc.action.runCommand",
        { cmd: "fill " + (origin.x + 24) + " " + (origin.y + 4) + " " + (origin.z + 24) + " "
            + (origin.x + 24) + " " + (origin.y + 6) + " " + (origin.z + 24) + " minecraft:air" });
});
