// Client-only — mc.client.input.setHotbarSlot writes Inventory.selected and
// syncs to the server. On dedicated server / GameTest CI the client classes
// aren't loaded; detect that up front and record a single skipped-PASS so
// headless runs stay green.

function clientAvailable() {
    try {
        Driver.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

if (!clientAvailable()) {
    ScriptTest.run("13_set_hotbar_slot: skipped (no client api — dedicated server)", function(t) {
        // no-op: PASS so headless runs stay green
    });
} else {

    ScriptTest.run("13_set_hotbar_slot: rejects out-of-range slot", function(t) {
        // Route-layer schema validation rejects the bounds violation (slot 0-8) before the tool runs.
        var msg = null;
        try { Driver.invoke("mc.client.input.setHotbarSlot", { slot: 99 }); } catch (e) { msg = String(e); }
        t.assertTrue(msg !== null && msg.indexOf("must be <= 8") >= 0,
            "slot 99 must be rejected by schema validation, got: " + msg);
    });

    ScriptTest.run("13_set_hotbar_slot: switches slot and is observable via observe.player", function(t) {
        var info = Driver.invoke("mc.client.screen.info", {});
        if (!info.hasPlayer) {
            // No player — the call should fail cleanly, not pretend success.
            var r = Driver.invoke("mc.client.input.setHotbarSlot", { slot: 3 });
            t.assertEqual(r.ok, false, "no-player call must report ok:false");
            return;
        }
        var before = Driver.observe.player();
        var target = (before.selectedSlot + 1) % 9;
        var r = Driver.invoke("mc.client.input.setHotbarSlot", { slot: target });
        t.assertTrue(r.ok, "switch must succeed (got " + JSON.stringify(r) + ")");
        t.assertEqual(r.slot, target, "echoed slot must match requested");
        t.assertEqual(r.previous, before.selectedSlot, "previous must report prior slot");
        var after = Driver.observe.player();
        t.assertEqual(after.selectedSlot, target,
            "observe.player must report the new selectedSlot");
        // Restore so we don't leave the player on a different slot for the
        // next test in the file.
        Driver.invoke("mc.client.input.setHotbarSlot", { slot: before.selectedSlot });
    });

    ScriptTest.run("13_set_hotbar_slot: schema-reject surfaces identically across in-JVM, RPC, MCP transports",
        function(t) {
            // The validator runs inside DriverApi.route(), shared by all three
            // transports — each must surface the SAME core violation message
            // (each transport adds its own wrapper prefix, so we compare cores).
            // This keeps the Hard Rule #1 contract: one route, one behavior.
            var args = { slot: 99 };
            var core = "invalid params for mc.client.input.setHotbarSlot:";
            function thrownMsg(fn) { try { fn(); return null; } catch (e) { return String(e); } }
            var direct = thrownMsg(function() { Driver.invoke("mc.client.input.setHotbarSlot", args); });
            var viaTcp = thrownMsg(function() { Driver.system.rpcRoundtrip("mc.client.input.setHotbarSlot", args); });
            var viaMcp = thrownMsg(function() { Driver.system.mcpRoundtrip("mc.client.input.setHotbarSlot", args); });
            t.assertTrue(direct !== null && direct.indexOf(core) >= 0, "in-JVM must throw validator error, got: " + direct);
            t.assertTrue(viaTcp !== null && viaTcp.indexOf(core) >= 0, "RPC must surface the validator error, got: " + viaTcp);
            t.assertTrue(viaMcp !== null && viaMcp.indexOf(core) >= 0, "MCP must surface the validator error, got: " + viaMcp);
        });

}
