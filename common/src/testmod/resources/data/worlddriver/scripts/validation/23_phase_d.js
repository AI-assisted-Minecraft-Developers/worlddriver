// Client-only — Phase D additions (mc.bot.farm process, expanded Parkour set
// in PathFinder). farm needs a live LocalPlayer; on dedicated-server CI we
// can still hit the tool catalog + the schema path, but startProcess will
// fail. Schema-shape assertions run unconditionally; behaviour assertions
// gate on clientAvailable().

function clientAvailable() {
    try {
        Driver.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

// The message a call threw; NO_PLAYER when it returned that refusal (a client still on the
// title screen answers before reading the box); null for any other return. Top level because
// Rhino leaves a function declared inside a block undefined when the tests below call it.
var NO_PLAYER = "no player";
function thrown(call) {
    try {
        var r = call();
        return (r && r.ok === false && String(r.error).indexOf(NO_PLAYER) >= 0) ? NO_PLAYER : null;
    } catch (e) { return String(e); }
}

if (!clientAvailable()) {
    ScriptTest.run("23_phase_d: skipped (no client api — dedicated server)", function(t) {
        // PASS — farm + Parkour3/2-diagonal need a real client.
    });
} else {

    ScriptTest.run("23_phase_d: farm rejects missing from/to", function(t) {
        // Route-layer schema validation rejects the missing required keys before the tool runs.
        var msg = null;
        try { Driver.invoke("mc.bot.farm", {}); } catch (e) { msg = String(e); }
        t.assertTrue(msg !== null && msg.indexOf("missing required 'from'") >= 0
                  && msg.indexOf("missing required 'to'") >= 0,
            "missing rect must be rejected by schema validation naming both keys, got: " + msg);
    });

    // 100×1×100 = 10000 cells, past the 4096 cap on the cells a search rescans.
    var OVERSIZE = { from: { x: 0, y: 64, z: 0 }, to: { x: 99, y: 64, z: 99 } };
    var OVERSIZE_CORE = "the box covers 10000 cells";

    ScriptTest.run("23_phase_d: farm rejects an oversize box as a bad argument", function(t) {
        var msg = thrown(function() { Driver.invoke("mc.bot.farm", OVERSIZE); });
        if (msg === NO_PLAYER) return;
        t.assertTrue(msg !== null && msg.indexOf(OVERSIZE_CORE) >= 0 && msg.indexOf("4096") >= 0,
            "oversize must throw naming the cell count and the cap, got: " + msg);
    });

    ScriptTest.run("23_phase_d: farm rejects invalid crops filter", function(t) {
        var r = Driver.invoke("mc.bot.farm", {
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
            var r = Driver.invoke("mc.bot.farm", {
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
                Driver.invoke("mc.bot.cancel", { process: "builder" });
            } else {
                t.assertTrue(String(r.error).indexOf("no player") >= 0,
                    "no-player rejection accepted: " + r.error);
            }
        });

    ScriptTest.run("23_phase_d: farm byte-identical across in-JVM/RPC/MCP transports",
        function(t) {
            // The oversize argument error is deterministic; each transport wraps it its own way,
            // so compare the message the route threw rather than the wrapper.
            var direct = thrown(function() { Driver.invoke("mc.bot.farm", OVERSIZE); });
            var viaTcp = thrown(function() { Driver.system.rpcRoundtrip("mc.bot.farm", OVERSIZE); });
            var viaMcp = thrown(function() { Driver.system.mcpRoundtrip("mc.bot.farm", OVERSIZE); });
            if (direct === NO_PLAYER) {
                t.assertEqual(viaTcp, NO_PLAYER, "RPC must refuse like in-JVM");
                t.assertEqual(viaMcp, NO_PLAYER, "MCP must refuse like in-JVM");
                return;
            }
            t.assertTrue(direct !== null && direct.indexOf(OVERSIZE_CORE) >= 0, "in-JVM: " + direct);
            t.assertTrue(viaTcp !== null && viaTcp.indexOf(OVERSIZE_CORE) >= 0, "RPC: " + viaTcp);
            t.assertTrue(viaMcp !== null && viaMcp.indexOf(OVERSIZE_CORE) >= 0, "MCP: " + viaMcp);
        });

}
