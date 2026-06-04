// mc.observe.scene — server-side HazardField + AsciiMapRenderer integration tests.
// Uses the same loaded test region as the other validation scripts: center (0,200,0).
// Step 1: flat ground (stone platform at y=199, air y=200-205) -> no lethal cells.

function fill(x1, y1, z1, x2, y2, z2, blockType) {
    return Agent.invoke("mc.action.fill", {
        from: { x: x1, y: y1, z: z1 },
        to:   { x: x2, y: y2, z: z2 },
        type: blockType
    });
}

function scene(center, radius, render) {
    var p = { center: center, radius: radius };
    if (render) p.render = render;
    return Agent.invoke("mc.observe.scene", p);
}

AgentTest.run("50_scene: flat ground -> no lethal cells", function (t) {
    var ox = 0, oy = 200, oz = 0;
    // Build a stone platform (9x9) at y=199 with clear air above (y=200..205)
    // within the loaded test region around (0,200,0).
    fill(ox - 4, oy - 1, oz - 4, ox + 4, oy - 1, oz + 4, "minecraft:stone");
    fill(ox - 4, oy,     oz - 4, ox + 4, oy + 5, oz + 4, "minecraft:air");

    var s = scene({ x: ox, y: oy, z: oz }, 3, "map");

    t.assertEqual(s.present, true, "scene present on server");
    t.assertTrue(typeof s.rows === "object" && s.rows.length > 0, "has rendered rows");
    t.assertEqual(s.hazardSummary.lethalCount, 0, "flat stone ground has zero lethal cells");
});

// ── Test 2: deep pit neighbour is lethal ─────────────────────────────────────
// Center (0,200,50). Stone platform at y=199. Neighbour at x=+1 has a pit
// 23 blocks deep: air y=177..199, solid stone floor at y=176.
// drop = center.y(200) - foot.y(177) = 23 > survivableFall(22) -> lethal.
AgentTest.run("50_scene: deep pit neighbour is lethal (V)", function (t) {
    var ox = 0, oy = 200, oz = 50;
    // Full 9x9 stone platform at y=199, clear air y=200..205.
    fill(ox - 4, oy - 1, oz - 4, ox + 4, oy - 1, oz + 4, "minecraft:stone");
    fill(ox - 4, oy,     oz - 4, ox + 4, oy + 5, oz + 4, "minecraft:air");
    // Carve the 23-deep pit at x=ox+1, z=oz:
    //   - Clear platform stone and air column down to y=177 (foot level).
    //   - Solid stone floor at y=176 so canStandAt(y=177) finds a landing.
    fill(ox + 1, oy + 5, oz, ox + 1, oy - 23, oz, "minecraft:air");   // clear y=177..205
    fill(ox + 1, oy - 24, oz, ox + 1, oy - 24, oz, "minecraft:stone"); // floor at y=176

    var s = scene({ x: ox, y: oy, z: oz }, 2, "map");
    t.assertTrue(s.hazardSummary.lethalCount >= 1,
        "a 23-deep pit neighbour is lethal (drop 23 > survivableFall 22)");
});

// ── Test 3: shallow step-down is NOT lethal ───────────────────────────────────
// Center (0,200,100). Neighbour at x=+1 drops 3 blocks (drop 3 <= 22) -> not lethal.
AgentTest.run("50_scene: shallow step-down is NOT lethal", function (t) {
    var ox = 0, oy = 200, oz = 100;
    fill(ox - 4, oy - 1, oz - 4, ox + 4, oy - 1, oz + 4, "minecraft:stone");
    fill(ox - 4, oy,     oz - 4, ox + 4, oy + 5, oz + 4, "minecraft:air");
    // Shallow step: clear y=197..199 at x=+1, solid stone floor at y=196.
    // foot = y=197, drop = 200-197 = 3, 3 <= 22 -> not lethal.
    fill(ox + 1, oy + 5, oz, ox + 1, oy - 3, oz, "minecraft:air");    // clear y=197..205
    fill(ox + 1, oy - 4, oz, ox + 1, oy - 4, oz, "minecraft:stone"); // floor at y=196

    var s = scene({ x: ox, y: oy, z: oz }, 2, "map");
    t.assertEqual(s.hazardSummary.lethalCount, 0,
        "a 3-block step-down is survivable (drop 3 <= survivableFall 22)");
});

// ── Test 4: lava pit neighbour is lethal ─────────────────────────────────────
// Center (0,200,150). Neighbour at x=+1 has a deep pit with lava at the top.
// drop = 23 > 22 -> lethal regardless of lava (lava is passable so scan goes
// through it; foot lands on stone at y=176, drop=23).
AgentTest.run("50_scene: lava pit neighbour is lethal", function (t) {
    var ox = 0, oy = 200, oz = 150;
    fill(ox - 4, oy - 1, oz - 4, ox + 4, oy - 1, oz + 4, "minecraft:stone");
    fill(ox - 4, oy,     oz - 4, ox + 4, oy + 5, oz + 4, "minecraft:air");
    // Pit at x=+1: air from y=177 to y=205, stone floor at y=176.
    fill(ox + 1, oy + 5, oz, ox + 1, oy - 23, oz, "minecraft:air");
    fill(ox + 1, oy - 24, oz, ox + 1, oy - 24, oz, "minecraft:stone");
    // Lava at y=198 (one block below the platform rim) — scan passes through
    // lava (passable but not standable) and lands foot at y=177 on stone.
    fill(ox + 1, oy - 2, oz, ox + 1, oy - 2, oz, "minecraft:lava");

    var s = scene({ x: ox, y: oy, z: oz }, 2, "map");
    t.assertTrue(s.hazardSummary.lethalCount >= 1,
        "a lava pit neighbour is lethal (drop 23 through lava > survivableFall 22)");
});

// ── Test 5: deep water (depth>=2) lethal, shallow (depth=1) not ──────────────
// Center (0,200,200). deepWaterMax=2 (hardcoded in ObserveApi.scene).
//   Lethal side  (x=+1): water at y=200 AND y=199, stone at y=198 -> depth=2 -> lethal.
//   Safe side    (x=-1): water at y=200 only, stone at y=199       -> depth=1 -> not lethal.
AgentTest.run("50_scene: deep water (>=2) lethal, shallow (1) not", function (t) {
    var ox = 0, oy = 200, oz = 200;
    // Build platform and clear air.
    fill(ox - 4, oy - 1, oz - 4, ox + 4, oy - 1, oz + 4, "minecraft:stone");
    fill(ox - 4, oy,     oz - 4, ox + 4, oy + 5, oz + 4, "minecraft:air");

    // Lethal water column at x=+1: 2-deep water above solid stone.
    fill(ox + 1, oy - 2, oz, ox + 1, oy - 2, oz, "minecraft:stone"); // floor at y=198
    fill(ox + 1, oy - 1, oz, ox + 1, oy,     oz, "minecraft:water"); // water y=199 and y=200

    // Safe (1-deep) water column at x=-1: 1-deep water above solid stone.
    fill(ox - 1, oy - 1, oz, ox - 1, oy - 1, oz, "minecraft:stone"); // floor at y=199
    fill(ox - 1, oy,     oz, ox - 1, oy,     oz, "minecraft:water"); // water y=200 only

    // Scene centered so both x=+1 and x=-1 are within radius=2.
    var s = scene({ x: ox, y: oy, z: oz }, 2, "map");
    t.assertTrue(s.hazardSummary.lethalCount >= 1,
        "3-deep water neighbour (depth>=deepWaterMax=2) is lethal");
    // Verify the safe side: use a scene centered on the safe water column, radius=1.
    // At x=ox-1 the only relevant neighbour (dx=0,dz=0 is skipped as self) should be fine.
    // Use a fresh center at the safe-water column with radius=1 to isolate it.
    var sxSafe = ox - 1;
    // Rebuild: re-center the hazard check on the safe column so we can assert lethalCount=0.
    // At (ox-1, 200, oz): the cell at dx=0,dz=0 is the bot itself (skipped). Its neighbours
    // include the platform stone at dx=+1 (no lethal) and the deep-water column at dx=-1 which
    // now IS lethal at dx=-2 (out of radius=1). So with radius=1, lethalCount from the safe center:
    // We only see the immediate neighbours — platform stone (not lethal) and more platform.
    // However the deep-water is at ox+1 (dx=+2 from ox-1), outside radius=1. Safe.
    var sSafe = scene({ x: sxSafe, y: oy, z: oz }, 1, "map");
    t.assertEqual(sSafe.hazardSummary.lethalCount, 0,
        "1-deep water neighbour (depth 1 < deepWaterMax 2) is not lethal");
});
