// Client-side validation. On a dedicated server no client is bound, so every mc.client.* call is
// capability_unavailable; that is the one check run there.

function clientAvailable() {
    try {
        Driver.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

/** The DriverApiError a call throws, or null when it returned. */
function errorOf(call) {
    try { call(); } catch (e) { return e; }
    return null;
}

if (!clientAvailable()) {
    ScriptTest.run("10_client: without a client every mc.client method is capability_unavailable", function(t) {
        var e = errorOf(function() { Driver.client.screen.info(); });
        t.assertEqual(e.code, "capability_unavailable", "code");
    });
} else {

    ScriptTest.run("10_client: screen.info exposes hasScreen/worldOpen/hasPlayer and a context", function(t) {
        var info = Driver.client.screen.info();
        t.assertEqual(typeof info.hasScreen, "boolean", "hasScreen must be boolean");
        t.assertEqual(typeof info.worldOpen, "boolean", "worldOpen must be boolean");
        t.assertEqual(typeof info.hasPlayer, "boolean", "hasPlayer must be boolean");
        t.assertEqual(typeof info.contextId, "string", "contextId must be a string");
    });

    ScriptTest.run("10_client: input.key 'E' opens inventory + close round-trip", function(t) {
        var info = Driver.client.screen.info();
        if (!info.hasPlayer) {
            // Client booted but never joined a world — exercise close path only.
            Driver.client.screen.close({});
            return;
        }
        Driver.client.input.key({ key: "E" });
        // Keybind processing runs on the next client tick; give it a beat.
        Driver.system.waitTicks({ ticks: 2 });
        var tree = Driver.client.screen.tree({});
        t.assertTrue(tree.hasScreen, "screen should be open after pressing E");
        t.assertTrue(tree.type && tree.type.length > 0, "screen type should be reported");
        t.assertTrue(tree.nodes.some(function(n) { return n.type === "Slot" && n.index === 0; }),
            "the inventory's slots are nodes of the tree");

        var closed = Driver.client.screen.close({});
        t.assertEqual(closed.closed, tree.type, "close names the screen it closed");
        var after = Driver.client.screen.info();
        t.assertEqual(after.hasScreen, false, "screen should be null after close");
    });

    ScriptTest.run("10_client: a write held to a screen that has since changed does nothing", function(t) {
        var info = Driver.client.screen.info();
        if (!info.hasPlayer) return;
        Driver.client.input.key({ key: "E" });
        Driver.system.waitTicks({ ticks: 2 });
        var e = errorOf(function() { Driver.client.screen.close({ contextId: info.contextId }); });
        t.assertEqual(e && e.code, "context_changed", "a close held to the screen before the inventory opened");
        var open = Driver.client.screen.info();
        t.assertEqual(open.hasScreen, true, "the refused close left the inventory open");
        t.assertTrue(open.contextId !== info.contextId, "opening the inventory changed the context");
        var closed = Driver.client.screen.close({ contextId: open.contextId });
        t.assertEqual(closed.closed, open.type, "a close held to the current screen closes it");
    });

    ScriptTest.run("10_client: screenshot returns base64 png with positive dims", function(t) {
        var shot = Driver.client.screenshot();
        t.assertEqual(shot.format, "png", "format must be png");
        t.assertTrue(shot.width > 0, "width must be positive (got " + shot.width + ")");
        t.assertTrue(shot.height > 0, "height must be positive (got " + shot.height + ")");
        t.assertTrue(shot.base64 && shot.base64.length > 100,
            "base64 payload must be non-trivial (got " + (shot.base64 ? shot.base64.length : 0) + " bytes)");
    });

    ScriptTest.run("10_client: a capture waits for a frame of its own", function(t) {
        // The failure this guards is invisible in the image: a capture of the frame that predates
        // the request looks entirely plausible. So the check is on the frame number, which is the
        // same quantity the capture is made of.
        var a = Driver.client.screenshot();
        var b = Driver.client.screenshot();
        t.assertTrue(a.frameWaited === true,
            "capture did not get a frame of its own (frame=" + a.frame + ", fps=" + a.fps + ")");
        t.assertTrue(b.frame > a.frame,
            "frame did not advance between captures (" + a.frame + " -> " + b.frame + ")");
    });

    ScriptTest.run("10_client: screen.info also reachable via WebSocket RPC", function(t) {
        var direct = Driver.client.screen.info();
        var viaTcp = Driver.system.rpcRoundtrip("mc.client.screen.info", {});
        t.assertEqual(direct.hasScreen, viaTcp.hasScreen, "hasScreen mismatch across paths");
        t.assertEqual(direct.worldOpen, viaTcp.worldOpen, "worldOpen mismatch across paths");
        t.assertEqual(direct.contextId, viaTcp.contextId, "the same screen is the same context on every path");
    });

    ScriptTest.run("10_client: screenshot via MCP returns merged {format,width,height,base64}", function(t) {
        // MCP splits the screenshot into [text(meta JSON), image(base64 bytes)] so
        // multimodal LLMs see it as vision input. McpBridge.call merges the image
        // block's data back under 'base64' so this round-trip is shape-compatible
        // with the in-JVM call. We can't byte-compare base64 (frames drift), but
        // we can prove the merge happens.
        var direct = Driver.client.screenshot();
        var viaMcp = Driver.system.mcpRoundtrip("mc.client.screenshot", {});

        t.assertEqual(viaMcp.format, direct.format, "format mismatch across paths");
        t.assertEqual(viaMcp.width,  direct.width,  "width mismatch across paths");
        t.assertEqual(viaMcp.height, direct.height, "height mismatch across paths");
        t.assertTrue(viaMcp.base64 && viaMcp.base64.length > 100,
            "MCP-path base64 must be re-merged from image block (got "
            + (viaMcp.base64 ? viaMcp.base64.length : 0) + " bytes)");
    });

}
