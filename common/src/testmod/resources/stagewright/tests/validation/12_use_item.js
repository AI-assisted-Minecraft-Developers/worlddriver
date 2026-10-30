// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test("12_use_item", { scene: ["wd.validation", "wd.validationIntegrated"], isolated: true }, (t) =>
    t.step("wd.validation", {
        script: "12_use_item",
        DriverApiError: DriverApiError,
        body: (ScriptTest) => {
            // mc.bot.useItem: a right-click in the air, on a block face with `pos`, or on an entity with
            // `entityId`. The request checks read no body and run on every topology; the clicks themselves need
            // this client's own player, so a dedicated server only sees self refused as unavailable.

            function clientAvailable() {
                try {
                    Driver.invoke("mc.client.screen.info", {});
                    return true;
                } catch (e) {
                    return false;
                }
            }

            /** The first issue of the DriverApiError a call throws, or null when it returned. */
            function issueOf(params) {
                try {
                    Driver.bot.useItem(params);
                } catch (e) {
                    return e.issues[0];
                }
                return null;
            }

            ScriptTest.run("12_use_item: a pos is an object and an entityId an integer", function (t) {
                t.assertEqual(issueOf({ pos: "not-a-pos" }).path, "/pos", "string pos");
                t.assertEqual(issueOf({ entityId: "abc" }).path, "/entityId", "string entityId");
                t.assertEqual(issueOf({ hand: "left" }).path, "/hand", "unknown hand");
            });

            ScriptTest.run("12_use_item: one target at a time, and its modifiers only with it", function (t) {
                t.assertEqual(
                    issueOf({ pos: { x: 0, y: 200, z: 0 }, entityId: 1 }).path,
                    "/entityId",
                    "pos and entityId"
                );
                t.assertEqual(issueOf({ face: "up" }).path, "/face", "face without pos");
                t.assertEqual(
                    issueOf({ pos: { x: 0, y: 200, z: 0 }, sneak: true }).path,
                    "/sneak",
                    "sneak without entityId"
                );
            });

            if (!clientAvailable()) {
                ScriptTest.run("12_use_item: self is unavailable on a dedicated server", function (t) {
                    var r = Driver.bot.useItem({});
                    t.assertEqual(r.outcome, "refused", "outcome");
                    t.assertEqual(r.reason, "bot_unavailable", "reason");
                });
            } else {
                ScriptTest.run("12_use_item: in the air with an empty hand answers done", function (t) {
                    var res = Driver.bot.useItem({ hand: "main" });
                    t.assertEqual(res.outcome, "done", "outcome (got " + JSON.stringify(res) + ")");
                    t.assertEqual(res.hand, "main", "hand must round-trip");
                    t.assertEqual(res.on.kind, "air", "used in the air");
                    t.assertTrue(typeof res.result === "string", "result names the interaction");
                    t.assertEqual(typeof res.consumed, "boolean", "consumed must be a boolean");
                });

                ScriptTest.run("12_use_item: on a block echoes the face it was given", function (t) {
                    var res = Driver.bot.useItem({ pos: { x: 0, y: 200, z: 0 }, face: "up", lookAt: false });
                    t.assertEqual(res.outcome, "done", "outcome (got " + JSON.stringify(res) + ")");
                    t.assertEqual(res.on.kind, "block", "used on a block");
                    t.assertEqual(res.on.face, "up", "face must echo back as requested");
                    t.assertEqual(res.hand, "main", "hand default must be main");
                    t.assertEqual(res.on.pos.x, 0, "pos.x must round-trip");
                    t.assertEqual(res.on.pos.y, 200, "pos.y must round-trip");
                    t.assertEqual(res.on.pos.z, 0, "pos.z must round-trip");
                });

                ScriptTest.run("12_use_item: on a block picks a face when none is given", function (t) {
                    var res = Driver.bot.useItem({ pos: { x: 0, y: 200, z: 0 }, lookAt: false });
                    t.assertEqual(res.outcome, "done", "outcome (got " + JSON.stringify(res) + ")");
                    var allowed = ["up", "down", "north", "south", "east", "west"];
                    t.assertTrue(allowed.indexOf(res.on.face) >= 0, "a face of the block, got '" + res.on.face + "'");
                });

                ScriptTest.run("12_use_item: an entity that does not exist is refused", function (t) {
                    // 2^30 is well past any real entity id in a fresh world
                    var r = Driver.bot.useItem({ entityId: 1073741824 });
                    t.assertEqual(r.outcome, "refused", "outcome");
                    t.assertEqual(r.reason, "invalid_world_target", "reason");
                });

                ScriptTest.run("12_use_item: on a boat with an empty hand mounts it", function (t) {
                    var me = Driver.invoke("mc.observe.player", {}).player;
                    var x = Math.round(me.pos.x),
                        y = Math.round(me.pos.y),
                        z = Math.round(me.pos.z);
                    // Mounting needs an EMPTY main hand: a held item would be used on the boat instead.
                    // A command runs as the server unless it names a player: @s is the client's own only with as.
                    var meRef = "player:" + me.name;
                    Driver.invoke("mc.action.runCommand", {
                        cmd: "item replace entity @s weapon.mainhand with minecraft:air",
                        as: meRef,
                    });
                    Driver.invoke("mc.action.runCommand", {
                        cmd: "summon minecraft:boat " + x + " " + y + " " + z + ' {Tags:["t12_boat"]}',
                    });
                    try {
                        var rows = Driver.invoke("mc.query", {
                            q: "entities",
                            center: { x: x, y: y, z: z },
                            filter: { in_radius: 8, type: "boat" },
                            select: ["id"],
                        }).entities;
                        t.assertTrue(
                            Array.isArray(rows) && rows.length >= 1,
                            "boat must be findable via mc.query, got " + JSON.stringify(rows)
                        );
                        // mc.query reads the server, which spawned the boat; self clicks from the client, which
                        // learns of it a tick or more later and until then answers that there is no such entity.
                        var res = null;
                        for (var i = 0; i < 20; i++) {
                            res = Driver.bot.useItem({ entityId: rows[0].id });
                            if (!(res.outcome === "refused" && res.reason === "invalid_world_target")) break;
                            Driver.system.waitTicks({ ticks: 1 });
                        }
                        t.assertEqual(res.outcome, "done", "useItem on the boat (got " + JSON.stringify(res) + ")");
                        // The answer rereads the vehicle after the click until the server's mount arrives.
                        t.assertEqual(res.on.riding, "minecraft:boat", "riding must report the mounted boat type");
                    } finally {
                        Driver.invoke("mc.action.runCommand", { cmd: "ride @s dismount", as: meRef });
                        Driver.invoke("mc.action.runCommand", { cmd: "kill @e[tag=t12_boat]" });
                    }
                });
            }
        },
    })
);
