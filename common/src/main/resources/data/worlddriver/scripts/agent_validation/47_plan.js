// ROADMAP Phase H — goal-directed acquisition planner (mc.plan.acquire). Pure
// computation over the recipe table (server-thread, like mc.recipe.resolve), so
// it runs the same on the dedicated GameTest server AND in runClient — no client
// gate. Asserts the planner turns "I want X" into an ordered mine→smelt→craft
// sequence and reports truly-unobtainable leaves.

function stepIndex(steps, action, needle) {
    for (var i = 0; i < steps.length; i++)
        if (steps[i].action === action && steps[i].item.indexOf(needle) >= 0) return i;
    return -1;
}
function blocksOf(steps, idx) { return (steps[idx].blocks || []).join(","); }

ScriptTest.run("47_plan: iron_pickaxe → mine raw_iron, smelt ingot, craft (ordered)", function (t) {
    var r = Agent.invoke("mc.plan.acquire", { target: "minecraft:iron_pickaxe", count: 1 });
    t.assertEqual(r.ok, true, "ok");
    var mine = stepIndex(r.steps, "mine", "raw_iron");
    t.assertTrue(mine >= 0, "mines raw_iron (" + JSON.stringify(r.steps) + ")");
    t.assertTrue(blocksOf(r.steps, mine).indexOf("iron_ore") >= 0, "mine targets an iron_ore block");
    var smelt = stepIndex(r.steps, "smelt", "iron_ingot");
    t.assertTrue(smelt >= 0, "smelts iron_ingot");
    t.assertTrue(r.steps[smelt].input.indexOf("raw_iron") >= 0, "smelt input = raw_iron (" + r.steps[smelt].input + ")");
    var craft = stepIndex(r.steps, "craft", "iron_pickaxe");
    t.assertTrue(craft >= 0, "crafts iron_pickaxe");
    // mine before smelt before craft — raw gathered, then smelted, then assembled.
    t.assertTrue(mine < smelt, "mine before smelt");
    t.assertTrue(smelt < craft, "smelt before craft");
    t.assertEqual(r.feasible, true, "iron pickaxe is fully obtainable");
});

ScriptTest.run("47_plan: diamond_pickaxe mines diamond from ore", function (t) {
    var r = Agent.invoke("mc.plan.acquire", { target: "minecraft:diamond_pickaxe", count: 1 });
    t.assertEqual(r.ok, true, "ok");
    var mine = stepIndex(r.steps, "mine", "diamond");
    t.assertTrue(mine >= 0, "mines diamond");
    t.assertTrue(blocksOf(r.steps, mine).indexOf("diamond_ore") >= 0, "mine targets a diamond_ore block");
    t.assertTrue(stepIndex(r.steps, "craft", "diamond_pickaxe") >= 0, "crafts the pickaxe");
});

ScriptTest.run("47_plan: `have` skips already-owned materials", function (t) {
    var r = Agent.invoke("mc.plan.acquire", {
        target: "minecraft:diamond_pickaxe", count: 1,
        have: { "minecraft:diamond": 3, "minecraft:stick": 2 }
    });
    t.assertEqual(r.ok, true, "ok");
    t.assertTrue(stepIndex(r.steps, "mine", "diamond") < 0, "no mine diamond when 3 in hand");
    t.assertTrue(stepIndex(r.steps, "craft", "diamond_pickaxe") >= 0, "still crafts the pickaxe");
});

ScriptTest.run("47_plan: reports unobtainable mob-drop leaves", function (t) {
    var r = Agent.invoke("mc.plan.acquire", { target: "minecraft:ender_pearl", count: 1 });
    t.assertEqual(r.ok, true, "ok");
    t.assertEqual(r.feasible, false, "ender_pearl can't be mined/farmed/smelted");
    t.assertTrue(r.unobtainable.length > 0, "unobtainable is non-empty (" + JSON.stringify(r.unobtainable) + ")");
});

ScriptTest.run("47_plan: rejects an unknown item", function (t) {
    var r = Agent.invoke("mc.plan.acquire", { target: "minecraft:not_a_real_item_xyz" });
    t.assertEqual(r.ok, false, "unknown item rejected");
    t.assertTrue(typeof r.error === "string" && r.error.indexOf("unknown") >= 0, "error names it (" + r.error + ")");
});
