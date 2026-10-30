// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test("21_blocks_to_avoid", { scene: ["wd.validation", "wd.validationIntegrated"], isolated: true }, (t) =>
    t.step("wd.validation", {
        script: "21_blocks_to_avoid",
        DriverApiError: DriverApiError,
        body: (ScriptTest) => {
            Driver.system.rpcRoundtrip = ScriptTest.rpcRoundtrip;
            Driver.system.mcpRoundtrip = ScriptTest.mcpRoundtrip;

            // blocksToAvoid through mc.bot.setting: a whole-list replacement of registered block ids. An id no
            // block is registered under refuses the patch at that element and writes nothing. Runs everywhere:
            // the settings are this game's, not only a client's.

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
                return Driver.bot.setting({ op: "read", keys: keys }).values;
            }
            function patch(values) {
                return Driver.bot.setting({ op: "patch", values: values }).values;
            }

            ScriptTest.run("21_blocks_to_avoid: valid ids round-trip", function (t) {
                var was = read(["blocksToAvoid"]);
                var list = patch({
                    blocksToAvoid: ["minecraft:powder_snow", "minecraft:sweet_berry_bush"],
                }).blocksToAvoid;
                t.assertTrue(Array.isArray(list), "the reply holds the list");
                t.assertEqual(list.length, 2, "list length round-trips");
                t.assertEqual(read(["blocksToAvoid"]).blocksToAvoid.length, 2, "a fresh read agrees");
                patch(was);
            });

            ScriptTest.run("21_blocks_to_avoid: empty list clears the set", function (t) {
                var was = read(["blocksToAvoid"]);
                patch({ blocksToAvoid: ["minecraft:powder_snow"] });
                t.assertEqual(patch({ blocksToAvoid: [] }).blocksToAvoid.length, 0, "list now empty");
                patch(was);
            });

            ScriptTest.run("21_blocks_to_avoid: an unregistered id refuses the whole write", function (t) {
                var was = read(["blocksToAvoid"]);
                var e = errorOf(function () {
                    patch({ blocksToAvoid: ["minecraft:stone", "not_a_real:block_id"] });
                });
                t.assertTrue(e !== null, "must refuse");
                t.assertEqual(e.issues[0].path, "/values/blocksToAvoid/1", "the bad element's own path");
                t.assertEqual(JSON.stringify(read(["blocksToAvoid"])), JSON.stringify(was), "nothing written");
            });

            ScriptTest.run("21_blocks_to_avoid: the same refusal over in-JVM, RPC and MCP", function (t) {
                var args = { op: "patch", values: { blocksToAvoid: ["not_a_real:block_id"] } };
                var direct = errorOf(function () {
                    Driver.invoke("mc.bot.setting", args);
                });
                var viaRpc = errorOf(function () {
                    Driver.system.rpcRoundtrip("mc.bot.setting", args);
                });
                var viaMcp = errorOf(function () {
                    Driver.system.mcpRoundtrip("mc.bot.setting", args);
                });
                [viaRpc, viaMcp].forEach(function (e, i) {
                    var via = i === 0 ? "RPC" : "MCP";
                    t.assertTrue(e !== null, via + " must throw");
                    t.assertEqual(e.code, direct.code, via + " code");
                    t.assertEqual(JSON.stringify(e.issues), JSON.stringify(direct.issues), via + " issues");
                });
            });
        },
    })
);
