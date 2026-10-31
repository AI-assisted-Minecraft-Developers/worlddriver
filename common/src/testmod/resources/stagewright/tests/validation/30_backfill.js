// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test("30_backfill", { scene: ["wd.validation", "wd.validationIntegrated"], isolated: true }, (t) =>
    t.step("wd.validation", {
        script: "30_backfill",
        DriverApiError: DriverApiError,
        body: (ScriptTest) => {
            // Phase D8 — the autoBackfill settings behind BackfillProcess. The settings are this game's, so the
            // round-trips run on every topology.

            /** The DriverApiError a call throws, or null when it returned. */
            function errorOf(call) {
                try {
                    call();
                } catch (e) {
                    return e;
                }
                return null;
            }

            function read(keys) {
                return Driver.bot.setting(keys === undefined ? { op: "read" } : { op: "read", keys: keys }).values;
            }
            function patch(values) {
                return Driver.bot.setting({ op: "patch", values: values }).values;
            }

            ScriptTest.run("30_backfill: autoBackfillBlock refuses an unregistered id", function (t) {
                var before = read(["autoBackfillBlock"]).autoBackfillBlock;
                var e = errorOf(function () {
                    patch({ autoBackfillBlock: "minecraft:not_a_real_block_xyz" });
                });
                t.assertEqual(e.issues[0].path, "/values/autoBackfillBlock", "path");
                t.assertTrue(
                    e.issues[0].message.indexOf("not_a_real_block_xyz") >= 0,
                    "names the id: " + e.issues[0].message
                );
                t.assertEqual(read(["autoBackfillBlock"]).autoBackfillBlock, before, "unchanged");
            });
        },
    })
);
