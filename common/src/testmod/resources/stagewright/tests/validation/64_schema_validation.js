// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test("64_schema_validation", { scene: ["wd.validation", "wd.validationIntegrated"], isolated: true }, (t) =>
    t.step("wd.validation", {
        script: "64_schema_validation",
        DriverApiError: DriverApiError,
        body: (ScriptTest) => {
            // Strict parameter decoding: every method's contract decodes its params before the handler runs, so a
            // violation surfaces as a thrown invalid_params error whose issues carry {path, code, message}.
            // Server-side methods work headless; no clientAvailable guard needed for runCommand/query cases.

            ScriptTest.run("64_schema_validation: free-form tuning rides in args, not beside it", function (t) {
                // A playbook's free-form tuning keys go in args, which is declared open JSON and becomes the
                // PLAYBOOK global; the params object itself is closed, so a stray key is refused where it sits.
                var e = null;
                try {
                    Driver.invoke("mc.bot.playbook", { op: "status", freeform: 1 });
                } catch (x) {
                    e = x;
                }
                t.assertEqual(e && e.issues[0].code, "unknown_field", "a stray key is refused");
                t.assertEqual(e.issues[0].path, "/freeform", "at the key");
            });
        },
    })
);
