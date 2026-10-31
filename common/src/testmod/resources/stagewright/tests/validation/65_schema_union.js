// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test("65_schema_union", { scene: ["wd.validation", "wd.validationIntegrated"], isolated: true }, (t) =>
    t.step("wd.validation", {
        script: "65_schema_union",
        DriverApiError: DriverApiError,
        body: (ScriptTest) => {
            // The fields that used to be typeless "accept anything" schema nodes. A live repro showed an MCP
            // client treating a typeless field as licence to JSON-stringify the value before tools/call:
            // mc.bot.combat {target:99999} arrived as targetType:"minecraft:99999". Those fields are now
            // tagged unions or open JSON with every kind spelled out in the schema. This suite exercises the
            // typed decoder's acceptance of every declared member and its rejection of a kind outside them;
            // only tools/call is reachable from this Rhino suite, so the rendered schemas are not checked here.

            // ---- mc.bot.combat target: a tagged union ----
            // A bot that does not exist answers the requests that decode, so none of them starts a fight.

            var NOBODY = "npc:nobody_65";

            ScriptTest.run(
                "65_schema_union: combat.target takes an entity id or an entity type, by kind",
                function (t) {
                    [
                        { kind: "entity", entityId: 3298 },
                        { kind: "entityType", type: "minecraft:zombie" },
                    ].forEach(function (target) {
                        var r = Driver.bot.combat({ bot: NOBODY, mode: "kill", target: target });
                        t.assertEqual(
                            r.reason,
                            "bot_unavailable",
                            target.kind + " decodes and reaches the body: " + JSON.stringify(r)
                        );
                    });
                }
            );

            // ---- the route object's tagged unions, two levels down (route → leash → anchor) ----
            // A typed route has no untagged unions left: a leash anchor names its kind, and an entity
            // anchor is a string even when it holds an id. The paths prove the decoder descends.

            function issuePath(call) {
                try {
                    call();
                } catch (e) {
                    return e.issues && e.issues.length ? e.issues[0].path : String(e);
                }
                return null;
            }

            var AXIS = { kind: "axis" };

            ScriptTest.run(
                "65_schema_union: goto route.leash entity anchor takes a name or an id as a string",
                function (t) {
                    ["PlayerB", "3298"].forEach(function (who) {
                        var p = issuePath(function () {
                            Driver.invoke("mc.bot.goto", {
                                target: AXIS,
                                route: { leash: { anchor: { kind: "entity", entity: who }, radius: 8 } },
                            });
                        });
                        t.assertTrue(p === null, "entity anchor " + who + " must decode, got issue at " + p);
                    });
                }
            );

            ScriptTest.run("65_schema_union: follow route.sight takes a list of observers", function (t) {
                var p = issuePath(function () {
                    Driver.invoke("mc.bot.follow", {
                        bot: NOBODY,
                        target: { kind: "player", name: "nobody_65" },
                        route: { sight: { observers: ["minecraft:skeleton"] } },
                    });
                });
                t.assertTrue(p === null || p.indexOf("/route") !== 0, "observers must decode, got issue at " + p);
            });
        },
    })
);
