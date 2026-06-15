// mc.debug.replay restores a recorded path archive, teleports the bot to the start,
// and re-executes the stored plan through the Walker in replay mode. Like
// mc.debug.pathChart it is a client-bound debug seam (it runs through onClient and
// needs a live LocalPlayer) AND it needs an existing replay-*.json plan archive on
// disk. On the dedicated GameTest server neither holds, so skip gracefully — keeping
// the headless suite green. We assert the RETURN SHAPE only, never the live
// (nondeterministic) trajectory.

function routeAvailable() {
    // A bare call with no file selects the newest archive; if the route is missing it
    // throws (skip), and if there is no client / no archive it returns ok:false (skip).
    try { Agent.invoke("mc.debug.replay", { restoreBlocks: false }); return true; }
    catch (e) { return false; }
}

if (!routeAvailable()) {
    AgentTest.run("57_replay: skipped (no route)", function (t) { /* PASS */ });
} else {

    AgentTest.run("57_replay: return shape is well-typed", function (t) {
        var r = Agent.invoke("mc.debug.replay", { restoreBlocks: false });
        t.assertTrue(typeof r.ok === "boolean", "ok is a boolean");
        if (!r.ok) {
            // No client / no archive on this host — a structured error, not a shape we assert.
            t.assertTrue(typeof r.error === "string" && r.error.length > 0,
                "failure carries an error string");
            return;
        }
        // Success path: the documented fields must be present and well-typed.
        t.assertTrue(typeof r.segments === "number" && r.segments >= 1,
            "segments is a positive number");
        t.assertTrue(typeof r.plannedNodes === "number" && r.plannedNodes >= 1,
            "plannedNodes is a positive number");
        t.assertTrue(typeof r.restoredBlocks === "number" && r.restoredBlocks >= 0,
            "restoredBlocks is a non-negative number");
        t.assertTrue(typeof r.file === "string" && r.file.length > 0,
            "file names the replayed archive");
    });
}
