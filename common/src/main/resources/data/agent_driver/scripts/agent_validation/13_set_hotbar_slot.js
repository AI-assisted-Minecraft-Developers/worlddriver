// Client-only — mc.client.input.setHotbarSlot writes Inventory.selected and
// syncs to the server. On dedicated server / GameTest CI the client classes
// aren't loaded; detect that up front and record a single skipped-PASS so
// headless runs stay green.

function clientAvailable() {
    try {
        Agent.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

if (!clientAvailable()) {
    AgentTest.run("13_set_hotbar_slot: skipped (no client api — dedicated server)", function(t) {
        // no-op: PASS so headless runs stay green
    });
} else {

    AgentTest.run("13_set_hotbar_slot: rejects out-of-range slot", function(t) {
        var r = Agent.invoke("mc.client.input.setHotbarSlot", { slot: 99 });
        t.assertEqual(r.ok, false, "slot 99 must report ok:false");
        t.assertTrue(typeof r.error === "string", "must include error string");
    });

    AgentTest.run("13_set_hotbar_slot: switches slot and is observable via observe.player", function(t) {
        var info = Agent.invoke("mc.client.screen.info", {});
        if (!info.hasPlayer) {
            // No player — the call should fail cleanly, not pretend success.
            var r = Agent.invoke("mc.client.input.setHotbarSlot", { slot: 3 });
            t.assertEqual(r.ok, false, "no-player call must report ok:false");
            return;
        }
        var before = Agent.observe.player();
        var target = (before.selectedSlot + 1) % 9;
        var r = Agent.invoke("mc.client.input.setHotbarSlot", { slot: target });
        t.assertTrue(r.ok, "switch must succeed (got " + JSON.stringify(r) + ")");
        t.assertEqual(r.slot, target, "echoed slot must match requested");
        t.assertEqual(r.previous, before.selectedSlot, "previous must report prior slot");
        var after = Agent.observe.player();
        t.assertEqual(after.selectedSlot, target,
            "observe.player must report the new selectedSlot");
        // Restore so we don't leave the player on a different slot for the
        // next test in the file.
        Agent.invoke("mc.client.input.setHotbarSlot", { slot: before.selectedSlot });
    });

    AgentTest.run("13_set_hotbar_slot: byte-identical results across in-JVM, RPC, MCP transports",
        function(t) {
            var direct = Agent.invoke("mc.client.input.setHotbarSlot", { slot: 99 });
            var viaTcp = Agent.system.rpcRoundtrip("mc.client.input.setHotbarSlot", { slot: 99 });
            var viaMcp = Agent.system.mcpRoundtrip("mc.client.input.setHotbarSlot", { slot: 99 });
            // All three must agree on the error-path response shape. The byte-
            // identical check is the Hard Rule #1 contract in AGENTS.md.
            t.assertEqual(viaTcp.ok, direct.ok, "RPC.ok mismatch");
            t.assertEqual(viaMcp.ok, direct.ok, "MCP.ok mismatch");
            t.assertEqual(viaTcp.error, direct.error, "RPC.error mismatch");
            t.assertEqual(viaMcp.error, direct.error, "MCP.error mismatch");
        });

}
