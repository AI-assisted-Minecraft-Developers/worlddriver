// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test("63_overlays_tutorial", { scene: ["wd.validation", "wd.validationIntegrated"], isolated: true }, (t) =>
    t.step("wd.validation", {
        script: "63_overlays_tutorial",
        DriverApiError: DriverApiError,
        body: (ScriptTest) => {
            // Client-only — mc.client.overlays must suppress the tutorial at the source.
            // Regression guard for the 2026-07-10 feedback §2: the old reflection lookup
            // used a bare class name (Class.forName("TutorialSteps")) and threw
            // ClassNotFoundException in EVERY runtime; the fix is a direct import.

            function clientAvailable() {
                try {
                    Driver.invoke("mc.client.screen.info", {});
                    return true;
                } catch (e) {
                    return false;
                }
            }

            if (!clientAvailable()) {
                ScriptTest.run("63_overlays_tutorial: skipped (no client api — dedicated server)", function (t) {
                    // no-op: PASS so headless runs stay green
                });
            } else {
                ScriptTest.run("63_overlays_tutorial: tutorial suppressed without reflection error", function (t) {
                    var res = Driver.client.overlays({});
                    t.assertEqual(res.tutorial, true, "tutorial must be dismissed (got " + JSON.stringify(res) + ")");
                    t.assertTrue(
                        res.tutorialError === undefined,
                        "tutorialError must be absent (got " + JSON.stringify(res) + ")"
                    );
                    t.assertEqual(res.toasts, true, "toast clearing must keep working");
                });
            }
        },
    })
);
