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
    for (var i = 0; i < 40 && hotbarHas(item) < (n || 1); i += 2) Driver.system.waitTicks(2);
}

// observe.player exposes the hotbar (crafted/smelted results shift-click into the
// hotbar first), so a hotbar scan confirms the output actually materialised.
function hotbarHas(item) {
    var me = Driver.invoke("mc.observe.player", {});
    if (!me.present || !me.hotbar) return 0;
    var n = 0;
    for (var i = 0; i < me.hotbar.length; i++) {
        var s = me.hotbar[i];
        if (s && !s.empty && s.id === item) n += s.count;
    }
    if (me.offHand && !me.offHand.empty && me.offHand.id === item) n += me.offHand.count;
    return n;
}

// Drive a craft/smelt slot to idle, polling status (the awaitMs fold can race a
// just-started process, so we confirm the slot actually went inactive).
function awaitSlot(slot, maxTicks) {
    var waited = 0;
    while (waited < maxTicks) {
        var st = Driver.invoke("mc.bot.status", {});
        if (st[slot] && st[slot].active === false) return st[slot];
        Driver.system.waitTicks(5);
        waited += 5;
    }
    return Driver.invoke("mc.bot.status", {})[slot];
}

ScriptTest.run("44_craft: mc.bot.craft reports missing materials, not a crash", function(t) {
    if (!clientAvailable()) { return; }   // behaviour is client-only
    cleanup();
    Driver.system.waitTicks(2);
    // Empty inventory → an iron pickaxe is entirely unobtainable.
    var r = Driver.invoke("mc.bot.craft", { item: "minecraft:iron_pickaxe", count: 1 });
    t.assertEqual(r.ok, true, "call accepted (async)");
    var slot = awaitSlot("craft", 40);
    t.assertTrue(typeof slot.lastError === "string", "fails with an error");
    t.assertTrue(slot.lastError.indexOf("缺") >= 0, "error names what's missing: " + slot.lastError);
    cleanup();
});

if (!clientAvailable()) {
    ScriptTest.run("44_craft: behaviour skipped (no client api — dedicated server)", function(t) {});
} else {

    ScriptTest.run("44_craft: 2x2 inventory craft makes a crafting table from planks", function(t) {
        cleanup();
        Driver.system.waitTicks(2);
        giveAndWait("minecraft:oak_planks", 4);
        var r = Driver.invoke("mc.bot.craft", { item: "minecraft:crafting_table", count: 1 });
        t.assertEqual(r.ok, true, "started");
        var slot = awaitSlot("craft", 120);
        t.assertEqual(slot.active, false, "craft finished");
        t.assertTrue(!slot.lastError, "no error: " + slot.lastError);
        t.assertTrue(hotbarHas("minecraft:crafting_table") >= 1, "a crafting table was produced");
        cleanup();
    });

    ScriptTest.run("44_craft: 3x3 table craft makes a wooden pickaxe (places + opens a table)", function(t) {
        cleanup();
        Driver.system.waitTicks(2);
        // Give all leaves so the only step is the 3x3 pickaxe, plus a table to place.
        giveAndWait("minecraft:oak_planks", 3);
        giveAndWait("minecraft:stick", 2);
        giveAndWait("minecraft:crafting_table", 1);
        var r = Driver.invoke("mc.bot.craft", { item: "minecraft:wooden_pickaxe", count: 1 });
        t.assertEqual(r.ok, true, "started");
        var slot = awaitSlot("craft", 160);
        t.assertEqual(slot.active, false, "craft finished");
        t.assertTrue(!slot.lastError, "no error: " + slot.lastError);
        t.assertTrue(hotbarHas("minecraft:wooden_pickaxe") >= 1, "a wooden pickaxe was produced");
        cleanup();
    });

    ScriptTest.run("44_craft: smelt raw iron into an iron ingot in a furnace", function(t) {
        cleanup();
        Driver.system.waitTicks(2);
        giveAndWait("minecraft:furnace", 1);
        giveAndWait("minecraft:coal", 1);
        giveAndWait("minecraft:raw_iron", 1);
        var r = Driver.invoke("mc.bot.smelt", { item: "minecraft:raw_iron", count: 1 });
        t.assertEqual(r.ok, true, "started");
        // One item smelts in ~200 ticks; allow generous slack for open/load/collect.
        var slot = awaitSlot("smelt", 360);
        t.assertEqual(slot.active, false, "smelt finished");
        t.assertTrue(hotbarHas("minecraft:iron_ingot") >= 1,
            "an iron ingot was produced" + (slot.lastError ? " (note: " + slot.lastError + ")" : ""));
        cleanup();
    });
}
