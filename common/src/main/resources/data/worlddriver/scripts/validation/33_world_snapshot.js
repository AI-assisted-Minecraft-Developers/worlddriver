// mc.world.snapshot / mc.world.restore — deterministic setup/teardown primitive
// (Phase 2 GameTest YAML prerequisite). Runs server-side: snapshot a region,
// mutate it, restore it verbatim, and confirm block states + block-entity
// contents come back. Also asserts metadata parity across the three transports.

function tick(n) { Driver.invoke("mc.system.waitTicks", { ticks: n || 1 }); }

ScriptTest.run("33_world_snapshot: block states restore after mutation", function(t) {
    var o = Driver.system.testOrigin();
    // Build a known 3x3x1 region in the air above the arena, independent of the
    // (mutated-by-earlier-scripts) seeded plane: 9 stone with an oak-planks center.
    var y = o.y + 5;
    var from = { x: o.x - 1, y: y, z: o.z - 1 };
    var to   = { x: o.x + 1, y: y, z: o.z + 1 };
    Driver.invoke("mc.action.fill", { from: from, to: to, type: "minecraft:stone" });
    Driver.invoke("mc.action.placeMany", { blocks: [{ pos: { x: o.x, y: y, z: o.z }, type: "minecraft:oak_planks" }] });
    tick(1);

    var snap = Driver.invoke("mc.world.snapshot", { id: "states", from: from, to: to });
    t.assertEqual(snap.ok, true, "snapshot must succeed");
    t.assertEqual(snap.id, "states", "id echoed");
    t.assertEqual(snap.blocks, 9, "3x3x1 = 9 cells");
    t.assertEqual(snap.nonAir, 9, "all 9 cells were just filled");
    t.assertEqual(snap.blockEntities, 0, "no block entities in this region");

    // Mutate: wipe the whole region to air.
    Driver.invoke("mc.action.fill", { from: from, to: to, type: "minecraft:air" });
    tick(1);
    var mid = Driver.observe.area({ center: { x: o.x, y: y, z: o.z }, radius: 0 });
    t.assertEqual(mid.blocks.length, 0, "center is air after the wipe");

    var res = Driver.invoke("mc.world.restore", { id: "states" });
    t.assertEqual(res.ok, true, "restore must succeed");
    t.assertEqual(res.restored, 9, "all 9 cells restored");
    tick(1);

    var center = Driver.observe.area({ center: { x: o.x, y: y, z: o.z }, radius: 0 });
    t.assertEqual(center.blocks[0].type, "minecraft:oak_planks", "center restored to oak_planks");
    var corner = { x: o.x - 1, y: y, z: o.z - 1 };
    var back = Driver.observe.area({ center: corner, radius: 0 });
    t.assertEqual(back.blocks.length, 1, "wiped corner is non-air again");
    t.assertEqual(back.blocks[0].type, "minecraft:stone", "corner restored to stone");
});

ScriptTest.run("33_world_snapshot: block-entity contents survive restore", function(t) {
    var o = Driver.system.testOrigin();
    var pos = { x: o.x, y: o.y + 3, z: o.z }; // empty air above the seeded log
    var c = pos.x + " " + pos.y + " " + pos.z;

    Driver.invoke("mc.action.runCommand", { cmd: "setblock " + c + " minecraft:chest" });
    Driver.invoke("mc.action.runCommand", { cmd: "item replace block " + c + " container.0 with minecraft:diamond 5" });
    tick(1);

    var snap = Driver.invoke("mc.world.snapshot", { id: "be", from: pos, to: pos });
    t.assertEqual(snap.blockEntities, 1, "chest captured as a block entity");

    // Destroy the chest entirely. It is emptied first, here and at the end: a chest of diamonds left
    // on the pad is broken by whatever runs there next, and on a server a client joined the real
    // player picks the drop up, which reddens every later check that reads that player's bag.
    function removeChest() {
        Driver.invoke("mc.action.runCommand", { cmd: "item replace block " + c + " container.0 with minecraft:air" });
        Driver.invoke("mc.action.runCommand", { cmd: "setblock " + c + " air" });
    }
    removeChest();
    tick(1);
    var gone = Driver.invoke("mc.observe.container", { pos: pos });
    t.assertEqual(gone.present, false, "chest is gone before restore");

    var res = Driver.invoke("mc.world.restore", { id: "be", discard: true });
    t.assertEqual(res.blockEntities, 1, "one block entity restored");
    tick(1);

    var cont = Driver.invoke("mc.observe.container", { pos: pos });
    t.assertEqual(cont.present, true, "chest is back");
    t.assertEqual(cont.type, "minecraft:chest", "block entity type restored");
    t.assertTrue(cont.slots && cont.slots[0] != null, "slot 0 has contents");
    t.assertEqual(cont.slots[0].id, "minecraft:diamond", "diamond contents restored");
    t.assertEqual(cont.slots[0].count, 5, "stack count restored");

    // discard:true freed it — a second restore must now fail.
    var threw = false;
    try { Driver.invoke("mc.world.restore", { id: "be" }); } catch (e) { threw = true; }
    t.assertTrue(threw, "restore of a discarded id must throw");
    removeChest();
});

ScriptTest.run("33_world_snapshot: metadata identical across in-JVM, RPC, MCP", function(t) {
    var o = Driver.system.testOrigin();
    var args = { id: "parity", from: { x: o.x, y: o.y, z: o.z }, to: { x: o.x + 1, y: o.y, z: o.z + 1 } };
    var direct = Driver.invoke("mc.world.snapshot", args);
    var viaTcp = Driver.system.rpcRoundtrip("mc.world.snapshot", args);
    var viaMcp = Driver.system.mcpRoundtrip("mc.world.snapshot", args);
    function key(r) { return [r.ok, r.id, r.blocks, r.nonAir, r.blockEntities].join(","); }
    t.assertEqual(key(viaTcp), key(direct), "RPC metadata must match in-JVM");
    t.assertEqual(key(viaMcp), key(direct), "MCP metadata must match in-JVM");
});
