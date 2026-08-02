// ROADMAP Phase H — persistent skill library (mc.skill, Voyager pattern). Writes
// a JS skill to disk, runs it, deletes it — all file + sandbox-evaluator work, so
// it runs the same on the dedicated GameTest server AND in runClient (no client
// gate). The skill itself only does pure JS here (no bot verbs) so it's valid on
// a headless server.

ScriptTest.run("48_skill: save / list / get / run / delete round-trip", function (t) {
    var name = "validation_demo_skill";
    Agent.invoke("mc.skill", { op: "delete", name: name });   // clean slate

    // A skill reads its call args from the injected SKILL global; last expr = result.
    var src = "var n = (typeof SKILL !== 'undefined' && SKILL.n) || 0; ({ doubled: n * 2 });";
    var sv = Agent.invoke("mc.skill", { op: "save", name: name, source: src });
    t.assertEqual(sv.ok, true, "save ok");
    t.assertEqual(sv.saved, true, "saved flag");

    var ls = Agent.invoke("mc.skill", { op: "list" });
    var inList = false;
    for (var i = 0; i < ls.skills.length; i++) if (ls.skills[i].name === name) inList = true;
    t.assertTrue(inList, "skill appears in list");

    var g = Agent.invoke("mc.skill", { op: "get", name: name });
    t.assertTrue(g.source.indexOf("doubled") >= 0, "get returns the source");

    var rn = Agent.invoke("mc.skill", { op: "run", name: name, args: { n: 21 } });
    t.assertEqual(rn.ok, true, "run ok (" + JSON.stringify(rn) + ")");
    t.assertTrue(rn.result && rn.result.doubled === 42, "skill computed 21*2=42 (" + JSON.stringify(rn.result) + ")");

    var del = Agent.invoke("mc.skill", { op: "delete", name: name });
    t.assertEqual(del.deleted, true, "deleted");
    var ls2 = Agent.invoke("mc.skill", { op: "list" });
    var still = false;
    for (var j = 0; j < ls2.skills.length; j++) if (ls2.skills[j].name === name) still = true;
    t.assertFalse(still, "gone from list after delete");
});

ScriptTest.run("48_skill: rejects bad name, syntax error, unknown skill", function (t) {
    var bad = Agent.invoke("mc.skill", { op: "save", name: "Bad Name!", source: "1" });
    t.assertEqual(bad.ok, false, "bad name rejected");

    var syn = Agent.invoke("mc.skill", { op: "save", name: "syntax_demo", source: "function ( {" });
    t.assertEqual(syn.ok, false, "syntax error rejected (not persisted)");
    t.assertTrue(typeof syn.error === "string" && syn.error.indexOf("syntax") >= 0,
        "error mentions syntax (" + syn.error + ")");

    var unk = Agent.invoke("mc.skill", { op: "run", name: "no_such_skill_xyz" });
    t.assertEqual(unk.ok, false, "running an unknown skill is rejected");
    t.assertTrue(typeof unk.error === "string" && unk.error.indexOf("unknown") >= 0, "error names it");
});
