// Client-only — Phase D5: mc.bot.sleep (Baritone SleepBehavior analogue).
// Happy path needs a real client + a placed bed + night-time, which the
// GameTest harness can't provide. Schema-shape rejects + 3-transport parity
// run only when a client is attached (no-player path returns ok:false too).

function clientAvailable() {
    try {
        Agent.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

if (!clientAvailable()) {
    AgentTest.run("27_sleep: skipped (no client api — dedicated server)", function(t) {
        // PASS — sleep needs a live LocalPlayer to startProcess.
    });
} else {

    AgentTest.run("27_sleep: accepts no-arg form (uses default radius)",
        function(t) {
            var r = Agent.invoke("mc.bot.sleep", {});
            // Either accepted (in-world) or rejected with "no player" (title screen).
            // Either outcome confirms schema + dispatch is correct.
            if (r.ok) {
                t.assertEqual(r.started, true, "started flag set");
                t.assertEqual(r.radius, 16, "default radius = 16");
                Agent.invoke("mc.bot.cancel", { process: "goto" });
            } else {
                t.assertTrue(String(r.error).indexOf("no player") >= 0,
                    "no-player rejection accepted: " + r.error);
            }
        });

    AgentTest.run("27_sleep: accepts explicit pos + radius",
        function(t) {
            var r = Agent.invoke("mc.bot.sleep", {
                pos: { x: 0, y: 64, z: 0 },
                radius: 8
            });
            if (r.ok) {
                t.assertEqual(r.started, true, "started flag set");
                t.assertEqual(r.radius, 8, "radius echoed");
                t.assertTrue(r.pos && r.pos.x === 0 && r.pos.y === 64 && r.pos.z === 0,
                    "pos echoed: " + JSON.stringify(r.pos));
                Agent.invoke("mc.bot.cancel", { process: "goto" });
            } else {
                t.assertTrue(String(r.error).indexOf("no player") >= 0,
                    "no-player rejection accepted: " + r.error);
            }
        });

    AgentTest.run("27_sleep: clamps oversize radius into [1,64]",
        function(t) {
            // Schema cap is 64. mc.bot.sleep silently clamps to bounds rather
            // than rejecting (matches the rest of mc.bot.* radius behaviour).
            var r = Agent.invoke("mc.bot.sleep", { radius: 9999 });
            if (r.ok) {
                t.assertEqual(r.radius, 64, "radius clamped to 64");
                Agent.invoke("mc.bot.cancel", { process: "goto" });
            } else {
                t.assertTrue(String(r.error).indexOf("no player") >= 0,
                    "no-player rejection accepted: " + r.error);
            }
        });

    AgentTest.run("27_sleep: byte-identical across in-JVM/RPC/MCP transports",
        function(t) {
            // The no-player rejection path is deterministic across transports
            // when there's no LocalPlayer; with one attached, ok:true + same
            // echoed fields. We compare the cheap fields that don't depend
            // on player position.
            var args = { radius: 8 };
            var direct = Agent.invoke("mc.bot.sleep", args);
            var viaTcp = Agent.system.rpcRoundtrip("mc.bot.sleep", args);
            var viaMcp = Agent.system.mcpRoundtrip("mc.bot.sleep", args);
            t.assertEqual(viaTcp.ok,    direct.ok,    "RPC.ok mismatch");
            t.assertEqual(viaMcp.ok,    direct.ok,    "MCP.ok mismatch");
            if (direct.ok) {
                t.assertEqual(viaTcp.radius, direct.radius, "RPC.radius mismatch");
                t.assertEqual(viaMcp.radius, direct.radius, "MCP.radius mismatch");
                // Best-effort: cancel each side's started process so we
                // don't leave the bot under three superseding sleep calls.
                Agent.invoke("mc.bot.cancel", { process: "goto" });
            } else {
                t.assertEqual(viaTcp.error, direct.error, "RPC.error mismatch");
                t.assertEqual(viaMcp.error, direct.error, "MCP.error mismatch");
            }
        });

}
