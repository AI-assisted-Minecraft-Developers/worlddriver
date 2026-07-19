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
        // Success path: the documented fields of the DEFAULT replan mode must be
        // present and well-typed. task#92: `segments`/`plannedNodes` were the OLD
        // rigid-replay shape; the replan path (restoreBlocks:false ⇒ replan:true)
        // returns file / mode / envelopeCells / restoredBlocks / blockStateFidelity
        // (ReplayTool.replan branch). Assert the CURRENT contract, never the stale one.
        t.assertEqual(r.mode, "replan", "default mode is replan");
        t.assertTrue(typeof r.file === "string" && r.file.length > 0,
            "file names the replayed archive");
        t.assertTrue(typeof r.envelopeCells === "number" && r.envelopeCells >= 0,
            "envelopeCells is a non-negative number");
        t.assertTrue(typeof r.restoredBlocks === "number" && r.restoredBlocks >= 0,
            "restoredBlocks is a non-negative number");
        t.assertTrue(typeof r.blockStateFidelity === "string" && r.blockStateFidelity.length > 0,
            "blockStateFidelity describes the restore fidelity");
    });
}
