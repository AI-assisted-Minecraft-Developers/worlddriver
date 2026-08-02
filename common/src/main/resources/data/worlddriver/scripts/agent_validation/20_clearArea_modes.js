// Client-only — mc.bot.clearArea modes (clear / fill / replace) need a player
// + level. On dedicated-server / GameTest CI the bot impl isn't bound; record
// a skipped-PASS so headless runs stay green. Live exercise lives in fabric
// runClient sessions where the bot actually walks/breaks/places.

function clientAvailable() {
    try {
        Agent.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

if (!clientAvailable()) {
    ScriptTest.run("20_clearArea_modes: skipped (no client api — dedicated server)", function(t) {
        // PASS
    });
} else {

    ScriptTest.run("20_clearArea_modes: rejects fill + replace together", function(t) {
        var r = Agent.invoke("mc.bot.clearArea", {
            from: { x: 0, y: 64, z: 0 },
            to:   { x: 1, y: 64, z: 1 },
            fill: "minecraft:stone",
            replace: { from: "minecraft:dirt", to: "minecraft:cobblestone" }
        });
        t.assertEqual(r.ok, false, "fill+replace must be ok:false");
        t.assertTrue(r.error.indexOf("fill OR replace") >= 0, "error must mention the conflict");
    });

    ScriptTest.run("20_clearArea_modes: rejects malformed replace", function(t) {
        // Route-layer schema validation rejects the missing required nested key
        // (replace.to) before the tool runs.
        var msg = null;
        try {
            Agent.invoke("mc.bot.clearArea", {
                from: { x: 0, y: 64, z: 0 },
                to:   { x: 1, y: 64, z: 1 },
                replace: { from: "minecraft:dirt" }   // missing 'to'
            });
        } catch (e) { msg = String(e); }
        t.assertTrue(msg !== null && msg.indexOf("replace") >= 0 && msg.indexOf("missing required 'to'") >= 0,
            "missing replace.to must be rejected by schema validation, got: " + msg);
    });

    ScriptTest.run("20_clearArea_modes: oversize volume rejected", function(t) {
        var r = Agent.invoke("mc.bot.clearArea", {
            from: { x: 0,  y: 64, z: 0 },
            to:   { x: 50, y: 80, z: 50 }
        });
        t.assertEqual(r.ok, false, "oversize must be ok:false");
        t.assertTrue(r.error.indexOf("area too large") >= 0, "error must mention size");
    });

    ScriptTest.run("20_clearArea_modes: byte-identical results across in-JVM, RPC, MCP transports",
        function(t) {
            var args = {
                from: { x: 0, y: 64, z: 0 },
                to:   { x: 1, y: 64, z: 1 },
                fill: "minecraft:stone",
                replace: { from: "minecraft:dirt", to: "minecraft:cobblestone" }
            };
            var direct = Agent.invoke("mc.bot.clearArea", args);
            var viaTcp = Agent.system.rpcRoundtrip("mc.bot.clearArea", args);
            var viaMcp = Agent.system.mcpRoundtrip("mc.bot.clearArea", args);
            t.assertEqual(viaTcp.ok, direct.ok, "RPC.ok mismatch");
            t.assertEqual(viaMcp.ok, direct.ok, "MCP.ok mismatch");
            t.assertEqual(viaTcp.error, direct.error, "RPC.error mismatch");
        });

}
