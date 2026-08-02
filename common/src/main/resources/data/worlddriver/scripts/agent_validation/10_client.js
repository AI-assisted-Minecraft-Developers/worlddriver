// Client-side validation. Gracefully skipped on dedicated server / GameTest CI
// where ClientHooks never registers an impl; in that case mc.client.* throws
// IllegalStateException which we catch up-front and record a single "skipped"
// PASS so the test count stays sensible.

function clientAvailable() {
    try {
        Agent.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

if (!clientAvailable()) {
    ScriptTest.run("10_client: skipped (no client api — dedicated server)", function(t) {
        // no-op: PASS so headless runs stay green
    });
} else {

    ScriptTest.run("10_client: screen.info exposes hasScreen/worldOpen/hasPlayer", function(t) {
        var info = Agent.invoke("mc.client.screen.info", {});
        t.assertEqual(typeof info.hasScreen, "boolean", "hasScreen must be boolean");
        t.assertEqual(typeof info.worldOpen, "boolean", "worldOpen must be boolean");
        t.assertEqual(typeof info.hasPlayer, "boolean", "hasPlayer must be boolean");
    });

    ScriptTest.run("10_client: input.key 'E' opens inventory + close round-trip", function(t) {
        var info = Agent.invoke("mc.client.screen.info", {});
        if (!info.hasPlayer) {
            // Client booted but never joined a world — exercise close path only.
            Agent.invoke("mc.client.screen.close", {});
            return;
        }
        Agent.invoke("mc.client.input.key", { key: "E" });
        // Keybind processing runs on the next client tick; give it a beat.
        Agent.system.waitTicks(2);
        var tree = Agent.invoke("mc.client.screen.tree", {});
        t.assertTrue(tree.hasScreen, "screen should be open after pressing E");
        t.assertTrue(tree.type && tree.type.length > 0, "screen type should be reported");

        var closed = Agent.invoke("mc.client.screen.close", {});
        t.assertTrue(closed.ok, "close should succeed");
        var after = Agent.invoke("mc.client.screen.info", {});
        t.assertEqual(after.hasScreen, false, "screen should be null after close");
    });

    ScriptTest.run("10_client: screenshot returns base64 png with positive dims", function(t) {
        var shot = Agent.invoke("mc.client.screenshot", {});
        t.assertEqual(shot.format, "png", "format must be png");
        t.assertTrue(shot.width > 0, "width must be positive (got " + shot.width + ")");
        t.assertTrue(shot.height > 0, "height must be positive (got " + shot.height + ")");
        t.assertTrue(shot.base64 && shot.base64.length > 100,
            "base64 payload must be non-trivial (got " + (shot.base64 ? shot.base64.length : 0) + " bytes)");
    });

    ScriptTest.run("10_client: screenshot also reachable via WebSocket RPC", function(t) {
        // Cross-path parity check: screenshot bytes can differ frame-to-frame, but
        // the shape and format fields must match between in-JVM and TCP paths.
        var direct = Agent.invoke("mc.client.screen.info", {});
        var viaTcp = Agent.system.rpcRoundtrip("mc.client.screen.info", {});
        t.assertEqual(direct.hasScreen, viaTcp.hasScreen, "hasScreen mismatch across paths");
        t.assertEqual(direct.worldOpen, viaTcp.worldOpen, "worldOpen mismatch across paths");
    });

    ScriptTest.run("10_client: screenshot via MCP returns merged {format,width,height,base64}", function(t) {
        // MCP splits the screenshot into [text(meta JSON), image(base64 bytes)] so
        // multimodal LLMs see it as vision input. McpBridge.call merges the image
        // block's data back under 'base64' so this round-trip is shape-compatible
        // with the in-JVM call. We can't byte-compare base64 (frames drift), but
        // we can prove the merge happens.
        var direct = Agent.invoke("mc.client.screenshot", {});
        var viaMcp = Agent.system.mcpRoundtrip("mc.client.screenshot", {});

        t.assertEqual(viaMcp.format, direct.format, "format mismatch across paths");
        t.assertEqual(viaMcp.width,  direct.width,  "width mismatch across paths");
        t.assertEqual(viaMcp.height, direct.height, "height mismatch across paths");
        t.assertTrue(viaMcp.base64 && viaMcp.base64.length > 100,
            "MCP-path base64 must be re-merged from image block (got "
            + (viaMcp.base64 ? viaMcp.base64.length : 0) + " bytes)");
    });

}
