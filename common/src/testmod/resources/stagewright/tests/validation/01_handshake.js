// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test('01_handshake', { scene: ['wd.validation', 'wd.validationIntegrated'], isolated: true }, (t) => t.step('wd.validation', {
    script: '01_handshake', DriverApiError: DriverApiError, body: (ScriptTest) => {

ScriptTest.run("01_handshake: system.version returns expected fields", function(t) {
    var info = Driver.system.version();
    t.assertEqual(info.modid, "worlddriver", "modid mismatch");
    // The loader's own reading of the mod's metadata, which the build fills in: a local build is
    // <base>-snapshot+<minecraft>, a numbered one <base>-alpha.<n>+<minecraft>.
    t.assertTrue(/^\d+\.\d+\.\d+-(snapshot|alpha\.\d+)\+\d+\.\d+/.test(info.version),
        "version is not the build's: " + info.version);
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

}}));
