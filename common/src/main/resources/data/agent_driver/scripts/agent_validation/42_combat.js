// ROADMAP Phase C — active combat loop (T1). mc.bot.combat is client-only
// (needs Minecraft.gameMode + real mobs), so every test self-skips with a
// recorded PASS on the dedicated GameTest server. The behavioural checks run in
// fabric runClient sessions where the AgentTest world (superflat CREATIVE) lets
// us summon mobs and watch the bot clear them. CREATIVE = the bot is invulnerable,
// so combat never gets preempted by autoRetreat and the assertions are about the
// bot's OFFENCE (clearing the pack, timing, kiting), not its survival.

function clientAvailable() {
    try { Agent.invoke("mc.client.screen.info", {}); return true; }
    catch (e) { return false; }
}

function dist(a, b) {
    var dx = a.x - b.x, dy = a.y - b.y, dz = a.z - b.z;
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
}

function countType(threats, needle) {
    var n = 0;
    for (var i = 0; i < threats.length; i++) if (threats[i].type.indexOf(needle) >= 0) n++;
    return n;
}

function findType(threats, needle) {
    for (var i = 0; i < threats.length; i++) if (threats[i].type.indexOf(needle) >= 0) return threats[i];
    return null;
}

// Top-level so the deferred AgentTest.run callbacks can reach them.
function cleanup() {
    Agent.invoke("mc.bot.cancel", { process: "all" });
    Agent.invoke("mc.action.runCommand", { cmd: "kill @e[type=!minecraft:player]" });
    Agent.invoke("mc.bot.setting", { autoFight: false, autoRetreat: false });
    Agent.invoke("mc.action.runCommand", { cmd: "clear @s" });
    Agent.system.waitTicks(3);
}

// Put a weapon in hotbar slot 0 and select it; wait for the client inventory to sync.
function equip(item) {
    Agent.invoke("mc.action.runCommand", { cmd: "item replace entity @s hotbar.0 with " + item });
    Agent.invoke("mc.client.input.setHotbarSlot", { slot: 0 });
    Agent.system.waitTicks(6);
}

if (!clientAvailable()) {
    AgentTest.run("42_combat: skipped (no client api — dedicated server)", function(t) {});
} else {

    AgentTest.run("42_combat: rejects an unknown mode", function(t) {
        var r = Agent.invoke("mc.bot.combat", { mode: "bogus" });
        t.assertEqual(r.ok, false, "bad mode must be ok:false");
        t.assertTrue(r.error.indexOf("engage") >= 0, "error names the valid modes");
    });

    AgentTest.run("42_combat: kill mode requires a target", function(t) {
        var r = Agent.invoke("mc.bot.combat", { mode: "kill" });
        t.assertEqual(r.ok, false, "kill without target must be ok:false");
        t.assertTrue(r.error.indexOf("target") >= 0, "error mentions target");
    });

    AgentTest.run("42_combat: melee engage clears a zombie pack with well-timed swings", function(t) {
        cleanup();
        equip("minecraft:diamond_sword");
        var me = Agent.invoke("mc.observe.player", {});
        t.assertTrue(me.present, "player present");
        var x = Math.round(me.pos.x), y = Math.round(me.pos.y), z = Math.round(me.pos.z);
        // A tight cluster of stationary (NoAI) zombies a few blocks away: the bot
        // must path in, then swing each down. NoAI keeps them put (no wandering /
        // despawn) so the clear is deterministic — they still take damage and die.
        var spots = [[x + 3, z], [x + 3, z + 1], [x + 4, z]];
        for (var i = 0; i < spots.length; i++) {
            Agent.invoke("mc.action.runCommand", {
                cmd: "summon zombie " + spots[i][0] + " " + y + " " + spots[i][1] + " {NoAI:1b,PersistenceRequired:1b}"
            });
        }
        Agent.system.waitTicks(3);
        var before = Agent.invoke("mc.observe.threats", { radius: 16 });
        t.assertTrue(countType(before.threats, "zombie") >= 3, "3 zombies summoned");

        var r = Agent.invoke("mc.bot.combat", { mode: "engage", awaitMs: 25000 });
        t.assertEqual(r.ok, true, "combat started");
        t.assertEqual(r.completed, true, "engage ran to completion (area cleared)");

        Agent.system.waitTicks(5);   // let the last corpse leave the client entity list
        var after = Agent.invoke("mc.observe.threats", { radius: 16 });
        t.assertEqual(countType(after.threats, "zombie"), 0, "all zombies cleared");

        var cs = r.status;   // combat slot at completion (swings/wellTimed/crits/kills)
        t.assertTrue(cs.swings > 0, "the bot actually swung (" + cs.swings + ")");
        // Every swing is cooldown-gated (scale ≥ 1.0), so wellTimed should equal
        // swings — the whole point of Phase C's timing fix.
        t.assertEqual(cs.wellTimed, cs.swings, "every swing landed at full attack strength");
        t.assertTrue(cs.kills >= 2, "credited the kills (" + cs.kills + ")");
        cleanup();
    });

    AgentTest.run("42_combat: ranged kite keeps distance from a skeleton and fires", function(t) {
        cleanup();
        equip("minecraft:bow");
        Agent.invoke("mc.action.runCommand", { cmd: "give @s minecraft:arrow 64" });
        Agent.invoke("mc.bot.setting", { kiteDistance: 8 });
        var me = Agent.invoke("mc.observe.player", {});
        var x = Math.round(me.pos.x), y = Math.round(me.pos.y), z = Math.round(me.pos.z);
        // Stationary skeleton 3 blocks away — inside the kite band, so the bot must
        // back off toward kiteDistance (8) while drawing. NoAI keeps it as a fixed
        // reference point even if an arrow drops it.
        var sx = x, sy = y, sz = z + 3;
        Agent.invoke("mc.action.runCommand", {
            cmd: "summon skeleton " + sx + " " + sy + " " + sz + " {NoAI:1b,PersistenceRequired:1b}"
        });
        Agent.system.waitTicks(3);
        var th = Agent.invoke("mc.observe.threats", { radius: 16 });
        var sk = findType(th.threats, "skeleton");
        t.assertTrue(sk !== null, "skeleton present");

        Agent.invoke("mc.bot.combat", { mode: "kill", target: { id: sk.id } });   // non-blocking
        Agent.system.waitTicks(50);

        var s = Agent.invoke("mc.bot.status", {});
        var now = Agent.invoke("mc.observe.player", {});
        var d = dist(now.pos, { x: sx + 0.5, y: sy, z: sz + 0.5 });
        // A melee bot would have closed to < combatReach (3); the kite backs AWAY,
        // so distance from the spawn point grows. Drawing slows movement to a crawl,
        // hence the modest >4 threshold over 50 ticks rather than the full 8.
        t.assertTrue(d > 4.0, "bot kited away from the skeleton, not into melee (d=" + d.toFixed(1) + ")");
        t.assertTrue(s.combat.swings >= 1, "drew and released at least one arrow (" + s.combat.swings + ")");
        cleanup();
    });

    AgentTest.run("42_combat: status.combat carries telemetry + cancel stands the chain down", function(t) {
        cleanup();
        equip("minecraft:diamond_sword");
        var me = Agent.invoke("mc.observe.player", {});
        var x = Math.round(me.pos.x), y = Math.round(me.pos.y), z = Math.round(me.pos.z);
        Agent.invoke("mc.action.runCommand", {
            cmd: "summon zombie " + (x + 3) + " " + y + " " + z + " {NoAI:1b,PersistenceRequired:1b}"
        });
        Agent.system.waitTicks(3);
        Agent.invoke("mc.bot.combat", { mode: "engage" });   // non-blocking
        Agent.system.waitTicks(4);
        var s = Agent.invoke("mc.bot.status", {});
        t.assertEqual(s.combat.active, true, "combat slot active while engaged");
        t.assertEqual(s.activeChain, "combat", "combat chain holds the movement channel");
        t.assertTrue(s.chainPriorities.combat >= 60, "combat bid in the COMBAT band");

        Agent.invoke("mc.bot.cancel", { process: "combat" });
        Agent.system.waitTicks(3);
        var after = Agent.invoke("mc.bot.status", {});
        t.assertEqual(after.combat.active, false, "cancel deactivated the combat slot");
        t.assertTrue(after.activeChain !== "combat", "combat chain released the channel");
        cleanup();
    });
}
