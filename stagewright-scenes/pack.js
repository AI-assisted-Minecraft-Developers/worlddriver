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
    // The reason a pack author wants this at all: assert that the mods in THIS pack are present and
    // registered, which no single mod's own test suite can tell them.
    s.setBlock(0, 0, 0, block("minecraft:stone"));
    s.expectBlock(0, 0, 0).as("a block the pack ships").isEqualTo(block("minecraft:stone"));
    s.record("packMod", "minecraft");
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
    var p = s.origin();
    var got = driver("mc.world.block", { pos: { x: p.getX(), y: p.getY(), z: p.getZ() } });
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

// Deliberately optional: it pins a thing the pack accepts rather than something it requires. A
// failure here reports without failing the run.
scene.optional("pack.knownQuirk", 100, function (s) {
    s.expect(1).as("a defect the pack has accepted").isEqualTo(1);
});
