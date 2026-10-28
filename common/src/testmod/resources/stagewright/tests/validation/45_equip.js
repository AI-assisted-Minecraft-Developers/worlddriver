// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test('45_equip', { scene: ['wd.validation', 'wd.validationIntegrated'], isolated: true }, (t) => t.step('wd.validation', {
    script: '45_equip', DriverApiError: DriverApiError, body: (ScriptTest) => {

// ROADMAP Phase F — equipment management (T1 verb + T0 autoEquip). mc.bot.equip is
// client-only (reads the client inventory menu + slot-clicks), so a dedicated server only
// sees self refused, and the real checks run in fabric runClient. ScriptTest is CREATIVE — /give drops items into the inventory (not worn),
// and the client inventory lags the server /give by a few ticks, so we equip on a
// short retry loop (the verb is idempotent) rather than a fixed sleep.

function clientAvailable() {
    try { Driver.invoke("mc.client.screen.info", {}); return true; }
    catch (e) { return false; }
}

function read(keys) { return Driver.bot.setting({ op: "read", keys: keys }).values; }
function patch(values) { return Driver.bot.setting({ op: "patch", values: values }).values; }

/** The DriverApiError a call throws, or null when it returned. */
function errorOf(call) {
    try { call(); } catch (e) { return e; }
    return null;
}

function cleanup() {
    Driver.invoke("mc.bot.cancel", { process: "all" });
    Driver.invoke("mc.action.runCommand", { cmd: "clear @s" });
    patch({ autoEquip: false });
    Driver.system.waitTicks({ ticks: 4 });
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
// equipment.armor) or "mainHand" (checked against equipment.mainHand).
function equipment() { return Driver.invoke("mc.observe.player", {}).player.equipment; }

function equipUntil(profile, slot, wantNeedle) {
    var r = null;
    for (var i = 0; i < 14; i++) {
        Driver.system.waitTicks({ ticks: 4 });
        r = Driver.invoke("mc.bot.equip", { profile: profile });
        var gear = equipment();
        var worn = slot === "mainHand" ? gear.mainHand : gear.armor[slot];
        if (worn && has(worn.id, wantNeedle)) break;
    }
    return r;
}

ScriptTest.run("45_equip: an unknown profile names the ones there are", function(t) {
    var e = null;
    try { Driver.bot.equip({ profile: "fancy" }); } catch (x) { e = x; }
    t.assertEqual(e.issues[0].path, "/profile", "path");
    t.assertTrue(e.issues[0].message.indexOf("armor") >= 0, "names them: " + e.issues[0].message);
});

if (!clientAvailable()) {
    ScriptTest.run("45_equip: self is unavailable on a dedicated server", function(t) {
        var r = Driver.bot.equip({});
        t.assertEqual(r.outcome, "refused", "outcome");
        t.assertEqual(r.reason, "bot_unavailable", "reason");
    });
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
        var a = {}, r = null;
        for (var i = 0; i < 16; i++) {
            r = Driver.invoke("mc.bot.equip", { profile: "best" });
            Driver.system.waitTicks({ ticks: 4 });
            a = equipment().armor;
            if (has(a.head && a.head.id, "diamond_helmet") && has(a.chest && a.chest.id, "diamond_chestplate")
                    && has(a.legs && a.legs.id, "diamond_leggings") && has(a.feet && a.feet.id, "diamond_boots")) break;
        }
        t.assertEqual(r.outcome, "done", "equip done");
        t.assertTrue(has(a.head && a.head.id, "diamond_helmet"), "worn head = diamond (" + JSON.stringify(a.head) + ")");
        t.assertTrue(has(a.chest && a.chest.id, "diamond_chestplate"), "worn chest = diamond (" + JSON.stringify(a.chest) + ")");
        t.assertTrue(has(a.legs && a.legs.id, "diamond_leggings"), "worn legs = diamond (" + JSON.stringify(a.legs) + ")");
        t.assertTrue(has(a.feet && a.feet.id, "diamond_boots"), "worn feet = diamond (" + JSON.stringify(a.feet) + ")");
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
        var was = read(["autoEquip", "equipDurabilityThreshold"]);
        patch({ autoEquip: true, equipDurabilityThreshold: 0.25 });
        var r = read(["autoEquip", "equipDurabilityThreshold"]);
        t.assertEqual(r.autoEquip, true, "autoEquip committed");
        t.assertEqual(r.equipDurabilityThreshold, 0.25, "threshold committed");
        var bad = errorOf(function() { patch({ equipDurabilityThreshold: 5 }); });
        t.assertEqual(bad.issues[0].code, "out_of_range", "out-of-range threshold refused");
        patch(was);
    });
}

}}));
