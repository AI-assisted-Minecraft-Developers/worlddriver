// What a script test leases and overrides through t, each given back or undone when the test ends.

const SCENE = "wd.jupiterSmoke";

function dayTime(t) {
    return t.call("mc.observe.player", { name: t.player().name }).player.time.dayTime;
}

test("a plot is leased empty and keeps what the test places", { scene: SCENE }, (t) => {
    const plot = t.plot({ size: 8 });
    t.expect(plot.size).equals({ x: 8, y: 8, z: 8 });
    const at = plot.at(1, 1, 1);
    t.expect(t.call("mc.world.block", { pos: at }).type).equals("minecraft:air");
    plot.place(1, 1, 1, "minecraft:stone");
    t.expect(t.call("mc.world.block", { pos: at }).type).equals("minecraft:stone");
});

test("built-in steps place a chest and fill it", { scene: SCENE }, (t) => {
    t.plot({ size: 8 });
    t.step("place", { at: [1, 1, 1], block: "minecraft:chest", as: "chest" });
    t.step("insert", { into: "chest", items: { "minecraft:iron_ingot": 3 } });
    t.expect(t.step("container", "chest")).equals({ "minecraft:iron_ingot": 3 });
});

test("perf runs each phase before measuring it", { scene: SCENE }, (t) => {
    let ran = 0;
    const result = t.perf({
        warmup: 5,
        sample: 20,
        repeat: 1,
        phases: [
            { name: "baseline" },
            {
                name: "after",
                run: () => {
                    ran++;
                },
            },
        ],
    });
    t.expect(ran).equals(1);
    t.expect(result.after.ticks).equals(20);
    t.expect(result.after).contains({ ticks: 20 });
});

test("a bot joins for the test and holds what it is given", { scene: SCENE }, (t) => {
    const bot = t.bot();
    t.expect(bot.ref).contains("player:");
    bot.give("minecraft:diamond", 3);
    t.expect(bot.count("minecraft:diamond")).equals(3);
});

// A refused call reaches the script's catch with the Java message; what the script does not catch still fails it.
test("a refused call can be caught and checked", { scene: SCENE }, (t) => {
    let caught = null;
    try {
        t.call("mc.world.block", { pos: { x: "not a number", y: 0, z: 0 } });
    } catch (e) {
        caught = e;
    }
    t.expect(caught === null).equals(false);
    t.expect(typeof caught.message).equals("string");
    t.expect(caught.message.length > 0).equals(true);
    t.record("refusal", caught.message);
});

// The Java global reaches a class by name, here a JDK one, whose name is the same in every game.
test("Java.type reaches a static method", { scene: SCENE }, (t) => {
    t.expect(Java.type("java.lang.Math").max(1, 2)).equals(2);
});

// Everything t.onServer's function calls runs in one server task, so in one tick.
test("t.onServer makes its calls in one tick", { scene: SCENE }, (t) => {
    const plot = t.plot({ size: 4 });
    const a = plot.at(0, 0, 0);
    const b = plot.at(3, 0, 3);
    const seen = t.onServer(() => {
        const first = t.step("wd.serverTick", {});
        t.call("mc.action.runCommand", { cmd: `setblock ${a.x} ${a.y} ${a.z} minecraft:stone` });
        t.call("mc.action.runCommand", { cmd: `setblock ${b.x} ${b.y} ${b.z} minecraft:stone` });
        t.check(t.call("mc.world.block", { pos: a }).type === "minecraft:stone", "the first block is placed");
        return { first: first, last: t.step("wd.serverTick", {}), b: t.call("mc.world.block", { pos: b }).type };
    });
    t.expect(seen.last).equals(seen.first);
    t.expect(seen.b).equals("minecraft:stone");
    let refused = null;
    try {
        t.onServer(() => t.wait(1));
    } catch (e) {
        refused = e.message;
    }
    t.expect(refused).contains("cannot be called inside t.onServer");
});

// A frozen world moves only when the test steps it, by exactly the ticks it waits. Isolated: every other test running
// would be frozen with it.
test("t.freezeTicks then t.wait steps the world", { scene: SCENE, isolated: true }, (t) => {
    t.freezeTicks();
    // The overworld's game time advances only on the ticks the world runs, not on every server tick.
    const before = t.step("wd.gameTime", {});
    t.wait(3);
    t.expect(t.step("wd.gameTime", {}) - before).equals(3);
    t.wait(1);
    t.expect(t.step("wd.gameTime", {}) - before).equals(4);
});

// A summoned mob in a plot is kept although no player is near it.
test("a mob summoned in a plot does not despawn", { scene: SCENE }, (t) => {
    const plot = t.plot({ size: 8 });
    plot.fill(0, 0, 0, 7, 0, 7, "minecraft:stone");
    const at = plot.at(4, 1, 4);
    t.call("mc.action.runCommand", { cmd: `summon minecraft:zombie ${at.x} ${at.y} ${at.z}` });
    t.wait(40);
    const zombies = t.call("mc.query", {
        q: "entities",
        center: at,
        filter: { in_radius: 8, type: "minecraft:zombie" },
    }).entities;
    t.expect(zombies.length).equals(1);
});

test("a script asks what the game has", { scene: SCENE }, (t) => {
    t.expect(t.capabilities().registered().length).gt(0);
    t.expect(t.capabilities().has("no-such-capability")).equals(false);
    t.expect(t.hasClass("java.lang.String")).equals(true);
    t.expect(t.hasClass("com.example.definitely.NotHere")).equals(false);
    const mods = t.call("mc.test.mods", { op: "list" });
    t.expect(mods.mods.map((m) => m.id)).contains("worlddriver");
    t.expect(t.call("mc.test.mods", { op: "has", id: "worlddriver" }).has).equals(true);
    t.expect(t.call("mc.test.recipes", { op: "count" }).total).gt(0);
    t.expect(t.call("mc.test.loot", { op: "list", namespace: "minecraft" }).count).gt(0);
    t.expect(t.call("mc.test.advancements", { op: "list" }).count).gt(0);
    t.expect(t.call("mc.test.structures", { op: "list" }).structures).contains("minecraft:village_plains");
});

// A record may be blank, and afterTest runs when the test ends, after the body that registered it.
const AFTER = "afterTest runs when the test ends";

test(AFTER, { scene: SCENE }, (t) => {
    t.record("blank", "");
    t.afterTest(() => t.step("wd.mark", AFTER + " / after"));
    t.afterTest(() => t.step("wd.mark", AFTER + " / body"));
});

test("and the marks it left are there in order", { scene: SCENE, dependsOn: [AFTER] }, (t) => {
    const marks = t.step("wd.marks", {});
    const body = marks.indexOf(AFTER + " / body");
    const after = marks.indexOf(AFTER + " / after");
    t.expect(body >= 0 && after > body).equals(true);
});

test("the client sees the player the server has", { scene: SCENE, requires: "client" }, (t) => {
    t.expect(t.client().call("mc.client.player", {}).name).equals(t.player().name);
});

// Script tests run concurrently, so an override names the lock that keeps the others off what it changes.
test(
    "an override holds for the test",
    {
        scene: SCENE,
        requires: "client",
        locks: ["worldClock", "worldRules"],
    },
    (t) => {
        t.overrideTime(1234);
        t.expect(dayTime(t)).equals(1234);
        t.override("randomTickSpeed", 0);
    }
);

// The scene declares no time, so its clock stands where a quiet world's does: 18000, frozen.
test(
    "and is undone when it ends",
    {
        scene: SCENE,
        requires: "client",
        dependsOn: ["an override holds for the test"],
        locks: ["worldClock"],
    },
    (t) => {
        t.expect(dayTime(t)).equals(18000);
    }
);

// Script tests run beside the Java tests, so either kind waits for the other it depends on.
const SMOKE = "net.magicterra.worlddriver.bot.stagewright.jupiter.JupiterSmokeTest";

// JupiterSmokeTest#runsAfterTheScriptTestItDependsOn depends on this, and finds the mark.
test("a Java test depends on this", { scene: SCENE }, (t) => {
    t.wait(20);
    t.step("wd.mark", "a Java test depends on this");
});

test(
    "runs after the Java test it depends on passed",
    { scene: SCENE, dependsOn: [SMOKE + "#ticksPassOnTheServer"] },
    (t) => {
        t.wait(1);
    }
);

// Aborted, never run: reported as skipped with "is not in this run", which is what this test is here to show.
test(
    "is aborted when the Java test it depends on is not in the run",
    {
        scene: SCENE,
        dependsOn: [SMOKE + "#noSuchTest"],
    },
    (t) => {
        t.expect("ran").equals("aborted before running");
    }
);

test(
    "a known issue is reported as one",
    {
        scene: SCENE,
        knownIssue: "fails on purpose, so the run shows knownIssue turning a failure into an abort",
    },
    (t) => {
        t.expect(1 + 1).equals(3);
    }
);
