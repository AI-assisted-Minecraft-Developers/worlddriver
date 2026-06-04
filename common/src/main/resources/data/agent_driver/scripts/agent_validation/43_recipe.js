// ROADMAP Phase D — read-only crafting knowledge over the game's RecipeManager.
// Recipes are server-authoritative, so these run for real on the dedicated-server
// GameTest harness (no client needed), unlike the bot tests.

function findByResult(recipes, id) {
    for (var i = 0; i < recipes.length; i++) {
        if (recipes[i].result && recipes[i].result.id === id) return recipes[i];
    }
    return null;
}

function stepIndex(steps, item) {
    for (var i = 0; i < steps.length; i++) if (steps[i].craft === item) return i;
    return -1;
}

function missingCount(missing, item) {
    for (var i = 0; i < missing.length; i++) if (missing[i].item === item) return missing[i].count;
    return 0;
}

AgentTest.run("43_recipe: lookup by result finds the diamond pickaxe recipe", function(t) {
    var r = Agent.invoke("mc.recipe.lookup", { result: "minecraft:diamond_pickaxe" });
    t.assertEqual(r.ok, true, "ok");
    t.assertTrue(r.count >= 1, "at least one recipe produces a diamond pickaxe");
    var rec = findByResult(r.recipes, "minecraft:diamond_pickaxe");
    t.assertTrue(rec !== null, "recipe with diamond_pickaxe result present");
    t.assertEqual(rec.station, "crafting_table", "pickaxe needs a crafting table (3x3)");
    t.assertEqual(rec.type, "minecraft:crafting_shaped", "shaped recipe");
    // 3 diamonds across the top + 2 sticks down the middle = 5 filled slots.
    t.assertEqual(rec.ingredients.length, 5, "five filled ingredient slots");
    var sawDiamond = false, sawStick = false;
    for (var i = 0; i < rec.ingredients.length; i++) {
        var acc = rec.ingredients[i].accepts;
        if (acc.indexOf("minecraft:diamond") >= 0) sawDiamond = true;
        if (acc.indexOf("minecraft:stick") >= 0) sawStick = true;
    }
    t.assertTrue(sawDiamond, "uses diamond");
    t.assertTrue(sawStick, "uses stick");
    t.assertTrue(Array.isArray(rec.pattern) && rec.pattern.length === 3, "3-row shaped pattern");
});

AgentTest.run("43_recipe: lookup by ingredient finds recipes that consume sticks", function(t) {
    var r = Agent.invoke("mc.recipe.lookup", { ingredient: "minecraft:stick", limit: 50 });
    t.assertEqual(r.ok, true, "ok");
    t.assertTrue(r.count >= 1, "something is made from sticks");
    // every returned recipe must actually list stick among some slot's accepts
    var allUseStick = true;
    for (var i = 0; i < r.recipes.length; i++) {
        var uses = false;
        for (var j = 0; j < r.recipes[i].ingredients.length; j++) {
            if (r.recipes[i].ingredients[j].accepts.indexOf("minecraft:stick") >= 0) uses = true;
        }
        if (!uses) allUseStick = false;
    }
    t.assertTrue(allUseStick, "every result consumes a stick");
});

AgentTest.run("43_recipe: resolve diamond pickaxe — plan ordered, diamonds missing", function(t) {
    // Give planks so only diamonds are unobtainable; exercises stick->pickaxe.
    var r = Agent.invoke("mc.recipe.resolve", {
        target: "minecraft:diamond_pickaxe", count: 1,
        have: { "minecraft:oak_planks": 10 }
    });
    t.assertEqual(r.ok, true, "ok");
    t.assertEqual(missingCount(r.missing, "minecraft:diamond"), 3, "3 diamonds missing");
    var si = stepIndex(r.steps, "minecraft:stick");
    var pi = stepIndex(r.steps, "minecraft:diamond_pickaxe");
    t.assertTrue(si >= 0, "a stick craft step exists");
    t.assertTrue(pi >= 0, "the pickaxe craft step exists");
    t.assertTrue(si < pi, "sticks are crafted before the pickaxe (topo order)");
    t.assertEqual(r.steps[pi].count, 1, "one pickaxe");
    t.assertTrue(r.stations_needed.indexOf("crafting_table") >= 0, "crafting table needed");
});

AgentTest.run("43_recipe: resolve substitutes a tag member from inventory", function(t) {
    // The crafting table recipe takes #planks; birch planks must satisfy it.
    var r = Agent.invoke("mc.recipe.resolve", {
        target: "minecraft:crafting_table", count: 1,
        have: { "minecraft:birch_planks": 4 }
    });
    t.assertEqual(r.ok, true, "ok");
    t.assertEqual(r.missing.length, 0, "birch planks satisfy #planks — nothing missing");
    t.assertTrue(r.steps.length >= 1, "at least the table step");
    var top = r.steps[r.steps.length - 1];
    t.assertEqual(top.craft, "minecraft:crafting_table", "final step makes the table");
    t.assertTrue(top.from.indexOf("minecraft:birch_planks") >= 0, "consumes birch planks");
});

AgentTest.run("43_recipe: resolve a raw material terminates (storage-pair safe)", function(t) {
    // diamond is craftable from a diamond block (decompression) — resolve must NOT
    // loop diamond->diamond_block->diamond; it reports diamond as missing.
    var r = Agent.invoke("mc.recipe.resolve", { target: "minecraft:diamond", count: 2 });
    t.assertEqual(r.ok, true, "ok (did not hang)");
    t.assertEqual(missingCount(r.missing, "minecraft:diamond"), 2, "raw diamond is missing, not decompressed");
    t.assertEqual(r.steps.length, 0, "no nonsensical decompression steps");
});

AgentTest.run("43_recipe: unknown item id is rejected, not crashed", function(t) {
    var r = Agent.invoke("mc.recipe.resolve", { target: "minecraft:not_a_real_item", count: 1 });
    t.assertEqual(r.ok, false, "ok:false for unknown item");
    t.assertTrue(typeof r.error === "string", "carries an error message");
});

AgentTest.run("43_recipe: lookup/resolve agree across in-JVM, RPC, MCP transports", function(t) {
    var la = { result: "minecraft:stick" };
    var ld = Agent.invoke("mc.recipe.lookup", la);
    var lt = Agent.system.rpcRoundtrip("mc.recipe.lookup", la);
    var lm = Agent.system.mcpRoundtrip("mc.recipe.lookup", la);
    t.assertEqual(lt.count, ld.count, "RPC lookup count matches");
    t.assertEqual(lm.count, ld.count, "MCP lookup count matches");

    var ra = { target: "minecraft:diamond_pickaxe", count: 1, have: { "minecraft:oak_planks": 10 } };
    var rd = Agent.invoke("mc.recipe.resolve", ra);
    var rt = Agent.system.rpcRoundtrip("mc.recipe.resolve", ra);
    var rm = Agent.system.mcpRoundtrip("mc.recipe.resolve", ra);
    t.assertEqual(rt.missing.length, rd.missing.length, "RPC resolve missing matches");
    t.assertEqual(rm.steps.length, rd.steps.length, "MCP resolve step count matches");
});
