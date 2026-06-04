// ROADMAP Phase G — boss sensing (mc.observe.boss) + playbook runner
// (mc.bot.playbook). Sensing + /summon need a live client + integrated server, so
// the whole suite self-skips with a recorded PASS on the dedicated GameTest server
// and runs for real in fabric runClient (AgentTest superflat CREATIVE world, where
// /summon works and the bot is invulnerable). Boss fights themselves aren't
// deterministic (entity AI) — per design 03 §6 we assert the SENSING layer + the
// playbook's structural guards (no-boss exit, gear gate), not a full kill.

function clientAvailable() {
    try { Agent.invoke("mc.client.screen.info", {}); return true; }
    catch (e) { return false; }
}
function cmd(c)  { return Agent.invoke("mc.action.runCommand", { cmd: c }); }
function boss(r) { return Agent.invoke("mc.observe.boss", r ? { radius: r } : {}); }
function has(s, needle) { return typeof s === "string" && s.indexOf(needle) >= 0; }

function selfPos() {
    var me = Agent.invoke("mc.observe.player", {});
    return { x: Math.floor(me.pos.x), y: Math.floor(me.pos.y), z: Math.floor(me.pos.z) };
}

function cleanupBosses() {
    Agent.invoke("mc.bot.cancel", { process: "all" });
    cmd("kill @e[type=minecraft:ender_dragon]");
    cmd("kill @e[type=minecraft:wither]");
    cmd("kill @e[type=minecraft:end_crystal]");
    Agent.system.waitTicks(4);
}

// Poll the runner until the background playbook goes idle; return final status.
function waitPlaybookIdle(timeoutTicks) {
    var s = null;
    for (var i = 0; i < (timeoutTicks || 60); i++) {
        s = Agent.invoke("mc.bot.playbook", { op: "status" });
        if (s && !s.active) break;
        Agent.system.waitTicks(2);
    }
    return s;
}

if (!clientAvailable()) {
    AgentTest.run("46_boss: skipped (no client api — dedicated server)", function (t) {});
} else {

    AgentTest.run("46_boss: observe.boss reports absent with no boss", function (t) {
        cleanupBosses();
        var b = boss();
        t.assertEqual(b.present, false, "no boss present");
        t.assertTrue(Array.isArray(b.crystals), "crystals is always an array");
    });

    AgentTest.run("46_boss: senses end crystals (id + pos)", function (t) {
        cleanupBosses();
        var p = selfPos();
        cmd("summon minecraft:end_crystal " + (p.x + 3) + " " + p.y + " " + p.z);
        cmd("summon minecraft:end_crystal " + p.x + " " + p.y + " " + (p.z + 3));
        Agent.system.waitTicks(6);
        var b = boss(32);
        t.assertTrue(b.crystals.length >= 2, "two crystals sensed (" + b.crystals.length + ")");
        var c = b.crystals[0];
        t.assertEqual(typeof c.id, "number", "crystal has numeric id");
        t.assertTrue(c.pos && typeof c.pos.x === "number", "crystal has pos");
        cleanupBosses();
    });

    AgentTest.run("46_boss: senses a wither (type, health, phase fields)", function (t) {
        cleanupBosses();
        var p = selfPos();
        cmd("summon minecraft:wither " + (p.x + 6) + " " + p.y + " " + p.z);
        Agent.system.waitTicks(6);
        var b = boss(48);
        t.assertEqual(b.present, true, "wither present");
        t.assertEqual(b.type, "wither", "type = wither");
        t.assertTrue(b.maxHealth >= 100, "wither maxHealth ~300 (" + b.maxHealth + ")");
        // /summon skips the soul-sand build sequence, so a summoned wither has no
        // spawn invul (invulTicks 0) — only naturally-built ones get the 220t shield.
        // Assert the fields exist and are well-formed rather than a specific value.
        t.assertEqual(typeof b.invulTicks, "number", "invulTicks exposed");
        t.assertEqual(typeof b.powered, "boolean", "powered flag exposed");
        t.assertTrue(b.phase === 1 || b.phase === 2 || b.phase === "spawning",
            "phase is 1|2|spawning (" + b.phase + ")");
        cleanupBosses();
    });

    AgentTest.run("46_boss: senses the ender dragon (phase + head)", function (t) {
        cleanupBosses();
        var p = selfPos();
        cmd("summon minecraft:ender_dragon " + p.x + " " + (p.y + 5) + " " + p.z);
        Agent.system.waitTicks(6);
        var b = boss(64);
        t.assertEqual(b.present, true, "dragon present");
        t.assertEqual(b.type, "ender_dragon", "type = ender_dragon");
        t.assertTrue(b.maxHealth >= 100, "dragon maxHealth = 200 (" + b.maxHealth + ")");
        t.assertTrue(typeof b.phase === "string" && b.phase.length > 0, "phase string (" + b.phase + ")");
        t.assertEqual(typeof b.perched, "boolean", "perched flag");
        t.assertTrue(b.head && typeof b.head.x === "number", "head position exposed");
        cleanupBosses();
    });

    AgentTest.run("46_boss: playbook rejects an unknown name", function (t) {
        var r = Agent.invoke("mc.bot.playbook", { name: "nope_not_real" });
        t.assertEqual(r.ok, false, "unknown playbook rejected");
        t.assertTrue(has(r.error, "unknown playbook"), "error names the problem (" + r.error + ")");
    });

    AgentTest.run("46_boss: dragon playbook exits cleanly with no dragon", function (t) {
        cleanupBosses();
        var r = Agent.invoke("mc.bot.playbook", { name: "dragon" });
        t.assertEqual(r.ok, true, "playbook started");
        t.assertEqual(r.started, true, "started flag");
        var s = waitPlaybookIdle(80);
        t.assertTrue(s && !s.active, "playbook finished");
        t.assertTrue(s.lastResult && has(s.lastResult.note, "no ender dragon"),
            "exited with no-dragon note (" + JSON.stringify(s && s.lastResult) + ")");
    });

    AgentTest.run("46_boss: wither playbook aborts on failed gear gate", function (t) {
        cleanupBosses();
        cmd("clear @s");
        Agent.system.waitTicks(4);
        var r = Agent.invoke("mc.bot.playbook", { name: "wither", summon: false });
        t.assertEqual(r.ok, true, "playbook started");
        var s = waitPlaybookIdle(80);
        t.assertTrue(s && !s.active, "playbook finished");
        t.assertTrue(s.lastResult && s.lastResult.ok === false, "gear gate failed -> ok:false");
        t.assertTrue(s.lastResult && has(s.lastResult.note, "gear"),
            "note mentions gear (" + JSON.stringify(s && s.lastResult) + ")");
    });
}
