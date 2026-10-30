// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test("42_combat", { scene: ["wd.validation", "wd.validationIntegrated"], isolated: true }, (t) =>
    t.step("wd.validation", {
        script: "42_combat",
        DriverApiError: DriverApiError,
        body: (ScriptTest) => {
            // ROADMAP Phase C — active combat loop (T1). The request checks run everywhere; self's fight
            // needs Minecraft.gameMode and real mobs, so a dedicated server only sees self refused as
            // unavailable. The behavioural checks run in
            // fabric runClient sessions where the ScriptTest world (superflat CREATIVE) lets
            // us summon mobs and watch the bot clear them. CREATIVE = the bot is invulnerable,
            // so combat never gets preempted by autoRetreat and the assertions are about the
            // bot's OFFENCE (clearing the pack, timing, kiting), not its survival.

            function clientAvailable() {
                try {
                    Driver.invoke("mc.client.screen.info", {});
                    return true;
                } catch (e) {
                    return false;
                }
            }

            function patch(values) {
                return Driver.bot.setting({ op: "patch", values: values }).values;
            }

            function countType(threats, needle) {
                var n = 0;
                for (var i = 0; i < threats.length; i++) if (threats[i].type.indexOf(needle) >= 0) n++;
                return n;
            }

            function findType(threats, needle) {
                for (var i = 0; i < threats.length; i++) if (threats[i].type.indexOf(needle) >= 0) return threats[i];
                return null;
            }

            // A command runs as the server unless it names a player; this names the client's own, so @s is it.
            function asSelf(c) {
                var me = Driver.invoke("mc.observe.player", {}).player;
                return Driver.invoke("mc.action.runCommand", { cmd: c, as: "player:" + me.name });
            }

            // Top-level so the deferred ScriptTest.run callbacks can reach them.
            function cleanup() {
                Driver.invoke("mc.bot.cancel", { process: "all" });
                Driver.invoke("mc.action.runCommand", { cmd: "kill @e[type=!minecraft:player]" });
                patch({ autoFight: false, autoRetreat: false });
                asSelf("clear @s");
                Driver.system.waitTicks({ ticks: 3 });
            }

            // Put a weapon in hotbar slot 0 and select it; wait for the client inventory to sync.
            function equip(item) {
                asSelf("item replace entity @s hotbar.0 with " + item);
                Driver.invoke("mc.client.input.setHotbarSlot", { slot: 0 });
                Driver.system.waitTicks({ ticks: 6 });
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

            /** The action as mc.wait.result reads it now. */
            function action(id) {
                return Driver.invoke("mc.wait.result", { kind: "action", actionId: id }).action;
            }

            // The request checks read no body, so they hold on every topology.
            ScriptTest.run("42_combat: an unknown mode names the valid ones", function (t) {
                var e = errorOf(function () {
                    Driver.bot.combat({ mode: "bogus" });
                });
                t.assertEqual(e.issues[0].path, "/mode", "path");
                t.assertTrue(e.issues[0].message.indexOf("engage") >= 0, "names the modes: " + e.issues[0].message);
            });

            ScriptTest.run("42_combat: kill needs a target, and only kill takes one", function (t) {
                var missing = errorOf(function () {
                    Driver.bot.combat({ mode: "kill" });
                });
                t.assertEqual(missing.issues[0].path, "/target", "path");
                t.assertEqual(missing.issues[0].code, "required", "issue code");
                var extra = errorOf(function () {
                    Driver.bot.combat({ target: { kind: "entity", entityId: 1 } });
                });
                t.assertEqual(extra.issues[0].path, "/target", "engage picks its own targets");
            });

            if (!clientAvailable()) {
                ScriptTest.run("42_combat: self is unavailable on a dedicated server", function (t) {
                    var r = Driver.bot.combat({});
                    t.assertEqual(r.outcome, "refused", "outcome");
                    t.assertEqual(r.reason, "bot_unavailable", "reason");
                });
            } else {
                // The arena is seeded afresh before each script (a 9x9 stone floor, everything else killed), so the pack is the
                // only thing in scan range and the area clear the engage ends on is reachable. NoAI zombies two blocks out on
                // the floor: the walk to each is short and none of them wanders off it.
                ScriptTest.run("42_combat: melee engage clears a zombie pack with well-timed swings", function (t) {
                    cleanup();
                    equip("minecraft:diamond_sword");
                    var me = Driver.invoke("mc.observe.player", {}).player;
                    var x = Math.floor(me.pos.x),
                        y = Math.floor(me.pos.y),
                        z = Math.floor(me.pos.z);
                    var spots = [
                        [2, 0],
                        [-2, 0],
                        [0, 2],
                    ];
                    for (var i = 0; i < spots.length; i++) {
                        Driver.invoke("mc.action.runCommand", {
                            cmd:
                                "summon zombie " +
                                (x + spots[i][0] + 0.5) +
                                " " +
                                y +
                                " " +
                                (z + spots[i][1] + 0.5) +
                                " {NoAI:1b,PersistenceRequired:1b}",
                        });
                    }
                    Driver.system.waitTicks({ ticks: 3 });
                    t.assertEqual(
                        countType(Driver.invoke("mc.observe.threats", { radius: 16 }).threats, "zombie"),
                        3,
                        "the pack is in",
                    );

                    var started = Driver.bot.combat({ mode: "engage" }); // non-blocking
                    t.assertEqual(started.outcome, "accepted", "outcome");
                    var id = started.action.actionId;
                    var a = action(id);
                    for (var waited = 0; a.state === "running" && waited < 600; waited += 20) {
                        Driver.system.waitTicks({ ticks: 20 });
                        a = action(id);
                    }
                    var tally = Driver.bot.status({}).status.reflexes.combat;
                    var left = countType(Driver.invoke("mc.observe.threats", { radius: 16 }).threats, "zombie");
                    var seen = JSON.stringify(a) + " tally=" + JSON.stringify(tally);
                    t.assertEqual(a.state, "succeeded", "the engage ends with the area clear: " + seen);
                    t.assertEqual(left, 0, "no zombie left: " + seen);
                    t.assertTrue(tally.kills >= 3, "every zombie credited as a kill: " + seen);
                    // Full cooldown is the swing the process waits for; an occasional early one (a target stepping into reach
                    // mid-wind-up) is tolerated, spamming is not.
                    t.assertTrue(tally.wellTimed * 2 >= tally.swings, "most swings at full cooldown: " + seen);
                    cleanup();
                });

                ScriptTest.run("42_combat: ranged kite keeps distance from a skeleton and fires", function (t) {
                    cleanup();
                    equip("minecraft:bow");
                    asSelf("give @s minecraft:arrow 64");
                    patch({ kiteDistance: 8 });
                    // Stand where backing off has room: two east of the oak log at the origin, half a block north of it, facing
                    // south. The melee check before this one ends wherever its last zombie fell, and a start at z = origin + 1.3
                    // backs straight into the log's south face, so the bot holds at 3.2 blocks and the check reads as no kiting.
                    var origin = Driver.system.testOrigin();
                    asSelf("tp @s " + (origin.x + 2.5) + " " + (origin.y + 1) + " " + (origin.z - 0.5) + " 0 0");
                    Driver.system.waitTicks({ ticks: 3 });
                    var me = Driver.invoke("mc.observe.player", {}).player;
                    var x = Math.round(me.pos.x),
                        y = Math.round(me.pos.y),
                        z = Math.round(me.pos.z);
                    // A skeleton 3 blocks away, inside the kite band, so the bot must back off toward kiteDistance (8) while
                    // drawing. NoAI keeps it put; 1024 HP keeps the fight on for the whole window, so every sample is kiting
                    // and none is the post-kill drop sweep walking back to the kill site.
                    Driver.invoke("mc.action.runCommand", {
                        cmd:
                            "summon skeleton " +
                            x +
                            " " +
                            y +
                            " " +
                            (z + 3) +
                            " {NoAI:1b,PersistenceRequired:1b,Health:1024f," +
                            'attributes:[{id:"minecraft:generic.max_health",base:1024d}]}',
                    });
                    Driver.system.waitTicks({ ticks: 3 });
                    var sk = findType(Driver.invoke("mc.observe.threats", { radius: 16 }).threats, "skeleton");
                    t.assertTrue(sk !== null, "skeleton present");
                    var d0 = sk.distance;

                    var started = Driver.bot.combat({ mode: "kill", target: { kind: "entity", entityId: sk.id } }); // non-blocking
                    var id = started.action.actionId;
                    // Distance to the live target every 5 ticks while the fight is on. Drawing slows movement to a crawl, so
                    // the bar is "out past melee at some point" and "never back closer than the start once 20 ticks in".
                    var samples = [],
                        a = null,
                        maxD = d0,
                        minLate = Infinity;
                    for (var tick = 5; tick <= 60; tick += 5) {
                        Driver.system.waitTicks({ ticks: 5 });
                        a = action(id);
                        var th = Driver.invoke("mc.observe.threats", { radius: 24 }).threats,
                            live = null;
                        for (var i = 0; i < th.length; i++) if (th[i].id === sk.id) live = th[i];
                        if (live === null || a.state !== "running") break; // the fight is over; what follows is not kiting
                        samples.push(tick + ":" + live.distance.toFixed(1));
                        maxD = Math.max(maxD, live.distance);
                        if (tick >= 20) minLate = Math.min(minLate, live.distance);
                    }
                    var tally = Driver.bot.status({}).status.reflexes.combat;
                    var seen =
                        "d0=" +
                        d0.toFixed(1) +
                        " samples=" +
                        samples.join(",") +
                        " state=" +
                        (a && a.state) +
                        " tally=" +
                        JSON.stringify(tally);
                    t.assertEqual(
                        a.state,
                        "running",
                        "the fight is still on across the window, so it measured kiting: " + seen,
                    );
                    t.assertTrue(maxD > 4.0, "the bot backed off past melee range: " + seen);
                    t.assertTrue(minLate >= d0, "once it had backed off it never closed back in toward melee: " + seen);
                    t.assertTrue(tally.swings >= 1, "drew and released at least one arrow: " + seen);
                    cleanup();
                });

                ScriptTest.run(
                    "42_combat: status.combat carries telemetry + cancel stands the chain down",
                    function (t) {
                        cleanup();
                        equip("minecraft:diamond_sword");
                        var me = Driver.invoke("mc.observe.player", {}).player;
                        var x = Math.round(me.pos.x),
                            y = Math.round(me.pos.y),
                            z = Math.round(me.pos.z);
                        Driver.invoke("mc.action.runCommand", {
                            cmd: "summon zombie " + (x + 3) + " " + y + " " + z + " {NoAI:1b,PersistenceRequired:1b}",
                        });
                        Driver.system.waitTicks({ ticks: 3 });
                        var started = Driver.bot.combat({ mode: "engage" }); // non-blocking
                        t.assertEqual(started.outcome, "accepted", "outcome");
                        var id = started.action.actionId;
                        Driver.system.waitTicks({ ticks: 4 });
                        var s = Driver.bot.status({}).status;
                        t.assertEqual(s.slots.combat.active, true, "combat slot active while engaged");
                        t.assertEqual(s.reflexes.activeChain, "combat", "combat chain holds the movement channel");
                        t.assertTrue(s.reflexes.chains.combat.priority >= 60, "combat bid in the COMBAT band");
                        t.assertEqual(action(id).state, "running", "the fight's action runs while engaged");

                        var stopped = Driver.bot.cancel({ process: "combat" });
                        t.assertEqual(
                            JSON.stringify(stopped.cancelled),
                            '["combat"]',
                            "names what it stopped: " + JSON.stringify(stopped),
                        );
                        Driver.system.waitTicks({ ticks: 3 });
                        var after = Driver.bot.status({}).status;
                        t.assertEqual(after.slots.combat.active, false, "cancel deactivated the combat slot");
                        t.assertTrue(after.reflexes.activeChain !== "combat", "combat chain released the channel");
                        var ended = action(id);
                        t.assertEqual(
                            ended.state,
                            "cancelled",
                            "the cancel ended the action: " + JSON.stringify(ended),
                        );
                        t.assertEqual(ended.reason, "requested", "reason");
                        cleanup();
                    },
                );
            }
        },
    }),
);
