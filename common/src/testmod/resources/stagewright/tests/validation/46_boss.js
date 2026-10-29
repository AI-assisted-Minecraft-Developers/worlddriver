// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test('46_boss', { scene: ['wd.validation', 'wd.validationIntegrated'], isolated: true }, (t) => t.step('wd.validation', {
    script: '46_boss', DriverApiError: DriverApiError, body: (ScriptTest) => {

// ROADMAP Phase G — boss sensing (mc.observe.boss) + playbook runner
// (mc.bot.playbook). Sensing + /summon need a live client + integrated server, so
// the whole suite self-skips with a recorded PASS on the dedicated GameTest server
// and runs for real in fabric runClient (ScriptTest superflat CREATIVE world, where
// /summon works and the bot is invulnerable). Boss fights themselves aren't
// deterministic (entity AI) — per design 03 §6 we assert the SENSING layer + the
// playbook's structural guards (no-boss exit, gear gate), not a full kill.

function clientAvailable() {
    try { Driver.invoke("mc.client.screen.info", {}); return true; }
    catch (e) { return false; }
}
function cmd(c)  { return Driver.invoke("mc.action.runCommand", { cmd: c }); }
// A command runs as the server unless it names a player; this names the client's own, so @s is it.
function asSelf(c) {
    var me = Driver.invoke("mc.observe.player", {}).player;
    return Driver.invoke("mc.action.runCommand", { cmd: c, as: "player:" + me.name });
}
function boss(r) { return Driver.invoke("mc.observe.boss", r ? { radius: r } : {}); }
function has(s, needle) { return typeof s === "string" && s.indexOf(needle) >= 0; }

function selfPos() {
    var me = Driver.invoke("mc.observe.player", {}).player;
    return { x: Math.floor(me.pos.x), y: Math.floor(me.pos.y), z: Math.floor(me.pos.z) };
}

function cleanupBosses() {
    Driver.invoke("mc.bot.cancel", { process: "all" });
    cmd("kill @e[type=minecraft:ender_dragon]");
    cmd("kill @e[type=minecraft:wither]");
    cmd("kill @e[type=minecraft:end_crystal]");
    // Wait for the bosses to be GONE, not for a fixed number of ticks. `/kill` does not
    // remove a living entity — it damages it to death, and a dying mob then sits in the
    // world through its ~20-tick death animation, sensed by observe.boss the entire time.
    // A 4-tick wait left that corpse standing for the NEXT check, which summoned its own
    // boss and then sensed the previous one: "senses the ender dragon" failing with
    // type=wither, and only sometimes, depending on which of the two the scan reached
    // first. Polling for absence is both correct and faster in the common case, where
    // nothing was alive to begin with and the loop exits without waiting at all.
    // Measured: a wither killed by /kill stays sensed for exactly 20 more ticks, so the
    // old 4-tick wait plus the dragon check's own 6 never once cleared it — the bug was
    // present every run and only the symptom was intermittent, depending on which of the
    // two bosses the scan happened to reach first.
    for (var i = 0; i < 30 && boss(64).boss; i++) Driver.system.waitTicks({ ticks: 2 });
}

// Poll the runner until the background playbook goes idle; return final status.
function waitPlaybookIdle(timeoutTicks) {
    var s = null;
    for (var i = 0; i < (timeoutTicks || 60); i++) {
        s = Driver.invoke("mc.bot.playbook", { op: "status" });
        if (s && !s.active) break;
        Driver.system.waitTicks({ ticks: 2 });
    }
    return s;
}

if (!clientAvailable()) {
    ScriptTest.run("46_boss: skipped (no client api — dedicated server)", function (t) {});
} else {

    ScriptTest.run("46_boss: observe.boss reports absent with no boss", function (t) {
        cleanupBosses();
        var b = boss();
        t.assertTrue(b.boss === undefined, "no boss present");
        t.assertTrue(Array.isArray(b.crystals), "crystals is always an array");
    });

    ScriptTest.run("46_boss: senses end crystals (id + pos)", function (t) {
        cleanupBosses();
        var p = selfPos();
        cmd("summon minecraft:end_crystal " + (p.x + 3) + " " + p.y + " " + p.z);
        cmd("summon minecraft:end_crystal " + p.x + " " + p.y + " " + (p.z + 3));
        Driver.system.waitTicks({ ticks: 6 });
        var b = boss(32);
        t.assertTrue(b.crystals.length >= 2, "two crystals sensed (" + b.crystals.length + ")");
        var c = b.crystals[0];
        t.assertEqual(typeof c.id, "number", "crystal has numeric id");
        t.assertTrue(c.pos && typeof c.pos.x === "number", "crystal has pos");
        cleanupBosses();
    });

    ScriptTest.run("46_boss: senses a wither (type, health, phase fields)", function (t) {
        cleanupBosses();
        var p = selfPos();
        cmd("summon minecraft:wither " + (p.x + 6) + " " + p.y + " " + p.z);
        Driver.system.waitTicks({ ticks: 6 });
        var b = boss(48).boss || {};
        t.assertEqual(b.type, "wither", "wither present, type = wither");
        t.assertTrue(b.maxHealth >= 100, "wither maxHealth ~300 (" + b.maxHealth + ")");
        // /summon skips the soul-sand build sequence, so a summoned wither has no
        // spawn invul (invulTicks 0) — only naturally-built ones get the 220t shield.
        // Assert the fields exist and are well-formed rather than a specific value.
        t.assertEqual(typeof b.invulTicks, "number", "invulTicks exposed");
        t.assertEqual(typeof b.powered, "boolean", "powered flag exposed");
        t.assertTrue(b.phase === "first" || b.phase === "second" || b.phase === "spawning",
            "phase is first|second|spawning (" + b.phase + ")");
        cleanupBosses();
    });

    ScriptTest.run("46_boss: senses the ender dragon (phase + head)", function (t) {
        cleanupBosses();
        var p = selfPos();
        cmd("summon minecraft:ender_dragon " + p.x + " " + (p.y + 5) + " " + p.z);
        Driver.system.waitTicks({ ticks: 6 });
        var b = boss(64).boss || {};
        t.assertEqual(b.type, "ender_dragon", "dragon present, type = ender_dragon");
        t.assertTrue(b.maxHealth >= 100, "dragon maxHealth = 200 (" + b.maxHealth + ")");
        t.assertTrue(typeof b.phase === "string" && b.phase.length > 0, "phase string (" + b.phase + ")");
        t.assertEqual(typeof b.perched, "boolean", "perched flag");
        t.assertTrue(b.head && typeof b.head.pos.x === "number", "head position exposed");
        cleanupBosses();
    });

    ScriptTest.run("46_boss: playbook rejects an unknown name", function (t) {
        var e = null;
        try { Driver.invoke("mc.bot.playbook", { op: "start", name: "nope_not_real" }); } catch (x) { e = x; }
        t.assertEqual(e && e.code, "invalid_params", "unknown playbook rejected");
        t.assertEqual(e.issues[0].path, "/name", "the name is what is wrong (" + e.message + ")");
    });

    ScriptTest.run("46_boss: dragon playbook exits cleanly with no dragon", function (t) {
        cleanupBosses();
        var r = Driver.invoke("mc.bot.playbook", { op: "start", name: "dragon" });
        t.assertTrue(r.runId.indexOf("playbook-dragon") === 0, "started as its own run (" + r.runId + ")");
        var s = waitPlaybookIdle(80);
        t.assertTrue(s && !s.active, "playbook finished");
        t.assertEqual(s.runId, r.runId, "status reports the run it started");
        var result = s.outcome && s.outcome.result;
        t.assertTrue(result && has(result.note, "no ender dragon"),
            "exited with no-dragon note (" + JSON.stringify(s && s.outcome) + ")");
    });

    ScriptTest.run("46_boss: wither playbook aborts on failed gear gate", function (t) {
        cleanupBosses();
        asSelf("clear @s");
        Driver.system.waitTicks({ ticks: 4 });
        Driver.invoke("mc.bot.playbook", { op: "start", name: "wither", args: { summon: false } });
        var s = waitPlaybookIdle(80);
        t.assertTrue(s && !s.active, "playbook finished");
        var result = s.outcome && s.outcome.result;
        t.assertTrue(result && result.ok === false, "gear gate failed -> ok:false");
        t.assertTrue(result && has(result.note, "gear"),
            "note mentions gear (" + JSON.stringify(s && s.outcome) + ")");
    });
}

}}));
