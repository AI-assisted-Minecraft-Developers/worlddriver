// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test("68_bot_menu", { scene: ["wd.validation", "wd.validationIntegrated"], isolated: true }, (t) =>
    t.step("wd.validation", {
        script: "68_bot_menu",
        DriverApiError: DriverApiError,
        body: (ScriptTest) => {
            // mc.observe.container and mc.client.input.slotClick with `bot`: the menu open on a server player is read
            // and clicked on the server, so neither needs a client and both run on every topology.

            function tick(n) {
                Driver.invoke("mc.system.waitTicks", { ticks: n || 1 });
            }

            /** The DriverApiError a call throws, or null when it returned. */
            function errorOf(call) {
                try {
                    call();
                } catch (e) {
                    return e;
                }
                return null;
            }

            ScriptTest.run("68_bot_menu: a bot's open chest menu reads and clicks on the server", function (t) {
                var o = Driver.system.testOrigin();
                // A stone pad in the air above the arena, so the bot stands beside the chest instead of falling.
                var y = o.y + 5;
                var chest = { x: o.x, y: y, z: o.z };
                var c = chest.x + " " + chest.y + " " + chest.z;
                var bot = "player:menubot";
                var pad = { from: { x: o.x - 1, y: y - 1, z: o.z - 1 }, to: { x: o.x + 3, y: y - 1, z: o.z + 1 } };
                function clearChest() {
                    Driver.invoke("mc.action.runCommand", {
                        cmd: "item replace block " + c + " container.0 with minecraft:air",
                    });
                    Driver.invoke("mc.action.runCommand", { cmd: "setblock " + c + " air" });
                }
                try {
                    Driver.invoke("mc.action.fill", { from: pad.from, to: pad.to, type: "minecraft:stone" });
                    Driver.invoke("mc.action.runCommand", { cmd: "setblock " + c + " minecraft:chest" });
                    Driver.invoke("mc.action.runCommand", {
                        cmd: "item replace block " + c + " container.0 with minecraft:diamond 5",
                    });
                    tick(1);
                    Driver.invoke("mc.bot.roster", { op: "spawn", name: "menubot", pos: { x: o.x + 2, y: y, z: o.z } });
                    tick(5);

                    Driver.invoke("mc.bot.useItem", { bot: bot, pos: chest });
                    var menu = null;
                    for (var i = 0; i < 20; i++) {
                        tick(2);
                        menu = Driver.invoke("mc.observe.container", { bot: bot });
                        if (menu.menuType === "minecraft:generic_9x3") break;
                    }
                    t.assertEqual(menu.source, "player", "source");
                    t.assertEqual(
                        menu.menuType,
                        "minecraft:generic_9x3",
                        "the chest menu is open (got " + JSON.stringify(menu) + ")",
                    );
                    t.assertEqual(menu.slots[0].id, "minecraft:diamond", "slot 0 item");
                    t.assertEqual(menu.slots[0].count, 5, "slot 0 count");

                    var click = Driver.invoke("mc.client.input.slotClick", { bot: bot, slot: 0, type: "quickMove" });
                    t.assertEqual(click.outcome, "done", "click outcome (got " + JSON.stringify(click) + ")");
                    t.assertTrue(click.contextId === undefined, "a server click has no screen context");
                    tick(1);

                    var after = Driver.invoke("mc.observe.container", { bot: bot });
                    t.assertEqual(after.slots[0].id, undefined, "slot 0 is empty in the bot's menu");
                    var block = Driver.invoke("mc.observe.container", { pos: chest });
                    t.assertEqual(block.slots[0].id, undefined, "slot 0 is empty in the chest too");
                } finally {
                    clearChest();
                    Driver.invoke("mc.action.fill", { from: pad.from, to: pad.to, type: "minecraft:air" });
                    Driver.invoke("mc.bot.roster", { op: "remove", bot: bot });
                }
            });

            ScriptTest.run("68_bot_menu: refusals", function (t) {
                var r = Driver.invoke("mc.client.input.slotClick", { bot: "player:nobody", slot: 0 });
                t.assertEqual(r.outcome, "refused", "outcome (got " + JSON.stringify(r) + ")");
                t.assertEqual(r.reason, "no_player", "reason");
                t.assertTrue(
                    errorOf(function () {
                        Driver.invoke("mc.observe.container", { bot: "player:nobody" });
                    }) !== null,
                    "reading an unknown player's menu throws",
                );
                var both = errorOf(function () {
                    Driver.invoke("mc.observe.container", { pos: { x: 0, y: 200, z: 0 }, bot: "player:nobody" });
                });
                t.assertEqual(both.code, "invalid_params", "pos with bot: code");
                t.assertEqual(both.issues[0].path, "/bot", "pos with bot: path");
            });
        },
    }),
);
