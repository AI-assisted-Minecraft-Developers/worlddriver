// mc.debug.replay restores a recorded path archive, teleports the bot to the start, and runs it again.
// It is published with the path-debug subsystem, at client init, so a dedicated server has no such
// method. Starting a real replay would move the bot mid-suite, so the check names an archive that does
// not exist: that is refused before anything runs, and the refusal is the contract being checked.

function errorOf(fn) {
    try { fn(); } catch (e) { return e; }
    return null;
}

var missing = errorOf(function () { Driver.invoke("mc.debug.replay", { file: "replay-does-not-exist-57.json" }); });

if (missing && missing.code === "method_not_found") {
    ScriptTest.run("57_replay: skipped (no path-debug methods on this topology)", function (t) { /* PASS */ });
} else {

    ScriptTest.run("57_replay: an unknown archive is refused at /file before anything runs", function (t) {
        t.assertEqual(missing && missing.code, "invalid_params", "refused as a bad parameter: " + (missing && missing.message));
        t.assertEqual(missing.issues[0].path, "/file", "the file is what is wrong");
        t.assertEqual(missing.execution, "not_started", "nothing ran");
    });
}
