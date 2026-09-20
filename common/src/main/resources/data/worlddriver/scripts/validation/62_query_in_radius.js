// Conviction/regression guard for docs/archive/feedback/2026-06-04 §C: mc.query
// q='entities' in_radius reportedly missed an entity standing 1 block from the
// center ({8,200,8} r=3 missed {8,200,9}) while it was taking AoE damage.
// The scan is a block-symmetric AABB slab — center CELL inflated by r, so cells
// center±r are fully inside and cell center±(r+1) is fully outside — with no
// same-cell exclusion and no sphere test. This sweep pins those semantics:
// if a static geometric hole existed (stale pos aside), a ring of pinned armor
// stands would expose it. The reported miss is setup-sensitive; the leading
// suspect is a death race (server removed the mob while its death animation
// still showed it standing). armor_stand + NoGravity keeps positions exact.
// Each block is hermetic: defensive pre-kill, own summons, kill in a finally
// so a failed assertion can't leak pinned stands into later suites.

ScriptTest.run("62_radius: r=3 ring — every in-range stand returned, none outside", function(t) {
    var origin = Driver.system.testOrigin();
    var cx = origin.x + 24, cy = origin.y + 6, cz = origin.z + 24;
    Driver.invoke("mc.action.runCommand", { cmd: "kill @e[tag=t62]" });

    // name → offset from center. In-range set: same cell, the reporter's exact
    // dz=+1 shape, axis extremes ±3, diagonal corner, vertical extremes ±3.
    var inRange = {
        "c":   [0, 0, 0],
        "z1":  [0, 0, 1],     // the reported miss: distance-1 neighbour
        "x3":  [3, 0, 0],
        "xm3": [-3, 0, 0],
        "d33": [3, 0, 3],     // AABB corner — a sphere test would drop this
        "y3":  [0, 3, 0],
        "ym3": [0, -3, 0]
    };
    var outRange = {
        "x4":  [4, 0, 0],
        "zm4": [0, 0, -4],
        "y4":  [0, 4, 0]
    };
    function summonAt(name, off) {
        var s = Driver.invoke("mc.action.runCommand",
            { cmd: "summon minecraft:armor_stand "
                + (cx + off[0]) + " " + (cy + off[1]) + " " + (cz + off[2])
                + " {NoGravity:1b,Tags:[\"t62\",\"t62_" + name + "\"]}" });
        t.assertEqual(s.success, true, "summon " + name + " should succeed: " + JSON.stringify(s));
    }
    try {
        for (var k in inRange) summonAt(k, inRange[k]);
        for (var k2 in outRange) summonAt(k2, outRange[k2]);

        var rows = Driver.query({
            q: "entities",
            center: { x: cx, y: cy, z: cz },
            filter: { in_radius: 3, type: "minecraft:armor_stand" }
        });
        // Map returned rows back to names by exact block pos.
        var got = {};
        for (var i = 0; i < rows.length; i++) {
            var p = rows[i].pos;
            for (var n in inRange) {
                var o = inRange[n];
                if (p.x === cx + o[0] && p.y === cy + o[1] && p.z === cz + o[2]) got[n] = true;
            }
            for (var n2 in outRange) {
                var o2 = outRange[n2];
                if (p.x === cx + o2[0] && p.y === cy + o2[1] && p.z === cz + o2[2]) {
                    t.assertTrue(false, "out-of-range stand '" + n2 + "' leaked into r=3 result");
                }
            }
        }
        for (var n3 in inRange) {
            t.assertTrue(got[n3] === true, "in-range stand '" + n3 + "' missing from r=3 result "
                + "(the 2026-06-04 §C silent-omission shape)");
        }
        t.assertEqual(rows.length, 7, "exactly the 7 in-range stands, got " + rows.length);
    } finally {
        Driver.invoke("mc.action.runCommand", { cmd: "kill @e[tag=t62]" });
    }
});

ScriptTest.run("62_radius: r=0 returns only the center cell", function(t) {
    var origin = Driver.system.testOrigin();
    var cx = origin.x + 24, cy = origin.y + 6, cz = origin.z + 24;
    Driver.invoke("mc.action.runCommand", { cmd: "kill @e[tag=t62]" });
    try {
        var s = Driver.invoke("mc.action.runCommand",
            { cmd: "summon minecraft:armor_stand " + cx + " " + cy + " " + cz
                + " {NoGravity:1b,Tags:[\"t62\",\"t62_c\"]}" });
        t.assertEqual(s.success, true, "summon center stand should succeed: " + JSON.stringify(s));
        var rows = Driver.query({
            q: "entities",
            center: { x: cx, y: cy, z: cz },
            filter: { in_radius: 0, type: "minecraft:armor_stand" }
        });
        t.assertEqual(rows.length, 1, "r=0 = just the center-cell stand, got " + rows.length);
    } finally {
        Driver.invoke("mc.action.runCommand", { cmd: "kill @e[tag=t62]" });
    }
});
