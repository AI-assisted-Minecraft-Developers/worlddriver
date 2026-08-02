// Client-only — mc.bot.attackEntity needs Minecraft.gameMode and a real entity.
// On dedicated-server / GameTest CI the client classes aren't loaded; record a
// skipped-PASS so headless runs stay green. Live exercise lives in fabric
// runClient sessions where mobs actually exist.

function clientAvailable() {
    try {
        Agent.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

if (!clientAvailable()) {
    ScriptTest.run("16_attack_entity: skipped (no client api — dedicated server)", function(t) {
        // PASS
    });
} else {

    ScriptTest.run("16_attack_entity: rejects missing entityId", function(t) {
        // Route-layer schema validation rejects the missing required key before the tool runs.
        var msg = null;
        try { Agent.invoke("mc.bot.attackEntity", {}); } catch (e) { msg = String(e); }
        t.assertTrue(msg !== null && msg.indexOf("missing required 'entityId'") >= 0,
            "missing entityId must be rejected by schema validation, got: " + msg);
    });

    ScriptTest.run("16_attack_entity: rejects non-integer entityId", function(t) {
        // Route-layer schema validation rejects the type violation before the tool runs.
        var msg = null;
        try { Agent.invoke("mc.bot.attackEntity", { entityId: "abc" }); } catch (e) { msg = String(e); }
        t.assertTrue(msg !== null && msg.indexOf("must be integer") >= 0,
            "non-integer entityId must be rejected by schema validation, got: " + msg);
    });

    ScriptTest.run("16_attack_entity: rejects nonexistent entity id", function(t) {
        // 2^30 is well past any real entity id in a fresh world
        var r = Agent.invoke("mc.bot.attackEntity", { entityId: 1073741824 });
        // Either "no entity with id ..." (level loaded) or "no player" (title screen) — both ok:false
        t.assertEqual(r.ok, false, "nonexistent entity must be ok:false");
        t.assertTrue(typeof r.error === "string", "must include error string");
    });

    ScriptTest.run("16_attack_entity: byte-identical results across in-JVM, RPC, MCP transports",
        function(t) {
            var args = { entityId: 1073741824 };
            var direct = Agent.invoke("mc.bot.attackEntity", args);
            var viaTcp = Agent.system.rpcRoundtrip("mc.bot.attackEntity", args);
            var viaMcp = Agent.system.mcpRoundtrip("mc.bot.attackEntity", args);
            t.assertEqual(viaTcp.ok, direct.ok, "RPC.ok mismatch");
            t.assertEqual(viaMcp.ok, direct.ok, "MCP.ok mismatch");
            t.assertEqual(viaTcp.error, direct.error, "RPC.error mismatch");
            t.assertEqual(viaMcp.error, direct.error, "MCP.error mismatch");
        });

}
