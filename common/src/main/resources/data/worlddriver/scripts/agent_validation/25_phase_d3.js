// Client-only — Phase D3 additions (Parkour3Diagonal Move gated under
// allowParkour4, Agent.bot.tunnel prelude helper that wraps clearArea).
// Both observable only with a real client+world. Dedicated-server CI: skip.

function clientAvailable() {
    try {
        Agent.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

if (!clientAvailable()) {
    AgentTest.run("25_phase_d3: skipped (no client api — dedicated server)", function(t) {
        // PASS
    });
} else {

    AgentTest.run("25_phase_d3: allowParkour4 still round-trips (gates parkour3d too)",
        function(t) {
            // Parkour3Diagonal shares the allowParkour4 gate — verify the toggle
            // still works after the new Move class is registered.
            var on = Agent.invoke("mc.bot.setting", { allowParkour4: true });
            t.assertEqual(on.settings.allowParkour4, true, "allowParkour4 on");
            var off = Agent.invoke("mc.bot.setting", { allowParkour4: false });
            t.assertEqual(off.settings.allowParkour4, false, "allowParkour4 off");
        });

    AgentTest.run("25_phase_d3: Agent.bot.tunnel rejects missing distance",
        function(t) {
            var r = Agent.bot.tunnel({ direction: "forward" });
            t.assertEqual(r.ok, false, "missing distance → ok:false");
            t.assertTrue(String(r.error).indexOf("distance") >= 0,
                "error mentions distance: " + r.error);
        });

    AgentTest.run("25_phase_d3: Agent.bot.tunnel rejects unknown direction",
        function(t) {
            // distance present but direction garbage. Note: 'banana' isn't a
            // known absolute or relative direction; helper rejects it.
            var r = Agent.bot.tunnel({ direction: "banana", distance: 5 });
            t.assertEqual(r.ok, false, "unknown dir → ok:false");
            t.assertTrue(String(r.error).indexOf("direction") >= 0
                      || String(r.error).indexOf("banana") >= 0,
                "error mentions direction: " + r.error);
        });

    AgentTest.run("25_phase_d3: Agent.bot.tunnel forwards to clearArea with computed bbox",
        function(t) {
            // Absolute direction skips yaw-snap so this works at TitleScreen too.
            // Either succeeds (in-world; clearArea dispatched) or rejects with
            // "no player" (mc.observe.player needs a player; helper catches
            // that before computing bbox).
            var r = Agent.bot.tunnel({ direction: "north", distance: 3, height: 2 });
            if (!r.ok) {
                t.assertTrue(String(r.error).indexOf("no player") >= 0
                          || String(r.error).indexOf("player") >= 0
                          || String(r.error).indexOf("too large") >= 0,
                    "expected no-player or clearArea reject, got: " + r.error);
            } else {
                t.assertEqual(r.started, true, "started flag set");
                // Cancel so we don't leak a builder process across tests.
                Agent.invoke("mc.bot.cancel", { process: "builder" });
            }
        });

    AgentTest.run("25_phase_d3: setting reads identical across in-JVM/RPC/MCP",
        function(t) {
            var direct = Agent.invoke("mc.bot.setting", {});
            var viaTcp = Agent.system.rpcRoundtrip("mc.bot.setting", {});
            var viaMcp = Agent.system.mcpRoundtrip("mc.bot.setting", {});
            t.assertEqual(viaTcp.settings.allowParkour4, direct.settings.allowParkour4,
                "allowParkour4 mismatch RPC");
            t.assertEqual(viaMcp.settings.allowParkour4, direct.settings.allowParkour4,
                "allowParkour4 mismatch MCP");
        });

}
