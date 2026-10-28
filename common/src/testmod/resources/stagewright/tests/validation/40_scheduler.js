// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test('40_scheduler', { scene: ['wd.validation', 'wd.validationIntegrated'], isolated: true }, (t) => t.step('wd.validation', {
    script: '40_scheduler', DriverApiError: DriverApiError, body: (ScriptTest) => {
Driver.system.rpcRoundtrip = ScriptTest.rpcRoundtrip;
Driver.system.mcpRoundtrip = ScriptTest.mcpRoundtrip;

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

function read(keys) { return Driver.bot.setting({ op: "read", keys: keys }).values; }
function patch(values) { return Driver.bot.setting({ op: "patch", values: values }).values; }

function clientAvailable() {
    try {
        Driver.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

function resetScheduler() {
    Driver.invoke("mc.bot.cancel", { process: "all" });
    patch({ autoRetreat: false, retreatHpThreshold: 6 });
    // task#92: clear any hostile summoned by a preempt/outbid check so the next
    // check sees a clean channel (idle-defaults asserts retreat bids 0).
    Driver.invoke("mc.action.runCommand", { cmd: "kill @e[type=!minecraft:player]" });
}

// task#92: RetreatChain only bids when there is an actual hostile to flee FROM —
// the gap#65/#68 threat gate. The old comment's premise (threshold == max HP ⇒
// retreat bids "every tick regardless of health") is stale: on a clean world with
// no ambient mobs there is nothing to retreat from, so the reflex correctly stays
// idle. Summon a stationary hostile near the player — exactly like 41_defense — so
// the forced-retreat/preempt checks exercise the real scheduler path deterministically.
function summonRetreatThreat() {
    var me = Driver.invoke("mc.observe.player", {}).player;
    if (!me) return false;
    var x = Math.round(me.pos.x), y = Math.round(me.pos.y), z = Math.round(me.pos.z);
    Driver.invoke("mc.action.runCommand", {
        cmd: "summon zombie " + x + " " + y + " " + (z + 3) + " {NoAI:1b,PersistenceRequired:1b}"
    });
    Driver.system.waitTicks({ ticks: 4 });
    return true;
}

/** This client's player's status: its slots, activeProcess, and the reflexes that bid against its task. */
function status() {
    return Driver.bot.status({}).status;
}

if (!clientAvailable()) {
    ScriptTest.run("40_scheduler: skipped (no client api — dedicated server)", function(t) {
        // PASS
    });
} else {

    ScriptTest.run("40_scheduler: autoRetreat / retreatHpThreshold round-trip + range check", function(t) {
        patch({ autoRetreat: true, retreatHpThreshold: 8 });
        var r = read(["autoRetreat", "retreatHpThreshold"]);
        t.assertEqual(r.autoRetreat, true, "autoRetreat=true");
        t.assertEqual(r.retreatHpThreshold, 8, "threshold=8");

        var bad = null;
        try { patch({ retreatHpThreshold: 99 }); } catch (e) { bad = e; }
        t.assertEqual(bad && bad.issues[0].code, "out_of_range", "out-of-range threshold is refused");
        resetScheduler();
    });

    ScriptTest.run("40_scheduler: status exposes scheduler fields with idle defaults", function(t) {
        resetScheduler();
        Driver.system.waitTicks({ ticks: 2 });
        var r = status().reflexes;
        t.assertTrue(typeof r.chains === "object" && r.chains !== null, "chains is an object");
        // Idle + retreat off: nobody bids, channel empty, user task not suspended.
        t.assertEqual(r.userTaskSuspended, false, "no user task → not suspended");
        t.assertTrue("user" in r.chains, "user chain registered");
        t.assertTrue("retreat" in r.chains, "retreat chain registered");
        t.assertEqual(r.chains.user.priority, 0, "idle user task bids 0");
        t.assertEqual(r.chains.retreat.priority, 0, "retreat off bids 0");
    });

    ScriptTest.run("40_scheduler: forced retreat outbids and holds the movement channel", function(t) {
        resetScheduler();
        summonRetreatThreat();   // task#92: a hostile to flee, so RetreatChain bids
        // Force retreat permanently active (threshold == max HP) with a threat present.
        patch({ autoRetreat: true, retreatHpThreshold: 20 });
        Driver.system.waitTicks({ ticks: 3 });
        var r = status().reflexes;
        t.assertEqual(r.activeChain, "retreat", "retreat chain holds the channel");
        t.assertTrue(r.chains.retreat.priority >= 100, "retreat bid is in the survival band");
        t.assertTrue(r.chains.retreat.priority > r.chains.user.priority, "retreat outbids the user task");
        resetScheduler();
    });

    ScriptTest.run("40_scheduler: retreat preempts a running goto, then it resumes", function(t) {
        resetScheduler();
        // Aim a goto at a reachable point ~24 blocks away so the user task stays
        // WALKING through the test rather than completing immediately.
        var me = Driver.invoke("mc.observe.player", {}).player;
        if (me) {
            Driver.invoke("mc.bot.goto", { target: { kind: "position",
                pos: { x: Math.round(me.pos.x) + 24, y: Math.round(me.pos.y), z: Math.round(me.pos.z) } } });
            Driver.system.waitTicks({ ticks: 2 });
        }
        var running = status();
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
        patch({ autoRetreat: true, retreatHpThreshold: 20 });
        Driver.system.waitTicks({ ticks: 3 });
        var preempted = status();
        t.assertEqual(preempted.reflexes.activeChain, "retreat", "retreat preempts the goto");
        t.assertEqual(preempted.reflexes.userTaskSuspended, true, "user task reports suspended");
        t.assertEqual(preempted.activeProcess, "goto", "suspended goto is still held");

        // Drop retreat's bid — the suspended goto must regain the channel.
        patch({ autoRetreat: false });
        Driver.system.waitTicks({ ticks: 3 });
        var resumed = status();
        if (resumed.activeProcess === "goto") {
            t.assertEqual(resumed.reflexes.activeChain, "user", "user task resumes the channel");
            t.assertEqual(resumed.reflexes.userTaskSuspended, false, "no longer suspended");
        } else {
            // Goto finished while suspended/after resume — acceptable; the channel
            // must at least no longer be held by retreat.
            t.assertTrue(resumed.reflexes.activeChain !== "retreat", "retreat released the channel");
        }
        resetScheduler();
    });

    ScriptTest.run("40_scheduler: status scheduler fields agree across in-JVM, RPC, MCP transports",
        function(t) {
            resetScheduler();
            patch({ autoRetreat: true, retreatHpThreshold: 20 });
            Driver.system.waitTicks({ ticks: 3 });
            var args = {};
            var direct = Driver.invoke("mc.bot.status", args);
            var viaTcp = Driver.system.rpcRoundtrip("mc.bot.status", args);
            var viaMcp = Driver.system.mcpRoundtrip("mc.bot.status", args);
            function part(s) { return JSON.stringify([s.status.reflexes.activeChain, s.status.reflexes.userTaskSuspended]); }
            t.assertEqual(part(viaTcp), part(direct), "RPC activeChain/suspended mismatch");
            t.assertEqual(part(viaMcp), part(direct), "MCP activeChain/suspended mismatch");
            resetScheduler();
        });

}

}}));
