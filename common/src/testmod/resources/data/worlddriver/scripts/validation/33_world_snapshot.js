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
    t.assertEqual(snap.id, "states", "id echoed");
    t.assertEqual(snap.blocks, 9, "3x3x1 = 9 cells");
    t.assertEqual(snap.nonAir, 9, "all 9 cells were just filled");
    t.assertEqual(snap.blockEntities, 0, "no block entities in this region");

    // Mutate: wipe the whole region to air.
    Driver.invoke("mc.action.fill", { from: from, to: to, type: "minecraft:air" });
    tick(1);
    var mid = Driver.query({ q: "blocks", center: { x: o.x, y: y, z: o.z }, filter: { in_radius: 0 } });
    t.assertEqual(mid.blocks.length, 0, "center is air after the wipe");

    var res = Driver.invoke("mc.world.restore", { id: "states" });
    t.assertEqual(res.restored, 9, "all 9 cells restored");
    tick(1);

    var center = Driver.query({ q: "blocks", center: { x: o.x, y: y, z: o.z }, filter: { in_radius: 0 } });
    t.assertEqual(center.blocks[0].type, "minecraft:oak_planks", "center restored to oak_planks");
    var corner = { x: o.x - 1, y: y, z: o.z - 1 };
    var back = Driver.query({ q: "blocks", center: corner, filter: { in_radius: 0 } });
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
    t.assertEqual(cont.blockEntity, "minecraft:chest", "block entity type restored");
    t.assertTrue(cont.slots && cont.slots[0].id !== undefined, "slot 0 has contents");
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
    function key(r) { return [r.id,r.blocks, r.nonAir, r.blockEntities].join(","); }
    t.assertEqual(key(viaTcp), key(direct), "RPC metadata must match in-JVM");
    t.assertEqual(key(viaMcp), key(direct), "MCP metadata must match in-JVM");
});

ScriptTest.run("33_world_snapshot: entities come back and newcomers go", function(t) {
    var o = Driver.system.testOrigin();
    var y = o.y + 8; // empty air above the arena
    var at = o.x + " " + y + " " + o.z;
    var from = { x: o.x - 2, y: y - 2, z: o.z - 2 };
    var to   = { x: o.x + 2, y: y + 2, z: o.z + 2 };
    function inBox() {
        return Driver.query({ q: "entities", center: { x: o.x, y: y, z: o.z }, filter: { in_radius: 3 },
            select: ["type", "uuid"] }).entities.filter(function (r) { return r.type !== "minecraft:player"; });
    }
    function clear() { Driver.invoke("mc.action.runCommand", { cmd: "kill @e[tag=wd33snap]" }); }

    clear();
    Driver.invoke("mc.action.runCommand", { cmd: "summon minecraft:pig " + at + " {NoAI:1b,Tags:[\"wd33snap\"]}" });
    tick(1);
    var pig = inBox().filter(function (r) { return r.type === "minecraft:pig"; });
    t.assertEqual(pig.length, 1, "one pig is in the box before the snapshot");
    var uuid = pig[0].uuid;

    var snap = Driver.invoke("mc.world.snapshot", { id: "ents", from: from, to: to, entities: true });
    t.assertEqual(snap.entities, 1, "the pig was kept");

    clear();
    Driver.invoke("mc.action.runCommand", { cmd: "summon minecraft:cow " + at + " {NoAI:1b,Tags:[\"wd33snap\"]}" });
    tick(1);
    t.assertEqual(inBox().filter(function (r) { return r.type === "minecraft:cow"; }).length, 1, "the newcomer cow is there");

    var res = Driver.invoke("mc.world.restore", { id: "ents", discard: true });
    t.assertTrue(res.entitiesRemoved >= 1, "the cow was removed, got " + res.entitiesRemoved);
    t.assertEqual(res.entitiesRestored, 1, "the pig was put back");
    tick(1);

    var after = inBox();
    var pigs = after.filter(function (r) { return r.type === "minecraft:pig"; });
    t.assertEqual(pigs.length, 1, "the pig is back");
    t.assertEqual(pigs[0].uuid, uuid, "with its own uuid");
    t.assertEqual(after.filter(function (r) { return r.type === "minecraft:cow"; }).length, 0, "no cow is left in the box");
    clear();
});

ScriptTest.run("33_world_snapshot: a snapshot in the nether restores there", function(t) {
    var o = Driver.system.testOrigin();
    var x = o.x, y = 100, z = o.z;
    var c = x + " " + y + " " + z;
    var pos = { x: x, y: y, z: z };
    var nether = "minecraft:the_nether";
    function inNether(cmd) { return Driver.invoke("mc.action.runCommand", { cmd: "execute in " + nether + " run " + cmd }); }

    inNether("forceload add " + x + " " + z);
    try {
        tick(10);
        inNether("setblock " + c + " minecraft:gold_block");
        tick(1);
        var snap = Driver.invoke("mc.world.snapshot", { id: "nether", from: pos, to: pos, dimension: nether });
        t.assertEqual(snap.blocks, 1, "one cell kept");
        t.assertEqual(snap.nonAir, 1, "the gold block was read in the nether");

        inNether("setblock " + c + " minecraft:diamond_block");
        tick(1);
        Driver.invoke("mc.world.restore", { id: "nether", discard: true });
        tick(1);
        var r = Driver.invoke("mc.action.runCommand", { cmd: "execute in " + nether + " if block " + c + " minecraft:gold_block" });
        t.assertEqual(r.success, true, "the nether cell is gold again");
    } finally {
        inNether("setblock " + c + " minecraft:air");
        inNether("forceload remove " + x + " " + z);
    }

    var threw = false;
    try { Driver.invoke("mc.world.snapshot", { from: pos, to: pos, dimension: "minecraft:no_such_place" }); } catch (e) { threw = true; }
    t.assertTrue(threw, "an unknown dimension must be refused");
});
