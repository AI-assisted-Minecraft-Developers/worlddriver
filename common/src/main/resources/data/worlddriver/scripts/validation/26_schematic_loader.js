// Client-only — Phase D4: mc.bot.build now accepts schematicBase64 (Sponge
// .schem v1/v2/v3 bytes). Happy path needs a real client+world (BuildProcess
// walks the player around placing blocks). Schema rejects exercise the
// codepath end-to-end on dedicated server too, but BuildProcess construction
// fails without a player, so we keep them gated like 23_phase_d.

function clientAvailable() {
    try {
        Driver.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

if (!clientAvailable()) {
    ScriptTest.run("26_schematic_loader: skipped (no client api — dedicated server)", function(t) {
        // PASS
    });
} else {

    ScriptTest.run("26_schematic_loader: build rejects missing both schematic and schematicBase64",
        function(t) {
            var r = Driver.invoke("mc.bot.build", { origin: { x: 0, y: 64, z: 0 } });
            t.assertEqual(r.ok, false, "missing both → ok:false");
            t.assertTrue(String(r.error).indexOf("schematic") >= 0,
                "error mentions schematic: " + r.error);
        });

    ScriptTest.run("26_schematic_loader: build rejects when both modes specified",
        function(t) {
            var r = Driver.invoke("mc.bot.build", {
                origin: { x: 0, y: 64, z: 0 },
                schematic: { w: 1, h: 1, d: 1, palette: ["minecraft:stone"], data: [[0,0,0,0]] },
                schematicBase64: "AAAA"
            });
            t.assertEqual(r.ok, false, "both → ok:false");
            t.assertTrue(String(r.error).indexOf("either") >= 0
                      || String(r.error).indexOf("not both") >= 0,
                "error mentions conflict: " + r.error);
        });

    ScriptTest.run("26_schematic_loader: invalid base64 is rejected with a clear error",
        function(t) {
            var r = Driver.invoke("mc.bot.build", {
                origin: { x: 0, y: 64, z: 0 },
                schematicBase64: "this is not base64!!! ###"
            });
            t.assertEqual(r.ok, false, "garbage base64 → ok:false");
            t.assertTrue(String(r.error).indexOf("base64") >= 0,
                "error mentions base64: " + r.error);
        });

    ScriptTest.run("26_schematic_loader: valid base64 but non-NBT bytes rejected",
        function(t) {
            // "Hello, world!" base64 — decodes fine, but isn't gzip nor raw NBT,
            // so both NbtIo paths in fromSpongeSchem throw and we surface the
            // gzip-attempt's error message.
            var r = Driver.invoke("mc.bot.build", {
                origin: { x: 0, y: 64, z: 0 },
                schematicBase64: "SGVsbG8sIHdvcmxkIQ=="
            });
            t.assertEqual(r.ok, false, "non-NBT bytes → ok:false");
            t.assertTrue(String(r.error).indexOf(".schem") >= 0
                      || String(r.error).indexOf("NBT") >= 0
                      || String(r.error).indexOf("schematic") >= 0,
                "error mentions schematic/NBT: " + r.error);
        });

    ScriptTest.run("26_schematic_loader: build rejects negative paths identically across transports",
        function(t) {
            // The base64-reject path doesn't touch the client thread (it's
            // checked before onClient), so it's deterministic across all three
            // transports — same rejection regardless of player state.
            var args = {
                origin: { x: 0, y: 64, z: 0 },
                schematicBase64: "this is not base64!!! ###"
            };
            var direct = Driver.invoke("mc.bot.build", args);
            var viaTcp = Driver.system.rpcRoundtrip("mc.bot.build", args);
            var viaMcp = Driver.system.mcpRoundtrip("mc.bot.build", args);
            t.assertEqual(viaTcp.ok,    direct.ok,    "RPC.ok mismatch");
            t.assertEqual(viaMcp.ok,    direct.ok,    "MCP.ok mismatch");
            t.assertEqual(viaTcp.error, direct.error, "RPC.error mismatch");
            t.assertEqual(viaMcp.error, direct.error, "MCP.error mismatch");
        });

}
