// Client-only — mc.client.input.slotClick needs an AbstractContainerScreen open
// (player inventory, crafting table, chest the server pushed). On dedicated-server
// / GameTest CI there's no client; record a skipped-PASS so headless runs stay
// green. The real interactive verification lives in fabric runClient sessions.

function clientAvailable() {
    try {
        Agent.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

if (!clientAvailable()) {
    ScriptTest.run("15_input_slot_click: skipped (no client api — dedicated server)", function(t) {
        // no-op: PASS
    });
} else {

    ScriptTest.run("15_input_slot_click: rejects unknown ClickType", function(t) {
        // Route-layer schema validation rejects the enum violation before the tool
        // runs; the validator message echoes the offending value.
        var msg = null;
        try {
            Agent.invoke("mc.client.input.slotClick", { slot: 0, button: 0, type: "wiggle" });
        } catch (e) { msg = String(e); }
        t.assertTrue(msg !== null && msg.indexOf("wiggle") >= 0,
            "unknown type must be rejected by schema validation echoing the bad type, got: " + msg);
    });

    ScriptTest.run("15_input_slot_click: rejects when no container screen open", function(t) {
        // Try to close any container screen first so the test is deterministic.
        // closeScreen on TitleScreen / level-only is a no-op, so this is safe.
        try { Agent.invoke("mc.client.screen.close", {}); } catch (e) {}
        var info = Agent.invoke("mc.client.screen.info", {});
        if (info && info.type && info.type.indexOf("ContainerScreen") >= 0) {
            // Some other test left a container open; skip this assertion rather
            // than mutate state. Recorded as a soft PASS.
            return;
        }
        var r = Agent.invoke("mc.client.input.slotClick", { slot: 0 });
        t.assertEqual(r.ok, false, "no container screen → ok:false");
        t.assertTrue(typeof r.error === "string", "must include error string");
    });

    ScriptTest.run("15_input_slot_click: schema-reject surfaces identically across in-JVM, RPC, MCP transports",
        function(t) {
            // The validator runs inside DriverApi.route(), shared by all three
            // transports — each must surface the SAME core violation message
            // (each transport adds its own wrapper prefix, so we compare cores).
            var args = { slot: 0, type: "wiggle" };
            var core = "invalid params for mc.client.input.slotClick:";
            function thrownMsg(fn) { try { fn(); return null; } catch (e) { return String(e); } }
            var direct = thrownMsg(function() { Agent.invoke("mc.client.input.slotClick", args); });
            var viaTcp = thrownMsg(function() { Agent.system.rpcRoundtrip("mc.client.input.slotClick", args); });
            var viaMcp = thrownMsg(function() { Agent.system.mcpRoundtrip("mc.client.input.slotClick", args); });
            t.assertTrue(direct !== null && direct.indexOf(core) >= 0, "in-JVM must throw validator error, got: " + direct);
            t.assertTrue(viaTcp !== null && viaTcp.indexOf(core) >= 0, "RPC must surface the validator error, got: " + viaTcp);
            t.assertTrue(viaMcp !== null && viaMcp.indexOf(core) >= 0, "MCP must surface the validator error, got: " + viaMcp);
        });

}
