ScriptTest.run("01_handshake: system.version returns expected fields", function(t) {
    var info = Driver.system.version();
    t.assertEqual(info.modid, "worlddriver", "modid mismatch");
    t.assertEqual(info.version, "0.1.0-dev", "version mismatch");
    t.assertTrue(info.uptimeMs >= 0, "uptimeMs should be non-negative");
});

ScriptTest.run("01_handshake: system.version stamps the build it is running", function(t) {
    var info = Driver.system.version();
    t.assertTrue(typeof info.loadedFrom === "string" && info.loadedFrom.length > 0,
        "loadedFrom missing");
    // builtAt is dropped only when the class source cannot be resolved to a readable file, and
    // that is the failure worth guarding: NeoForge's modlauncher hands out `union:` URLs rather
    // than `jar:` ones, so a resolver that knows only the two standard protocols still answers —
    // it just answers with a stamp that cannot date itself, on the loader we ship to.
    t.assertTrue(!!info.builtAt,
        "no builtAt for " + info.loadedKind + " at " + info.loadedFrom);
    t.assertTrue(info.builtMs > 0, "builtMs should be a real epoch time, got " + info.builtMs);
});
