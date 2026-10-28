// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test('13_set_hotbar_slot', { scene: ['wd.validation', 'wd.validationIntegrated'], isolated: true }, (t) => t.step('wd.validation', {
    script: '13_set_hotbar_slot', DriverApiError: DriverApiError, body: (ScriptTest) => {
Driver.system.rpcRoundtrip = ScriptTest.rpcRoundtrip;
Driver.system.mcpRoundtrip = ScriptTest.mcpRoundtrip;

// mc.client.input.setHotbarSlot writes Inventory.selected and syncs to the server. The request checks
// read nothing and run on every topology; the switch itself needs a client.

function clientAvailable() {
    try {
        Driver.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

/** The DriverApiError a call throws, or null when it returned. */
function errorOf(call) {
    try { call(); } catch (e) { return e; }
    return null;
}

// Poll a tick at a time instead of reading once. setHotbarSlot writes the CLIENT's selected slot
// and syncs by packet; observe.player reads the SERVER's. Those are two threads either side of a
// queue even on an integrated server, so "read it straight back" is a race that passes on an idle
// machine and fails on a busy one — which is exactly how it presented, as an intermittent RED.
function waitUntil(pred, maxTicks) {
    var budget = maxTicks || 40;
    for (var i = 0; i < budget; i++) {
        if (pred()) return true;
        Driver.system.waitTicks({ ticks: 1 });
    }
    return pred();
}

ScriptTest.run("13_set_hotbar_slot: rejects out-of-range slot", function(t) {
    var e = errorOf(function() { Driver.client.input.setHotbarSlot({ slot: 99 }); });
    t.assertEqual(e.code, "invalid_params", "code");
    t.assertEqual(e.issues[0].path, "/slot", "path");
    t.assertEqual(e.issues[0].code, "out_of_range", "issue code");
});

ScriptTest.run("13_set_hotbar_slot: the same rejection over in-JVM, RPC and MCP", function(t) {
    var args = { slot: 99 };
    var direct = errorOf(function() { Driver.invoke("mc.client.input.setHotbarSlot", args); });
    var viaTcp = errorOf(function() { Driver.system.rpcRoundtrip("mc.client.input.setHotbarSlot", args); });
    var viaMcp = errorOf(function() { Driver.system.mcpRoundtrip("mc.client.input.setHotbarSlot", args); });
    t.assertEqual(JSON.stringify(viaTcp.issues), JSON.stringify(direct.issues), "RPC issues");
    t.assertEqual(JSON.stringify(viaMcp.issues), JSON.stringify(direct.issues), "MCP issues");
});

if (!clientAvailable()) {
    ScriptTest.run("13_set_hotbar_slot: a dedicated server has no hotbar to switch", function(t) {
        var e = errorOf(function() { Driver.client.input.setHotbarSlot({ slot: 3 }); });
        t.assertEqual(e.code, "capability_unavailable", "code");
    });
} else {

    ScriptTest.run("13_set_hotbar_slot: switches slot and is observable via observe.player", function(t) {
        var info = Driver.client.screen.info();
        if (!info.hasPlayer) {
            // No player — the call should be refused, not pretend success.
            var r = Driver.client.input.setHotbarSlot({ slot: 3 });
            t.assertEqual(r.reason, "no_player", "no-player call must be refused");
            return;
        }
        var selected = function () { return Driver.observe.player({}).player.equipment.selectedSlot; };
        var before = selected();
        var target = (before + 1) % 9;
        var r = Driver.client.input.setHotbarSlot({ slot: target });
        t.assertEqual(r.outcome, "done", "switch must succeed (got " + JSON.stringify(r) + ")");
        t.assertEqual(r.slot, target, "echoed slot must match requested");
        t.assertEqual(r.previous, before, "previous must report prior slot");
        var after = before;
        waitUntil(function () {
            after = selected();
            return after === target;
        }, 40);
        t.assertEqual(after, target,
            "observe.player must report the new selectedSlot");
        // Restore so we don't leave the player on a different slot for the
        // next test in the file.
        Driver.client.input.setHotbarSlot({ slot: before });
    });

}

}}));
