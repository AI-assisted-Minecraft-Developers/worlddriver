// Run by kit.js's 'wd.validation' step, which says what ScriptTest is.
test("03_events_since", { scene: ["wd.validation", "wd.validationIntegrated"], isolated: true }, (t) =>
    t.step("wd.validation", {
        script: "03_events_since",
        DriverApiError: DriverApiError,
        body: (ScriptTest) => {
            ScriptTest.run("03_events_since: cursor + setblock destroy produces block.break event", function (t) {
                var cursorBefore = Driver.observe.cursor().cursor;
                t.assertEqual(typeof cursorBefore, "string", "the cursor is a decimal string");
                var origin = Driver.system.testOrigin();
                // Destroy one of the seeded stones
                var cmd = "setblock " + origin.x + " " + origin.y + " " + origin.z + " air destroy";
                var r = Driver.action.runCommand({ cmd: cmd });
                t.assertEqual(r.ok, true, "runCommand should succeed");

                var page = Driver.observe.eventsSince({ cursor: cursorBefore });
                var events = page.events;
                t.assertTrue(Array.isArray(events), "eventsSince must return an events array");
                t.assertTrue(events.length >= 1, "expected at least one event after destroy");
                t.assertEqual(page.cursor, events[events.length - 1].seq, "the page cursor is its last event's seq");

                var found = events.filter(function (e) {
                    return (
                        e.type === "block.break" && e.pos.x === origin.x && e.pos.y === origin.y && e.pos.z === origin.z
                    );
                });
                t.assertEqual(found.length, 1, "expected exactly one block.break at origin");
            });
        },
    }),
);
