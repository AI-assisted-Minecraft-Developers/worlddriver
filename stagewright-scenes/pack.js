// A modpack author's scene file. No build, no source set, no Java — this sits beside the pack's
// configs and is picked up by the same harness that runs the compiled ones. In the results file
// these are indistinguishable from scenes written in Java, which is the point: they register into
// the same registry, are caught by the same canaries, and are reconciled against the same
// expected-scenes manifest.
//
// Not loader-specific, and deliberately so — the same file is installed into all six of this
// project's topologies. A pack ships one set of scenes, not one per loader.

scene("pack.placesAndReadsBack", 100, function (s) {
    s.setBlock(0, 0, 0, block("minecraft:stone"));
    s.expectBlock(0, 0, 0).as("the block we just placed").isEqualTo(block("minecraft:stone"));
});

scene("pack.awaitsAcrossTicks", 200, function (s) {
    // The tick model, from JS: register a condition and what to do when it holds. A loop here
    // would be wrong — the world cannot advance while the body is running.
    s.setBlock(0, 0, 0, block("minecraft:dirt"));
    s.await(function () { return s.ticks() >= 20; }).within(100).then(function () {
        s.record("ticksWaited", s.ticks());
        s.expectBlock(0, 0, 0).as("the block survived the wait").isEqualTo(block("minecraft:dirt"));
    });
});

scene("pack.seesTheModsItShipsWith", 100, function (s) {
    // The reason a pack author wants this at all: assert that the mods in THIS pack are present, at
    // the versions the pack was built against, which no single mod's own test suite can tell them.
    s.record("mods.count", s.mods().count());
    s.record("worlddriver", s.mods().version("worlddriver"));
    s.expect(s.mods().loaded("minecraft")).as("Minecraft in the mod list").isTrue();
    s.expect(s.mods().loaded("worlddriver")).as("the mod this pack is about").isTrue();
    s.expect(s.mods().loaded("definitely_not_a_mod")).as("a mod nobody has").isFalse();

    // And what the pack ships still registers, which is the half a mod list cannot answer: a mod
    // loaded with its content switched off in config is present and contributes nothing.
    s.setBlock(0, 0, 0, block("minecraft:stone"));
    s.expectBlock(0, 0, 0).as("a block the pack ships").isEqualTo(block("minecraft:stone"));
});

scene("pack.usesACapabilityItDeclared", 100, function (s) {
    // The declarative seam, from the side it was built for: a .json file beside this one names a
    // mod and a class, and a scene reaches it without a build tool, a jar, or a line of Java. The
    // class name lives in that file rather than in this scene — which is the entire difference
    // between this and calling s.probe('...') here.
    s.expect(s.hasCapability("pack:driver")).as("the capability this pack declared").isTrue();

    var driver = s.capability("pack:driver");
    s.record("capability.source", driver.source());
    s.record("capability.version", driver.version());
    s.expect(driver.mods()).as("the mods it named that are loaded").contains("worlddriver");

    s.expect(driver.probe().className()).as("what its probe bound")
        .isEqualTo("net.magicterra.worlddriver.api.DriverApi");

    // The other half, and the one that has to be true for any of this to be trustworthy: the
    // descriptors StageWright itself ships are all here, and none of them claims to be available in
    // a runtime that has none of those mods.
    s.expect(s.capabilityProviders()).as("a shipped descriptor").contains("mekanism");
    s.expect(s.hasCapability("mekanism")).as("Mekanism in this runtime").isFalse();
});

scene("pack.measuresItsOwnTickCost", 300, function (s) {
    // Perf is baseline-relative and therefore two measurement windows with the work between them.
    // Nested callbacks rather than sequential statements for the same reason as await: the windows
    // span real ticks, and a scene body does not get to sit through them.
    //
    // Beacons rather than an inert block, so the suite exercises what a pack perf scene actually
    // does: place machines. 64 block entities is also the only coverage the harness has of
    // setBlock reverting block-entity placements at teardown — an inert block would not reach it.
    s.perf().sampleFor(60, function (baseline) {
        s.perf().afterLoading(function () {
            for (var i = 0; i < 64; i++) {
                s.setBlock(i % 8, 0, Math.floor(i / 8), block("minecraft:beacon"));
            }
        }, 60, function (loaded) {
            s.record("tps.baseline", baseline.tps());
            s.record("tps.loaded", loaded.tps());
            s.check(loaded.tpsDropVs(baseline)).as("TPS drop from 64 beacons").isAtMost(0.10);
        });
    });
});

scene("pack.reachesJava", 100, function (s) {
    // Both idioms a pack author might reach for. Java.loadClass is the one KubeJS taught them;
    // package traversal is the one every other JS-on-JVM environment taught them.
    var ArrayList = Java.loadClass("java.util.ArrayList");
    var list = new ArrayList();
    list.add("one");
    s.expect(list.get(0)).as("a Java collection built inside a scene file").isEqualTo("one");

    var uuid = java.util.UUID.randomUUID();
    s.expect(String(uuid).length === 36 ? "ok" : String(uuid))
        .as("java.util.UUID reached by package path").isEqualTo("ok");

    s.expect(Java.tryLoadClass("no.such.Class") === null ? "null" : "something")
        .as("tryLoadClass answers null rather than throwing").isEqualTo("null");
});

scene("pack.drivesTheGame", 200, function (s) {
    // One binding, every verb. This is worlddriver's own router — the same entry point MCP and the
    // WebSocket RPC go through — so a pack author gets the whole verb surface without StageWright
    // carrying a copy of any of it.
    s.setBlock(0, 0, 0, block("minecraft:gold_block"));
    // originX() rather than origin().getX(): a scene file must not call methods ON Minecraft
    // objects. Their names are remapped and a production Fabric jar is intermediary, where getX is
    // method_10263 — the same line passes on NeoForge and fails on Fabric.
    var got = driver("mc.world.block", { pos: { x: s.originX(), y: s.originY(), z: s.originZ() } });
    s.record("driverSawType", String(got.type));
    s.expect(String(got.type)).as("the driver read back the block this scene placed")
        .isEqualTo("minecraft:gold_block");

    // Object.keys only works on a NATIVE object, so this asserts the Java->JS conversion as much as
    // it asserts the verb: an unconverted java.util.Map would answer with no keys at all.
    var version = driver("mc.system.version");
    s.expect(Object.keys(version).length > 0 ? "converted" : "raw java map")
        .as("a verb's result arrives as a native JS object").isEqualTo("converted");
    s.record("driverVersionKeys", Object.keys(version).join(","));
});

scene("pack.runsCommands", 200, function (s) {
    // The widest surface a scene file has, and the one that needs no Java at all: items, mobs,
    // effects, gamerules and every command the pack's own mods register, all as strings — so none
    // of it goes through a method name that is spelled differently on Fabric.
    //
    // `~ ~ ~` is the scene's own arena, not the world origin, which is what lets the same line run
    // in whichever grid slot this scene was handed.
    // A block a command placed is NOT reverted at teardown: setBlock records what it overwrote,
    // a command goes through the game's own paths and leaves no such record.
    s.cleanup(function () { s.setBlock(0, 0, 0, block("minecraft:air")); });

    s.command("setblock ~ ~ ~ minecraft:chest");
    s.command("item replace block ~ ~ ~ container.0 with minecraft:diamond 7");

    // Commands read as well as write. `data get` answers with the value as the command's own
    // result, which is how a scene asserts about state no block lookup can reach — what is in a
    // container, what a machine has stored, whatever a mod keeps in its block entity.
    var read = s.command("data get block ~ ~ ~ Items[0].count");
    s.record("commandSaid", read.text());
    s.expect(String(read.result())).as("diamonds the command put in the chest").isEqualTo("7");
}, { terrain: "superflat" });

// The ground a scene stands on is declared, not built. A pack whose mods only misbehave on real
// terrain — a mob that spawns wrong on a slope, a machine that needs to see stone below it — cannot
// test any of that in the empty sky the default arena is, and building a convincing landscape out of
// setBlock calls is not testing worldgen, it is testing your own scene.
scene("pack.standsOnSuperflatGround", 100, function (s) {
    s.expectBlock(0, -1, 0).as("the arena landed on the superflat's grass")
        .isEqualTo(block("minecraft:grass_block"));
    s.expectBlock(0, 0, 0).as("and the scene's own level is the air above it")
        .isEqualTo(block("minecraft:air"));
    s.record("surfaceY", s.originY());
}, { terrain: "superflat" });

scene("pack.standsOnGeneratedGround", 200, function (s) {
    // Whatever worldgen put here — ocean, hillside, forest floor. The scene asserts that it is ON
    // it, and records what "it" turned out to be rather than demanding a particular landscape.
    s.expectBlock(0, -1, 0).as("generated terrain, not the empty sky")
        .isNotEqualTo(block("minecraft:air"));
    s.record("surfaceY", s.originY());
}, { terrain: "generated" });

// The clock is held still for the whole run, at night, so nothing sun-sensitive can ignite on a
// dice roll a scene did not ask for. This asserts the pin from inside a scene rather than trusting
// the header that announces it.
scene("pack.runsAtTheFrozenNight", 100, function (s) {
    var t = s.command("time query daytime");
    s.expect(String(t.result())).as("the default clock every scene gets").isEqualTo("18000");
});

// ...and a scene whose subject IS daylight says so, instead of being quietly hidden by that default.
scene("pack.runsAtTheClockItAsked", 100, function (s) {
    var t = s.command("time query daytime");
    s.expect(String(t.result())).as("the clock this scene declared").isEqualTo("6000");
}, { clock: "noon" });

// Deliberately optional: it pins a thing the pack accepts rather than something it requires. A
// failure here reports without failing the run.
scene.optional("pack.knownQuirk", 100, function (s) {
    s.expect(1).as("a defect the pack has accepted").isEqualTo(1);
});
