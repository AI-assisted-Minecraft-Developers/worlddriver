// ROADMAP Phase B — defensive reflexes (T0). Settings round-trips run server-side
// (GameTest). The behavioural checks need a live client + a LocalPlayer to spawn
// mobs around and drive the reflexes, so they self-skip on the dedicated server.

function clientAvailable() {
    try { Driver.invoke("mc.client.screen.info", {}); return true; }
    catch (e) { return false; }
}

// Poll a tick at a time instead of waiting a fixed number and reading once. waitTicks counts SERVER
// ticks, but ThreatScanner is CLIENT-side — so a fixed server-tick wait does not guarantee the
// client scanner has run even once, and on a loaded machine it sometimes had not. Polling also takes
// the FIRST moment the condition holds, which matters for a check whose subject is in flight and
// stops existing shortly after it arrives.
function waitUntil(pred, maxTicks) {
    var budget = maxTicks || 40;
    for (var i = 0; i < budget; i++) {
        if (pred()) return true;
        Driver.system.waitTicks({ ticks: 1 });
    }
    return pred();
}

// Top-level (not inside a block) so the deferred ScriptTest.run callbacks can see it.
function cleanup() {
    Driver.invoke("mc.action.runCommand", { cmd: "kill @e[type=!minecraft:player]" });
    Driver.bot.patchSettings({ autoDodge: false });
    Driver.invoke("mc.bot.cancel", { process: "all" });
}

ScriptTest.run("41_defense: mc.observe.threats has the documented shape", function(t) {
    // Works server-side too — returns empty arrays when no client is attached.
    var r = Driver.invoke("mc.observe.threats", { radius: 16 });
    t.assertTrue(typeof r === "object" && r !== null, "object");
    t.assertTrue(Array.isArray(r.threats), "threats array");
    t.assertTrue(Array.isArray(r.incomingProjectiles), "incomingProjectiles array");
});

if (!clientAvailable()) {
    ScriptTest.run("41_defense: behaviour skipped (no client api — dedicated server)", function(t) {});
} else {

    ScriptTest.run("41_defense: reflex toggles round-trip", function(t) {
        var keys = ["autoTotem", "autoShield", "autoHeal", "autoDodge", "healHpThreshold",
                    "creeperKeepDistance", "projectileDodgeRadius"];
        var was = Driver.bot.readSettings(keys);
        Driver.bot.patchSettings({
            autoTotem: true, autoShield: true, autoHeal: true, autoDodge: true,
            healHpThreshold: 10, creeperKeepDistance: 4, projectileDodgeRadius: 10
        });
        var r = Driver.bot.readSettings(keys);
        ["autoTotem","autoShield","autoHeal","autoDodge"].forEach(function(k){
            t.assertEqual(r[k], true, k + " committed");
        });
        t.assertEqual(r.healHpThreshold, 10, "healHpThreshold committed");
        t.assertEqual(r.creeperKeepDistance, 4, "creeperKeepDistance committed");
        var bad = null;
        try { Driver.bot.patchSettings({ creeperKeepDistance: 999 }); } catch (e) { bad = e; }
        t.assertEqual(bad && bad.issues[0].code, "out_of_range", "out-of-range refused");
        Driver.bot.patchSettings(was);
    });

    ScriptTest.run("41_defense: observe.threats detects and scores a summoned hostile", function(t) {
        cleanup();
        var me = Driver.invoke("mc.observe.player", {}).player;
        t.assertTrue(!!me, "player present");
        var x = Math.round(me.pos.x), y = Math.round(me.pos.y), z = Math.round(me.pos.z);
        Driver.invoke("mc.action.runCommand", { cmd: "summon zombie " + x + " " + y + " " + (z + 4) });
        Driver.system.waitTicks({ ticks: 4 });
        var r = Driver.invoke("mc.observe.threats", { radius: 16 });
        var zombie = null;
        for (var i = 0; i < r.threats.length; i++) {
            if (r.threats[i].type.indexOf("zombie") >= 0) zombie = r.threats[i];
        }
        t.assertTrue(zombie !== null, "zombie shows up as a threat");
        t.assertTrue(zombie.threat > 0, "has a positive threat score");
        t.assertTrue(typeof zombie.canSeeMe === "boolean", "canSeeMe present");
        cleanup();
    });

    ScriptTest.run("41_defense: PanicChain takes the channel for a nearby creeper", function(t) {
        cleanup();
        // Widen keep-distance so a summon ~3.5 blocks away is comfortably inside it
        // (a +2 block summon lands at ~3.5 due to entity centring + rounding).
        Driver.bot.patchSettings({ autoDodge: true, creeperKeepDistance: 6 });
        var me = Driver.invoke("mc.observe.player", {}).player;
        var x = Math.round(me.pos.x), y = Math.round(me.pos.y), z = Math.round(me.pos.z);
        Driver.invoke("mc.action.runCommand", { cmd: "summon creeper " + (x + 2) + " " + y + " " + z });
        Driver.system.waitTicks({ ticks: 3 });
        var s = Driver.bot.status({}).status.reflexes;
        // Assert engagement (not survival) — deterministic and safe; we kill the
        // creeper immediately after so it never detonates.
        t.assertEqual(s.activeChain, "panic", "panic chain holds the channel");
        t.assertTrue(s.chains.panic.priority >= 1000, "panic priority in the panic band");
        cleanup();
        Driver.system.waitTicks({ ticks: 3 });
        var after = Driver.bot.status({}).status.reflexes;
        t.assertTrue(after.activeChain !== "panic", "panic releases once the creeper is gone");
    });

    ScriptTest.run("41_defense: ThreatScanner senses an incoming projectile", function(t) {
        cleanup();
        Driver.bot.patchSettings({ autoDodge: true });
        var me = Driver.invoke("mc.observe.player", {}).player;
        var x = Math.round(me.pos.x), y = Math.round(me.pos.y) + 1, z = Math.round(me.pos.z);
        // A slow arrow 14 blocks north, drifting south at the player. Slow + far so
        // the velocity tracker (needs ~2 ticks of position delta) catches it before
        // it arrives — client projectiles report ~0 deltaMovement so velocity is
        // derived from per-tick motion.
        Driver.invoke("mc.action.runCommand", {
            cmd: "summon arrow " + x + " " + y + " " + (z + 14) + " {Motion:[0.0,0.05,-0.8]}"
        });
        // The arrow covers 0.8 blocks a tick from 14 away, so it is gone in ~18 ticks — the budget
        // is what it takes to see it, not a margin on top of a guess.
        var sensed = false;
        waitUntil(function () {
            var threats = Driver.invoke("mc.observe.threats", { radius: 24 });
            var s = Driver.bot.status({}).status.reflexes;
            sensed = threats.incomingProjectiles.length > 0 || s.activeChain === "dodge";
            return sensed;
        }, 20);
        t.assertTrue(sensed, "incoming arrow sensed (or dodge already engaged)");
        cleanup();
    });
}
