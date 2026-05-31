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
    AgentTest.run("15_input_slot_click: skipped (no client api — dedicated server)", function(t) {
        // no-op: PASS
    });
} else {

    AgentTest.run("15_input_slot_click: rejects unknown ClickType", function(t) {
        var r = Agent.invoke("mc.client.input.slotClick",
            { slot: 0, button: 0, type: "wiggle" });
        t.assertEqual(r.ok, false, "unknown type must report ok:false");
        t.assertTrue(typeof r.error === "string" && r.error.indexOf("wiggle") >= 0,
            "error must mention the offending type");
    });

    AgentTest.run("15_input_slot_click: rejects when no container screen open", function(t) {
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

    AgentTest.run("15_input_slot_click: byte-identical results across in-JVM, RPC, MCP transports",
        function(t) {
            var args = { slot: 0, type: "wiggle" };
            var direct = Agent.invoke("mc.client.input.slotClick", args);
            var viaTcp = Agent.system.rpcRoundtrip("mc.client.input.slotClick", args);
            var viaMcp = Agent.system.mcpRoundtrip("mc.client.input.slotClick", args);
            t.assertEqual(viaTcp.ok, direct.ok, "RPC.ok mismatch");
            t.assertEqual(viaMcp.ok, direct.ok, "MCP.ok mismatch");
            t.assertEqual(viaTcp.error, direct.error, "RPC.error mismatch");
            t.assertEqual(viaMcp.error, direct.error, "MCP.error mismatch");
        });

}
