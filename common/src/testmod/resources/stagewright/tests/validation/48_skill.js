// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test("48_skill", { scene: ["wd.validation", "wd.validationIntegrated"], isolated: true }, (t) =>
    t.step("wd.validation", {
        script: "48_skill",
        DriverApiError: DriverApiError,
        body: (ScriptTest) => {
            // The persistent skill library (mc.skill, the Voyager pattern). Writes a JS skill to disk, runs it,
            // deletes it: file and evaluator work only, so it runs the same on a dedicated server and in a
            // client. The skill itself does pure JS (no bot verbs), so it is valid on a headless server.

            function errorOf(fn) {
                try {
                    fn();
                } catch (e) {
                    return e;
                }
                return null;
            }

            ScriptTest.run("48_skill: save / list / get / run / delete round-trip", function (t) {
                var name = "validation_demo_skill";
                Driver.invoke("mc.skill", { op: "delete", name: name }); // clean slate

                // A skill reads its call args from the injected SKILL global; last expr = result.
                var src = "var n = (typeof SKILL !== 'undefined' && SKILL.n) || 0; ({ doubled: n * 2 });";
                var sv = Driver.invoke("mc.skill", { op: "save", name: name, source: src });
                t.assertEqual(sv.op, "save", "save answers its op");
                t.assertTrue(sv.bytes > 0, "saved bytes");

                var ls = Driver.invoke("mc.skill", { op: "list" });
                var inList = false;
                for (var i = 0; i < ls.skills.length; i++) if (ls.skills[i].name === name) inList = true;
                t.assertTrue(inList, "skill appears in list");

                var g = Driver.invoke("mc.skill", { op: "get", name: name });
                t.assertTrue(g.source.indexOf("doubled") >= 0, "get returns the source");

                var rn = Driver.invoke("mc.skill", { op: "run", name: name, args: { n: 21 } });
                t.assertEqual(rn.outcome.error, undefined, "run ok (" + JSON.stringify(rn) + ")");
                t.assertEqual(
                    rn.outcome.result.doubled,
                    42,
                    "skill computed 21*2=42 (" + JSON.stringify(rn.outcome.result) + ")"
                );
                t.assertTrue(
                    rn.outcome.runId.indexOf("skill-" + name) === 0,
                    "a run named after the skill: " + rn.outcome.runId
                );

                var del = Driver.invoke("mc.skill", { op: "delete", name: name });
                t.assertEqual(del.existed, true, "deleted");
                var ls2 = Driver.invoke("mc.skill", { op: "list" });
                var still = false;
                for (var j = 0; j < ls2.skills.length; j++) if (ls2.skills[j].name === name) still = true;
                t.assertFalse(still, "gone from list after delete");
            });

            ScriptTest.run("48_skill: rejects bad name, syntax error, unknown skill", function (t) {
                var bad = errorOf(function () {
                    Driver.invoke("mc.skill", { op: "save", name: "Bad Name!", source: "1" });
                });
                t.assertEqual(bad && bad.issues[0].path, "/name", "bad name rejected");

                var syn = errorOf(function () {
                    Driver.invoke("mc.skill", { op: "save", name: "syntax_demo", source: "function ( {" });
                });
                t.assertEqual(syn && syn.issues[0].path, "/source", "syntax error rejected (not persisted)");
                t.assertTrue(syn.message.indexOf("syntax") >= 0, "error mentions syntax (" + syn.message + ")");

                var unk = errorOf(function () {
                    Driver.invoke("mc.skill", { op: "run", name: "no_such_skill_xyz" });
                });
                t.assertEqual(unk && unk.code, "invalid_params", "running an unknown skill is rejected");
                t.assertEqual(unk.issues[0].path, "/name", "the name is what is wrong");
            });
        },
    })
);
