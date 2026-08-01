// ROADMAP Phase B — defensive reflexes (T0). Settings round-trips run server-side
// (GameTest). The behavioural checks need a live client + a LocalPlayer to spawn
// mobs around and drive the reflexes, so they self-skip on the dedicated server.

function clientAvailable() {
    try { Agent.invoke("mc.client.screen.info", {}); return true; }
    catch (e) { return false; }
}

// Top-level (not inside a block) so the deferred AgentTest.run callbacks can see it.
function cleanup() {
    Agent.invoke("mc.action.runCommand", { cmd: "kill @e[type=!minecraft:player]" });
    Agent.invoke("mc.bot.setting", { autoDodge: false });
    Agent.invoke("mc.bot.cancel", { process: "all" });
}

AgentTest.run("41_defense: mc.observe.threats has the documented shape", function(t) {
    // Works server-side too — returns empty arrays when no client is attached.
    var r = Agent.invoke("mc.observe.threats", { radius: 16 });
    t.assertTrue(typeof r === "object" && r !== null, "object");
    t.assertTrue(Array.isArray(r.threats), "threats array");
    t.assertTrue(Array.isArray(r.incomingProjectiles), "incomingProjectiles array");
});

if (!clientAvailable()) {
    AgentTest.run("41_defense: behaviour skipped (no client api — dedicated server)", function(t) {});
} else {

    AgentTest.run("41_defense: reflex toggles round-trip", function(t) {
        var r = Agent.invoke("mc.bot.setting", {
            autoTotem: true, autoShield: true, autoHeal: true, autoDodge: true,
            healHpThreshold: 10, creeperKeepDistance: 4, projectileDodgeRadius: 10
        });
        t.assertEqual(r.ok, true, "write ok");
        ["autoTotem","autoShield","autoHeal","autoDodge"].forEach(function(k){
            t.assertTrue(r.applied.indexOf(k) >= 0, k + " applied");
            t.assertEqual(r.settings[k], true, k + " reflected");
        });
        t.assertEqual(r.settings.healHpThreshold, 10, "healHpThreshold reflected");
        t.assertEqual(r.settings.creeperKeepDistance, 4, "creeperKeepDistance reflected");
        var bad = Agent.invoke("mc.bot.setting", { creeperKeepDistance: 999 });
        t.assertTrue(Array.isArray(bad.rejected) && bad.rejected.length > 0, "out-of-range rejected");
        Agent.invoke("mc.bot.setting", {
            autoTotem: false, autoShield: false, autoHeal: false, autoDodge: false,
            healHpThreshold: 12, creeperKeepDistance: 3.5, projectileDodgeRadius: 12
        });
    });

    AgentTest.run("41_defense: observe.threats detects and scores a summoned hostile", function(t) {
        cleanup();
        var me = Agent.invoke("mc.observe.player", {});
        t.assertTrue(me.present, "player present");
        var x = Math.round(me.pos.x), y = Math.round(me.pos.y), z = Math.round(me.pos.z);
        Agent.invoke("mc.action.runCommand", { cmd: "summon zombie " + x + " " + y + " " + (z + 4) });
        Agent.system.waitTicks(4);
        var r = Agent.invoke("mc.observe.threats", { radius: 16 });
        var zombie = null;
        for (var i = 0; i < r.threats.length; i++) {
            if (r.threats[i].type.indexOf("zombie") >= 0) zombie = r.threats[i];
        }
        t.assertTrue(zombie !== null, "zombie shows up as a threat");
        t.assertEqual(zombie.hostile, true, "marked hostile");
        t.assertTrue(zombie.threat > 0, "has a positive threat score");
        t.assertTrue(typeof zombie.canSeeMe === "boolean", "canSeeMe present");
        cleanup();
    });

    AgentTest.run("41_defense: PanicChain takes the channel for a nearby creeper", function(t) {
        cleanup();
        // Widen keep-distance so a summon ~3.5 blocks away is comfortably inside it
        // (a +2 block summon lands at ~3.5 due to entity centring + rounding).
        Agent.invoke("mc.bot.setting", { autoDodge: true, creeperKeepDistance: 6 });
        var me = Agent.invoke("mc.observe.player", {});
        var x = Math.round(me.pos.x), y = Math.round(me.pos.y), z = Math.round(me.pos.z);
        Agent.invoke("mc.action.runCommand", { cmd: "summon creeper " + (x + 2) + " " + y + " " + z });
        Agent.system.waitTicks(3);
        var s = Agent.invoke("mc.bot.status", {});
        // Assert engagement (not survival) — deterministic and safe; we kill the
        // creeper immediately after so it never detonates.
        t.assertEqual(s.activeChain, "panic", "panic chain holds the channel");
        t.assertTrue(s.chainPriorities.panic >= 1000, "panic priority in the panic band");
        cleanup();
        Agent.system.waitTicks(3);
        var after = Agent.invoke("mc.bot.status", {});
        t.assertTrue(after.activeChain !== "panic", "panic releases once the creeper is gone");
    });

    AgentTest.run("41_defense: ThreatScanner senses an incoming projectile", function(t) {
        cleanup();
        Agent.invoke("mc.bot.setting", { autoDodge: true });
        var me = Agent.invoke("mc.observe.player", {});
        var x = Math.round(me.pos.x), y = Math.round(me.pos.y) + 1, z = Math.round(me.pos.z);
        // A slow arrow 14 blocks north, drifting south at the player. Slow + far so
        // the velocity tracker (needs ~2 ticks of position delta) catches it before
        // it arrives — client projectiles report ~0 deltaMovement so velocity is
        // derived from per-tick motion.
        Agent.invoke("mc.action.runCommand", {
            cmd: "summon arrow " + x + " " + y + " " + (z + 14) + " {Motion:[0.0,0.05,-0.8]}"
        });
        Agent.system.waitTicks(5);
        var threats = Agent.invoke("mc.observe.threats", { radius: 24 });
        var s = Agent.invoke("mc.bot.status", {});
        t.assertTrue(threats.incomingProjectiles.length > 0 || s.activeChain === "dodge",
            "incoming arrow sensed (or dodge already engaged)");
        cleanup();
    });
}
