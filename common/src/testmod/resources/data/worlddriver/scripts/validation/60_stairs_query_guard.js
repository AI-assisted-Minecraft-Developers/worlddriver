// Conviction/regression guard for docs/archive/feedback/2026-06-07: mc.query q='blocks'
// was reported to silently omit stair blocks (vanilla + modded) while returning
// neighbours normally. Today's scan code has no shape predicate that could skip
// them, so the bug likely died in a refactor — this sweep either convicts it or
// closes the case and keeps it closed.

ScriptTest.run("60_stairs: every placed stair variant is returned by query", function(t) {
    var origin = Driver.system.testOrigin();
    var y = origin.y + 3, z = origin.z + 26;
    var stairs = [
        "minecraft:oak_stairs",
        "minecraft:cobblestone_stairs",
        "minecraft:stone_brick_stairs",
        "minecraft:quartz_stairs",
        "minecraft:deepslate_tile_stairs"
    ];
    var states = ["", "[facing=north]", "[facing=east,half=top]", "[facing=west,shape=inner_left]"];

    for (var i = 0; i < stairs.length; i++) {
        var x = origin.x + 26 + i * 2;
        var st = states[i % states.length];
        var s = Driver.invoke("mc.action.runCommand",
            { cmd: "setblock " + x + " " + y + " " + z + " " + stairs[i] + st });
        t.assertEqual(s.success, true, "setblock " + stairs[i] + " should succeed");

        var rows = Driver.query({
            q: "blocks",
            center: { x: x, y: y, z: z },
            filter: { in_radius: 1, type: stairs[i] }
        });
        t.assertEqual(rows.length, 1, stairs[i] + st + " must appear in query results");
        t.assertEqual(rows[0].type, stairs[i]);
        t.assertEqual(rows[0].pos.x, x);

        // The 2026-06-07 report's exact shape: an unfiltered neighbourhood scan
        // must include the stair, not just its non-stair neighbours.
        var hood = Driver.query({
            q: "blocks",
            center: { x: x, y: y, z: z },
            filter: { in_radius: 1 },
            select: ["pos", "type"]
        });
        var found = false;
        for (var k = 0; k < hood.length; k++) {
            if (hood[k].type === stairs[i]) { found = true; break; }
        }
        t.assertTrue(found, stairs[i] + " missing from unfiltered scan (2026-06-07 blindspot)");

        Driver.invoke("mc.action.runCommand",
            { cmd: "setblock " + x + " " + y + " " + z + " minecraft:air" });
    }
});
