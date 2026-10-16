// ROADMAP Phase E — craft / smelt execution. Resolution is server-authoritative
// (covered server-side by 43_recipe), but executing a plan drives client-side
// slot simulation (recipe-book placement, table/furnace open, shift-click out),
// so the behavioural checks need a live client and self-skip on the dedicated
// server like the other bot tests.

function clientAvailable() {
    try { Driver.invoke("mc.client.screen.info", {}); return true; }
    catch (e) { return false; }
}

// Top-level so the deferred ScriptTest.run callbacks can see them.
function cleanup() {
    Driver.invoke("mc.bot.cancel", { process: "all" });
    Driver.invoke("mc.action.runCommand", { cmd: "clear @s" });
    Driver.invoke("mc.action.runCommand", { cmd: "kill @e[type=item]" });
}

function give(item, n) {
    Driver.invoke("mc.action.runCommand", { cmd: "give @s " + item + " " + (n || 1) });
}

// `give` mutates the SERVER inventory; CraftProcess/SmeltProcess plan against the
// CLIENT inventory, which only reflects the give a few ticks later (packet sync).
// Poll until the item lands client-side so the craft doesn't plan on stale (empty)
// inventory and wrongly report it missing.
function giveAndWait(item, n) {
    give(item, n);
    for (var i = 0; i < 40 && hotbarHas(item) < (n || 1); i += 2) Driver.system.waitTicks({ ticks: 2 });
}

// Crafted/smelted results shift-click into the hotbar first, so a scan of inventory slots 0–8
// plus the offhand (slot 40) confirms the output actually materialised.
function hotbarHas(item) {
    var me = Driver.invoke("mc.observe.player", {}).player;
    if (!me) return 0;
    var n = 0;
    for (var i = 0; i < me.inventory.length; i++) {
        var s = me.inventory[i];
        if ((s.slot < 9 || s.slot === 40) && s.id === item) n += s.count;
    }
    return n;
}

/**
 * The action {@code started} began, once it has ended, polling mc.wait.result between ticks; still
 * running after {@code maxTicks} answers with what it is then. Polled rather than awaitMs because a
 * script may run where waiting is refused.
 */
function awaitAction(started, maxTicks) {
    var id = started.action.actionId;
    for (var waited = 0; waited < maxTicks; waited += 5) {
        var a = Driver.invoke("mc.wait.result", { kind: "action", actionId: id }).action;
        if (a.state !== "running") return a;
        Driver.system.waitTicks({ ticks: 5 });
    }
    return Driver.invoke("mc.wait.result", { kind: "action", actionId: id }).action;
}

/** The DriverApiError a call throws, or null when it returned. */
function errorOf(call) {
    try { call(); } catch (e) { return e; }
    return null;
}

ScriptTest.run("44_craft: an item this game lacks is invalid_params at /item", function(t) {
    var e = errorOf(function() { Driver.bot.craft({ item: "minecraft:no_such_item" }); });
    t.assertEqual(e.issues[0].path, "/item", "path");
    t.assertEqual(e.issues[0].code, "invalid_value", "issue code");
});

if (!clientAvailable()) {
    ScriptTest.run("44_craft: self is unavailable on a dedicated server", function(t) {
        var r = Driver.bot.craft({ item: "minecraft:crafting_table" });
        t.assertEqual(r.outcome, "refused", "outcome");
        t.assertEqual(r.reason, "bot_unavailable", "reason");
    });
} else {

    ScriptTest.run("44_craft: mc.bot.craft reports missing materials, not a crash", function(t) {
        cleanup();
        Driver.system.waitTicks({ ticks: 2 });
        // Empty inventory → an iron pickaxe is entirely unobtainable.
        var a = awaitAction(Driver.bot.craft({ item: "minecraft:iron_pickaxe", count: 1 }), 40);
        t.assertEqual(a.state, "failed", "fails: " + JSON.stringify(a));
        // A bare "missing" would also match the recipe-placement timeout; only the up-front report has "N x item".
        t.assertTrue(/missing \d+ x /.test(a.message), "error names what's missing: " + a.message);
        cleanup();
    });

    ScriptTest.run("44_craft: 2x2 inventory craft makes a crafting table from planks", function(t) {
        cleanup();
        Driver.system.waitTicks({ ticks: 2 });
        giveAndWait("minecraft:oak_planks", 4);
        var a = awaitAction(Driver.bot.craft({ item: "minecraft:crafting_table", count: 1 }), 120);
        t.assertEqual(a.state, "succeeded", "craft succeeded: " + JSON.stringify(a));
        t.assertEqual(a.result.kind, "craft", "result kind");
        t.assertTrue(a.result.crafted >= 1, "crafted counted: " + a.result.crafted);
        t.assertTrue(hotbarHas("minecraft:crafting_table") >= 1, "a crafting table was produced");
        cleanup();
    });

    ScriptTest.run("44_craft: 3x3 table craft makes a wooden pickaxe (places + opens a table)", function(t) {
        cleanup();
        Driver.system.waitTicks({ ticks: 2 });
        // Give all leaves so the only step is the 3x3 pickaxe, plus a table to place.
        giveAndWait("minecraft:oak_planks", 3);
        giveAndWait("minecraft:stick", 2);
        giveAndWait("minecraft:crafting_table", 1);
        var a = awaitAction(Driver.bot.craft({ item: "minecraft:wooden_pickaxe", count: 1 }), 160);
        t.assertEqual(a.state, "succeeded", "craft succeeded: " + JSON.stringify(a));
        t.assertTrue(hotbarHas("minecraft:wooden_pickaxe") >= 1, "a wooden pickaxe was produced");
        cleanup();
    });

    ScriptTest.run("44_craft: smelt raw iron into an iron ingot in a furnace", function(t) {
        cleanup();
        Driver.system.waitTicks({ ticks: 2 });
        giveAndWait("minecraft:furnace", 1);
        giveAndWait("minecraft:coal", 1);
        giveAndWait("minecraft:raw_iron", 1);
        // One item smelts in ~200 ticks; allow generous slack for open/load/collect.
        var a = awaitAction(Driver.bot.smelt({ item: "minecraft:raw_iron", count: 1 }), 360);
        t.assertTrue(a.state !== "running", "smelt finished: " + JSON.stringify(a));
        t.assertTrue(hotbarHas("minecraft:iron_ingot") >= 1,
            "an iron ingot was produced" + (a.message ? " (note: " + a.message + ")" : ""));
        if (a.state === "succeeded") t.assertEqual(a.result.fuel, "coal", "the fuel it loaded");
        cleanup();
    });
}
