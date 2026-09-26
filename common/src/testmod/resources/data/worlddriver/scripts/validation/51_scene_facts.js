// mc.observe.scene — cornered flag, byte-stable render, and 3-transport parity.
// Uses the same loaded test region as 50_scene_hazard.js: center (0,200,0).
// Each arena is at a distinct z-offset to avoid overlap with 50_scene_hazard.js.

function fill(x1, y1, z1, x2, y2, z2, blockType) {
    return Driver.invoke("mc.action.fill", {
        from: { x: x1, y: y1, z: z1 },
        to:   { x: x2, y: y2, z: z2 },
        type: blockType
    });
}

function scene(center, radius, render) {
    var p = { center: center, radius: radius };
    if (render) p.render = render;
    return Driver.invoke("mc.observe.scene", p);
}

// ── Test 1: not cornered — flat platform ────────────────────────────────────
// z=250. Flat 5x5 stone platform at y=199, air y=200-205.
// All 8 immediate neighbours at radius=1 are standable, non-lethal → cornered=false.
ScriptTest.run("51_scene_facts: flat platform -> not cornered", function (t) {
    var ox = 0, oy = 200, oz = 250;
    fill(ox - 4, oy - 1, oz - 4, ox + 4, oy - 1, oz + 4, "minecraft:stone");
    fill(ox - 4, oy,     oz - 4, ox + 4, oy + 5, oz + 4, "minecraft:air");

    var s = scene({ x: ox, y: oy, z: oz }, 1);
    t.assertEqual(s.present, true, "scene present");
    t.assertEqual(s.hazardSummary.cornered, false,
        "flat stone neighbours are standable and non-lethal -> not cornered");
});

// ── Test 2: cornered — all 8 neighbours are solid walls ─────────────────────
// z=260. Stone floor at y=199. Center cell (y=200, y=201) is air.
// 8 ring cells: stone at y=200 AND y=201 (feet+head) → canStandAt fails for
// every foot candidate the scanner probes → none are standable → cornered=true.
ScriptTest.run("51_scene_facts: walled ring -> cornered", function (t) {
    var ox = 0, oy = 200, oz = 260;
    // Clear the arena first.
    fill(ox - 4, oy - 2, oz - 4, ox + 4, oy + 5, oz + 4, "minecraft:air");
    // Stone floor across the whole 3x3 footprint at y=199.
    fill(ox - 1, oy - 1, oz - 1, ox + 1, oy - 1, oz + 1, "minecraft:stone");
    // Fill the 8 ring cells solid at feet (y=200) and head (y=201) height.
    // Leave the center (ox, oy, oz) and (ox, oy+1, oz) as air.
    fill(ox - 1, oy,     oz - 1, ox + 1, oy + 1, oz + 1, "minecraft:stone");
    // Carve the center column back to air (the bot's own cell must be passable).
    fill(ox, oy, oz, ox, oy + 1, oz, "minecraft:air");

    var s = scene({ x: ox, y: oy, z: oz }, 1);
    t.assertEqual(s.present, true, "scene present");
    t.assertEqual(s.hazardSummary.cornered, true,
        "all 8 neighbours solid at feet and head height -> no standable exit -> cornered");
});

// ── Test 3: byte-stable render ───────────────────────────────────────────────
// z=270. Flat 5x5 stone platform, radius=1 → 3x3 map.
// Middle row of the 3x3 grid with center '@' must be ". @ ." (walk-me-walk).
// Two successive calls must return JSON-identical rows arrays.
ScriptTest.run("51_scene_facts: render rows are byte-stable and middle row is '. @ .'", function (t) {
    var ox = 0, oy = 200, oz = 270;
    fill(ox - 4, oy - 1, oz - 4, ox + 4, oy - 1, oz + 4, "minecraft:stone");
    fill(ox - 4, oy,     oz - 4, ox + 4, oy + 5, oz + 4, "minecraft:air");

    var center = { x: ox, y: oy, z: oz };
    var s1 = scene(center, 1, "map");
    var s2 = scene(center, 1, "map");

    t.assertTrue(Array.isArray(s1.rows) && s1.rows.length === 3,
        "radius=1 -> 3 rows");
    t.assertEqual(JSON.stringify(s1.rows), JSON.stringify(s2.rows),
        "two successive calls produce JSON-identical rows (byte-stable)");
    // Middle row (index 1) for a flat platform at radius=1:
    // dz=0 row: dx=-1 is '.', dx=0 is '@', dx=1 is '.', joined by single spaces.
    t.assertEqual(s1.rows[1], ". @ .",
        "middle row of a flat 3x3 platform is '. @ .'");
});

// ── Test 4: 3-transport parity ───────────────────────────────────────────────
// z=20 — near origin, in chunk Z=1, persistently loaded by the GameTest server.
// Flat 5x5 stone platform. Compare rows JSON across in-JVM, RPC, MCP.
ScriptTest.run("51_scene_facts: rows JSON identical across in-JVM / RPC / MCP", function (t) {
    var ox = 0, oy = 200, oz = 20;
    fill(ox - 4, oy - 1, oz - 4, ox + 4, oy - 1, oz + 4, "minecraft:stone");
    fill(ox - 4, oy,     oz - 4, ox + 4, oy + 5, oz + 4, "minecraft:air");

    var args = { center: { x: ox, y: oy, z: oz }, radius: 1, render: "map" };
    var direct = Driver.invoke("mc.observe.scene", args);
    var viaTcp = Driver.system.rpcRoundtrip("mc.observe.scene", args);
    var viaMcp = Driver.system.mcpRoundtrip("mc.observe.scene", args);

    var rowsDirect = JSON.stringify(direct.rows);
    var rowsTcp    = JSON.stringify(viaTcp.rows);
    var rowsMcp    = JSON.stringify(viaMcp.rows);

    t.assertEqual(rowsTcp, rowsDirect,
        "RPC rows must match in-JVM rows");
    t.assertEqual(rowsMcp, rowsDirect,
        "MCP rows must match in-JVM rows");
});
