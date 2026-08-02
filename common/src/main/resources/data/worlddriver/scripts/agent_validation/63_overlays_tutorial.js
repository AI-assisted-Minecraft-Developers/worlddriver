// Client-only — mc.client.overlays must suppress the tutorial at the source.
// Regression guard for the 2026-07-10 feedback §2: the old reflection lookup
// used a bare class name (Class.forName("TutorialSteps")) and threw
// ClassNotFoundException in EVERY runtime; the fix is a direct import.

function clientAvailable() {
    try {
        Agent.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

if (!clientAvailable()) {
    ScriptTest.run("63_overlays_tutorial: skipped (no client api — dedicated server)", function(t) {
        // no-op: PASS so headless runs stay green
    });
} else {
    ScriptTest.run("63_overlays_tutorial: tutorial suppressed without reflection error", function(t) {
        var res = Agent.invoke("mc.client.overlays", {});
        t.assertEqual(res.ok, true, "overlays must report ok (got " + JSON.stringify(res) + ")");
        t.assertEqual(res.tutorial, "NONE", "tutorial must be set to NONE (got " + JSON.stringify(res) + ")");
        t.assertTrue(res.tutorialError === undefined || res.tutorialError === null,
            "tutorialError must be absent (got " + JSON.stringify(res) + ")");
        t.assertEqual(res.toasts, "cleared", "toast clearing must keep working");
    });
}
