// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test("15_input_slot_click", { scene: ["wd.validation", "wd.validationIntegrated"], isolated: true }, (t) =>
    t.step("wd.validation", {
        script: "15_input_slot_click",
        DriverApiError: DriverApiError,
        body: (ScriptTest) => {
            Driver.system.rpcRoundtrip = ScriptTest.rpcRoundtrip;
            Driver.system.mcpRoundtrip = ScriptTest.mcpRoundtrip;

            // mc.client.input.slotClick needs an AbstractContainerScreen open (player inventory, crafting table,
            // chest the server pushed). The request checks run on every topology; the clicks need a client.

            function clientAvailable() {
                try {
                    Driver.invoke("mc.client.screen.info", {});
                    return true;
                } catch (e) {
                    return false;
                }
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

            ScriptTest.run("15_input_slot_click: rejects unknown ClickType", function (t) {
                var e = errorOf(function () {
                    Driver.client.input.slotClick({ slot: 0, button: 0, type: "wiggle" });
                });
                t.assertEqual(e.code, "invalid_params", "code");
                t.assertEqual(e.issues[0].path, "/type", "path");
                t.assertTrue(
                    e.issues[0].message.indexOf("quickMove") >= 0,
                    "names the click types: " + e.issues[0].message
                );
            });

            ScriptTest.run("15_input_slot_click: the same rejection over in-JVM, RPC and MCP", function (t) {
                var args = { slot: 0, type: "wiggle" };
                var direct = errorOf(function () {
                    Driver.invoke("mc.client.input.slotClick", args);
                });
                var viaTcp = errorOf(function () {
                    Driver.system.rpcRoundtrip("mc.client.input.slotClick", args);
                });
                var viaMcp = errorOf(function () {
                    Driver.system.mcpRoundtrip("mc.client.input.slotClick", args);
                });
                t.assertEqual(JSON.stringify(viaTcp.issues), JSON.stringify(direct.issues), "RPC issues");
                t.assertEqual(JSON.stringify(viaMcp.issues), JSON.stringify(direct.issues), "MCP issues");
            });

            if (!clientAvailable()) {
                ScriptTest.run("15_input_slot_click: a dedicated server has no menu to click", function (t) {
                    var e = errorOf(function () {
                        Driver.client.input.slotClick({ slot: 0 });
                    });
                    t.assertEqual(e.code, "capability_unavailable", "code");
                });
            } else {
                ScriptTest.run("15_input_slot_click: rejects when no container screen open", function (t) {
                    // Close any container screen first so the test is deterministic.
                    // closeScreen on TitleScreen / level-only is a no-op, so this is safe.
                    Driver.client.screen.close({});
                    var r = Driver.client.input.slotClick({ slot: 0 });
                    t.assertEqual(r.outcome, "refused", "no container screen → refused");
                    t.assertEqual(r.reason, "no_container", "reason");
                });

                ScriptTest.run("15_input_slot_click: a click held to a menu since reopened does nothing", function (t) {
                    var info = Driver.client.screen.info();
                    if (!info.hasPlayer) return;
                    Driver.client.input.key({ key: "E" });
                    Driver.system.waitTicks({ ticks: 2 });
                    var menu = Driver.observe.container({});
                    t.assertEqual(menu.source, "menu", "no pos reads the open menu");
                    t.assertEqual(menu.present, true, "the inventory is a container menu");
                    Driver.client.screen.close({});
                    Driver.client.input.key({ key: "E" });
                    Driver.system.waitTicks({ ticks: 2 });
                    var e = errorOf(function () {
                        Driver.client.input.slotClick({ slot: 0, contextId: menu.contextId });
                    });
                    t.assertEqual(
                        e && e.code,
                        "context_changed",
                        "the same kind of menu, opened again, is another context"
                    );
                    Driver.client.screen.close({});
                });
            }
        },
    })
);
