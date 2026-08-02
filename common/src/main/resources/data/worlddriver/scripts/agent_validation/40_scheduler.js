// Client-only — ROADMAP Phase A (priority-chain scheduler). The preempt/resume
// loop needs the bot impl + a LocalPlayer + a level; on dedicated-server /
// GameTest CI the bot impl isn't bound, so record a skipped-PASS. The live
// exercise (RetreatChain taking the movement channel from a goto and handing it
// back) runs under fabric runClient.
//
// Deterministic trick: instead of scripting damage (flaky in CI), we force the
// RetreatChain permanently active by setting retreatHpThreshold to max HP (20) —
// then `hp > threshold` is always false, so retreat bids every tick regardless
// of health. Turning autoRetreat off drops its bid to 0 and the user task
// resumes. That exercises the exact same scheduler path as a real low-HP flee.

function clientAvailable() {
    try {
        Agent.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

function resetScheduler() {
    Agent.invoke("mc.bot.cancel", { process: "all" });
    Agent.invoke("mc.bot.setting", { autoRetreat: false, retreatHpThreshold: 6 });
    // task#92: clear any hostile summoned by a preempt/outbid check so the next
    // check sees a clean channel (idle-defaults asserts retreat bids 0).
    Agent.invoke("mc.action.runCommand", { cmd: "kill @e[type=!minecraft:player]" });
}

// task#92: RetreatChain only bids when there is an actual hostile to flee FROM —
// the gap#65/#68 threat gate. The old comment's premise (threshold == max HP ⇒
// retreat bids "every tick regardless of health") is stale: on a clean world with
// no ambient mobs there is nothing to retreat from, so the reflex correctly stays
// idle. Summon a stationary hostile near the player — exactly like 41_defense — so
// the forced-retreat/preempt checks exercise the real scheduler path deterministically.
function summonRetreatThreat() {
    var me = Agent.invoke("mc.observe.player", {});
    if (!me || !me.present || !me.pos) return false;
    var x = Math.round(me.pos.x), y = Math.round(me.pos.y), z = Math.round(me.pos.z);
    Agent.invoke("mc.action.runCommand", {
        cmd: "summon zombie " + x + " " + y + " " + (z + 3) + " {NoAI:1b,PersistenceRequired:1b}"
    });
    Agent.system.waitTicks(4);
    return true;
}

if (!clientAvailable()) {
    ScriptTest.run("40_scheduler: skipped (no client api — dedicated server)", function(t) {
        // PASS
    });
} else {

    ScriptTest.run("40_scheduler: autoRetreat / retreatHpThreshold round-trip + range check", function(t) {
        var r = Agent.invoke("mc.bot.setting", { autoRetreat: true, retreatHpThreshold: 8 });
        t.assertEqual(r.ok, true, "write must succeed");
        t.assertTrue(r.applied.indexOf("autoRetreat") >= 0, "autoRetreat must be applied");
        t.assertTrue(r.applied.indexOf("retreatHpThreshold") >= 0, "threshold must be applied");
        t.assertEqual(r.settings.autoRetreat, true, "snapshot reflects autoRetreat=true");
        t.assertEqual(r.settings.retreatHpThreshold, 8, "snapshot reflects threshold=8");

        var bad = Agent.invoke("mc.bot.setting", { retreatHpThreshold: 99 });
        t.assertTrue(Array.isArray(bad.rejected) && bad.rejected.length > 0,
            "out-of-range threshold must be rejected");
        resetScheduler();
    });

    ScriptTest.run("40_scheduler: status exposes scheduler fields with idle defaults", function(t) {
        resetScheduler();
        Agent.system.waitTicks(2);
        var s = Agent.invoke("mc.bot.status", {});
        t.assertTrue("activeChain" in s, "activeChain present");
        t.assertTrue("userTaskSuspended" in s, "userTaskSuspended present");
        t.assertTrue(typeof s.chainPriorities === "object" && s.chainPriorities !== null,
            "chainPriorities is an object");
        // Idle + retreat off: nobody bids, channel empty, user task not suspended.
        t.assertEqual(s.userTaskSuspended, false, "no user task → not suspended");
        t.assertTrue("user" in s.chainPriorities, "user chain registered");
        t.assertTrue("retreat" in s.chainPriorities, "retreat chain registered");
        t.assertEqual(s.chainPriorities.user, 0, "idle user task bids 0");
        t.assertEqual(s.chainPriorities.retreat, 0, "retreat off bids 0");
    });

    ScriptTest.run("40_scheduler: forced retreat outbids and holds the movement channel", function(t) {
        resetScheduler();
        summonRetreatThreat();   // task#92: a hostile to flee, so RetreatChain bids
        // Force retreat permanently active (threshold == max HP) with a threat present.
        Agent.invoke("mc.bot.setting", { autoRetreat: true, retreatHpThreshold: 20 });
        Agent.system.waitTicks(3);
        var s = Agent.invoke("mc.bot.status", {});
        t.assertEqual(s.activeChain, "retreat", "retreat chain holds the channel");
        t.assertTrue(s.chainPriorities.retreat >= 100, "retreat bid is in the survival band");
        t.assertTrue(s.chainPriorities.retreat > s.chainPriorities.user,
            "retreat outbids the user task");
        resetScheduler();
    });

    ScriptTest.run("40_scheduler: retreat preempts a running goto, then it resumes", function(t) {
        resetScheduler();
        // Aim a goto at a reachable point ~24 blocks away so the user task stays
        // WALKING through the test rather than completing immediately.
        var me = Agent.invoke("mc.observe.player", {});
        if (me && me.present && me.pos) {
            // A block goal needs the nested `pos` form — a flat {x,y,z} with y
            // equal to the current level resolves to a YLevel goal that's already
            // satisfied and completes instantly. pos targets the actual block.
            Agent.invoke("mc.bot.goto", {
                pos: { x: Math.round(me.pos.x) + 24, y: Math.round(me.pos.y), z: Math.round(me.pos.z) }
            });
            Agent.system.waitTicks(2);
        }
        var running = Agent.invoke("mc.bot.status", {});
        if (running.activeProcess !== "goto") {
            // Goto didn't stay active (geometry-dependent) — preempt assertion is
            // best-effort; the forced-retreat test above already proved the bid.
            resetScheduler();
            t.assertTrue(true, "goto not running; skipped preempt sub-check");
            return;
        }
        // While the goto runs, force retreat — it must take the channel and the
        // user task must report suspended (but still held as activeProcess).
        summonRetreatThreat();   // task#92: a hostile to flee, so RetreatChain bids
        Agent.invoke("mc.bot.setting", { autoRetreat: true, retreatHpThreshold: 20 });
        Agent.system.waitTicks(3);
        var preempted = Agent.invoke("mc.bot.status", {});
        t.assertEqual(preempted.activeChain, "retreat", "retreat preempts the goto");
        t.assertEqual(preempted.userTaskSuspended, true, "user task reports suspended");
        t.assertEqual(preempted.activeProcess, "goto", "suspended goto is still held");

        // Drop retreat's bid — the suspended goto must regain the channel.
        Agent.invoke("mc.bot.setting", { autoRetreat: false });
        Agent.system.waitTicks(3);
        var resumed = Agent.invoke("mc.bot.status", {});
        if (resumed.activeProcess === "goto") {
            t.assertEqual(resumed.activeChain, "user", "user task resumes the channel");
            t.assertEqual(resumed.userTaskSuspended, false, "no longer suspended");
        } else {
            // Goto finished while suspended/after resume — acceptable; the channel
            // must at least no longer be held by retreat.
            t.assertTrue(resumed.activeChain !== "retreat", "retreat released the channel");
        }
        resetScheduler();
    });

    ScriptTest.run("40_scheduler: status scheduler fields agree across in-JVM, RPC, MCP transports",
        function(t) {
            resetScheduler();
            Agent.invoke("mc.bot.setting", { autoRetreat: true, retreatHpThreshold: 20 });
            Agent.system.waitTicks(3);
            var args = {};
            var direct = Agent.invoke("mc.bot.status", args);
            var viaTcp = Agent.system.rpcRoundtrip("mc.bot.status", args);
            var viaMcp = Agent.system.mcpRoundtrip("mc.bot.status", args);
            t.assertEqual(viaTcp.activeChain, direct.activeChain, "RPC activeChain mismatch");
            t.assertEqual(viaMcp.activeChain, direct.activeChain, "MCP activeChain mismatch");
            t.assertEqual(viaTcp.userTaskSuspended, direct.userTaskSuspended, "RPC suspended mismatch");
            t.assertEqual(viaMcp.userTaskSuspended, direct.userTaskSuspended, "MCP suspended mismatch");
            resetScheduler();
        });

}
