ScriptTest.run("03_events_since: cursor + setblock destroy produces block.break event", function(t) {
    var cursorBefore = Agent.observe.cursor();
    var origin = Agent.system.testOrigin();
    // Destroy one of the seeded stones
    var cmd = "setblock " + origin.x + " " + origin.y + " " + origin.z + " air destroy";
    var r = Agent.action.runCommand(cmd);
    t.assertEqual(r.ok, true, "runCommand should succeed");

    var events = Agent.observe.eventsSince(cursorBefore);
    t.assertTrue(Array.isArray(events), "eventsSince must return array");
    t.assertTrue(events.length >= 1, "expected at least one event after destroy");

    var found = events.filter(function(e) {
        return e.type === "block.break"
            && e.pos.x === origin.x && e.pos.y === origin.y && e.pos.z === origin.z;
    });
    t.assertEqual(found.length, 1, "expected exactly one block.break at origin");
});
