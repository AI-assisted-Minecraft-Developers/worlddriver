// Guards for the mc.query projection gaps reported by external consumers
// (docs/feedback/2026-06-04 bugs #3/#4/#6, 2026-06-08 fix #2):
//   - q='entities' rows carry uuid / id / effects (LivingEntity only)
//   - filter.is_living drops non-living rows
//   - unknown select keys are rejected (isError) instead of silently ignored
//   - q='blocks' rows carry blockstate properties in `state`
// Uses a corner of the seeded arena away from the mock cow/sheep pair.

AgentTest.run("59_proj: entity effects/uuid/id round-trip", function(t) {
    var origin = Agent.system.testOrigin();
    var x = origin.x + 20, y = origin.y + 1, z = origin.z + 20;
    // Defensive cleanup: the gametest world persists across runs, so an aborted
    // earlier run can leave tagged strays (see 58's comment).
    Agent.invoke("mc.action.runCommand", { cmd: "kill @e[tag=t59]" });
    var s = Agent.invoke("mc.action.runCommand",
        { cmd: "summon minecraft:zombie " + x + " " + y + " " + z + " {NoAI:1b,Tags:[\"t59\"],PersistenceRequired:1b}" });
    t.assertEqual(s.success, true, "summon should succeed: " + JSON.stringify(s));
    var g = Agent.invoke("mc.action.runCommand",
        { cmd: "effect give @e[tag=t59] minecraft:speed 60 2" });
    t.assertEqual(g.success, true, "effect give should succeed");

    var rows = Agent.query({
        q: "entities",
        center: { x: x, y: y, z: z },
        filter: { in_radius: 4, type: "minecraft:zombie" }
    });
    t.assertEqual(rows.length, 1, "exactly the tagged zombie, got " + rows.length);
    var row = rows[0];
    t.assertHasKey(row, "uuid");
    t.assertTrue(String(row.uuid).length >= 32, "uuid should be a UUID string");
    t.assertHasKey(row, "id");
    t.assertHasKey(row, "effects");
    t.assertEqual(row.effects.length, 1, "zombie should carry exactly the given effect");
    t.assertEqual(row.effects[0].id, "minecraft:speed");
    t.assertEqual(row.effects[0].amplifier, 2);
    t.assertTrue(row.effects[0].durationTicks > 0, "duration should be positive");
});

AgentTest.run("59_proj: is_living filter drops item entities", function(t) {
    var origin = Agent.system.testOrigin();
    var x = origin.x + 20, y = origin.y + 1, z = origin.z + 20;
    var s = Agent.invoke("mc.action.runCommand",
        { cmd: "summon minecraft:item " + x + " " + (y + 2) + " " + z
            + " {Item:{id:\"minecraft:stone\",count:1},Tags:[\"t59\"]}" });
    t.assertEqual(s.success, true, "item summon should succeed");

    var living = Agent.query({
        q: "entities",
        center: { x: x, y: y, z: z },
        filter: { in_radius: 4, is_living: true }
    });
    for (var i = 0; i < living.length; i++) {
        t.assertFalse(living[i].type === "minecraft:item",
            "is_living:true must not return item entities");
    }
    var nonLiving = Agent.query({
        q: "entities",
        center: { x: x, y: y, z: z },
        filter: { in_radius: 4, is_living: false }
    });
    t.assertEqual(nonLiving.length, 1, "only the dropped item is non-living");
    t.assertEqual(nonLiving[0].type, "minecraft:item");
    t.assertFalse("health" in nonLiving[0], "non-living rows carry no health");
    t.assertFalse("effects" in nonLiving[0], "non-living rows carry no effects");
});

AgentTest.run("59_proj: unknown select key is rejected", function(t) {
    var threw = false, msg = "";
    try {
        Agent.query({ q: "entities", filter: { in_radius: 2 }, select: ["type", "bogus_key"] });
    } catch (e) {
        threw = true;
        msg = String(e);
    }
    t.assertTrue(threw, "unknown select key must throw, not be silently dropped");
    t.assertTrue(msg.indexOf("bogus_key") >= 0, "error should name the bad key, got: " + msg);

    threw = false;
    try {
        Agent.query({ q: "blocks", filter: { in_radius: 1 }, select: ["pos", "nope"] });
    } catch (e) { threw = true; }
    t.assertTrue(threw, "blocks branch must reject unknown select keys too");
});

AgentTest.run("59_proj: blocks rows carry blockstate properties", function(t) {
    var origin = Agent.system.testOrigin();
    var x = origin.x + 22, y = origin.y + 3, z = origin.z + 22;
    var s = Agent.invoke("mc.action.runCommand",
        { cmd: "setblock " + x + " " + y + " " + z + " minecraft:oak_stairs[facing=south,half=top]" });
    t.assertEqual(s.success, true, "setblock stairs should succeed");

    var rows = Agent.query({
        q: "blocks",
        center: { x: x, y: y, z: z },
        filter: { in_radius: 0 },
        select: ["pos", "type", "state"]
    });
    t.assertEqual(rows.length, 1, "the stair cell must be returned");
    t.assertEqual(rows[0].type, "minecraft:oak_stairs");
    t.assertHasKey(rows[0], "state");
    t.assertEqual(rows[0].state.facing, "south");
    t.assertEqual(rows[0].state.half, "top");
    t.assertEqual(rows[0].state.waterlogged, "false");

    // Property-less blocks omit `state` to keep large scans lean.
    Agent.invoke("mc.action.runCommand",
        { cmd: "setblock " + x + " " + (y + 1) + " " + z + " minecraft:stone" });
    var plain = Agent.query({
        q: "blocks",
        center: { x: x, y: y + 1, z: z },
        filter: { in_radius: 0 }
    });
    t.assertEqual(plain.length, 1);
    t.assertFalse("state" in plain[0], "stone has no properties, so no state key");

    // Cleanup: scrub the scratch cells and the test entities.
    Agent.invoke("mc.action.runCommand",
        { cmd: "fill " + x + " " + y + " " + z + " " + x + " " + (y + 1) + " " + z + " minecraft:air" });
    var kill = Agent.invoke("mc.action.runCommand", { cmd: "kill @e[tag=t59]" });
    t.assertEqual(kill.success, true, "cleanup kill should succeed");
});
