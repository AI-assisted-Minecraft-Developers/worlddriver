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
