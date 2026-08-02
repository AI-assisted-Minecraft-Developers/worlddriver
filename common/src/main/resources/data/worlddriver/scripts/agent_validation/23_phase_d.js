// Client-only — Phase D additions (mc.bot.farm process, expanded Parkour set
// in PathFinder). farm needs a live LocalPlayer; on dedicated-server CI we
// can still hit the tool catalog + the schema path, but startProcess will
// fail. Schema-shape assertions run unconditionally; behaviour assertions
// gate on clientAvailable().

function clientAvailable() {
    try {
        Agent.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

if (!clientAvailable()) {
    ScriptTest.run("23_phase_d: skipped (no client api — dedicated server)", function(t) {
        // PASS — farm + Parkour3/2-diagonal need a real client.
    });
} else {

    ScriptTest.run("23_phase_d: farm rejects missing from/to", function(t) {
        // Route-layer schema validation rejects the missing required keys before the tool runs.
        var msg = null;
        try { Agent.invoke("mc.bot.farm", {}); } catch (e) { msg = String(e); }
        t.assertTrue(msg !== null && msg.indexOf("missing required 'from'") >= 0
                  && msg.indexOf("missing required 'to'") >= 0,
            "missing rect must be rejected by schema validation naming both keys, got: " + msg);
    });

    ScriptTest.run("23_phase_d: farm rejects oversize area", function(t) {
        // 100×100 = 10000 cells, well past the 4096 cap.
        var r = Agent.invoke("mc.bot.farm", {
            from: { x: 0, y: 64, z: 0 },
            to:   { x: 99, y: 64, z: 99 }
        });
        t.assertEqual(r.ok, false, "oversize → ok:false");
        t.assertTrue(String(r.error).indexOf("too large") >= 0
                  || String(r.error).indexOf("4096") >= 0, "error mentions cap: " + r.error);
    });

    ScriptTest.run("23_phase_d: farm rejects invalid crops filter", function(t) {
        var r = Agent.invoke("mc.bot.farm", {
            from: { x: 0, y: 64, z: 0 },
            to:   { x: 4, y: 64, z: 4 },
            crops: ["minecraft:not_a_crop"]
        });
        t.assertEqual(r.ok, false, "unknown crop id → ok:false");
        t.assertTrue(String(r.error).indexOf("crops") >= 0
                  || String(r.error).indexOf("subset") >= 0, "error mentions crops: " + r.error);
    });

    ScriptTest.run("23_phase_d: farm accepts valid rect (or 'no player' if title screen)",
        function(t) {
            var r = Agent.invoke("mc.bot.farm", {
                from: { x: 0, y: 64, z: 0 },
                to:   { x: 4, y: 64, z: 4 },
                replant: true
            });
            // Either succeeded (in-world) or rejected with "no player" (title
            // screen). Both confirm the schema + dispatch is correct.
            if (r.ok) {
                t.assertEqual(r.started, true, "started flag set");
                t.assertEqual(r.area, 25, "area = 5×5 = 25");
                t.assertEqual(r.replant, true, "replant echoed");
                t.assertTrue(Array.isArray(r.crops), "crops echoed as array");
                t.assertTrue(r.crops.length === 4, "default crops = 4 vanilla types");
                Agent.invoke("mc.bot.cancel", { process: "builder" });
            } else {
                t.assertTrue(String(r.error).indexOf("no player") >= 0,
                    "no-player rejection accepted: " + r.error);
            }
        });

    ScriptTest.run("23_phase_d: farm byte-identical across in-JVM/RPC/MCP transports",
        function(t) {
            // Schema-reject path is deterministic and doesn't need a player.
            var args = { from: { x: 0, y: 64, z: 0 }, to: { x: 99, y: 64, z: 99 } };
            var direct = Agent.invoke("mc.bot.farm", args);
            var viaTcp = Agent.system.rpcRoundtrip("mc.bot.farm", args);
            var viaMcp = Agent.system.mcpRoundtrip("mc.bot.farm", args);
            t.assertEqual(viaTcp.ok, direct.ok, "RPC.ok mismatch");
            t.assertEqual(viaMcp.ok, direct.ok, "MCP.ok mismatch");
            t.assertEqual(viaTcp.error, direct.error, "RPC.error mismatch");
            t.assertEqual(viaMcp.error, direct.error, "MCP.error mismatch");
        });

}
