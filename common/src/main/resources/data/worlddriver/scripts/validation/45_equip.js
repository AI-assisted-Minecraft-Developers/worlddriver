// ROADMAP Phase F — equipment management (T1 verb + T0 autoEquip). mc.bot.equip is
// client-only (reads the client inventory menu + slot-clicks), so the tests self-skip
// with a recorded PASS on the dedicated GameTest server and run for real in fabric
// runClient. ScriptTest is CREATIVE — /give drops items into the inventory (not worn),
// and the client inventory lags the server /give by a few ticks, so we equip on a
// short retry loop (the verb is idempotent) rather than a fixed sleep.

function clientAvailable() {
    try { Driver.invoke("mc.client.screen.info", {}); return true; }
    catch (e) { return false; }
}

function cleanup() {
    Driver.invoke("mc.bot.cancel", { process: "all" });
    Driver.invoke("mc.action.runCommand", { cmd: "clear @s" });
    Driver.invoke("mc.bot.setting", { autoEquip: false });
    Driver.system.waitTicks(4);
}

function give(item) {
    Driver.invoke("mc.action.runCommand", { cmd: "give @s " + item });
}

function has(s, needle) { return typeof s === "string" && s.indexOf(needle) >= 0; }

// Equip on a retry loop until the SERVER confirms the wanted piece is worn (via
// observe, which is server-authoritative) — not just the client's optimistic
// loadout. This rides out the /give → inventory sync lag: a click issued before
// the item has reached the server is reverted, so we re-issue until it sticks
// (exactly what a real agent would do). `slot` is "head".."feet" (checked against
// observe.armor) or "mainHand" (checked against observe.mainHand).
function equipUntil(profile, slot, wantNeedle) {
    var r = null;
    for (var i = 0; i < 14; i++) {
        Driver.system.waitTicks(4);
        r = Driver.invoke("mc.bot.equip", { profile: profile });
        var me = Driver.invoke("mc.observe.player", {});
        var worn = slot === "mainHand" ? me.mainHand : (me.armor ? me.armor[slot] : null);
        if (worn && has(worn.id, wantNeedle)) break;
    }
    return r;
}

if (!clientAvailable()) {
    ScriptTest.run("45_equip: skipped (no client api — dedicated server)", function(t) {});
} else {

    ScriptTest.run("45_equip: equips the best armor on every slot (diamond over iron)", function(t) {
        cleanup();
        ["iron_helmet", "diamond_helmet", "iron_chestplate", "diamond_chestplate",
         "iron_leggings", "diamond_leggings", "iron_boots", "diamond_boots"].forEach(function(a) {
            give("minecraft:" + a);
        });
        // Re-equip until the SERVER confirms all four diamond pieces are worn — a
        // click issued before a /give has reached the server is reverted, so we
        // re-issue until each slot sticks (what a real agent does). observe is
        // server-authoritative; the client loadout can read optimistically-worn.
        var me = null, r = null;
        for (var i = 0; i < 16; i++) {
            r = Driver.invoke("mc.bot.equip", { profile: "best" });
            Driver.system.waitTicks(4);
            me = Driver.invoke("mc.observe.player", {});
            var a = me.armor || {};
            if (has(a.head && a.head.id, "diamond_helmet") && has(a.chest && a.chest.id, "diamond_chestplate")
                    && has(a.legs && a.legs.id, "diamond_leggings") && has(a.feet && a.feet.id, "diamond_boots")) break;
        }
        t.assertEqual(r.ok, true, "equip ok");
        t.assertTrue(has(me.armor.head.id, "diamond_helmet"), "worn head = diamond (" + JSON.stringify(me.armor.head) + ")");
        t.assertTrue(has(me.armor.chest.id, "diamond_chestplate"), "worn chest = diamond (" + JSON.stringify(me.armor.chest) + ")");
        t.assertTrue(has(me.armor.legs.id, "diamond_leggings"), "worn legs = diamond (" + JSON.stringify(me.armor.legs) + ")");
        t.assertTrue(has(me.armor.feet.id, "diamond_boots"), "worn feet = diamond (" + JSON.stringify(me.armor.feet) + ")");
        t.assertEqual(r.missing.length, 0, "nothing missing with a full set");
        cleanup();
    });

    ScriptTest.run("45_equip: picks the best weapon — sword over axe, by tier", function(t) {
        cleanup();
        give("minecraft:diamond_axe");
        give("minecraft:iron_sword");
        give("minecraft:diamond_sword");
        var r = equipUntil("best", "mainHand", "diamond_sword");
        t.assertTrue(has(r.loadout.mainHand, "diamond_sword"),
            "main hand = diamond sword, not the axe/iron (" + r.loadout.mainHand + ")");
        cleanup();
    });

    ScriptTest.run("45_equip: flags a low-durability equipped item", function(t) {
        cleanup();
        // Diamond sword max durability 1561; damage 1555 → ~0.4% left, well under the
        // default 10% threshold. 1.21 component syntax: [damage=N].
        give("minecraft:diamond_sword[damage=1555]");
        var r = equipUntil("best", "mainHand", "diamond_sword");
        t.assertTrue(has(r.loadout.mainHand, "diamond_sword"), "worn the only weapon");
        var flagged = false;
        for (var i = 0; i < r.lowDurability.length; i++) if (has(r.lowDurability[i], "diamond_sword")) flagged = true;
        t.assertTrue(flagged, "near-broken sword flagged in lowDurability (" + JSON.stringify(r.lowDurability) + ")");
        cleanup();
    });

    ScriptTest.run("45_equip: reports missing armor slots", function(t) {
        cleanup();
        give("minecraft:diamond_helmet");   // only a helmet — three slots stay empty
        var r = equipUntil("armor", "head", "diamond_helmet");
        t.assertTrue(has(r.loadout.head, "diamond_helmet"), "helmet equipped");
        ["chest", "legs", "feet"].forEach(function(s) {
            t.assertTrue(r.missing.indexOf(s) >= 0, s + " reported missing (" + JSON.stringify(r.missing) + ")");
        });
        cleanup();
    });

    ScriptTest.run("45_equip: autoEquip + threshold settings round-trip", function(t) {
        var r = Driver.invoke("mc.bot.setting", { autoEquip: true, equipDurabilityThreshold: 0.25 });
        t.assertEqual(r.ok, true, "write ok");
        t.assertTrue(r.applied.indexOf("autoEquip") >= 0, "autoEquip applied");
        t.assertEqual(r.settings.autoEquip, true, "autoEquip reflected");
        t.assertEqual(r.settings.equipDurabilityThreshold, 0.25, "threshold reflected");
        var bad = Driver.invoke("mc.bot.setting", { equipDurabilityThreshold: 5 });
        t.assertTrue(Array.isArray(bad.rejected) && bad.rejected.length > 0, "out-of-range threshold rejected");
        Driver.invoke("mc.bot.setting", { autoEquip: false, equipDurabilityThreshold: 0.1 });
    });
}
